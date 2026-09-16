package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.mixin.Args
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * In-place rewriting (`ModifyArg` / `ModifyArgs` / `ModifyConstant`).
 *
 * These cases **all really define the class and execute it** — because this area's risk is
 * concentrated in the stack juggling of "store the arguments into locals in reverse, change one,
 * push back in order": if the juggling is wrong it either produces `VerifyError` or the caller
 * receives a misaligned value. Asserting only the instruction sequence cannot reveal that, so these
 * cases all take "what the called method actually received" as the source of truth.
 */
class InjectionModifyTest {

    private val owner = "omltest/ModifyTarget"

    // ---------- ModifyArg ----------

    @Test
    fun `modifyArg rewrites an int buried under two later args`() {
        // Target: CallSink.record(seed, "orig", 100L); change arg 0 (int) — under it sit a String and a long
        val clazz = define(runSpec("patchInt", "(I)I", index = 0), targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 7)

        assertEquals(listOf<Any?>(8, "orig", 100L), CallSink.calls.single(), "only the 1st argument should be modified")
    }

    @Test
    fun `modifyArg rewrites a reference argument`() {
        val clazz =
            define(runSpec("patchString", "(Ljava/lang/String;)Ljava/lang/String;", index = 1), targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 7)

        assertEquals(listOf<Any?>(7, "patched", 100L), CallSink.calls.single())
    }

    @Test
    fun `modifyArg rewrites the last (two slot) argument`() {
        val clazz = define(runSpec("patchLong", "(J)J", index = 2), targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 7)

        assertEquals(listOf<Any?>(7, "orig", 601L), CallSink.calls.single())
    }

    @Test
    fun `modifyArg with index minus one finds the argument by type`() {
        // There is only one String among the target arguments, so index = -1 should auto-locate it
        val clazz =
            define(runSpec("patchString", "(Ljava/lang/String;)Ljava/lang/String;", index = -1), targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 7)

        assertEquals(listOf<Any?>(7, "patched", 100L), CallSink.calls.single())
    }

