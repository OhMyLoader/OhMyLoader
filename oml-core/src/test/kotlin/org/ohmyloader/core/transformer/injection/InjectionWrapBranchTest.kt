package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.inject.HandlerKind
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.local
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Conditional-branch decisions (`WrapBranch`). Every positive case **defines and executes** the class:
 * the replacement `IFNE` jumps into the original target frame, which is exactly the part that fails as
 * a `VerifyError` when the anchor rewrite is off; the assertions read the branch actually taken.
 *
 * Handler semantics: the return value is "whether the jump executes" — true = branch to the original
 * target, false = fall through. `if (x > 5) return 100; return 0;` compiles to `IF_ICMPLE else`, so a
 * handler seeing `(x, 5)` decides on the **bytecode** test (`x <= 5`).
 */
class InjectionWrapBranchTest {

    private val owner = "omltest/BranchTarget"

    @Test
    fun `a false decision suppresses the branch`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atJump(opcode = Opcodes.IF_ICMPLE) {
                            wrapBranch(BranchHandlers.OWNER, "deny", "(II)Z")
                        }
                    }
                }
            },
            compareTarget(),
        )

        assertEquals(100, invoke(clazz, "cmp", 1), "the jump must be suppressed for x <= 5")
        assertEquals(100, invoke(clazz, "cmp", 9), "the false path must fall through to the same value")
    }

    @Test
    fun `the handler receives the compared values in order`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atJump(opcode = Opcodes.IF_ICMPLE) {
                            wrapBranch(BranchHandlers.OWNER, "keepOriginal", "(II)Z")
                        }
                    }
                }
            },
            compareTarget(),
        )

        assertEquals(0, invoke(clazz, "cmp", 1), "x <= 5 takes the original branch")
        assertEquals(100, invoke(clazz, "cmp", 9), "x > 5 falls through")
    }

    @Test
    fun `a handler can relocate the threshold`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atJump(opcode = Opcodes.IF_ICMPLE) {
                            wrapBranch(BranchHandlers.OWNER, "upToTen", "(II)Z")
                        }
                    }
                }
            },
            compareTarget(),
        )

        assertEquals(0, invoke(clazz, "cmp", 7), "7 <= 10 now takes the branch (the original test said 7 > 5)")
        assertEquals(100, invoke(clazz, "cmp", 11))
    }

    @Test
    fun `the single-int form passes one value`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("single", desc = "(I)I") {
                        atJump(opcode = Opcodes.IFNE) {
                            wrapBranch(BranchHandlers.OWNER, "always", "(I)Z")
                        }
                    }
                }
            },
            singleIntTarget(),
        )

        assertEquals(2, invoke(clazz, "single", 0), "a forced branch must run for a zero argument too")
    }

    @Test
    fun `the reference form compares objects`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("same", desc = "(Ljava/lang/Object;Ljava/lang/Object;)I") {
                        atJump(opcode = Opcodes.IF_ACMPNE) {
                            wrapBranch(BranchHandlers.OWNER, "never", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                        }
                    }
                }
            },
            referenceCompareTarget(),
        )

        assertEquals(1, invoke(clazz, "same", "a", "b"), "suppressing the IF_ACMPNE must take the equal path")
    }

    @Test
    fun `IFNULL accepts a null check handler`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("nullish", desc = "(Ljava/lang/Object;)I") {
                        atJump(opcode = Opcodes.IFNONNULL) {
                            wrapBranch(BranchHandlers.OWNER, "never", "(Ljava/lang/Object;)Z")
                        }
                    }
                }
            },
            nullCheckTarget(),
        )

        assertEquals(1, invoke(clazz, "nullish", Any()))
        assertEquals(1, invoke(clazz, "nullish", null))
    }

    @Test
    fun `a local capture is appended after the operands`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("cmp", desc = "(I)I") {
                        atJump(opcode = Opcodes.IF_ICMPLE) {
                            wrapBranch(
                                BranchHandlers.OWNER, "withSlack", "(III)Z",
                                extras = listOf(local(index = 0, type = "I")),
                            )
                        }
                    }
                }
            },
            compareTarget(),
        )

        // x=7: 7 <= 5 + 7 → take the branch; without the captured local the original test says 7 > 5
        assertEquals(0, invoke(clazz, "cmp", 7))
    }

    @Test
    fun `the merged instance form passes this as the receiver`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(II)I") {
                        atJump(opcode = Opcodes.IF_ICMPGE) {
                            handlerCall(owner, "decide", "(II)Z", kind = HandlerKind.WRAP_BRANCH)
                        }
                    }
                }
            },
            mergedTarget(),
        )

        val result = clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "target" }.invoke(instance, 3, 5)
        }

        // The handler returns `a < b`; target(3, 5) takes the branch (2), where the original test would fall through (1)
        assertEquals(2, result)
    }

    @Test
    fun `a handler whose operand type does not match is refused`() {
        val spec = specOf {
            classTarget(owner) {
                method("cmp", desc = "(I)I") {
                    atJump(opcode = Opcodes.IF_ICMPLE) {
                        wrapBranch(BranchHandlers.OWNER, "denyStrings", "(Ljava/lang/String;Ljava/lang/String;)Z")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, compareTarget()))
        }
        assertTrue(failure.message!!.contains("operand 1"), failure.message)
    }

    @Test
    fun `a non-boolean handler is refused`() {
        val spec = specOf {
            classTarget(owner) {
                method("cmp", desc = "(I)I") {
                    atJump(opcode = Opcodes.IF_ICMPLE) {
                        wrapBranch(BranchHandlers.OWNER, "denyReceiver", "(II)I")
                    }
                    require(1)
                }
            }
        }

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, compareTarget()))
        }
        assertTrue(failure.message!!.contains("boolean"), failure.message)
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
            maxStack = 4
            maxLocals = 4
        }

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
            add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>(Opcodes.INTEGER), 0, emptyArray<Any>()))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int single(int x) { if (x != 0) return 2; return 1; }` */
    private fun singleIntTarget(): ClassNode = classNode(
        methodNode("single", "(I)I") {
            val nonZero = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(JumpInsnNode(Opcodes.IFNE, nonZero))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IRETURN))
            add(nonZero)
            add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>(Opcodes.INTEGER), 0, emptyArray<Any>()))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int same(Object a, Object b) { if (a != b) return 2; return 1; }` */
    private fun referenceCompareTarget(): ClassNode = classNode(
        methodNode("same", "(Ljava/lang/Object;Ljava/lang/Object;)I") {
            val different = LabelNode()
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(JumpInsnNode(Opcodes.IF_ACMPNE, different))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IRETURN))
            add(different)
            add(
                FrameNode(
                    Opcodes.F_NEW, 2,
                    arrayOf<Any>("java/lang/Object", "java/lang/Object"), 0, emptyArray<Any>(),
                ),
            )
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int nullish(Object o) { if (o != null) return 2; return 1; }` */
    private fun nullCheckTarget(): ClassNode = classNode(
        methodNode("nullish", "(Ljava/lang/Object;)I") {
            val notNull = LabelNode()
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(JumpInsnNode(Opcodes.IFNONNULL, notNull))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IRETURN))
            add(notNull)
            add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>("java/lang/Object"), 0, emptyArray<Any>()))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /**
     * The merged form: `int target(int a, int b) { if (a >= b) return 2; return 1; }` plus the
     * in-class handler `boolean decide(int a, int b) { return a < b; }` (the merge is already applied).
     */
    private fun mergedTarget(): ClassNode = classNode(
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(
                MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false),
            )
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 1
        },
        MethodNode(Opcodes.ACC_PUBLIC, "target", "(II)I", null, null).apply {
            val atLeast = LabelNode()
            instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
            instructions.add(VarInsnNode(Opcodes.ILOAD, 2))
            instructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, atLeast))
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            instructions.add(atLeast)
            instructions.add(
                FrameNode(
                    Opcodes.F_NEW, 3,
                    arrayOf<Any>(owner, Opcodes.INTEGER, Opcodes.INTEGER), 0, emptyArray<Any>(),
                ),
            )
            instructions.add(InsnNode(Opcodes.ICONST_2))
            instructions.add(InsnNode(Opcodes.IRETURN))
            maxStack = 4
            maxLocals = 4
        },
        MethodNode(Opcodes.ACC_PUBLIC, "decide", "(II)Z", null, null).apply {
            val notLess = LabelNode()
            instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
            instructions.add(VarInsnNode(Opcodes.ILOAD, 2))
            instructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, notLess))
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            instructions.add(notLess)
            instructions.add(
                FrameNode(
                    Opcodes.F_NEW, 3,
                    arrayOf<Any>(owner, Opcodes.INTEGER, Opcodes.INTEGER), 0, emptyArray<Any>(),
                ),
            )
            instructions.add(InsnNode(Opcodes.ICONST_0))
            instructions.add(InsnNode(Opcodes.IRETURN))
            maxStack = 4
            maxLocals = 4
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionWrapBranchTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Int =
        (clazz.methods.first { it.name == name }.invoke(null, *args) as Number).toInt()

    object BranchHandlers {
        val OWNER: String = BranchHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun deny(a: Int, b: Int): Boolean = false

        @JvmStatic
        fun keepOriginal(a: Int, b: Int): Boolean = a <= b

        @JvmStatic
        fun upToTen(a: Int, b: Int): Boolean = a <= 10

        @JvmStatic
        fun always(x: Int): Boolean = true

        @JvmStatic
        fun never(a: Any?, b: Any?): Boolean = false

        @JvmStatic
        fun never(value: Any?): Boolean = false

        @JvmStatic
        fun withSlack(a: Int, b: Int, slack: Int): Boolean = a <= b + slack

        @JvmStatic
        fun denyString(value: String): Boolean = false

        @JvmStatic
        fun denyStrings(a: String, b: String): Boolean = false

        @JvmStatic
        fun denyReceiver(a: Int, b: Int): Int = 0
    }
}
