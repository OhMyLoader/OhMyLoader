package org.ohmyloader.core.mixin

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The class-merging engine (`@Shadow` / `@Unique` / `@Overwrite`).
 *
 * Its failure modes almost all **surface only at "class definition" or "first call"** time: a self-reference
 * whose owner is not rewritten ⇒ runtime `NoSuchFieldError`/`NoSuchMethodError`; stack frames not rewritten
 * along ⇒ `VerifyError`; a renamed member whose call sites were not updated ⇒ likewise `NoSuchMethodError`.
 * So every case is "merge → write back → define → reflectively call → assert **behavior**", not "assert it
 * is in the member list".
 */
class ClassMergerTest {

    private val targetName = "omltest/MergeTarget"
    private val mixinName = "omltest/MergeMixin"

    private companion object {
        const val ACCESSOR = "Lorg/ohmyloader/api/mixin/Accessor;"
        const val INVOKER = "Lorg/ohmyloader/api/mixin/Invoker;"
    }

    // ---------- @Shadow ----------

    @Test
    fun `shadow members are validated and not merged`() {
        val [target, result] = mergeInto(target(), richMixin())

        assertEquals(emptyList(), result.problems)
        assertEquals(2, result.shadows, "one @Shadow field + one @Shadow method")
        // That field in the target class must appear exactly once (the mixin's copy is a **declaration** and
        // must not be merged in a second time)
        assertEquals(1, target.fields.count { it.name == "payload" })
    }

    @Test
    fun `missing shadow field is reported instead of silently ignored`() {
        val bad = richMixin().apply { fields.add(shadowField("nope", "I")) }

        val [_, result] = mergeInto(target(), bad)

        assertTrue(result.problems.single().contains("@Shadow field could not find"), result.problems.toString())
        assertTrue(result.problems.single().contains("nope"), result.problems.toString())
    }

    @Test
    fun `shadow with a mismatching descriptor is reported`() {
        // The target's payload is a String, but the mixin declares it as I — a type mismatch means a mistake;
        // we cannot make do
        val bad = richMixin().apply {
            fields.removeIf { it.name == "payload" }
            fields.add(shadowField("payload", "I"))
        }

        val [_, result] = mergeInto(target(), bad)

        assertTrue(result.problems.single().contains("same name and descriptor"), result.problems.toString())
    }

    @Test
    fun `shadow alias renames the reference to the target member`() {
        // That field in the target class is named realName, and in the mixin payload (declared via aliases)
        // — after merging, references inside the mixin must be re-pointed to realName
        val t = target(fieldName = "realName")
        val m = richMixin().apply {
            fields.removeIf { it.name == "payload" }
            fields.add(shadowField("payload", "Ljava/lang/String;", aliases = listOf("realName")))
        }

        val [merged, result] = mergeInto(t, m)
        assertEquals(emptyList(), result.problems)
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()

        // What is read must be the value from the target class's realName field
        assertEquals("orig", invoke(clazz, instance, "useShadow"))
    }

    // ---------- @Overwrite ----------

    @Test
    fun `overwrite replaces the target body`() {
        val [merged, result] = mergeInto(target(), richMixin())

        assertEquals(1, result.overwrites, "value() was overwritten")
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()

        // The target originally returns 1; the mixin overwrites it to 2
        assertEquals(2, invoke(clazz, instance, "value"))
    }

    @Test
    fun `overwrite without a matching target method is reported`() {
        val bad = plainMixin().apply {
            methods.add(
                overwriteMethod("nope", "()I") {
                    add(InsnNode(Opcodes.ICONST_1))
                    add(InsnNode(Opcodes.IRETURN))
                }
            )
        }

        val [_, result] = mergeInto(target(), bad)

        assertTrue(
            result.problems.single().contains("has no corresponding method in the target class"),
            result.problems.toString()
        )
    }

    @Test
    fun `two mixins overwriting the same method is a conflict`() {
        val ledger = mutableMapOf<String, String>()
        val first = ClassMerger.merge(mixinOf(richMixin(), "a"), target(), ledger)
        val second = ClassMerger.merge(mixinOf(richMixin(), "b"), target(), ledger)

        assertEquals(emptyList(), first.problems)
        assertTrue(second.problems.single().contains("@Overwrite conflict"), second.problems.toString())
    }

