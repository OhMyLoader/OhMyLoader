package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lambda selectors: a rule names the **enclosing** method and the functional interface instead of the
 * generated `lambda$…$N` name. The engine reads the `LambdaMetafactory` `invokedynamic`'s implementation
 * handle and targets that synthetic method; the tests build real `invokedynamic` bytecode and execute it.
 */
class InjectionLambdaTest {

    private val owner = "omltest/LambdaTarget"

    @Test
    fun `a lambda is targeted through its enclosing method`() {
        LambdaHandlers.clear()
        val target = supplierTarget()
        val spec = specOf {
            classTarget(owner) {
                lambdaMethod("use", interfaceInternal = "java/util/function/Supplier") {
                    atHead {
                        call(LambdaHandlers.OWNER, "seen", "(I)V", args = listOf(DslValue.Arg(0)))
                    }
                }
            }
        }

        val clazz = define(spec, target)
        assertEquals(6, invoke(clazz, "use", 5))
        assertEquals(listOf(5), LambdaHandlers.values, "the handler ran inside the synthetic lambda method")
    }

    @Test
    fun `the ordinal selects among several lambdas of one interface`() {
        val target = twoSupplierTarget()
        val spec = specOf {
            classTarget(owner) {
                lambdaMethod("two", interfaceInternal = "java/util/function/Supplier", ordinal = 1) {
                    atHead {
                        call(LambdaHandlers.OWNER, "seen", "(I)V", args = listOf(DslValue.Arg(0)))
                    }
                }
            }
        }

        assertTrue(spec.transform(TransformContext(owner, target)))

        assertFalseHasHandler(target, "lambda\$two\$0", "(I)Ljava/lang/Integer;")
        assertTrueHasHandler(target, "lambda\$two\$1", "(I)Ljava/lang/Integer;")
    }

    @Test
    fun `a missing lambda is reported`() {
        val spec = specOf {
            classTarget(owner) {
                lambdaMethod("use", interfaceInternal = "java/lang/Runnable") {
                    atHead { call(LambdaHandlers.OWNER, "seen", "(I)V", args = listOf(DslValue.Arg(0))) }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, supplierTarget()))
        }
        assertTrue(failure.message!!.contains("no lambda for"), failure.message)
    }

    // ---------- fixtures ----------

    /** `static int use(int x) { Supplier<Integer> s = () -> x + 1; return s.get(); }` */
    private fun supplierTarget(): ClassNode = classNode(
        method("use", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(supplierIndy("lambda\$use\$0", "(I)Ljava/lang/Integer;"))
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
                    "()Ljava/lang/Object;", true,
                ),
            )
            add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
            add(InsnNode(Opcodes.IRETURN))
        },
        lambdaMethod("lambda\$use\$0", "(I)Ljava/lang/Integer;", 1),
    )

    /** `static int two(int x) { Supplier<Integer> a = () -> x + 1; Supplier<Integer> b = () -> x + 2; ... }` */
    private fun twoSupplierTarget(): ClassNode = classNode(
        method("two", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(supplierIndy("lambda\$two\$0", "(I)Ljava/lang/Integer;"))
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(supplierIndy("lambda\$two\$1", "(I)Ljava/lang/Integer;"))
            add(VarInsnNode(Opcodes.ASTORE, 2))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
                    "()Ljava/lang/Object;", true,
                ),
            )
            add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
            add(VarInsnNode(Opcodes.ALOAD, 2))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
                    "()Ljava/lang/Object;", true,
                ),
            )
            add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
            add(InsnNode(Opcodes.IADD))
            add(InsnNode(Opcodes.IRETURN))
        },
        lambdaMethod("lambda\$two\$0", "(I)Ljava/lang/Integer;", 1),
        lambdaMethod("lambda\$two\$1", "(I)Ljava/lang/Integer;", 2),
    )

    /** The synthetic lambda body: `Integer lambda(int x) { return x + delta; }` */
    private fun lambdaMethod(name: String, desc: String, delta: Int): MethodNode =
        MethodNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, name, desc, null, null)
            .apply {
                instructions.add(VarInsnNode(Opcodes.ILOAD, 0))
                instructions.add(InsnNode(Opcodes.ICONST_0 + delta))
                instructions.add(InsnNode(Opcodes.IADD))
                instructions.add(
                    MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false),
                )
                instructions.add(InsnNode(Opcodes.ARETURN))
                maxStack = 4
                maxLocals = 2
            }

    private fun supplierIndy(implName: String, implDesc: String): InvokeDynamicInsnNode =
        InvokeDynamicInsnNode(
            "get",
            "(I)Ljava/util/function/Supplier;",
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                    "Ljava/lang/invoke/CallSite;",
                false,
            ),
            Type.getMethodType("()Ljava/lang/Object;"),
            Handle(Opcodes.H_INVOKESTATIC, owner, implName, implDesc, false),
            Type.getMethodType("()Ljava/lang/Object;"),
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

    private fun assertHasHandler(node: ClassNode, name: String, desc: String) {
        val method = node.methods.first { it.name == name && it.desc == desc }
        assertTrue(
            method.instructions.toArray().any {
                it is MethodInsnNode && it.owner == LambdaHandlers.OWNER && it.name == "seen"
            },
            "$name should carry the injected handler call",
        )
    }

    private fun assertTrueHasHandler(node: ClassNode, name: String, desc: String) = assertHasHandler(node, name, desc)

    private fun assertFalseHasHandler(node: ClassNode, name: String, desc: String) {
        val method = node.methods.first { it.name == name && it.desc == desc }
        assertTrue(
            method.instructions.toArray().none {
                it is MethodInsnNode && it.owner == LambdaHandlers.OWNER && it.name == "seen"
            },
            "$name must not carry the injected handler call",
        )
    }

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionLambdaTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object LambdaHandlers {
        val OWNER: String = LambdaHandlers::class.java.name.replace('.', '/')
        val values = mutableListOf<Int>()

        fun clear() = values.clear()

        @JvmStatic
        fun seen(value: Int) {
            values += value
        }
    }
}
