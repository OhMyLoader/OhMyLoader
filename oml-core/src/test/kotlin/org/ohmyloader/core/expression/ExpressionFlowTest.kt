package org.ohmyloader.core.expression

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.core.expression.flow.FlowInterpreter
import org.ohmyloader.core.expression.flow.FlowValue
import org.ohmyloader.core.expression.utils.FlowDecorations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The value-flow reconstruction: each case builds a real method body and asserts the graph the
 * expression matcher will walk — producers and their inputs, plus the synthetic nodes the
 * compound-instruction post-processors insert.
 */
class ExpressionFlowTest {

    private val owner = "omltest/FlowTarget"

    @Test
    fun `an arithmetic producer links its operands`() {
        val method = method("add", "(II)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IADD))
            add(InsnNode(Opcodes.IRETURN))
        }

        val flows = analyze(method)

        val add = flows.single { it.insn?.opcode == Opcodes.IADD }
        assertEquals(2, add.inputCount())
        assertEquals(Opcodes.ILOAD, add.getInput(0).insn?.opcode)
        assertEquals(Opcodes.ILOAD, add.getInput(1).insn?.opcode)
        assertEquals("I", add.type.descriptor)
    }

    @Test
    fun `a jump compares its two operands`() {
        val method = method("cmp", "(II)V") {
            val skip = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(JumpInsnNode(Opcodes.IF_ICMPGE, skip))
            add(skip)
            add(InsnNode(Opcodes.RETURN))
        }

        val flows = analyze(method)

        val jump = flows.single { it.insn?.opcode == Opcodes.IF_ICMPGE }
        assertEquals(2, jump.inputCount())
        assertTrue(jump.inputCount() == 2 && jump.getInput(0).insn?.opcode == Opcodes.ILOAD)
    }

    @Test
    fun `a single-value comparison is expanded to an explicit zero`() {
        val method = method("flag", "(Z)V") {
            val skip = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(JumpInsnNode(Opcodes.IFEQ, skip))
            add(skip)
            add(InsnNode(Opcodes.RETURN))
        }

        val flows = analyze(method)

        // The post-processor rewrites the jump into `IF_ICMPEQ` against a synthetic zero
        val jump = flows.single { it.insn?.opcode == Opcodes.IF_ICMPEQ }
        val zero = jump.getInput(1)
        assertNotNull(zero.insn)
        assertTrue(zero.insn!!.opcode in Opcodes.ICONST_0..Opcodes.ICONST_5)
        assertTrue(flows.none { it.insn?.opcode == Opcodes.IFEQ }, "the original jump is replaced in the flow")
    }

    @Test
    fun `iinc becomes load-add-store`() {
        val method = method("inc", "(I)I") {
            add(org.objectweb.asm.tree.IincInsnNode(0, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.IRETURN))
        }

        val flows = analyze(method)

        val store = flows.single { it.insn?.opcode == Opcodes.ISTORE }
        assertEquals(1, store.inputCount())
        assertEquals(Opcodes.IADD, store.getInput(0).insn?.opcode)
        assertEquals(Opcodes.ICONST_1, store.getInput(0).getInput(1).insn?.opcode)
    }

    @Test
    fun `an array literal carries creation info`() {
        val method = method("arr", "()[I") {
            add(InsnNode(Opcodes.ICONST_2))
            add(org.objectweb.asm.tree.IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
            add(InsnNode(Opcodes.DUP))
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.ICONST_4))
            add(InsnNode(Opcodes.IASTORE))
            add(InsnNode(Opcodes.DUP))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.ICONST_5))
            add(InsnNode(Opcodes.IASTORE))
            add(InsnNode(Opcodes.ARETURN))
        }

        val flows = analyze(method)

        val creation = flows.single { it.insn?.opcode == Opcodes.NEWARRAY }
        assertNotNull(creation.getDecoration<Any>(FlowDecorations.ARRAY_CREATION_INFO))
        // The literal's values become the creation node's inputs
        assertEquals(2, creation.inputCount())
        assertEquals(listOf(Opcodes.ICONST_4, Opcodes.ICONST_5), creationInputs(creation))
    }

    @Test
    fun `a field read keeps its receiver`() {
        val method = method("field", "()I", isStatic = false) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETFIELD, owner, "value", "I"))
            add(InsnNode(Opcodes.IRETURN))
        }

        val flows = analyze(method)

        val get = flows.single { it.insn?.opcode == Opcodes.GETFIELD }
        assertEquals(1, get.inputCount())
        assertSame(flows.single { it.insn?.opcode == Opcodes.ALOAD }, get.getInput(0))
    }

    // ---------- fixtures ----------

    private fun creationInputs(node: FlowValue): List<Int> =
        (0 until node.inputCount()).mapNotNull { node.getInput(it).insn?.opcode }

    private fun method(
        name: String,
        desc: String,
        isStatic: Boolean = true,
        build: InsnList.() -> Unit,
    ): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or if (isStatic) Opcodes.ACC_STATIC else 0, name, desc, null, null).apply {
            instructions.build()
            maxStack = 8
            maxLocals = 8
        }

    private fun analyze(method: MethodNode): List<FlowValue> {
        val classNode = ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            methods.add(method)
        }
        return FlowInterpreter.analyze(classNode, method, null).toList()
    }

    private val FlowValue.insn: AbstractInsnNode?
        get() = if (isComplex) null else runCatching { getInsn() }.getOrNull()

    private val FlowValue.type: org.objectweb.asm.Type
        get() = runCatching { getType() }.getOrDefault(org.objectweb.asm.Type.VOID_TYPE)
}
