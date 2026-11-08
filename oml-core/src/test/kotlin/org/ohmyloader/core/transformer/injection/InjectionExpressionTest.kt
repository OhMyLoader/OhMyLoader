package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Expression anchors against real bytecode: the pattern is matched on the reconstructed value flow,
 * then handed to the existing payload emitters. Every positive case defines and executes the class —
 * a wrong target instruction would silently skip the handler, which only the executed result shows.
 */
class InjectionExpressionTest {

    private val owner = "omltest/ExpressionTarget"

    @Test
    fun `modify an expression value through a definition`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("calc", desc = "(I)I") {
                        atExpression(
                            "max(?, 3)",
                            after = true,
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                                ),
                            ),
                        ) {
                            modifyExpressionValue(ExpressionHandlers.OWNER, "double", "(I)I")
                        }
                    }
                }
            },
            maxTarget(),
        )

        assertEquals(10, invoke(clazz, "calc", 5), "Math.max(5, 3) = 5, doubled by the handler")
        assertEquals(6, invoke(clazz, "calc", 2), "Math.max(2, 3) = 3, doubled by the handler")
    }

    @Test
    fun `the ordinal picks the nth captured instruction`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("calc", desc = "(I)I") {
                        atExpression(
                            "max(?, ?)",
                            ordinal = 1,
                            after = true,
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                                ),
                            ),
                        ) {
                            modifyExpressionValue(ExpressionHandlers.OWNER, "ninetyNine", "(I)I")
                        }
                    }
                }
            },
            nestedMaxTarget(),
        )

        assertEquals(99, invoke(clazz, "calc", 4), "the outer max call is the second capture in method order")
    }

    @Test
    fun `an expression comparison drives wrapBranch`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atExpression("? > 5") {
                            wrapBranch(ExpressionHandlers.OWNER, "deny", "(II)Z")
                        }
                    }
                }
            },
            compareTarget(),
        )

        assertEquals(100, invoke(clazz, "cmp", 1), "suppressing the branch takes the true path for x <= 5")
    }

    @Test
    fun `an undeclared identifier fails the transform`() {
        val spec = specOf {
            classTarget(owner) {
                method("calc", desc = "(I)I") {
                    atExpression("nosuch(?)", after = true) {
                        modifyExpressionValue(ExpressionHandlers.OWNER, "double", "(I)I")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, maxTarget()))
        }
        assertTrue(failure.message!!.contains("undeclared identifier"), failure.message)
    }

    @Test
    fun `an expression that matches nothing reports the miss`() {
        val spec = specOf {
            classTarget(owner) {
                method("calc", desc = "(I)I") {
                    atExpression(
                        "max(?, 4)",
                        after = true,
                        definitions = listOf(
                            InjectionPoint.Expression.Definition(
                                "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                            ),
                        ),
                    ) {
                        modifyExpressionValue(ExpressionHandlers.OWNER, "double", "(I)I")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, maxTarget()))
        }
        assertTrue(failure.message!!.contains("require=1"), failure.message)
    }

    // ---------- fixtures ----------

    /** `static int calc(int x) { return Math.max(x, 3); }` */
    private fun maxTarget(): ClassNode = classNode(
        methodNode("calc", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_3))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int calc(int x) { return Math.max(Math.max(x, 3), 4); }` */
    private fun nestedMaxTarget(): ClassNode = classNode(
        methodNode("calc", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_3))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
            add(InsnNode(Opcodes.ICONST_4))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int cmp(int x) { if (x > 5) return 100; return 0; }` */
    private fun compareTarget(): ClassNode = classNode(
        methodNode("cmp", "(I)I") {
            val otherwise = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_5))
            add(JumpInsnNode(Opcodes.IF_ICMPLE, otherwise))
            add(IntInsnNode(Opcodes.BIPUSH, 100))
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

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
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
        return object : ClassLoader(InjectionExpressionTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Int =
        (clazz.methods.first { it.name == name }.invoke(null, *args) as Number).toInt()

    object ExpressionHandlers {
        val OWNER: String = ExpressionHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun double(value: Int): Int = value * 2

        @JvmStatic
        fun ninetyNine(value: Int): Int = 99

        @JvmStatic
        fun deny(a: Int, b: Int): Boolean = false
    }
}