    @Test
    fun `overwrite keeps the target access flags`() {
        val [merged, _] = mergeInto(target(), richMixin())

        val value = merged.methods.first { it.name == "value" }
        // The target's value() is public final: swapping the method body must not touch these flags (it is
        // still "this class's own method")
        assertTrue(value.access and Opcodes.ACC_PUBLIC != 0)
        assertTrue(value.access and Opcodes.ACC_FINAL != 0)
    }

    // ---------- @Unique / <clinit> / reference re-owning ----------

    @Test
    fun `unique static field initializer survives via clinit merge`() {
        val [merged, result] = mergeInto(target(), richMixin())

        assertTrue(result.fields >= 2, "both counter and extra must be merged in")
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()

        // The 7 comes from the mixin's <clinit>; if it were not stitched in this would be 0 — that is the
        // whole point of "<clinit> must be merged"
        assertEquals(7, invoke(clazz, instance, "readExtra"))
    }

    @Test
    fun `self references are re-owned to the target class`() {
        val [merged, result] = mergeInto(target(), richMixin())
        assertEquals(emptyList(), result.problems)
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()

        // 1) Field: useShadow() holds GETFIELD <mixin>.payload → must be re-pointed to the target class
        assertEquals("orig", invoke(clazz, instance, "useShadow"))
        // 2) Method: selfCall() holds INVOKEVIRTUAL <mixin>.readExtra → likewise re-owned
        assertEquals(7, invoke(clazz, instance, "selfCall"))
    }

