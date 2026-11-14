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
import org.ohmyloader.api.hook.Args
import org.ohmyloader.api.hook.Operation
import org.ohmyloader.api.inject.Anchor
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.api.inject.Payload
import org.ohmyloader.core.expression.point.ExpressionContext
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
    fun `modifyExpressionValue rewrites the comparison result`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atExpression("? > 5") {
                            modifyExpressionValue(ExpressionHandlers.OWNER, "negate", "(Z)Z")
                        }
                    }
                }
            },
            compareTarget(),
        )

        // The handler sees the source-level result ("x > 5") and flips it
        assertEquals(100, invoke(clazz, "cmp", 1), "false flipped to true takes the 100 path")
        assertEquals(0, invoke(clazz, "cmp", 9), "true flipped to false takes the 0 path")
    }

    @Test
    fun `modifyExpressionValue rewrites a complex comparison`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("dcmp", desc = "(D)I") {
                        atExpression("? > 5.0") {
                            modifyExpressionValue(ExpressionHandlers.OWNER, "negate", "(Z)Z")
                        }
                    }
                }
            },
            doubleCompareTarget(),
        )

        assertEquals(100, invoke(clazz, "dcmp", 1.0), "1.0 > 5.0 is false, flipped to true")
        assertEquals(0, invoke(clazz, "dcmp", 9.0), "9.0 > 5.0 is true, flipped to false")
    }

    @Test
    fun `modifyExpressionValue rewrites a string concatenation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("greet", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                        atExpression("'Hi ' + ?") {
                            modifyExpressionValue(
                                ExpressionHandlers.OWNER, "suffix",
                                "(Ljava/lang/String;)Ljava/lang/String;",
                            )
                        }
                    }
                }
            },
            concatTarget(),
        )

        assertEquals("Hi Bob!", invoke(clazz, "greet", "Bob"))
    }

    @Test
    fun `modifyExpressionValue targets a concatenation component`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("greet", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                        atExpression("'Hi '") {
                            modifyExpressionValue(
                                ExpressionHandlers.OWNER, "hello",
                                "(Ljava/lang/String;)Ljava/lang/String;",
                            )
                        }
                    }
                }
            },
            concatTarget(),
        )

        // The captured constant is one part of the concatenation; the compound is rewritten before injection
        assertEquals("Hello Bob", invoke(clazz, "greet", "Bob"))
    }

    @Test
    fun `a local identifier matches a computed local`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("local", desc = "(I)I") {
                        atExpression(
                            "x * 2",
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "x",
                                    locals = listOf(
                                        InjectionPoint.Expression.Definition.Local(index = 1, type = "I"),
                                    ),
                                ),
                            ),
                        ) {
                            modifyExpressionValue(ExpressionHandlers.OWNER, "triple", "(I)I")
                        }
                    }
                }
            },
            localTarget(),
        )

        // x = 5, x * 2 = 10, the handler triples it to 30
        assertEquals(30, invoke(clazz, "local", 4))
    }

    @Test
    fun `modifyExpressionValue rewrites a constructed object`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("name", desc = "()Ljava/lang/String;") {
                        atExpression(
                            "new StringBuilder(?)",
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "StringBuilder", types = listOf("Ljava/lang/StringBuilder;"),
                                ),
                            ),
                        ) {
                            modifyExpressionValue(
                                ExpressionHandlers.OWNER, "shout",
                                "(Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;",
                            )
                        }
                    }
                }
            },
            newTarget(),
        )

        assertEquals("x!", invoke(clazz, "name"))
    }

    @Test
    fun `modifyExpressionValue rewrites an array literal`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("arr", desc = "()[I") {
                        atExpression("new int[]{1, 2}") {
                            modifyExpressionValue(ExpressionHandlers.OWNER, "replace", "([I)[I")
                        }
                    }
                }
            },
            arrayTarget(),
        )

        assertEquals(9, (invoke(clazz, "arr") as IntArray)[0])
    }

    @Test
    fun `wrapOperation wraps a comparison with an operation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atExpression("? > 5") {
                            wrapOperation(
                                ExpressionHandlers.OWNER, "invert",
                                "(IILorg/ohmyloader/api/hook/Operation;)Z",
                            )
                        }
                    }
                }
            },
            compareTarget(),
        )

        assertEquals(100, invoke(clazz, "cmp", 1), "the operation re-evaluates 1 > 5 and the handler inverts it")
        assertEquals(0, invoke(clazz, "cmp", 9), "9 > 5 is true, inverted to false")
    }

    @Test
    fun `redirect replaces a call captured by an expression`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("calc", desc = "(I)I") {
                        atExpression(
                            "max(?, 3)",
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                                ),
                            ),
                        ) {
                            redirect(ExpressionHandlers.OWNER, "sum", "(II)I")
                        }
                    }
                }
            },
            maxTarget(),
        )

        assertEquals(8, invoke(clazz, "calc", 5), "Math.max(5, 3) is replaced by 5 + 3")
        assertEquals(5, invoke(clazz, "calc", 2), "Math.max(2, 3) is replaced by 2 + 3")
    }

    @Test
    fun `redirect rejects a handler that does not match the captured call`() {
        val spec = specOf {
            classTarget(owner) {
                method("calc", desc = "(I)I") {
                    atExpression(
                        "max(?, 3)",
                        definitions = listOf(
                            InjectionPoint.Expression.Definition(
                                "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                            ),
                        ),
                    ) {
                        redirect(ExpressionHandlers.OWNER, "double", "(I)I")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, maxTarget()))
        }
        assertTrue(failure.message!!.contains("must start with"), failure.message)
    }

    @Test
    fun `modifyArg rewrites an argument of an expression call`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("calc", desc = "(I)I") {
                        atExpression(
                            "max(?, 3)",
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                                ),
                            ),
                        ) {
                            modifyArg(ExpressionHandlers.OWNER, "timesTen", "(I)I", index = 1)
                        }
                    }
                }
            },
            maxTarget(),
        )

        assertEquals(30, invoke(clazz, "calc", 5), "the second argument becomes 30, so max(5, 30) = 30")
    }

    @Test
    fun `modifyArgs rewrites all arguments of an expression call`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("calc", desc = "(I)I") {
                        atExpression(
                            "max(?, 3)",
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                                ),
                            ),
                        ) {
                            modifyArgs(ExpressionHandlers.OWNER, "raiseFirst", "(Lorg/ohmyloader/api/hook/Args;)V")
                        }
                    }
                }
            },
            maxTarget(),
        )

        assertEquals(20, invoke(clazz, "calc", 5), "the first argument becomes 20, so max(20, 3) = 20")
    }

    @Test
    fun `wrapOperation wraps a string concatenation`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("greet", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                        atExpression("'Hi ' + ?") {
                            wrapOperation(
                                ExpressionHandlers.OWNER, "wrapConcat",
                                "(Ljava/lang/String;Lorg/ohmyloader/api/hook/Operation;)Ljava/lang/String;",
                            )
                        }
                    }
                }
            },
            concatTarget(),
        )

        assertEquals(
            "Hi Bob!",
            invoke(clazz, "greet", "Bob"),
            "the operation re-runs the invokedynamic with the handler's argument",
        )
    }

    @Test
    fun `modifyReturnValue rewrites a call captured by an expression`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("calc", desc = "(I)I") {
                        atExpression(
                            "max(?, 3)",
                            definitions = listOf(
                                InjectionPoint.Expression.Definition(
                                    "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                                ),
                            ),
                        ) {
                            transformReturn(ExpressionHandlers.OWNER, "double", "(I)I")
                        }
                    }
                }
            },
            maxTarget(),
        )

        assertEquals(10, invoke(clazz, "calc", 5), "Math.max(5, 3) = 5, rewritten to 10 before the return")
        assertEquals(6, invoke(clazz, "calc", 2), "Math.max(2, 3) = 3, rewritten to 6 before the return")
    }

    @Test
    fun `modifyReturnValue rejects a handler for another value type`() {
        val spec = specOf {
            classTarget(owner) {
                method("calc", desc = "(I)I") {
                    atExpression(
                        "max(?, 3)",
                        definitions = listOf(
                            InjectionPoint.Expression.Definition(
                                "max", methods = listOf("Ljava/lang/Math;max(II)I"),
                            ),
                        ),
                    ) {
                        transformReturn(ExpressionHandlers.OWNER, "negate", "(Z)Z")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, maxTarget()))
        }
        assertTrue(failure.message!!.contains("value's type"), failure.message)
    }

    @Test
    fun `wrapOperation wraps a complex comparison`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("dcmp", desc = "(D)I") {
                        atExpression("? > 5.0") {
                            wrapOperation(
                                ExpressionHandlers.OWNER, "invertDouble",
                                "(DDLorg/ohmyloader/api/hook/Operation;)Z",
                            )
                        }
                    }
                }
            },
            doubleCompareTarget(),
        )

        assertEquals(100, invoke(clazz, "dcmp", 1.0), "the operation re-evaluates 1.0 > 5.0 and the handler inverts it")
        assertEquals(0, invoke(clazz, "dcmp", 9.0), "9.0 > 5.0 is true, inverted to false")
    }

    @Test
    fun `wrapBranch decides a complex comparison`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("dcmp", desc = "(D)I") {
                        atExpression("? > 5.0") {
                            wrapBranch(ExpressionHandlers.OWNER, "denyDouble", "(DD)Z")
                        }
                    }
                }
            },
            doubleCompareTarget(),
        )

        assertEquals(100, invoke(clazz, "dcmp", 1.0), "the handler suppresses the branch, taking the fall-through path")
        assertEquals(100, invoke(clazz, "dcmp", 9.0), "raw branch semantics: false always falls through")
    }

    @Test
    fun `wrapOperation wraps an array length`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("len", desc = "([I)I") {
                        atExpression("?.length") {
                            wrapOperation(
                                ExpressionHandlers.OWNER, "arrayLengthPlus",
                                "([ILorg/ohmyloader/api/hook/Operation;)I",
                            )
                        }
                    }
                }
            },
            arrayLengthTarget(),
        )

        assertEquals(13, invoke(clazz, "len", intArrayOf(1, 2, 3)), "length 3 + 10")
    }

    @Test
    fun `an expression bounds the slice window`() {
        val definition = InjectionPoint.Expression.Definition(
            "max", methods = listOf("Ljava/lang/Math;max(II)I"),
        )
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("seq", desc = "(I)I") {
                        within(from = Anchor.expression("max(?, 3)", definitions = listOf(definition))) {
                            afterCall(
                                owner = "java/lang/Math", name = "max", desc = "(II)I",
                                opcode = Opcodes.INVOKESTATIC,
                            ) {
                                modifyExpressionValue(ExpressionHandlers.OWNER, "plusOne", "(I)I")
                            }
                        }
                    }
                }
            },
            sequenceTarget(),
        )

        // Only the second max (after the `max(?, 3)` boundary) is modified
        assertEquals(11, invoke(clazz, "seq", 10))
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

    @Test
    fun `a missing expression reports the closest partial match`() {
        val node = maxTarget()
        val method = node.methods.first()
        val definition = InjectionPoint.Expression.Definition(
            "max", methods = listOf("Ljava/lang/Math;max(II)I"),
        )
        val point = InjectionPoint.Expression(listOf("max(?, 4)"), null, listOf(definition))
        val resolution = ExpressionAnchors.resolve(
            node, method, point,
            Payload.ModifyExpressionValue(ExpressionHandlers.OWNER, "double", "(I)I"),
            ExpressionContext.Type.MODIFY_EXPRESSION_VALUE,
        )

        assertTrue(resolution.instructions.isEmpty())
        val hint = resolution.missHint ?: error("a miss must carry a hint")
        assertTrue(hint.contains("max(?, 4)"), hint)
        assertTrue(hint.contains("Math.max"), hint)
    }

    @Test
    fun `an unresolved definition local is named in the miss hint`() {
        val node = localTarget()
        val method = node.methods.first()
        val point = InjectionPoint.Expression(
            listOf("x * 2"), null,
            listOf(
                InjectionPoint.Expression.Definition(
                    "x",
                    locals = listOf(
                        InjectionPoint.Expression.Definition.Local(type = "J", ordinal = 0),
                    ),
                ),
            ),
        )
        val resolution = ExpressionAnchors.resolve(
            node, method, point,
            Payload.ModifyExpressionValue(ExpressionHandlers.OWNER, "double", "(I)I"),
            ExpressionContext.Type.MODIFY_EXPRESSION_VALUE,
        )

        assertTrue(resolution.instructions.isEmpty())
        val hint = resolution.missHint ?: error("a miss must carry a hint")
        assertTrue(hint.contains("@DefinitionLocal \"x\""), hint)
        assertTrue(hint.contains("never resolved"), hint)
        assertTrue(hint.contains("type J"), hint)
    }

    @Test
    fun `an out-of-range expression ordinal is reported`() {
        val node = maxTarget()
        val method = node.methods.first()
        val definition = InjectionPoint.Expression.Definition(
            "max", methods = listOf("Ljava/lang/Math;max(II)I"),
        )
        val point = InjectionPoint.Expression(listOf("max(?, 3)"), 3, listOf(definition))
        val resolution = ExpressionAnchors.resolve(
            node, method, point,
            Payload.ModifyExpressionValue(ExpressionHandlers.OWNER, "double", "(I)I"),
            ExpressionContext.Type.MODIFY_EXPRESSION_VALUE,
        )

        assertTrue(resolution.instructions.isEmpty())
        val hint = resolution.missHint ?: error("a miss must carry a hint")
        assertTrue(hint.contains("ordinal 3"), hint)
        assertTrue(hint.contains("Math.max"), hint)
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

    /** `static int dcmp(double x) { if (x > 5.0) return 100; return 0; }` */
    private fun doubleCompareTarget(): ClassNode = classNode(
        methodNode("dcmp", "(D)I") {
            val otherwise = LabelNode()
            add(VarInsnNode(Opcodes.DLOAD, 0))
            add(org.objectweb.asm.tree.LdcInsnNode(5.0))
            add(InsnNode(Opcodes.DCMPL))
            add(JumpInsnNode(Opcodes.IFLE, otherwise))
            add(IntInsnNode(Opcodes.BIPUSH, 100))
            add(InsnNode(Opcodes.IRETURN))
            add(otherwise)
            add(
                org.objectweb.asm.tree.FrameNode(
                    Opcodes.F_NEW, 1, arrayOf<Any>(Opcodes.DOUBLE), 0, emptyArray<Any>(),
                ),
            )
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static String greet(String name) { return "Hi " + name; }` */
    private fun concatTarget(): ClassNode = classNode(
        methodNode("greet", "(Ljava/lang/String;)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(
                org.objectweb.asm.tree.InvokeDynamicInsnNode(
                    "makeConcatWithConstants",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    org.objectweb.asm.Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                            "Ljava/lang/invoke/MethodType;Ljava/lang/String;" +
                            "[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false,
                    ),
                    "Hi \u0001",
                ),
            )
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static String name() { return new StringBuilder("x").toString(); }` */
    private fun newTarget(): ClassNode = classNode(
        methodNode("name", "()Ljava/lang/String;") {
            add(org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"))
            add(InsnNode(Opcodes.DUP))
            add(org.objectweb.asm.tree.LdcInsnNode("x"))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                    "(Ljava/lang/String;)V", false,
                ),
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false,
                ),
            )
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static int[] arr() { return new int[]{1, 2}; }` */
    private fun arrayTarget(): ClassNode = classNode(
        methodNode("arr", "()[I") {
            add(InsnNode(Opcodes.ICONST_2))
            add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
            add(InsnNode(Opcodes.DUP))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IASTORE))
            add(InsnNode(Opcodes.DUP))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IASTORE))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static int seq(int x) { int a = Math.max(x, 3); int b = Math.max(a, 7); return b; }` */
    private fun sequenceTarget(): ClassNode = classNode(
        methodNode("seq", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_3))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(IntInsnNode(Opcodes.BIPUSH, 7))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
            add(VarInsnNode(Opcodes.ISTORE, 2))
            add(VarInsnNode(Opcodes.ILOAD, 2))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int local(int seed) { int x = seed + 1; return x * 2; }` */
    private fun localTarget(): ClassNode = classNode(
        methodNode("local", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IMUL))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int len(int[] values) { return values.length; }` */
    private fun arrayLengthTarget(): ClassNode = classNode(
        methodNode("len", "([I)I") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(InsnNode(Opcodes.ARRAYLENGTH))
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

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object ExpressionHandlers {
        val OWNER: String = ExpressionHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun double(value: Int): Int = value * 2

        @JvmStatic
        fun ninetyNine(value: Int): Int = 99

        @JvmStatic
        fun deny(a: Int, b: Int): Boolean = false

        @JvmStatic
        fun negate(value: Boolean): Boolean = !value

        @JvmStatic
        fun suffix(value: String): String = "$value!"

        @JvmStatic
        fun hello(@Suppress("UNUSED_PARAMETER") value: String): String = "Hello "

        @JvmStatic
        fun triple(value: Int): Int = value * 3

        @JvmStatic
        fun shout(builder: StringBuilder): StringBuilder = builder.append("!")

        @JvmStatic
        fun replace(value: IntArray): IntArray = intArrayOf(9)

        @JvmStatic
        fun invert(a: Int, b: Int, operation: Operation<Boolean>): Boolean = !operation.call(a, b)

        @JvmStatic
        fun plusOne(value: Int): Int = value + 1

        @JvmStatic
        fun sum(a: Int, b: Int): Int = a + b

        @JvmStatic
        fun timesTen(value: Int): Int = value * 10

        @JvmStatic
        fun raiseFirst(args: Args) {
            args[0] = 20
        }

        @JvmStatic
        fun wrapConcat(name: String, operation: Operation<String>): String = operation.call("$name!")

        @JvmStatic
        fun invertDouble(a: Double, b: Double, operation: Operation<Boolean>): Boolean = !operation.call(a, b)

        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun denyDouble(a: Double, b: Double): Boolean = false

        @JvmStatic
        fun arrayLengthPlus(array: IntArray, operation: Operation<Int>): Int = operation.call(array) + 10
    }
}
