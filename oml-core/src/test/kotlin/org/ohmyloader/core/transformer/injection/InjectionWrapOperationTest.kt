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
import org.ohmyloader.api.hook.HookContext
import org.ohmyloader.api.hook.HookValue
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `wrapOperation wraps an instantiation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("make", desc = "(I)L${Receiver.OWNER};") {
                        beforeNew(owner = Receiver.OWNER, desc = "(I)V") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "construct",
                                "(I$OPERATION_DESC)L${Receiver.OWNER};",
                            )
                        }
                    }
                }
            },
            targetWithInstantiation(),
        )

        val made = invoke(clazz, "make", 5) as Receiver
        assertEquals(6, made.factor, "the constructor must run with the argument the handler passed")
    }

    @Test
    fun `wrapOperation can replace an instantiation without calling the original`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("make", desc = "(I)L${Receiver.OWNER};") {
                        beforeNew(owner = Receiver.OWNER, desc = "(I)V") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "substitute",
                                "(I$OPERATION_DESC)L${Receiver.OWNER};",
                            )
                        }
                    }
                }
            },
            targetWithInstantiation(),
        )

        val made = invoke(clazz, "make", 5) as Receiver
        assertEquals(99, made.factor, "the handler returned its own object; the original constructor never ran")
    }

    @Test
    fun `cancellable HookValue stops a wrapped NEW`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("make", desc = "(I)L${Receiver.OWNER};") {
                        beforeNew(owner = Receiver.OWNER, desc = "(I)V") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "cancelConstruct",
                                "(I$OPERATION_DESC$HOOK_VALUE_DESC)L${Receiver.OWNER};",
                                extras = listOf(DslValue.CancelableHandle(HOOK_VALUE_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithInstantiation(),
        )

        val made = invoke(clazz, "make", 5) as Receiver
        assertEquals(77, made.factor, "cancel() returns the handle's value instead of the constructed object")
    }

    @Test
    fun `cancellable HookContext stops a wrapped NEW in a void target`() {
        Ops.recordedReceiver = null
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("sink", desc = "(I)V") {
                        beforeNew(owner = Receiver.OWNER, desc = "(I)V") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "skipConstructVoid",
                                "(I$OPERATION_DESC$HOOK_CONTEXT_DESC)L${Receiver.OWNER};",
                                extras = listOf(DslValue.CancelableHandle(HOOK_CONTEXT_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithSunkInstantiation(),
        )

        invoke(clazz, "sink", 5)
        assertEquals(null, Ops.recordedReceiver, "cancel() returns from the void target before the object is used")
    }

    @Test
    fun `cancellable HookValue returns a value from a non-void target`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Ops.OWNER, name = "times2", desc = "(I)I") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "cancelWithValue",
                                "(I$OPERATION_DESC$HOOK_VALUE_DESC)I",
                                extras = listOf(DslValue.CancelableHandle(HOOK_VALUE_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithStaticCall(),
        )

        assertEquals(77, invoke(clazz, "target", 5), "cancel() on the HookValue returns its value from the target")
    }

    @Test
    fun `cancellable HookValue does not disturb the normal path`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Ops.OWNER, name = "times2", desc = "(I)I") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "passThrough",
                                "(I$OPERATION_DESC$HOOK_VALUE_DESC)I",
                                extras = listOf(DslValue.CancelableHandle(HOOK_VALUE_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithStaticCall(),
        )

        assertEquals(11, invoke(clazz, "target", 5), "without cancel() the handler's ordinary result is kept")
    }

    @Test
    fun `cancellable HookValue casts a reference return`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)Ljava/lang/String;") {
                        beforeCall(owner = Ops.OWNER, name = "label", desc = "(I)Ljava/lang/String;") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "cancelWithLabel",
                                "(I$OPERATION_DESC$HOOK_VALUE_DESC)Ljava/lang/String;",
                                extras = listOf(DslValue.CancelableHandle(HOOK_VALUE_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithReferenceCall(),
        )

        assertEquals("cancelled", invoke(clazz, "target", 5))
    }

    @Test
    fun `cancellable HookValue pops a wide operation result`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)J") {
                        beforeCall(owner = Ops.OWNER, name = "wide", desc = "(I)J") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "cancelWithWide",
                                "(I$OPERATION_DESC$HOOK_VALUE_DESC)J",
                                extras = listOf(DslValue.CancelableHandle(HOOK_VALUE_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithWideCall(),
        )

        assertEquals(123L, invoke(clazz, "target", 5))
    }

    @Test
    fun `cancellable HookContext stops a void target`() {
        Ops.recorded = null
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)V") {
                        beforeCall(owner = Ops.OWNER, name = "record", desc = "(I)V") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "skipVoid",
                                "(I$OPERATION_DESC$HOOK_CONTEXT_DESC)V",
                                extras = listOf(DslValue.CancelableHandle(HOOK_CONTEXT_DESC)),
                            )
                        }
                    }
                }
            },
            targetWithVoidCall(),
        )

        invoke(clazz, "target", 5)
        assertEquals(null, Ops.recorded, "cancel() returns from the void target before the wrapped call")
    }

    @Test
    fun `redirect replaces an instantiation with a factory`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("make", desc = "(I)L${Receiver.OWNER};") {
                        beforeNew(owner = Receiver.OWNER, desc = "(I)V") {
                            redirect(WrapOpHandlers.OWNER, "factory", "(I)L${Receiver.OWNER};")
                        }
                    }
                }
            },
            targetWithInstantiation(),
        )

        val made = invoke(clazz, "make", 5) as Receiver
        assertEquals(6, made.factor, "the factory supplies the instance; the original constructor does not run")
    }

    @Test
    fun `redirect on NEW rejects a handler returning another type`() {
        val spec = specOf {
            classTarget(owner) {
                method("make", desc = "(I)L${Receiver.OWNER};") {
                    beforeNew(owner = Receiver.OWNER, desc = "(I)V") {
                        redirect(WrapOpHandlers.OWNER, "factory", "(I)Ljava/lang/String;")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<org.ohmyloader.api.inject.InjectionError> {
            spec.transform(TransformContext(owner, targetWithInstantiation()))
        }
        assertTrue(failure.message!!.contains("constructed type"), failure.message)
    }

    @Test
    fun `wrapOperation coerces a reference argument and return`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("echo", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                        beforeCall(
                            owner = Ops.OWNER, name = "echo",
                            desc = "(Ljava/lang/String;)Ljava/lang/String;",
                        ) {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "coerceEcho",
                                "(Ljava/lang/Object;Lorg/ohmyloader/api/hook/Operation;)Ljava/lang/Object;",
                                coerceParams = setOf(0),
                                coerceReturn = true,
                            )
                        }
                    }
                }
            },
            targetWithEcho(),
        )

        assertEquals("ABC", invoke(clazz, "echo", "abc"))
    }

    @Test
    fun `wrapOperation boxes and unboxes coerced primitive values`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        beforeCall(owner = Ops.OWNER, name = "times2", desc = "(I)I") {
                            wrapOperation(
                                WrapOpHandlers.OWNER, "coerceTimes",
                                "(Ljava/lang/Object;Lorg/ohmyloader/api/hook/Operation;)Ljava/lang/Object;",
                                coerceParams = setOf(0),
                                coerceReturn = true,
                            )
                        }
                    }
                }
            },
            targetWithStaticCall(),
        )

        assertEquals(11, invoke(clazz, "target", 5), "times2(5) = 10 arrives boxed, unboxes to 11")
    }

    // ---------- fixtures ----------

    private val OPERATION_DESC = "Lorg/ohmyloader/api/hook/Operation;"
    private val HOOK_CONTEXT_DESC = "Lorg/ohmyloader/api/hook/HookContext;"
    private val HOOK_VALUE_DESC = "Lorg/ohmyloader/api/hook/HookValue;"

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

    /** `static String echo(String value) { return Ops.echo(value); }` */
    private fun targetWithEcho(): ClassNode = classNode(
        methodNode("echo", "(Ljava/lang/String;)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, Ops.OWNER, "echo", "(Ljava/lang/String;)Ljava/lang/String;", false))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static String target(int seed) { return Ops.label(seed); }` */
    private fun targetWithReferenceCall(): ClassNode = classNode(
        methodNode("target", "(I)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, Ops.OWNER, "label", "(I)Ljava/lang/String;", false))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static long target(int seed) { return Ops.wide(seed); }` */
    private fun targetWithWideCall(): ClassNode = classNode(
        methodNode("target", "(I)J") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, Ops.OWNER, "wide", "(I)J", false))
            add(InsnNode(Opcodes.LRETURN))
        },
    )

    /** `static void target(int seed) { Ops.record(seed); }` */
    private fun targetWithVoidCall(): ClassNode = classNode(
        methodNode("target", "(I)V") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, Ops.OWNER, "record", "(I)V", false))
            add(InsnNode(Opcodes.RETURN))
        },
    )

    /** `static Receiver make(int seed) { return new Receiver(seed); }` */
    private fun targetWithInstantiation(): ClassNode = classNode(
        methodNode("make", "(I)L${Receiver.OWNER};") {
            add(TypeInsnNode(Opcodes.NEW, Receiver.OWNER))
            add(InsnNode(Opcodes.DUP))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, Receiver.OWNER, "<init>", "(I)V", false))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static void sink(int seed) { Ops.recordReceiver(new Receiver(seed)); }` */
    private fun targetWithSunkInstantiation(): ClassNode = classNode(
        methodNode("sink", "(I)V") {
            add(TypeInsnNode(Opcodes.NEW, Receiver.OWNER))
            add(InsnNode(Opcodes.DUP))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, Receiver.OWNER, "<init>", "(I)V", false))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, Ops.OWNER, "recordReceiver", "(L${Receiver.OWNER};)V", false))
            add(InsnNode(Opcodes.RETURN))
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
        var recorded: Int? = null

        @JvmStatic
        var recordedReceiver: Receiver? = null

        @JvmStatic
        fun record(value: Int) {
            recorded = value
        }

        @JvmStatic
        fun recordReceiver(receiver: Receiver) {
            recordedReceiver = receiver
        }

        @JvmStatic
        fun times2(value: Int): Int = value * 2

        @JvmStatic
        fun label(value: Int): String = "label$value"

        @JvmStatic
        fun echo(value: String): String = value

        @JvmStatic
        fun wide(value: Int): Long = value.toLong()
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

        @JvmStatic
        fun construct(value: Int, operation: Operation<Receiver>): Receiver = operation.call(value + 1)

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun substitute(value: Int, operation: Operation<Receiver>): Receiver = Receiver(99)

        @JvmStatic
        fun factory(value: Int): Receiver = Receiver(value + 1)

        @JvmStatic
        fun coerceEcho(value: Any?, operation: Operation<String>): Any? =
            operation.call((value as String).uppercase())

        @JvmStatic
        fun coerceTimes(value: Any?, operation: Operation<Int>): Any? =
            operation.call(value as Int) + 1

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun cancelWithValue(value: Int, operation: Operation<Int>, hook: HookValue<Int>): Int {
            hook.setReturnValue(77)
            return -1
        }

        @JvmStatic
        fun passThrough(value: Int, operation: Operation<Int>, hook: HookValue<Int>): Int = operation.call(value) + 1

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun skipVoid(value: Int, operation: Operation<Void>, hook: HookContext) {
            hook.cancel()
        }

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun cancelWithLabel(value: Int, operation: Operation<String>, hook: HookValue<String>): String {
            hook.setReturnValue("cancelled")
            return ""
        }

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun cancelWithWide(value: Int, operation: Operation<Long>, hook: HookValue<Long>): Long {
            hook.setReturnValue(123L)
            return 0L
        }

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun cancelConstruct(value: Int, operation: Operation<Receiver>, hook: HookValue<Receiver>): Receiver {
            hook.setReturnValue(Receiver(77))
            return Receiver(-1)
        }

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun skipConstructVoid(value: Int, operation: Operation<Receiver>, hook: HookContext): Receiver {
            hook.cancel()
            return Receiver(-1)
        }
    }
}