    @Test
    fun `colliding member is renamed and internal calls follow`() {
        // The target already has extra()I (returns 9); the mixin also brings an extra()I (returns 5), and it
        // has a method that calls it
        val t = target().apply {
            methods.add(
                instanceMethod("extra", "()I") {
                    add(IntInsnNode(Opcodes.BIPUSH, 9))
                    add(InsnNode(Opcodes.IRETURN))
                }
            )
        }
        val m = plainMixin().apply {
            methods.add(
                instanceMethod("extra", "()I") {
                    add(IntInsnNode(Opcodes.BIPUSH, 5))
                    add(InsnNode(Opcodes.IRETURN))
                }
            )
            methods.add(
                instanceMethod("callExtra", "()I") {
                    add(VarInsnNode(Opcodes.ALOAD, 0))
                    add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinName, "extra", "()I", false))
                    add(InsnNode(Opcodes.IRETURN))
                }
            )
        }

        val [merged, result] = mergeInto(t, m)
        assertEquals(emptyList(), result.problems)
        assertTrue(result.renamed >= 1, "the colliding extra should have been renamed")
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()

        // The target's extra is still 9 (not displaced); the mixin's copy, once renamed, stands alone, and
        // callExtra must find it
        assertEquals(9, invoke(clazz, instance, "extra"))
        assertEquals(5, invoke(clazz, instance, "callExtra"))
    }

    // ---------- Where problems are reported ----------

    // ---------- Constructor merging (instance initializers) ----------

    @Test
    fun `final field assignment in a mixin constructor lands in the target constructor`() {
        // `answer` is final: the JVM only allows assigning it inside the `<init>` of **the class that
        // declares it**. So this PUTFIELD must physically land in the target class's <init> — merging it
        // into an ordinary target method would VerifyError.
        val [merged, result] = mergeInto(ctorCarrier(), answerMixin())

        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertEquals(1, result.constructors)

        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals(
            42, invokeInt(clazz, instance, "answer"),
            "the merged segment must land **after** the target's own field initializers, otherwise 41 would overwrite it",
        )
        assertEquals(1, invokeInt(clazz, instance, "marker"), "the target's own field initializer is unaffected")
    }

    @Test
    fun `the target alone keeps its own value`() {
        // Baseline: with no constructor mixin, answer IS 41 — so the previous assertion carries weight
        val clazz = define(ctorCarrier())
        assertEquals(41, invokeInt(clazz, clazz.getDeclaredConstructor().newInstance(), "answer"))
    }

    @Test
    fun `unique instance field initializer is applied to every constructor`() {
        val [merged, result] = mergeInto(ctorCarrier(twoCtors = true), uniqueInstanceFieldMixin())

        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertEquals(
            2,
            result.constructors,
            "every super() constructor must be merged in: whichever path creates the object needs the initial value"
        )

        val clazz = define(merged)
        assertEquals(5, invokeInt(clazz, clazz.getDeclaredConstructor().newInstance(), "tag"))
        assertEquals(
            5,
            invokeInt(clazz, clazz.getDeclaredConstructor(Integer.TYPE).newInstance(9), "tag"),
        )
    }

    @Test
    fun `a constructor delegating with this is not injected`() {
        val [merged, result] = mergeInto(
            ctorCarrier(twoCtors = true, thisDelegating = true),
            uniqueInstanceFieldMixin(),
        )

        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertEquals(
            2,
            result.constructors,
            "the one delegated via this() is skipped (merging it in would run it twice)"
        )

        // But the this() path still gets its initial value — it eventually lands in the delegated
        // constructor
        val clazz = define(merged)
        assertEquals(
            5,
            invokeInt(clazz, clazz.getDeclaredConstructor(java.lang.Long.TYPE).newInstance(3L), "tag"),
        )
    }

    @Test
    fun `a constructor mixin reading a local is reported`() {
        // The target constructor's slot layout is decided by its own descriptor, independent of the mixin
        // constructor ⇒ reading a parameter is assuming both parameter tables are identical, an
        // assumption that fails silently
        val bad = plainMixin().apply {
            val ctor = methods.first { it.name == "<init>" }
            ctor.instructions.insertBefore(ctor.instructions.last, VarInsnNode(Opcodes.ILOAD, 1))
            ctor.instructions.insertBefore(ctor.instructions.last, InsnNode(Opcodes.POP))
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(result.problems.single().contains("reads local variable slot"), result.problems.toString())
    }

    @Test
    fun `a constructor mixin with a branch is reported`() {
        // Write-back uses the frame-preserving mode and does not recompute StackMapTable: a segment with a
        // branch needs a stack frame, but a frame would not line up inside the target constructor
        val bad = plainMixin().apply {
            val ctor = methods.first { it.name == "<init>" }
            val target = LabelNode()
            ctor.instructions.insertBefore(ctor.instructions.last, target)
            ctor.instructions.insertBefore(ctor.instructions.last, JumpInsnNode(Opcodes.GOTO, target))
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(result.problems.single().contains("jump/branch"), result.problems.toString())
    }

    @Test
    fun `a constructor mixin without a super call is reported`() {
        val bad = plainMixin().apply {
            val ctor = methods.first { it.name == "<init>" }
            ctor.instructions.clear()
            ctor.instructions.add(InsnNode(Opcodes.RETURN))
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(
            result.problems.single().contains("could not find the delegate call to the superclass constructor"),
            result.problems.toString(),
        )
    }

    @Test
    fun `a constructor mixin delegating with this is reported`() {
        // The mixin's own constructor uses this(...): the spliced segment is ambiguous (the delegating
        // constructor also carries a segment)
        val bad = plainMixin().apply {
            val ctor = methods.first { it.name == "<init>" }
            ctor.instructions.clear()
            ctor.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            ctor.instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, mixinName, "<init>", "()V", false))
            ctor.instructions.add(InsnNode(Opcodes.RETURN))
            addAbsCall(ctor.instructions)
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(
            result.problems.single().contains("delegates to another constructor with `this(...)`"),
            result.problems.toString()
        )
    }

    @Test
    fun `two constructor sources are reported`() {
        // The order of two sources can only be decided by declaration order — an implicit rule, not guessed
        val bad = plainMixin().apply {
            val ctor = methods.first { it.name == "<init>" }
            addAbsCall(ctor.instructions)
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null).apply {
                    instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                    instructions.add(
                        MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false),
                    )
                    addAbsCall(instructions)
                    instructions.add(InsnNode(Opcodes.RETURN))
                }
            )
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(
            result.problems.single().contains("the mixin declares 2 constructors with a method body"),
            result.problems.toString(),
        )
    }

    @Test
    fun `overwrite on a constructor is reported`() {
        // A wholesale replacement would also replace the target's delegation call — surely not desired
        val bad = plainMixin().apply {
            methods.first { it.name == "<init>" }.visibleAnnotations =
                listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Overwrite;"))
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(
            result.problems.single().contains("constructor does not support @Overwrite"),
            result.problems.toString()
        )
    }

    @Test
    fun `shadow constructor missing in the target is reported`() {
        val bad = plainMixin().apply {
            methods.first { it.name == "<init>" }.apply {
                desc = "(I)V"
                visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Shadow;"))
            }
        }

        val [_, result] = mergeInto(ctorCarrier(), bad)

        assertTrue(
            result.problems.single().contains("@Shadow constructor could not find"),
            result.problems.toString(),
        )
    }

    @Test
    fun `shadow constructor present in the target is not merged`() {
        val ok = plainMixin().apply {
            methods.first { it.name == "<init>" }.apply {
                desc = "(I)V"
                visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Shadow;"))
            }
        }

        val [merged, result] = mergeInto(ctorCarrier(twoCtors = true), ok)

        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertEquals(1, result.shadows)
        assertEquals(0, result.constructors, "@Shadow constructor is only a declaration and is not merged in itself")

        val clazz = define(merged)
        assertEquals(
            9,
            invokeInt(clazz, clazz.getDeclaredConstructor(Integer.TYPE).newInstance(9), "answer"),
            "the target's (I)V constructor body was not touched: answer is still seed itself",
        )
    }

    @Test
    fun `abstract member without shadow is reported`() {
        val bad = plainMixin().apply {
            methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "orphan", "()V", null, null))
        }

        val [_, result] = mergeInto(target(), bad)

        assertTrue(result.problems.single().contains("has no method body and no @Shadow"), result.problems.toString())
    }

    // ---------- Scaffolding ----------

    // ---------- Interface mixin: @Accessor / @Invoker ----------

    @Test
    fun `accessor getter reads the private field without reflection`() {
        val [merged, result] = mergeInto(
            target(),
            accessorMixin(abstractMethod("readPayload", "()Ljava/lang/String;", ACCESSOR, "payload"))
        )

        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertEquals(1, result.accessors)

        // The synthetic body runs inside the target class: private fields are directly visible (this is the
        // "no-reflection access to private members" win)
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals("orig", invoke(clazz, instance, "readPayload"))
    }

    @Test
    fun `accessor setter writes the private field`() {
        val [merged, result] = mergeInto(
            target(),
            accessorMixin(
                abstractMethod("writePayload", "(Ljava/lang/String;)V", ACCESSOR, "payload"),
                abstractMethod("readPayload", "()Ljava/lang/String;", ACCESSOR, "payload"),
            ),
        )
        assertEquals(emptyList(), result.problems, result.problems.toString())

        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()
        clazz.methods.first { it.name == "writePayload" }.invoke(instance, "new")
        assertEquals("new", invoke(clazz, instance, "readPayload"))
        // The original payload() also reads the same field — the write really landed
        assertEquals("new", invoke(clazz, instance, "payload"))
    }

    @Test
    fun `accessor name is inferred from the get prefix`() {
        // `value` is left empty: getPayload() → target field payload (matching Mixin's prefix convention)
        val [merged, result] = mergeInto(
            target(),
            accessorMixin(abstractMethod("getPayload", "()Ljava/lang/String;", ACCESSOR))
        )

        assertEquals(emptyList(), result.problems, result.problems.toString())

        val clazz = define(merged)
        assertEquals("orig", invoke(clazz, clazz.getDeclaredConstructor().newInstance(), "getPayload"))
    }

    @Test
    fun `accessor pointing to a missing field is reported`() {
        val [_, result] = mergeInto(target(), accessorMixin(abstractMethod("readNope", "()I", ACCESSOR, "nope")))

        assertTrue(result.problems.single().contains("could not find the field"), result.problems.toString())
    }

    @Test
    fun `invoker forwards to a private target method`() {
        val [merged, result] = mergeInto(
            targetWithSecret(),
            accessorMixin(abstractMethod("callSecret", "(I)I", INVOKER, "secret"))
        )
        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertEquals(1, result.invokers)

        // A private method must go through INVOKESPECIAL — INVOKEVIRTUAL would VerifyError, exploding at
        // define time
        val clazz = define(merged)
        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals(42, clazz.methods.first { it.name == "callSecret" }.invoke(instance, 41))
    }

    @Test
    fun `invoker with a mismatching signature is reported`() {
        // The target secret(I)I is declared as (J)I — the parameter type must not match, so it must be
        // reported rather than silently generating a broken call
        val [_, result] = mergeInto(
            targetWithSecret(),
            accessorMixin(abstractMethod("callSecret", "(J)I", INVOKER, "secret"))
        )

        assertTrue(result.problems.single().contains("could not find the method"), result.problems.toString())
    }

    @Test
    fun `interface method without accessor annotation is reported`() {
        val [_, result] = mergeInto(target(), accessorMixin(abstractMethod("rogue", "()V", "")))

        assertTrue(result.problems.single().contains("must carry @Accessor"), result.problems.toString())
    }

    /** `interface Accessors { … }` — the methods are supplied by the case itself (all abstract). */
    private fun accessorMixin(vararg methods: MethodNode): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        name = mixinName + "Accessors"
        superName = "java/lang/Object"
        this.methods.addAll(methods)
    }

    private fun abstractMethod(name: String, desc: String, annotation: String, value: String = ""): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, desc, null, null).apply {
            if (annotation.isNotEmpty()) {
                visibleAnnotations = listOf(
                    AnnotationNode(Opcodes.ASM9, annotation).also {
                        if (value.isNotEmpty()) it.values = mutableListOf<Any?>("value", value)
                    }
                )
            }
        }

    /** target() plus a private method `secret(int) → int` (returns n + 1), for @Invoker forwarding verification. */
    private fun targetWithSecret(): ClassNode = target().apply {
        methods.add(
            instanceMethod("secret", "(I)I", Opcodes.ACC_PRIVATE) {
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(InsnNode(Opcodes.ICONST_1))
                add(InsnNode(Opcodes.IADD))
                add(InsnNode(Opcodes.IRETURN))
            }
        )
    }

    // ---------- Class-file version of the merged segment ----------

    /**
     * Merging is "move the bytecode verbatim into another class", so the merged segment must defer to
     * the **target class's class-file version**: a target may be compiled to Java 6 (version 50), while
     * `invokedynamic` needs version ≥ 51.
     * Splicing it in does not mean "this code does not take effect" — it means **the whole class fails
     * to load**, and the error only names the target class. So it must be called out at merge time (in
     * production it was Kotlin's string templates that stepped here).
     */
    @Test
    fun `a merged segment using invokedynamic is rejected on a Java 6 target`() {
        val t = target().apply { version = Opcodes.V1_6 }
        val m = richMixin().apply { methods.add(indyMethod()) }

        val [_, result] = mergeInto(t, m)

        assertTrue(
            result.problems.any { it.contains("invokedynamic") && it.contains("Java 6") },
            result.problems.toString(),
        )
    }

    /** Baseline: the same code reports no problem on a target class with a modern enough version. */
    @Test
    fun `the same segment is accepted when the target class file is modern`() {
        val t = target().apply { version = Opcodes.V17 }
        val m = richMixin().apply { methods.add(indyMethod()) }

        val [_, result] = mergeInto(t, m)

        assertEquals(emptyList(), result.problems, result.problems.toString())
    }

    /** A method using `invokedynamic` (Kotlin string concatenation looks exactly like this). */
    private fun indyMethod(): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, "concatenate", "()V", null, null).apply {
            instructions.add(
                InvokeDynamicInsnNode(
                    "makeConcatWithConstants",
                    "()V",
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "()V",
                        false,
                    ),
                    "x",
                )
            )
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 1
        }

    /** Merge into [t]; returns the merged target class and result (`VerifyError` surfaces at [define] or on the call). */
    private fun mergeInto(t: ClassNode, m: ClassNode): Pair<ClassNode, ClassMerger.Result> {
        val ledger = mutableMapOf<String, String>()
        val result = ClassMerger.merge(mixinOf(m, "testmod"), t, ledger)
        return t to result
    }

    private fun mixinOf(node: ClassNode, modId: String) =
        ClassMerger.MixinClass(modId, node.name.replace('/', '.'), targetName, node)

    private fun define(node: ClassNode): Class<*> {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(ClassMergerTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(targetName.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, instance: Any, name: String): Any? =
        clazz.methods.first { it.name == name }.invoke(instance)

    // ---------- fixtures ----------

    /**
     * `class MergeTarget { private String <fieldName> = "orig"; String payload(); int value(); }`
     *
     * `value()` is `public final` — used to verify `@Overwrite` only changes the body and does not
     * touch the access flags.
     */
    private fun target(fieldName: String = "payload"): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = targetName
        superName = "java/lang/Object"
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, fieldName, "Ljava/lang/String;", null, null))
        methods.add(
            MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(LdcInsnNode("orig"))
                instructions.add(FieldInsnNode(Opcodes.PUTFIELD, targetName, fieldName, "Ljava/lang/String;"))
                instructions.add(InsnNode(Opcodes.RETURN))
                maxStack = 4
                maxLocals = 8
            }
        )
        methods.add(
            instanceMethod("payload", "()Ljava/lang/String;") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, targetName, fieldName, "Ljava/lang/String;"))
                add(InsnNode(Opcodes.ARETURN))
            }
        )
        methods.add(
            instanceMethod("value", "()I", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL) {
                add(InsnNode(Opcodes.ICONST_1))
                add(InsnNode(Opcodes.IRETURN))
            }
        )
    }

    /** A mixin with only an implicit constructor (for verifying one thing at a time). */
    private fun plainMixin(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = mixinName
        superName = "java/lang/Object"
        methods.add(trivialConstructor())
    }

    /**
     * A mixin using all three annotations:
     * - `@Shadow` field `payload` + `@Shadow` method `payload()`
     * - `@Overwrite value()I` (the target returns 1, here it returns 2)
     * - `@Unique` static field `extra` (initializer written in `<clinit>`) + instance field `counter`
     * - `useShadow()` / `readExtra()` / `selfCall()` to observe whether "the reference is re-owned"
     */
    private fun richMixin(): ClassNode = plainMixin().apply {
        fields.add(shadowField("payload", "Ljava/lang/String;"))
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "counter", "I", null, null))
        fields.add(FieldNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "extra", "I", null, null))

        // static int extra = 7;   ← the initializer lives in <clinit>
        methods.add(
            MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
                instructions.add(IntInsnNode(Opcodes.BIPUSH, 7))
                instructions.add(FieldInsnNode(Opcodes.PUTSTATIC, mixinName, "extra", "I"))
                instructions.add(InsnNode(Opcodes.RETURN))
                maxStack = 1
                maxLocals = 0
            }
        )
        // @Shadow method: the target already has it ⇒ only declared, not merged (hence the body is abstract)
        methods.add(
            MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "payload", "()Ljava/lang/String;", null, null)
                .apply { visibleAnnotations = listOf(shadowAnnotation(emptyList())) }
        )
        methods.add(
            overwriteMethod("value", "()I") {
                add(InsnNode(Opcodes.ICONST_2))
                add(InsnNode(Opcodes.IRETURN))
            }
        )
        // Read the target class's field (via @Shadow) — the reference must be re-owned to the target class
        methods.add(
            instanceMethod("useShadow", "()Ljava/lang/String;") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, mixinName, "payload", "Ljava/lang/String;"))
                add(InsnNode(Opcodes.ARETURN))
            }
        )
        // Read its own static field merged in (the value comes from the spliced <clinit>)
        methods.add(
            instanceMethod("readExtra", "()I") {
                add(FieldInsnNode(Opcodes.GETSTATIC, mixinName, "extra", "I"))
                add(InsnNode(Opcodes.IRETURN))
            }
        )
        // Call another method of the same class — the self-reference is re-owned
        methods.add(
            instanceMethod("selfCall", "()I") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinName, "readExtra", "()I", false))
                add(InsnNode(Opcodes.IRETURN))
            }
        )
    }

    private fun trivialConstructor(): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 1
        }

    private fun shadowField(name: String, desc: String, aliases: List<String> = emptyList()): FieldNode =
        FieldNode(Opcodes.ACC_PRIVATE, name, desc, null, null).apply {
            visibleAnnotations = listOf(shadowAnnotation(aliases))
        }

    private fun shadowAnnotation(aliases: List<String>): AnnotationNode {
        val pairs = mutableListOf<Any?>()
        if (aliases.isNotEmpty()) pairs.addAll(listOf("aliases", aliases))
        return AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Shadow;").apply { values = pairs }
    }

    private fun overwriteMethod(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        instanceMethod(name, desc, build = build).apply {
            visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Overwrite;"))
        }

    private fun instanceMethod(
        name: String,
        desc: String,
        access: Int = Opcodes.ACC_PUBLIC,
        build: InsnList.() -> Unit,
    ): MethodNode = MethodNode(access, name, desc, null, null).apply {
        instructions.build()
        maxStack = 4
        maxLocals = 8
    }

    // ---------- Constructor-merging fixtures ----------

    /**
     * A target class whose constructors all delegate with `super()` (to observe which constructors
     * the spliced segment lands in): `()V` runs `super(); answer = 41; marker = 1;`, the optional
     * `(I)V` ([twoCtors]) runs `super(); answer = seed; marker = 1;`, and the optional `(J)V`
     * ([thisDelegating]) delegates with `this(...)`, so it is not spliced into. `answer` is `final`,
     * `marker` is not — the former can only be assigned inside this class's `<init>`.
     */
    private fun ctorCarrier(twoCtors: Boolean = false, thisDelegating: Boolean = false): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = targetName
            superName = "java/lang/Object"
            fields.add(FieldNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "answer", "I", null, null))
            fields.add(FieldNode(Opcodes.ACC_PRIVATE, "marker", "I", null, null))

            methods.add(
                superDelegatingCtor("()V") { add(IntInsnNode(Opcodes.BIPUSH, 41)) }
            )
            if (twoCtors) {
                methods.add(superDelegatingCtor("(I)V") { add(VarInsnNode(Opcodes.ILOAD, 1)) })
            }
            if (thisDelegating) {
                methods.add(thisDelegatingCtor("(J)V"))
            }
            methods.add(intGetter("answer", targetName))
            methods.add(intGetter("marker", targetName))
        }

    /** `super(); answer = <answerSource>; marker = 1;` */
    private fun superDelegatingCtor(desc: String, answerSource: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.answerSource()
            instructions.add(FieldInsnNode(Opcodes.PUTFIELD, targetName, "answer", "I"))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(FieldInsnNode(Opcodes.PUTFIELD, targetName, "marker", "I"))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 3
            maxLocals = 8
        }

    /** `this();` */
    private fun thisDelegatingCtor(desc: String): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, targetName, "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 4
        }

    /**
     * `class AnswerMixin { @Shadow private int answer; AnswerMixin() { super(); this.answer = 42; } }`
     *
     * The `@Shadow` field is a **declaration** (the target already has it), so it is not merged; the
     * `PUTFIELD` in the constructor body is re-owned to the target class and spliced into the target
     * constructor — the only way to assign a `final` field.
     */
    private fun answerMixin(): ClassNode = plainMixin().apply {
        fields.add(shadowField("answer", "I"))
        methods.removeIf { it.name == "<init>" }
        methods.add(ctorAssigning("answer", 42))
    }

    /**
     * `class UniqueMixin { @Unique private int tag; UniqueMixin() { super(); this.tag = 5; } int tag(); }`
     *
     * A `@Unique` instance field's initializer compiles to exactly this: a `PUTFIELD` that can only
     * run inside the target constructor.
     */
    private fun uniqueInstanceFieldMixin(): ClassNode = plainMixin().apply {
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "tag", "I", null, null))
        methods.removeIf { it.name == "<init>" }
        methods.add(ctorAssigning("tag", 5))
        methods.add(intGetter("tag", mixinName))
    }

    /** `Mixin() { super(); this.<field> = <value>; }` */
    private fun ctorAssigning(field: String, value: Int): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(IntInsnNode(Opcodes.BIPUSH, value))
            instructions.add(FieldInsnNode(Opcodes.PUTFIELD, mixinName, field, "I"))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 2
            maxLocals = 1
        }

    /** `int <name>() { return this.<field>; }` ([owner] decides whose reference it is written on, so the re-owning is observable). */
    private fun intGetter(name: String, owner: String): MethodNode = instanceMethod(name, "()I") {
        add(VarInsnNode(Opcodes.ALOAD, 0))
        add(FieldInsnNode(Opcodes.GETFIELD, owner, name, "I"))
        add(InsnNode(Opcodes.IRETURN))
    }

    /** Push a "non-trivial but no-locals/no-branches" snippet into the constructor body: `Math.abs(1);`. */
    private fun addAbsCall(list: InsnList) {
        list.add(InsnNode(Opcodes.ICONST_1))
        list.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false))
        list.add(InsnNode(Opcodes.POP))
    }

    private fun invokeInt(clazz: Class<*>, instance: Any, name: String): Int =
        clazz.methods.first { it.name == name }.invoke(instance) as Int
}