    @Test
    fun `modifyArg multi arg mode can see sibling arguments`() {
        // Multi-argument mode: the handler receives all arguments and returns the new value at the
        // modified position
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArg(
                            ModifyHandlers.OWNER, "multiArg",
                            "(ILjava/lang/String;J)Ljava/lang/String;",
                            index = 1,
                        )
                    }
                }
            }
        }
        val clazz = define(spec, targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 3)

        // multiArg combines (3, "orig", 100) into "3-100"
        assertEquals(listOf<Any?>(3, "3-100", 100L), CallSink.calls.single())
    }

    @Test
    fun `modifyArg passes extras after the modified argument`() {
        // Single-argument mode + extras: handler(modified arg, target method's Arg(0))
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArg(
                            ModifyHandlers.OWNER, "withExtra", "(II)I",
                            index = 0,
                            extras = listOf(DslValue.Arg(0)),
                        )
                    }
                }
            }
        }
        val clazz = define(spec, targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 5)

        // Modified argument 5 -> 5 * 100 + seed(5) = 505
        assertEquals(listOf<Any?>(505, "orig", 100L), CallSink.calls.single())
    }

    @Test
    fun `modifyArg keeps the receiver of an instance call`() {
        // Instance call: the receiver sits **under** the arguments. After modifying, the stack must still
        // be [receiver, arg], otherwise INVOKEVIRTUAL gets a misaligned receiver -> VerifyError or
        // wrong behavior
        val spec = specOf {
            classTarget(owner) {
                method("sb", desc = "()Ljava/lang/String;") {
                    beforeCall(
                        owner = "java/lang/StringBuilder",
                        name = "append",
                        desc = "(I)Ljava/lang/StringBuilder;"
                    ) {
                        modifyArg(ModifyHandlers.OWNER, "times100", "(I)I", index = 0)
                    }
                }
            }
        }
        val clazz = define(spec, targetWithStringBuilder())
        CallSink.clear()

        assertEquals("500", invoke(clazz, "sb"), "the receiver must be intact: 5 -> 500, not anything else")
    }

    // ---------- ModifyArgs ----------

    @Test
    fun `modifyArgs rewrites every argument`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArgs(ModifyHandlers.OWNER, "allArgs", "(Lorg/ohmyloader/api/mixin/Args;)V")
                    }
                }
            }
        }
        val clazz = define(spec, targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 7)

        assertEquals(listOf<Any?>(9, "multi", 42L), CallSink.calls.single())
    }

    @Test
    fun `modifyArgs leaves original values when the handler does not touch them`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArgs(ModifyHandlers.OWNER, "noopArgs", "(Lorg/ohmyloader/api/mixin/Args;)V")
                    }
                }
            }
        }
        val clazz = define(spec, targetWithSinkCall())
        CallSink.clear()

        invoke(clazz, "target", 7)

        assertEquals(
            listOf<Any?>(7, "orig", 100L),
            CallSink.calls.single(),
            "the boxing round-trip must not change the original value"
        )
    }

    @Test
    fun `modifyArgs keeps the receiver of an instance call`() {
        val spec = specOf {
            classTarget(owner) {
                method("sb", desc = "()Ljava/lang/String;") {
                    beforeCall(
                        owner = "java/lang/StringBuilder",
                        name = "append",
                        desc = "(I)Ljava/lang/StringBuilder;"
                    ) {
                        modifyArgs(ModifyHandlers.OWNER, "setFirst", "(Lorg/ohmyloader/api/mixin/Args;)V", emptyList())
                    }
                }
            }
        }
        val clazz = define(spec, targetWithStringBuilder())
        CallSink.clear()

        assertEquals("77", invoke(clazz, "sb"))
    }

    // ---------- ModifyConstant ----------

    @Test
    fun `modifyConstant rewrites a loaded int constant`() {
        // Target: `return 7;` (javac emits BIPUSH, not LDC)
        val spec = specOf {
            classTarget(owner) {
                method("seven", desc = "()I") {
                    afterConstant(value = 7) {
                        modifyConstant(ModifyHandlers.OWNER, "times10", "(I)I")
                    }
                }
            }
        }
        val clazz = define(
            spec,
            classNode(
                methodNode("seven", "()I") {
                    add(IntInsnNode(Opcodes.BIPUSH, 7))
                    add(InsnNode(Opcodes.IRETURN))
                }
            ),
        )

        assertEquals(70, invoke(clazz, "seven"))
    }

    @Test
    fun `modifyConstant rewrites a long constant`() {
        val spec = specOf {
            classTarget(owner) {
                method("big", desc = "()J") {
                    afterConstant(value = 5L) {
                        modifyConstant(ModifyHandlers.OWNER, "times10Long", "(J)J")
                    }
                }
            }
        }
        val clazz = define(
            spec,
            classNode(
                methodNode("big", "()J") {
                    add(LdcInsnNode(5L))
                    add(InsnNode(Opcodes.LRETURN))
                }
            ),
        )

        assertEquals(50L, invoke(clazz, "big"))
    }

    @Test
    fun `modifyConstant rewrites a string constant`() {
        val spec = specOf {
            classTarget(owner) {
                method("text", desc = "()Ljava/lang/String;") {
                    afterConstant(value = "hello") {
                        modifyConstant(ModifyHandlers.OWNER, "shout", "(Ljava/lang/String;)Ljava/lang/String;")
                    }
                }
            }
        }
        val clazz = define(
            spec,
            classNode(
                methodNode("text", "()Ljava/lang/String;") {
                    add(LdcInsnNode("hello"))
                    add(InsnNode(Opcodes.ARETURN))
                }
            ),
        )

        assertEquals("HELLO", invoke(clazz, "text"))
    }

    // ---------- rule-level validation ----------

    @Test
    fun `modifyArg on a non call anchor is reported`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    atHead { modifyArg(ModifyHandlers.OWNER, "patchInt", "(I)I", index = 0) }
                }
            }
        }
        assertTrue(
            spec.verify().any { it.contains("ModifyArg requires a method-call anchor") },
            spec.verify().toString(),
        )
    }

    @Test
    fun `modifyConstant without an after anchor is reported`() {
        val spec = specOf {
            classTarget(owner) {
                method("seven", desc = "()I") {
                    beforeConstant(value = 7) { modifyConstant(ModifyHandlers.OWNER, "times10", "(I)I") }
                }
            }
        }
        assertTrue(
            spec.verify().any { it.contains("ModifyConstant requires an afterConstant") },
            spec.verify().toString(),
        )
    }

    @Test
    fun `modifyArgs with a non void handler is reported`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArgs(ModifyHandlers.OWNER, "patchInt", "(Lorg/ohmyloader/api/mixin/Args;)I")
                    }
                }
            }
        }
        assertTrue(
            spec.verify().any { it.contains("the handler of ModifyArgs must return void") },
            spec.verify().toString(),
        )
    }

    @Test
    fun `modifyArgs without the Args parameter is reported`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArgs(ModifyHandlers.OWNER, "patchInt", "(I)V")
                    }
                }
            }
        }
        assertTrue(
            spec.verify().any { it.contains("the first parameter of ModifyArgs' handler must be") },
            spec.verify().toString(),
        )
    }

    @Test
    fun `modifyArg return type mismatch with the target argument fails hard`() {
        // The handler returns String, but arg 0 is int — provably invalid, must fail hard
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = CallSink.OWNER, name = "record") {
                        modifyArg(
                            ModifyHandlers.OWNER,
                            "patchString",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            index = 0
                        )
                    }
                }
            }
        }
        val node = targetWithSinkCall()

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext(owner, node)) }
        assertTrue(error.message!!.contains("must equal the type of the modified argument"), error.message)
    }

    @Test
    fun `auto index with several type matches fails hard`() {
        // record(int, String, long) has no second String; use an all-int call site to create "multiple
        // candidates"
        val spec = specOf {
            classTarget(owner) {
                method("two", desc = "()V") {
                    beforeCall(owner = CallSink.OWNER, name = "pair") {
                        modifyArg(ModifyHandlers.OWNER, "patchInt", "(I)I", index = -1)
                    }
                }
            }
        }
        val node = classNode(
            methodNode("two", "()V") {
                add(InsnNode(Opcodes.ICONST_1))
                add(InsnNode(Opcodes.ICONST_2))
                add(MethodInsnNode(Opcodes.INVOKESTATIC, CallSink.OWNER, "pair", "(II)V", false))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext(owner, node)) }
        assertTrue(error.message!!.contains("auto-locates multiple candidates by type"), error.message)
    }

    // ---------- scaffolding ----------

    private fun runSpec(handler: String, handlerDesc: String, index: Int): InjectionSpec = specOf {
        classTarget(owner) {
            method("target", desc = "(I)I") {
                beforeCall(owner = CallSink.OWNER, name = "record") {
                    modifyArg(ModifyHandlers.OWNER, handler, handlerDesc, index = index)
                }
            }
        }
    }

    private fun classNode(vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            this.methods.addAll(methods)
        }

    /** `static int target(int seed) { CallSink.record(seed, "orig", 100L); return 0; }` */
    private fun targetWithSinkCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(LdcInsnNode("orig"))
            add(LdcInsnNode(100L))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, CallSink.OWNER, "record", "(ILjava/lang/String;J)V", false))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        }
    )

    /** `static String sb() { StringBuilder b = new StringBuilder(); b.append(5); return b.toString(); }` */
    private fun targetWithStringBuilder(): ClassNode = classNode(
        methodNode("sb", "()Ljava/lang/String;") {
            add(TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"))
            add(InsnNode(Opcodes.DUP))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false))
            add(VarInsnNode(Opcodes.ASTORE, 0))
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(InsnNode(Opcodes.ICONST_5))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(I)Ljava/lang/StringBuilder;", false,
                )
            )
            add(InsnNode(Opcodes.POP))
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false,
                )
            )
            add(InsnNode(Opcodes.ARETURN))
        }
    )

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 8
            maxLocals = 8
        }

    /** Inject → write back → define and link (`VerifyError` would be thrown here). */
    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionModifyTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)
}

