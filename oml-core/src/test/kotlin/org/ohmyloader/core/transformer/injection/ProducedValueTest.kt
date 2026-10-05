package org.ohmyloader.core.transformer.injection

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.ClassTargetBuilder
import org.ohmyloader.api.inject.HandlerKind
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.mixin.ClassMerger
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val OWNER = "omltest/ValueTarget"
private const val MIXIN = "omltest/ValueMixin"
private const val STUB = "net/minecraft/Foo"
private const val HOOKS = "org/ohmyloader/core/transformer/injection/ValueHooks"
private const val PROBE = "org/ohmyloader/core/transformer/injection/ValueProbe"

/** Observation point: who ran and when (used to prove that "the expression still executed, only its result was replaced"). */
object ValueProbe {
    val events = mutableListOf<String>()

    @JvmStatic
    fun hit(tag: String) {
        events += tag
    }
}

/** Handlers used by the static form (`Payload.ModifyExpressionValue` / `Payload.TransformReturn`). */
object ValueHooks {
    @JvmStatic
    fun triple(value: Int): Int {
        ValueProbe.hit("triple")
        return value * 3
    }

    @JvmStatic
    fun shout(value: String): String {
        ValueProbe.hit("shout")
        return "$value!"
    }
}

/**
 * Modifying the value **produced by the anchor**: `MODIFY_RETURN` (the return value) and
 * `MODIFY_EXPR_VALUE` (the result of an expression). Unlike the `@ModifyArg`/`@ModifyConstant` family,
 * the value is not "a chosen argument/constant/local variable" but **what the anchor instruction left
 * behind** — a call is its return value, a field read is the field's value, a constant is that
 * constant; the type therefore can only be resolved from that instruction (see `ProducedValue`). All
 * cases go through define-and-run: whether the type/stack position is correct is decided only when the
 * JVM defines the class.
 */
class ProducedValueTest {

    // ---------- MODIFY_RETURN ----------

    @Test
    fun `modifyReturnValue rewrites the value of every return`() {
        val clazz = build(
            targetSpec {
                method("pick", desc = "(Z)I") {
                    atReturn {
                        handlerCall(OWNER, "onReturn", "(I)I", kind = HandlerKind.MODIFY_RETURN)
                    }
                }
            },
        )

        ValueProbe.events.clear()
        val instance = clazz.getDeclaredConstructor().newInstance()
        val pick = clazz.getMethod("pick", Boolean::class.javaPrimitiveType)

        // Both return paths must reach the handler: 7 → 107, 9 → 109
        assertEquals(107, pick.invoke(instance, true))
        assertEquals(109, pick.invoke(instance, false))
        assertEquals(listOf("handler.onReturn", "handler.onReturn"), ValueProbe.events)
    }

    @Test
    fun `atTail only rewrites the final return`() {
        val clazz = build(
            targetSpec {
                method("pick", desc = "(Z)I") {
                    atTail {
                        handlerCall(OWNER, "onReturn", "(I)I", kind = HandlerKind.MODIFY_RETURN)
                    }
                }
            },
        )

        val instance = clazz.getDeclaredConstructor().newInstance()
        val pick = clazz.getMethod("pick", Boolean::class.javaPrimitiveType)

        assertEquals(7, pick.invoke(instance, true), "the first return must not be hit by TAIL")
        assertEquals(109, pick.invoke(instance, false))
    }

    @Test
    fun `modifyReturnValue refuses a void target`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("callVoid", desc = "()V") {
                        atReturn {
                            handlerCall(OWNER, "onReturn", "(I)I", kind = HandlerKind.MODIFY_RETURN)
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "returns void")
    }

    @Test
    fun `modifyReturnValue refuses anchors other than RETURN`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("callValue", desc = "()I") {
                        beforeCall(STUB, "value", "()I") {
                            handlerCall(OWNER, "onReturn", "(I)I", kind = HandlerKind.MODIFY_RETURN)
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "must be RETURN")
    }

    @Test
    fun `modifyReturnValue refuses a handler whose value type differs`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("pick", desc = "(Z)I") {
                        atReturn {
                            handlerCall(OWNER, "onReturnLong", "(J)J", kind = HandlerKind.MODIFY_RETURN)
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "the value's type")
    }

    // ---------- MODIFY_EXPR_VALUE ----------

    @Test
    fun `modifyExpressionValue replaces a call result and still runs the call`() {
        val clazz = build(
            targetSpec {
                method("callValue", desc = "()I") {
                    afterCall(STUB, "value", "()I") {
                        handlerCall(OWNER, "onValue", "(I)I", kind = HandlerKind.MODIFY_EXPR_VALUE)
                    }
                }
            },
        )

        ValueProbe.events.clear()
        val instance = clazz.getDeclaredConstructor().newInstance()

        // Stub returns 7, handler ×3 ⇒ 21; the event order shows the **call itself still executed**
        // (stub first, then handler) — this is exactly its division of labor with `@Redirect`,
        // which would replace the whole call.
        assertEquals(21, clazz.getMethod("callValue").invoke(instance))
        assertEquals(listOf("stub.value", "handler.onValue"), ValueProbe.events)
    }

