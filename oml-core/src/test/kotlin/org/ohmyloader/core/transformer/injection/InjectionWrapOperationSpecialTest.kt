package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WrapOperation on type checks, array accesses and array creation. Every case defines and executes the
 * class: the Operation bridge re-executes the wrapped instruction, and a wrong stack/boxing shape only
 * shows up at link or call time.
 */
class InjectionWrapOperationSpecialTest {

    private val owner = "omltest/WrapSpecialTarget"
    private val op = "Lorg/ohmyloader/api/hook/Operation;"

    @Test
    fun `wrapOperation wraps a checkcast`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cast", desc = "(Ljava/lang/Object;)Ljava/lang/String;") {
                        point(InjectionPoint.TypeCheck(Opcodes.CHECKCAST, "java/lang/String")) {
                            wrapOperation(SpecialHandlers.OWNER, "decorate", "(Ljava/lang/Object;$op)Ljava/lang/String;")
                        }
                    }
                }
            },
            targetWithCheckcast(),
        )

        assertEquals("x!", invoke(clazz, "cast", "x"))
    }

    @Test
    fun `wrapOperation wraps an instanceof`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("test", desc = "(Ljava/lang/Object;)Z") {
                        point(InjectionPoint.TypeCheck(Opcodes.INSTANCEOF, "java/lang/String")) {
                            wrapOperation(SpecialHandlers.OWNER, "invert", "(Ljava/lang/Object;$op)Z")
                        }
                    }
                }
            },
            targetWithInstanceof(),
        )

        assertEquals(false, invoke(clazz, "test", "x"), "the true result is inverted")
        assertEquals(true, invoke(clazz, "test", 1), "the false result is inverted")
    }

    @Test
    fun `wrapOperation wraps an int array load`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("load", desc = "([II)I") {
                        point(InjectionPoint.ArrayAccess(Opcodes.IALOAD)) {
                            wrapOperation(SpecialHandlers.OWNER, "doubleIt", "([II$op)I")
                        }
                    }
                }
            },
            targetWithArrayLoad(),
        )

        assertEquals(6, invoke(clazz, "load", intArrayOf(3), 0))
    }

    @Test
    fun `wrapOperation wraps an int array store`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("store", desc = "([III)V") {
                        point(InjectionPoint.ArrayAccess(Opcodes.IASTORE)) {
                            wrapOperation(SpecialHandlers.OWNER, "bumpStore", "([III$op)V")
                        }
                    }
                }
            },
            targetWithArrayStore(),
        )

        val array = intArrayOf(0)
        invoke(clazz, "store", array, 0, 5)

        assertEquals(6, array[0], "the handler's operation writes v + 1")
    }

    @Test
    fun `wrapOperation wraps an array creation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("make", desc = "(I)[Ljava/lang/String;") {
                        point(InjectionPoint.ArrayCreate(Opcodes.ANEWARRAY, desc = "java/lang/String")) {
                            wrapOperation(SpecialHandlers.OWNER, "growStrings", "(I$op)[Ljava/lang/String;")
                        }
                    }
                }
            },
            targetWithANewArray(),
        )

        val made = invoke(clazz, "make", 3) as Array<*>
        assertEquals(4, made.size, "the handler asked the operation for one more element")
    }

    @Test
    fun `wrapOperation wraps a primitive array creation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("makeInts", desc = "(I)[I") {
                        point(InjectionPoint.ArrayCreate(Opcodes.NEWARRAY, atype = 10)) {
                            wrapOperation(SpecialHandlers.OWNER, "growInts", "(I$op)[I")
                        }
                    }
                }
            },
            targetWithNewArray(),
        )

        val made = invoke(clazz, "makeInts", 3) as IntArray
        assertEquals(4, made.size)
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

    private fun targetWithCheckcast(): ClassNode = classNode(
        methodNode("cast", "(Ljava/lang/Object;)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    private fun targetWithInstanceof(): ClassNode = classNode(
        methodNode("test", "(Ljava/lang/Object;)Z") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/String"))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun targetWithArrayLoad(): ClassNode = classNode(
        methodNode("load", "([II)I") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IALOAD))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun targetWithArrayStore(): ClassNode = classNode(
        methodNode("store", "([III)V") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 2))
            add(InsnNode(Opcodes.IASTORE))
            add(InsnNode(Opcodes.RETURN))
        },
    )

    private fun targetWithANewArray(): ClassNode = classNode(
        methodNode("make", "(I)[Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    private fun targetWithNewArray(): ClassNode = classNode(
        methodNode("makeInts", "(I)[I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(IntInsnNode(Opcodes.NEWARRAY, 10))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionWrapOperationSpecialTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object SpecialHandlers {
        val OWNER: String = SpecialHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun decorate(value: Any?, operation: Operation<String>): String = operation.call(value) + "!"

        @JvmStatic
        fun invert(value: Any?, operation: Operation<Boolean>): Boolean = !operation.call(value)

        @JvmStatic
        fun doubleIt(array: IntArray, index: Int, operation: Operation<Int>): Int = operation.call(array, index) * 2

        @JvmStatic
        fun bumpStore(array: IntArray, index: Int, value: Int, operation: Operation<Void>) {
            operation.call(array, index, value + 1)
        }

        @JvmStatic
        fun growStrings(size: Int, operation: Operation<Array<String?>>): Array<String?> =
            operation.call(size + 1)

        @JvmStatic
        fun growInts(size: Int, operation: Operation<IntArray>): IntArray =
            operation.call(size + 1)
    }
}