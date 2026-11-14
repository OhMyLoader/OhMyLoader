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
 * Receiver rewriting (`ModifyReceiver`). Both positive cases **define and execute** the class: the
 * risk lives in the store/reload juggling around the untouched instruction, which otherwise surfaces
 * only as a `VerifyError` or a call landing on the wrong receiver.
 */
class InjectionModifyReceiverTest {

    private val owner = "omltest/ModifyReceiverTarget"

    @Test
    fun `modifyReceiver swaps the receiver of an instance call`() {
        ReceiverSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Receiver.OWNER, name = "record", desc = "(I)V") {
                            modifyReceiver(
                                ModifyReceiverHandlers.OWNER, "swap",
                                "(L${Receiver.OWNER};I)L${Receiver.OWNER};",
                            )
                        }
                    }
                }
            },
            targetWithCall(),
        )

        invoke(clazz, "target", 7)

        assertEquals(
            listOf("b:7"), ReceiverSink.calls,
            "the call must run on the receiver the handler returned",
        )
    }

    @Test
    fun `modifyReceiver swaps the receiver of a field write`() {
        ModifyReceiverHandlers.swapped.value = 0
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("put", desc = "(I)I") {
                        beforeField(owner = Receiver.OWNER, name = "value", desc = "I", opcode = Opcodes.PUTFIELD) {
                            modifyReceiver(
                                ModifyReceiverHandlers.OWNER, "swap",
                                "(L${Receiver.OWNER};I)L${Receiver.OWNER};",
                            )
                        }
                    }
                }
            },
            targetWithFieldWrite(),
        )

        val readBack = invoke(clazz, "put", 42)

        assertEquals(42, ModifyReceiverHandlers.swapped.value, "the write must land on the new receiver")
        assertEquals(0, readBack, "the original receiver's field must stay untouched")
    }

    @Test
    fun `modifyReceiver refuses a static call`() {
        val spec = specOf {
            classTarget(owner) {
                method("target", desc = "(I)I") {
                    beforeCall(owner = "java/lang/Integer", name = "toString", desc = "(I)Ljava/lang/String;") {
                        modifyReceiver(
                            ModifyReceiverHandlers.OWNER, "swap",
                            "(L${Receiver.OWNER};I)L${Receiver.OWNER};",
                        )
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, targetWithStaticCall()))
        }
        assertTrue(failure.message!!.contains("require"), failure.message)
    }

    @Test
    fun `modifyReceiver coerces a reference receiver and return`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") {
                        beforeCall(
                            owner = "java/lang/String", name = "concat",
                            desc = "(Ljava/lang/String;)Ljava/lang/String;",
                        ) {
                            modifyReceiver(
                                ModifyReceiverHandlers.OWNER, "upperReceiver",
                                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                                coerceParams = setOf(0),
                                coerceReturn = true,
                            )
                        }
                    }
                }
            },
            targetWithStringConcat(),
        )

        assertEquals("ABCdef", invoke(clazz, "target", "abc", "def"))
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

    private fun newReceiver(list: InsnList, name: String) {
        list.add(TypeInsnNode(Opcodes.NEW, Receiver.OWNER))
        list.add(InsnNode(Opcodes.DUP))
        list.add(LdcInsnNode(name))
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, Receiver.OWNER, "<init>", "(Ljava/lang/String;)V", false))
    }

    /** `static int target(int seed) { Receiver a = new Receiver("a"); a.record(seed); return 0; }` */
    private fun targetWithCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            newReceiver(this, "a")
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, Receiver.OWNER, "record", "(I)V", false))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static String target(String a, String b) { return a.concat(b); }` */
    private fun targetWithStringConcat(): ClassNode = classNode(
        methodNode("target", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false,
                ),
            )
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static int put(int v) { Receiver a = new Receiver("a"); a.value = v; return a.value; }` */
    private fun targetWithFieldWrite(): ClassNode = classNode(
        methodNode("put", "(I)I") {
            newReceiver(this, "a")
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(FieldInsnNode(Opcodes.PUTFIELD, Receiver.OWNER, "value", "I"))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(FieldInsnNode(Opcodes.GETFIELD, Receiver.OWNER, "value", "I"))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int target(int seed) { Integer.toString(seed); return 0; }` — a static call. */
    private fun targetWithStaticCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false))
            add(InsnNode(Opcodes.POP))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionModifyReceiverTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    /** The receiver type shared by the target, the handler and the sink. */
    class Receiver(val name: String) {
        @JvmField
        var value: Int = 0

        fun record(x: Int) {
            ReceiverSink.calls += "$name:$x"
        }

        companion object {
            val OWNER: String = Receiver::class.java.name.replace('.', '/')
        }
    }

    object ReceiverSink {
        val calls = mutableListOf<String>()

        fun clear() = calls.clear()
    }

    object ModifyReceiverHandlers {
        val OWNER: String = ModifyReceiverHandlers::class.java.name.replace('.', '/')

        val swapped = Receiver("b")

        @JvmStatic
        fun swap(receiver: Receiver, value: Int): Receiver = swapped

        @JvmStatic
        fun upperReceiver(receiver: Any?, value: String): Any? = (receiver as String).uppercase()
    }
}