    @Test
    fun `modifyExpressionValue rewrites a field read`() {
        val clazz = build(
            targetSpec {
                method("readNumber", desc = "()I") {
                    afterField(STUB, "NUMBER", "I", Opcodes.GETSTATIC) {
                        handlerCall(OWNER, "onNumber", "(I)I", kind = HandlerKind.MODIFY_EXPR_VALUE)
                    }
                }
            },
        )

        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals(50, clazz.getMethod("readNumber").invoke(instance), "stub field is 5, handler multiplies by 10")
    }

    @Test
    fun `modifyExpressionValue works for a reference-typed value`() {
        val clazz = build(
            targetSpec {
                method("callText", desc = "()Ljava/lang/String;") {
                    afterCall(STUB, "text", "()Ljava/lang/String;") {
                        handlerCall(
                            OWNER, "onText", "(Ljava/lang/String;)Ljava/lang/String;",
                            kind = HandlerKind.MODIFY_EXPR_VALUE,
                        )
                    }
                }
            },
        )

        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals("hit?", clazz.getMethod("callText").invoke(instance))
    }

    @Test
    fun `modifyExpressionValue refuses a before-position anchor`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("callValue", desc = "()I") {
                        beforeCall(STUB, "value", "()I") {
                            handlerCall(OWNER, "onValue", "(I)I", kind = HandlerKind.MODIFY_EXPR_VALUE)
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "already been produced")
    }

    @Test
    fun `modifyExpressionValue refuses a void call`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("callVoid", desc = "()V") {
                        afterCall(STUB, "sink", "(I)V") {
                            handlerCall(OWNER, "onValue", "(I)I", kind = HandlerKind.MODIFY_EXPR_VALUE)
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "produces no value")
    }

    @Test
    fun `modifyExpressionValue points at the constructor call when the anchor is NEW`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("makeOne", desc = "()V") {
                        beforeNew(STUB) {
                            handlerCall(OWNER, "onValue", "(I)I", kind = HandlerKind.MODIFY_EXPR_VALUE)
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "not-yet-initialized")
    }

