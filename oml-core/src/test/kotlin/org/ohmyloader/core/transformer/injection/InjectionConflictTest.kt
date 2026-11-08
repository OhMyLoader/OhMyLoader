package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exclusive-injection conflicts: `@Redirect` / `@WrapOperation` / `@WrapBranch` replace the instruction
 * they anchor on, so two rules on the same site cannot both run. The engine resolves both on the
 * **untouched** method and names both, instead of letting the second silently miss after the first
 * replaced the instruction.
 */
class InjectionConflictTest {

    private val owner = "omltest/ConflictTarget"

    @Test
    fun `two redirects on the same call are reported with both handlers`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)V") {
                    redirectsTo("first")
                }
                method("target", desc = "(I)V") {
                    redirectsTo("second")
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithCall()))
        }
        assertTrue(failure.message!!.contains("conflicting exclusive injections"), failure.message)
        assertTrue(failure.message!!.contains("first"), failure.message)
        assertTrue(failure.message!!.contains("second"), failure.message)
    }

    @Test
    fun `a redirect and a wrap on the same call conflict`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)V") {
                    redirectsTo("first")
                }
                method("target", desc = "(I)V") {
                    beforeCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V") {
                        wrapOperation(
                            ConflictHandlers.OWNER, "wrap",
                            "(ILorg/ohmyloader/api/hook/Operation;)V",
                        )
                    }
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithCall()))
        }
        assertTrue(failure.message!!.contains("Redirect"), failure.message)
        assertTrue(failure.message!!.contains("WrapOperation"), failure.message)
    }

    @Test
    fun `a condition and a redirect may share a call`() {
        ConflictSink.clear()
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)V") {
                    beforeCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V") {
                        wrapWithCondition(ConflictHandlers.OWNER, "deny", "(I)Z")
                    }
                }
                method("target", desc = "(I)V") {
                    redirectsTo("blocked")
                }
            }
        }

        val clazz = define(spec, targetWithCall())
        invoke(clazz, 7)

        assertEquals(emptyList(), ConflictSink.calls, "the false condition must skip the redirected handler")
    }

    @Test
    fun `two wraps on the same call conflict`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)V") {
                    beforeCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V") {
                        wrapOperation(ConflictHandlers.OWNER, "wrap", "(ILorg/ohmyloader/api/hook/Operation;)V")
                    }
                }
                method("target", desc = "(I)V") {
                    beforeCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V") {
                        wrapOperation(ConflictHandlers.OWNER, "wrap2", "(ILorg/ohmyloader/api/hook/Operation;)V")
                    }
                }
            }
        }

        assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithCall()))
        }
    }

    @Test
    fun `two branch decisions on the same jump conflict`() {
        val spec = specOf {
            classTarget(owner) {
                method("cmp", desc = "(I)I") {
                    atJump(opcode = Opcodes.IF_ICMPLE) {
                        wrapBranch(ConflictHandlers.OWNER, "deny", "(II)Z")
                    }
                }
                method("cmp", desc = "(I)I") {
                    atJump(opcode = Opcodes.IF_ICMPLE) {
                        wrapBranch(ConflictHandlers.OWNER, "deny", "(II)Z")
                    }
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, compareTarget()))
        }
        assertTrue(failure.message!!.contains("WrapBranch"), failure.message)
    }

    @Test
    fun `two whole-method wraps conflict`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)V") {
                    wrapMethod(ConflictHandlers.OWNER, "wrapMethod", "(ILorg/ohmyloader/api/hook/Operation;)V")
                }
                method("target", desc = "(I)V") {
                    wrapMethod(ConflictHandlers.OWNER, "wrapMethod2", "(ILorg/ohmyloader/api/hook/Operation;)V")
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithCall()))
        }
        assertTrue(failure.message!!.contains("whole method"), failure.message)
    }

    @Test
    fun `exclusive rules on different calls do not conflict`() {
        val spec = specOf {
            classTarget(owner) {
                method("two", desc = "(II)V") {
                    redirectCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V", handlerOwner = ConflictHandlers.OWNER, handlerMethod = "blocked")
                }
                method("two", desc = "(II)V") {
                    redirectCall(owner = ConflictSink.OWNER, name = "recordOther", desc = "(I)V", handlerOwner = ConflictHandlers.OWNER, handlerMethod = "blocked")
                }
            }
        }

        assertTrue(spec.transform(TransformContext(owner, twoCallTarget())))
    }

    @Test
    fun `non-exclusive rules may stack on the same call`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)V") {
                    beforeCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V") {
                        modifyArg(ConflictHandlers.OWNER, "double", "(I)I", index = 0)
                    }
                }
                method("target", desc = "(I)V") {
                    beforeCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V") {
                        modifyArg(ConflictHandlers.OWNER, "double", "(I)I", index = 0)
                    }
                }
            }
        }

        assertTrue(spec.transform(TransformContext(owner, targetWithCall())))
    }

    // ---------- fixtures ----------

    private fun org.ohmyloader.api.inject.MethodRuleBuilder.redirectsTo(handler: String) {
        redirectCall(owner = ConflictSink.OWNER, name = "record", desc = "(I)V", handlerOwner = ConflictHandlers.OWNER, handlerMethod = handler)
    }

    /** `static void target(int x) { ConflictSink.record(x); }` */
    private fun targetWithCall(): ClassNode = classNode(
        method("target", "(I)V") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ConflictSink.OWNER, "record", "(I)V", false))
            add(InsnNode(Opcodes.RETURN))
        },
    )

    /** `static void two(int a, int b) { ConflictSink.record(a); ConflictSink.recordOther(b); }` */
    private fun twoCallTarget(): ClassNode = classNode(
        method("two", "(II)V") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ConflictSink.OWNER, "record", "(I)V", false))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ConflictSink.OWNER, "recordOther", "(I)V", false))
            add(InsnNode(Opcodes.RETURN))
        },
    )

    /** `static int cmp(int x) { if (x > 5) return 100; return 0; }` */
    private fun compareTarget(): ClassNode = classNode(
        method("cmp", "(I)I") {
            val otherwise = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_5))
            add(JumpInsnNode(Opcodes.IF_ICMPLE, otherwise))
            add(org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 100))
            add(InsnNode(Opcodes.IRETURN))
            add(otherwise)
            add(
                org.objectweb.asm.tree.FrameNode(
                    Opcodes.F_NEW, 1, arrayOf<Any>(Opcodes.INTEGER), 0, emptyArray<Any>(),
                ),
            )
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun classNode(vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            this.methods.addAll(methods)
        }

    private fun method(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 8
            maxLocals = 8
        }

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionConflictTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, vararg args: Any?) {
        clazz.methods.first { it.name == "target" }.invoke(null, *args)
    }

    object ConflictSink {
        val OWNER: String = ConflictSink::class.java.name.replace('.', '/')
        val calls = mutableListOf<Int>()

        fun clear() = calls.clear()

        @JvmStatic
        fun record(value: Int) {
            calls += value
        }

        @JvmStatic
        fun recordOther(value: Int) {
            calls += value
        }
    }

    object ConflictHandlers {
        val OWNER: String = ConflictHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun blocked(value: Int) {
            ConflictSink.calls += value
        }

        @JvmStatic
        fun first(value: Int) {
            ConflictSink.calls += value
        }

        @JvmStatic
        fun second(value: Int) {
            ConflictSink.calls += value
        }

        @JvmStatic
        fun deny(value: Int): Boolean = false

        @JvmStatic
        fun wrap(value: Int, operation: org.ohmyloader.api.hook.Operation<Void>) {
            operation.call(value)
        }

        @JvmStatic
        fun wrap2(value: Int, operation: org.ohmyloader.api.hook.Operation<Void>) {
            operation.call(value)
        }

        @JvmStatic
        fun wrapMethod(value: Int, operation: org.ohmyloader.api.hook.Operation<Void>) {
            operation.call(value)
        }

        @JvmStatic
        fun wrapMethod2(value: Int, operation: org.ohmyloader.api.hook.Operation<Void>) {
            operation.call(value)
        }

        @JvmStatic
        fun double(value: Int): Int = value * 2
    }
}
