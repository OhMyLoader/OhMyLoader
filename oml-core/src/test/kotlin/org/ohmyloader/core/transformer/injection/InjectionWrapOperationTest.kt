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
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Operation wrapping (`WrapOperation`). Every case defines and executes the class, because the risky
 * parts — the LambdaMetafactory handshake, the synthetic bridge's argument unpacking and the receiver
 * traveling through `Operation.call` — only show up at link or call time.
 */
class InjectionWrapOperationTest {

    private val owner = "omltest/WrapOperationTarget"

    @Test
    fun `wrapOperation re-runs a static call through the operation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Ops.OWNER, name = "times2", desc = "(I)I") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "plusOne",
                                "(I$OPERATION_DESC)I",
                            )
                        }
                    }
                }
            },
            targetWithStaticCall(),
        )

        assertEquals(11, invoke(clazz, "target", 5), "times2(5) = 10, then +1")
    }

    @Test
    fun `wrapOperation can pass different arguments to the original`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Ops.OWNER, name = "times2", desc = "(I)I") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "bumpArg",
                                "(I$OPERATION_DESC)I",
                            )
                        }
                    }
                }
            },
            targetWithStaticCall(),
        )

        assertEquals(12, invoke(clazz, "target", 5), "times2(6) = 12 after the handler bumped the argument")
    }

    @Test
    fun `wrapOperation carries an instance receiver through the operation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Receiver.OWNER, name = "scale", desc = "(I)I") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "scaleReceiver",
                                "(L${Receiver.OWNER};I$OPERATION_DESC)I",
                            )
                        }
                    }
                }
            },
            targetWithInstanceCall(),
        )

        // Receiver(2).scale(5) = 10, then the handler multiplies by 10.
        assertEquals(100, invoke(clazz, "target", 5))
    }

    @Test
    fun `wrapOperation wraps a field read`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeField(owner = Receiver.OWNER, name = "factor", desc = "I", opcode = Opcodes.GETFIELD) {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "readField",
                                "(L${Receiver.OWNER};$OPERATION_DESC)I",
                            )
                        }
                    }
                }
            },
            targetWithFieldRead(),
        )

        assertEquals(107, invoke(clazz, "target", 7), "factor(7) + 100")
    }

    @Test
    fun `wrapOperation wraps a field write`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeField(owner = Receiver.OWNER, name = "stored", desc = "I", opcode = Opcodes.PUTFIELD) {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "writeField",
                                "(L${Receiver.OWNER};I$OPERATION_DESC)V",
                            )
                        }
                    }
                }
            },
            targetWithFieldWrite(),
        )

        // The handler re-runs the write with value + 1.
        assertEquals(8, invoke(clazz, "target", 7))
    }

    // ---------- fixtures ----------

    private val OPERATION_DESC = "Lorg/ohmyloader/api/hook/Operation;"

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

    private fun newReceiver(list: InsnList, factor: Int) {
        list.add(TypeInsnNode(Opcodes.NEW, Receiver.OWNER))
        list.add(InsnNode(Opcodes.DUP))
        list.add(LdcInsnNode(factor))
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, Receiver.OWNER, "<init>", "(I)V", false))
    }

    /** `static int target(int seed) { return Ops.times2(seed); }` */
    private fun targetWithStaticCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, Ops.OWNER, "times2", "(I)I", false))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { Receiver r = new Receiver(2); return r.scale(seed); }` */
    private fun targetWithInstanceCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            newReceiver(this, 2)
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, Receiver.OWNER, "scale", "(I)I", false))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { Receiver r = new Receiver(seed); return r.factor; }` */
    private fun targetWithFieldRead(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            newReceiver(this, 7)
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(FieldInsnNode(Opcodes.GETFIELD, Receiver.OWNER, "factor", "I"))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { Receiver r = new Receiver(0); r.stored = seed; return r.stored; }` */
    private fun targetWithFieldWrite(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            newReceiver(this, 0)
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(FieldInsnNode(Opcodes.PUTFIELD, Receiver.OWNER, "stored", "I"))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(FieldInsnNode(Opcodes.GETFIELD, Receiver.OWNER, "stored", "I"))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionWrapOperationTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    class Receiver(factorValue: Int) {
        @JvmField
        val factor: Int = factorValue

        @JvmField
        var stored: Int = 0

        fun scale(value: Int): Int = factor * value

        companion object {
            val OWNER: String = Receiver::class.java.name.replace('.', '/')
        }
    }

    object Ops {
        val OWNER: String = Ops::class.java.name.replace('.', '/')

        @JvmStatic
        fun times2(value: Int): Int = value * 2
    }

    object WrapOpHandlers {
        val OWNER: String = WrapOpHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun plusOne(value: Int, operation: Operation<Int>): Int = operation.call(value) + 1

        @JvmStatic
        fun bumpArg(value: Int, operation: Operation<Int>): Int = operation.call(value + 1)

        @JvmStatic
        fun scaleReceiver(receiver: Receiver, value: Int, operation: Operation<Int>): Int =
            operation.call(receiver, value) * 10

        @JvmStatic
        fun readField(receiver: Receiver, operation: Operation<Int>): Int = operation.call(receiver) + 100

        @JvmStatic
        fun writeField(receiver: Receiver, value: Int, operation: Operation<Void>) {
            operation.call(receiver, value + 1)
        }
    }
}