    @Test
    fun `modifyExpressionValue refuses a handler whose value type differs`() {
        val failure = assertFailsWith<InjectionError> {
            build(
                targetSpec {
                    method("callValue", desc = "()I") {
                        afterCall(STUB, "value", "()I") {
                            handlerCall(
                                OWNER, "onText", "(Ljava/lang/String;)Ljava/lang/String;",
                                kind = HandlerKind.MODIFY_EXPR_VALUE,
                            )
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "the value's type")
    }

    // ---------- static form (non-merged mixin / adapters using the DSL directly) ----------

    @Test
    fun `the static payload rewrites a call result too`() {
        // No merge: the handler is an external static method, and the value type is still decided by
        // the anchor
        val clazz = buildStatic(
            specOf {
                classTarget(OWNER) {
                    method("callValue", desc = "()I") {
                        afterCall(STUB, "value", "()I") {
                            modifyExpressionValue(HOOKS, "triple", "(I)I")
                        }
                    }
                }
            },
        )

        ValueProbe.events.clear()
        val instance = clazz.getDeclaredConstructor().newInstance()
        assertEquals(21, clazz.getMethod("callValue").invoke(instance))
        assertEquals(listOf("stub.value", "triple"), ValueProbe.events)
    }

    @Test
    fun `the static payload checks the declared type against the anchor`() {
        val failure = assertFailsWith<InjectionError> {
            buildStatic(
                specOf {
                    classTarget(OWNER) {
                        method("callText", desc = "()Ljava/lang/String;") {
                            afterCall(STUB, "text", "()Ljava/lang/String;") {
                                modifyExpressionValue(HOOKS, "triple", "(I)I")
                            }
                        }
                    }
                },
            )
        }
        assertContains(failure.message.orEmpty(), "the value's type")
    }

    // ---------- startup-time self-check ----------

    @Test
    fun `verify reports anchors that cannot produce a value`() {
        val before = specOf {
            classTarget(OWNER) {
                method("callValue", desc = "()I") {
                    beforeCall(STUB, "value", "()I") {
                        modifyExpressionValue(HOOKS, "triple", "(I)I")
                    }
                }
            }
        }.verify(null)
        assertTrue(before.any { it.contains("the value is not yet on the stack") }, before.toString())

        val notExpression = specOf {
            classTarget(OWNER) {
                method("callValue", desc = "()I") {
                    atHead { modifyExpressionValue(HOOKS, "triple", "(I)I") }
                }
            }
        }.verify(null)
        assertTrue(notExpression.any { it.contains("produces a value") }, notExpression.toString())
    }

    @Test
    fun `verify reports the merged handler shapes`() {
        val wrongAnchor = specOf {
            classTarget(OWNER) {
                method("callValue", desc = "()I") {
                    beforeCall(STUB, "value", "()I") {
                        handlerCall(OWNER, "onReturn", "(I)I", kind = HandlerKind.MODIFY_RETURN)
                    }
                }
            }
        }.verify(null)
        assertTrue(
            wrongAnchor.any { it.contains("@ModifyReturnValue") && it.contains("RETURN") },
            wrongAnchor.toString(),
        )

        val wrongShape = specOf {
            classTarget(OWNER) {
                method("callValue", desc = "()I") {
                    afterCall(STUB, "value", "()I") {
                        handlerCall(OWNER, "onValue", "()V", kind = HandlerKind.MODIFY_EXPR_VALUE)
                    }
                }
            }
        }.verify(null)
        assertTrue(wrongShape.any { it.contains("@ModifyExpressionValue") }, wrongShape.toString())
    }

    // ---------- fixtures ----------

    private class Probe(val loader: ClassLoader, val clazz: Class<*>)

    private class ProbeLoader : ClassLoader(ProducedValueTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> =
            defineClass(name.replace('/', '.'), bytes, 0, bytes.size)
    }

    /** Merge (handler merged into target) → inject → define following the production write-back pattern. */
    private fun build(spec: InjectionSpec): Class<*> {
        val target = mergedTarget()
        spec.transform(TransformContext(OWNER, target))
        return define(target)
    }

    /** No merge: only the target class + static handlers. */
    private fun buildStatic(spec: InjectionSpec): Class<*> {
        val target = targetClass()
        spec.transform(TransformContext(OWNER, target))
        return define(target)
    }

    /**
     * Define the target class (the stub and record points are on the test classpath).
     *
     * The write-back must use `COMPUTE_MAXS` (matching production): recomputing frames would mask
     * errors like "the new slots introduced by injection inconsistent with the existing
     * `StackMapTable`", which tests a different path (see `OMLClassLoader`).
     */
    private fun define(node: ClassNode): Class<*> {
        val loader = ProbeLoader()
        // The stub must be defined first: the `INVOKESTATIC net/minecraft/Foo.value()` in the
        // target class must resolve to it
        val stub = write(stub())
        loader.define(STUB, stub)
        return loader.define(node.name, write(node))
    }

    private fun write(node: ClassNode): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    /**
     * "Game class" stub: `class net/minecraft/Foo`.
     *
     * Each of the two static methods records a marker before returning a fixed value — so "the call
     * still happens" and "the result was swapped" can be asserted separately; there is also a static
     * field for the "field read" anchor and an empty implementation for the "void call" rejection
     * cases.
     */
    private fun stub(): ClassNode = node(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, STUB, "java/lang/Object").apply {
        fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "NUMBER", "I", null, 5))
        method(Opcodes.ACC_PUBLIC, "<init>", "()V") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            add(InsnNode(Opcodes.RETURN))
        }
        method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I") {
            add(LdcInsnNode("stub.value"))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "hit", "(Ljava/lang/String;)V", false))
            add(IntInsnNode(Opcodes.BIPUSH, 7))
            add(InsnNode(Opcodes.IRETURN))
        }
        method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "text", "()Ljava/lang/String;") {
            add(LdcInsnNode("hit"))
            add(InsnNode(Opcodes.ARETURN))
        }
        method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sink", "(I)V") {
            add(InsnNode(Opcodes.RETURN))
        }
    }

    private fun mergedTarget(): ClassNode {
        val target = targetClass()
        val result = ClassMerger.merge(
            ClassMerger.MixinClass(
                modId = "testmod",
                className = MIXIN.replace('/', '.'),
                targetInternal = OWNER,
                node = mixin(),
                handlerMethods = HANDLERS,
            ),
            target,
            mutableMapOf(),
        )
        assertEquals(emptyList(), result.problems, result.problems.toString())
        return target
    }

    private fun targetSpec(block: ClassTargetBuilder.() -> Unit): InjectionSpec =
        specOf { classTarget(OWNER, block) }

    private fun node(access: Int, name: String, superName: String): ClassNode = ClassNode(Opcodes.ASM9).also {
        it.version = Opcodes.V17
        it.access = access
        it.name = name
        it.superName = superName
    }

    private fun ClassNode.method(access: Int, name: String, desc: String, body: InsnList.() -> Unit = {}) {
        methods.add(
            MethodNode(access, name, desc, null, null).apply {
                body(instructions)
                maxStack = 8
                maxLocals = 8
            },
        )
    }

    /**
     * Target class.
     *
     * `pick` has **two returns**; `callValue`/`readNumber`/`callText` each hand a "game class"
     * produced value to `return` or consume it directly — whether the value changed and whether
     * the call still ran can both be observed from outside.
     */
    private fun targetClass(): ClassNode = node(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, OWNER, "java/lang/Object")
        .apply {
            method(Opcodes.ACC_PUBLIC, "<init>", "()V") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            }
            method(Opcodes.ACC_PUBLIC, "pick", "(Z)I") {
                val other = LabelNode()
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(JumpInsnNode(Opcodes.IFEQ, other))
                add(IntInsnNode(Opcodes.BIPUSH, 7))
                add(InsnNode(Opcodes.IRETURN))
                add(other)
                // The branch target must carry its own stack frame: the verifier for class file version 50+
                // only pushes forward frame by frame, and the write-back uses the production
                // `COMPUTE_MAXS` (which does not recompute frames)
                add(FrameNode(Opcodes.F_NEW, 2, arrayOf<Any>(OWNER, Opcodes.INTEGER), 0, emptyArray()))
                add(IntInsnNode(Opcodes.BIPUSH, 9))
                add(InsnNode(Opcodes.IRETURN))
            }
            method(Opcodes.ACC_PUBLIC, "callValue", "()I") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, STUB, "value", "()I", false))
                add(InsnNode(Opcodes.IRETURN))
            }
            method(Opcodes.ACC_PUBLIC, "readNumber", "()I") {
                add(FieldInsnNode(Opcodes.GETSTATIC, STUB, "NUMBER", "I"))
                add(InsnNode(Opcodes.IRETURN))
            }
            method(Opcodes.ACC_PUBLIC, "callText", "()Ljava/lang/String;") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, STUB, "text", "()Ljava/lang/String;", false))
                add(InsnNode(Opcodes.ARETURN))
            }
            method(Opcodes.ACC_PUBLIC, "callVoid", "()V") {
                add(InsnNode(Opcodes.ICONST_1))
                add(MethodInsnNode(Opcodes.INVOKESTATIC, STUB, "sink", "(I)V", false))
                add(InsnNode(Opcodes.RETURN))
            }
            method(Opcodes.ACC_PUBLIC, "makeOne", "()V") {
                add(TypeInsnNode(Opcodes.NEW, STUB))
                add(InsnNode(Opcodes.DUP))
                add(MethodInsnNode(Opcodes.INVOKESPECIAL, STUB, "<init>", "()V", false))
                add(InsnNode(Opcodes.POP))
                add(InsnNode(Opcodes.RETURN))
            }
        }

    /** The handlers are merged into the target class with the class — so their `this` is the target instance. */
    private fun mixin(): ClassNode = node(Opcodes.ACC_PUBLIC, MIXIN, "java/lang/Object").apply {
        method(Opcodes.ACC_PUBLIC, "onReturn", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(IntInsnNode(Opcodes.BIPUSH, 100))
            add(InsnNode(Opcodes.IADD))
            add(InsnNode(Opcodes.DUP))
            add(LdcInsnNode("handler.onReturn"))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "hit", "(Ljava/lang/String;)V", false))
            add(InsnNode(Opcodes.IRETURN))
        }
        method(Opcodes.ACC_PUBLIC, "onReturnLong", "(J)J") {
            add(VarInsnNode(Opcodes.LLOAD, 1))
            add(InsnNode(Opcodes.LRETURN))
        }
        method(Opcodes.ACC_PUBLIC, "onValue", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.ICONST_3))
            add(InsnNode(Opcodes.IMUL))
            add(InsnNode(Opcodes.DUP))
            add(LdcInsnNode("handler.onValue"))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "hit", "(Ljava/lang/String;)V", false))
            add(InsnNode(Opcodes.IRETURN))
        }
        method(Opcodes.ACC_PUBLIC, "onNumber", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(IntInsnNode(Opcodes.BIPUSH, 10))
            add(InsnNode(Opcodes.IMUL))
            add(InsnNode(Opcodes.IRETURN))
        }
        method(Opcodes.ACC_PUBLIC, "onText", "(Ljava/lang/String;)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(LdcInsnNode("?"))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false,
                ),
            )
            add(InsnNode(Opcodes.ARETURN))
        }
    }
}

/**
 * Must be **name-preserved + visibility-promoted** so the injection point's `INVOKEVIRTUAL` can
 * call it (the merger moves members by name/descriptor).
 */
private val HANDLERS = setOf(
    "onReturn(I)I",
    "onReturnLong(J)J",
    "onValue(I)I",
    "onNumber(I)I",
    "onText(Ljava/lang/String;)Ljava/lang/String;",
)
