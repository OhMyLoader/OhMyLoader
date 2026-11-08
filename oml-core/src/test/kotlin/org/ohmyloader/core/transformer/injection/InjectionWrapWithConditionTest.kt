package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Operation gating (`WrapWithCondition`). Every positive case **defines and executes** the class: the
 * conditional branch and the skip label's frame are exactly the parts that fail as a `VerifyError` or
 * let the original operation run when it should not.
 */
class InjectionWrapWithConditionTest {

    private val owner = "omltest/WrapConditionTarget"

    @Test
    fun `a false condition skips an instance call`() {
        WrapSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = WrapReceiver.OWNER, name = "record", desc = "(I)V") {
                            wrapWithCondition(
                                WrapConditionHandlers.OWNER, "denyReceiver",
                                "(L${WrapReceiver.OWNER};I)Z",
                            )
                        }
                    }
                }
            },
            targetWithInstanceCall(),
        )

        invoke(clazz, "target", 7)

        assertEquals(emptyList(), WrapSink.calls, "the original call must be skipped")
    }

    @Test
    fun `a true condition runs an instance call`() {
        WrapSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = WrapReceiver.OWNER, name = "record", desc = "(I)V") {
                            wrapWithCondition(
                                WrapConditionHandlers.OWNER, "allowReceiver",
                                "(L${WrapReceiver.OWNER};I)Z",
                            )
                        }
                    }
                }
            },
            targetWithInstanceCall(),
        )

        invoke(clazz, "target", 7)

        assertEquals(listOf(7), WrapSink.calls, "the original call must run unchanged")
    }

    @Test
    fun `a false condition skips a static call`() {
        WrapSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = WrapSink.OWNER, name = "record", desc = "(I)V") {
                            wrapWithCondition(WrapConditionHandlers.OWNER, "denyStatic", "(I)Z")
                        }
                    }
                }
            },
            targetWithStaticCall(),
        )

        invoke(clazz, "target", 9)

        assertEquals(emptyList(), WrapSink.calls)
    }

    @Test
    fun `a false condition skips a field write`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("put", desc = "(I)I") {
                        beforeField(owner = WrapReceiver.OWNER, name = "value", desc = "I", opcode = Opcodes.PUTFIELD) {
                            wrapWithCondition(
                                WrapConditionHandlers.OWNER, "denyReceiver",
                                "(L${WrapReceiver.OWNER};I)Z",
                            )
                        }
                    }
                }
            },
            targetWithFieldWrite(),
        )

        val readBack = invoke(clazz, "put", 42)

        assertEquals(0, readBack, "the field write must be skipped")
    }

    @Test
    fun `a non-void operation is refused`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(
                        owner = "java/lang/Integer", name = "parseInt",
                        desc = "(Ljava/lang/String;)I",
                    ) {
                        wrapWithCondition(WrapConditionHandlers.OWNER, "denyString", "(Ljava/lang/String;)Z")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithNonVoidCall()))
        }
        assertTrue(failure.message!!.contains("require"), failure.message)
    }

    @Test
    fun `a false condition skips a non-void call whose result is popped`() {
        ProduceSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = ProduceOps.OWNER, name = "produce", desc = "(I)I") {
                            wrapWithCondition(WrapConditionHandlers.OWNER, "denyStatic", "(I)Z")
                        }
                    }
                }
            },
            targetWithPoppedCall(),
        )

        val result = invoke(clazz, "target", 5)

        assertEquals(emptyList(), ProduceSink.calls, "the non-void call must be skipped")
        assertEquals(5, result)
    }

    @Test
    fun `a true condition runs a non-void call whose result is popped`() {
        ProduceSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = ProduceOps.OWNER, name = "produce", desc = "(I)I") {
                            wrapWithCondition(WrapConditionHandlers.OWNER, "allowStatic", "(I)Z")
                        }
                    }
                }
            },
            targetWithPoppedCall(),
        )

        val result = invoke(clazz, "target", 5)

        assertEquals(listOf(5), ProduceSink.calls)
        assertEquals(5, result)
    }

    @Test
    fun `a non-void call whose result is used is refused`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = ProduceOps.OWNER, name = "produce", desc = "(I)I") {
                        wrapWithCondition(WrapConditionHandlers.OWNER, "denyStatic", "(I)Z")
                    }
                    require(1)
                }
            }
        }

        assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithUsedCall()))
        }
    }

    // ---------- fixtures ----------

    private fun classNode(vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            this.methods.addAll(methods)
        }

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 8
            maxLocals = 8
        }

    private fun newReceiver(list: InsnList) {
        list.add(TypeInsnNode(Opcodes.NEW, WrapReceiver.OWNER))
        list.add(InsnNode(Opcodes.DUP))
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, WrapReceiver.OWNER, "<init>", "()V", false))
    }

    /** `static int target(int seed) { WrapReceiver r = new WrapReceiver(); r.record(seed); return 0; }` */
    private fun targetWithInstanceCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            newReceiver(this)
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, WrapReceiver.OWNER, "record", "(I)V", false))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { WrapSink.record(seed); return 0; }` */
    private fun targetWithStaticCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, WrapSink.OWNER, "record", "(I)V", false))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int put(int v) { WrapReceiver r = new WrapReceiver(); r.value = v; return r.value; }` */
    private fun targetWithFieldWrite(): ClassNode = classNode(
        methodNode("put", "(I)I") {
            newReceiver(this)
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(FieldInsnNode(Opcodes.PUTFIELD, WrapReceiver.OWNER, "value", "I"))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(FieldInsnNode(Opcodes.GETFIELD, WrapReceiver.OWNER, "value", "I"))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { int x = Integer.parseInt("5"); return x; }` — a non-void result is used. */
    private fun targetWithNonVoidCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(LdcInsnNode("5"))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { ProduceOps.produce(seed); return seed; }` — the result is POPped. */
    private fun targetWithPoppedCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ProduceOps.OWNER, "produce", "(I)I", false))
            add(InsnNode(Opcodes.POP))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { return ProduceOps.produce(seed); }` — the result is used. */
    private fun targetWithUsedCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ProduceOps.OWNER, "produce", "(I)I", false))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionWrapWithConditionTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    class WrapReceiver {
        @JvmField
        var value: Int = 0

        fun record(x: Int) {
            WrapSink.calls += x
        }

        companion object {
            val OWNER: String = WrapReceiver::class.java.name.replace('.', '/')
        }
    }

    object WrapSink {
        val OWNER: String = WrapSink::class.java.name.replace('.', '/')

        val calls = mutableListOf<Int>()

        fun clear() = calls.clear()

        @JvmStatic
        fun record(x: Int) {
            calls += x
        }
    }

    object WrapConditionHandlers {
        val OWNER: String = WrapConditionHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun denyReceiver(receiver: WrapReceiver, value: Int): Boolean = false

        @JvmStatic
        fun allowReceiver(receiver: WrapReceiver, value: Int): Boolean = true

        @JvmStatic
        fun denyStatic(value: Int): Boolean = false

        @JvmStatic
        fun allowStatic(value: Int): Boolean = true

        @JvmStatic
        fun denyString(value: String): Boolean = false
    }

    object ProduceOps {
        val OWNER: String = ProduceOps::class.java.name.replace('.', '/')

        @JvmStatic
        fun produce(value: Int): Int {
            ProduceSink.calls += value
            return value * 2
        }
    }

    object ProduceSink {
        val calls = mutableListOf<Int>()

        fun clear() = calls.clear()
    }
}