/** The target class calls it to record "what arguments it actually received" — the observation
 *  point of the whole test series. */
object CallSink {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/CallSink"

    val calls = mutableListOf<List<Any?>>()

    fun clear() = calls.clear()

    @JvmStatic
    fun record(a: Int, b: String, c: Long) {
        calls += listOf(a, b, c)
    }

    /** Used to create a "type-based auto-location has multiple candidates" scenario. */
    @JvmStatic
    fun pair(a: Int, b: Int) {
        calls += listOf(a, b)
    }
}

/** Handlers (all must be static — the engine emits INVOKESTATIC). */
object ModifyHandlers {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/ModifyHandlers"

    @JvmStatic
    fun patchInt(v: Int): Int = v + 1

    @JvmStatic
    fun times100(v: Int): Int = v * 100

    @JvmStatic
    fun times10(v: Int): Int = v * 10

    @JvmStatic
    fun times10Long(v: Long): Long = v * 10

    @JvmStatic
    fun shout(v: String): String = v.uppercase()

    @JvmStatic
    fun patchString(v: String): String = "patched"

    @JvmStatic
    fun patchLong(v: Long): Long = v + 501

    @JvmStatic
    fun withExtra(v: Int, seed: Int): Int = v * 100 + seed

    /** Multi-argument mode: can see the sibling arguments, returns the new value at the modified position. */
    @JvmStatic
    fun multiArg(a: Int, b: String, c: Long): String = "$a-$c"

    @JvmStatic
    fun allArgs(args: Args) {
        args[0] = 9
        args[1] = "multi"
        args[2] = 42L
    }

    @JvmStatic
    fun noopArgs(args: Args) {
        // Reads the original values back, verifying that boxing round-trip does not change numbers
    }

    @JvmStatic
    fun setFirst(args: Args) {
        args[0] = 77
    }
}
