package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whole-method wrapping (`WrapMethod`). Every case defines and executes the class: moving the body and
 * re-entering it through the `Operation` is exactly where a bad descriptor, a broken frame or a wrong
 * injection order would show up.
 */
class InjectionWrapMethodTest {

    private val owner = "omltest/WrapMethodTarget"

    @Test
    fun `wrapMethod rewrites a static method through the operation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        wrapMethod(WrapMethodHandlers.OWNER, "addOne", "(I$OPERATION_DESC)I")
                    }
                }
            },
            targetWithStaticInt(),
        )

        assertEquals(11, invoke(clazz, "target", 5), "target(5) = 10, then +1")
    }

    @Test
    fun `wrapMethod boxes coerced primitive parameters and return`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        wrapMethod(
                            WrapMethodHandlers.OWNER, "coerceAddOne",
                            "(Ljava/lang/Object;$OPERATION_DESC)Ljava/lang/Object;",
                            coerceParams = setOf(0),
                            coerceReturn = true,
                        )
                    }
                }
            },
            targetWithStaticInt(),
        )

        assertEquals(11, invoke(clazz, "target", 5), "the parameter is boxed and the result unboxed")
    }

    @Test
    fun `wrapMethod can pass different arguments to the original body`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        wrapMethod(WrapMethodHandlers.OWNER, "bumpArg", "(I$OPERATION_DESC)I")
                    }
                }
            },
            targetWithStaticInt(),
        )

        assertEquals(12, invoke(clazz, "target", 5), "target(6) = 12")
    }

    @Test
    fun `wrapMethod can replace the body without calling it`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        wrapMethod(WrapMethodHandlers.OWNER, "fixed", "(I$OPERATION_DESC)I")
                    }
                }
            },
            targetWithStaticInt(),
        )

        assertEquals(99, invoke(clazz, "target", 5))
    }

    @Test
    fun `wrapMethod gates a void method through the operation`() {
        WrapMethodSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("record", desc = "(I)V") {
                        wrapMethod(WrapMethodHandlers.OWNER, "recordShifted", "(I$OPERATION_DESC)V")
                    }
                }
            },
            targetWithStaticVoid(),
        )

        invoke(clazz, "record", 5)

        assertEquals(listOf(6), WrapMethodSink.calls, "the body must run with the argument the handler passed")
    }

    @Test
    fun `wrapMethod wraps an instance method and keeps this`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("compute", desc = "(I)I") {
                        wrapMethod(WrapMethodHandlers.OWNER, "timesTen", "(I$OPERATION_DESC)I")
                    }
                }
            },
            targetWithInstanceMethod(),
        )

        val instance = clazz.getDeclaredConstructor(java.lang.Integer.TYPE).newInstance(10)
        val result = clazz.getMethod("compute", java.lang.Integer.TYPE).invoke(instance, 5)

        // base(10) + 5 = 15, then the handler multiplies by 10.
        assertEquals(150, result)
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

    /** `static int target(int seed) { return seed * 2; }` */
    private fun targetWithStaticInt(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IMUL))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static void record(int x) { WrapMethodSink.record(x); }` */
    private fun targetWithStaticVoid(): ClassNode = classNode(
        methodNode("record", "(I)V") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, WrapMethodSink.OWNER, "record", "(I)V", false))
            add(InsnNode(Opcodes.RETURN))
        },
    )

    /** `int base; int compute(int x) { return base + x; }` */
    private fun targetWithInstanceMethod(): ClassNode = classNode(
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null).apply {
            instructions.apply {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(FieldInsnNode(Opcodes.PUTFIELD, owner, "base", "I"))
                add(InsnNode(Opcodes.RETURN))
            }
            maxStack = 8
            maxLocals = 8
        },
        MethodNode(Opcodes.ACC_PUBLIC, "compute", "(I)I", null, null).apply {
            instructions.apply {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, owner, "base", "I"))
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(InsnNode(Opcodes.IADD))
                add(InsnNode(Opcodes.IRETURN))
            }
            maxStack = 8
            maxLocals = 8
        },
    ).apply {
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "base", "I", null, null))
    }

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionWrapMethodTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object WrapMethodSink {
        val OWNER: String = WrapMethodSink::class.java.name.replace('.', '/')

        val calls = mutableListOf<Int>()

        fun clear() = calls.clear()

        @JvmStatic
        fun record(x: Int) {
            calls += x
        }
    }

    object WrapMethodHandlers {
        val OWNER: String = WrapMethodHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun addOne(value: Int, operation: Operation<Int>): Int = operation.call(value) + 1

        @JvmStatic
        fun coerceAddOne(value: Any?, operation: Operation<Int>): Any? =
            operation.call(value as Int) + 1

        @JvmStatic
        fun bumpArg(value: Int, operation: Operation<Int>): Int = operation.call(value + 1)

        @JvmStatic
        fun fixed(value: Int, operation: Operation<Int>): Int = 99

        @JvmStatic
        fun recordShifted(value: Int, operation: Operation<Void>) {
            operation.call(value + 1)
        }

        @JvmStatic
        fun timesTen(value: Int, operation: Operation<Int>): Int = operation.call(value) * 10
    }
}