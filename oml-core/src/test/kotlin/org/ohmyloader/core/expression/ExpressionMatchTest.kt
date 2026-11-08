package org.ohmyloader.core.expression

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.core.expression.ast.expressions.Expression
import org.ohmyloader.core.expression.flow.FlowInterpreter
import org.ohmyloader.core.expression.flow.FlowValue
import org.ohmyloader.core.expression.point.ExpressionContext
import org.ohmyloader.core.expression.pool.IdentifierPool
import org.ohmyloader.core.expression.pool.SimpleMemberDefinition
import org.ohmyloader.core.expression.utils.ExpressionDecorations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matching a parsed expression against the value-flow graph. The identifier pool is populated the
 * way the engine will populate it (target members), because an undeclared identifier is a hard error
 * by design — a typo must not silently match anything.
 */
class ExpressionMatchTest {

    private val owner = "omltest/MatchTarget"

    @Test
    fun `this member access matches a getfield`() {
        val method = method("value", "()I", isStatic = false) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETFIELD, owner, "value", "I"))
            add(InsnNode(Opcodes.IRETURN))
        }

        val pool = fieldPool()
        assertTrue(match(method, "this.value", Opcodes.GETFIELD, pool))
        assertFalse(match(method, "this.other", Opcodes.GETFIELD, pool))
    }

    @Test
    fun `declared identifiers pin the operand`() {
        val method = method("add", "(II)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(InsnNode(Opcodes.IRETURN))
        }

        val pool = IdentifierPool().apply {
            addMember("x", SimpleMemberDefinition { insn -> insn.opcode == Opcodes.ILOAD })
        }
        assertTrue(match(method, "x + 1", Opcodes.IADD, pool))
        assertFailsWith<IllegalStateException> { match(method, "y + 1", Opcodes.IADD, pool) }
    }

    @Test
    fun `a method call matches name and arguments`() {
        val method = method("call", "()I", isStatic = false) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(InsnNode(Opcodes.ICONST_3))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "foo", "(I)I", false))
            add(InsnNode(Opcodes.IRETURN))
        }

        val pool = IdentifierPool().apply {
            addMember("foo", SimpleMemberDefinition { insn ->
                insn is MethodInsnNode && insn.name == "foo"
            })
        }
        assertTrue(match(method, "this.foo(3)", Opcodes.INVOKEVIRTUAL, pool))
        assertFalse(match(method, "this.foo(4)", Opcodes.INVOKEVIRTUAL, pool))
    }

    @Test
    fun `a comparison matches the jump and decorates the injector`() {
        val method = method("cmp", "(II)V") {
            val skip = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(JumpInsnNode(Opcodes.IF_ICMPGE, skip))
            add(skip)
            add(InsnNode(Opcodes.RETURN))
        }

        val pool = IdentifierPool().apply {
            addMember("a", SimpleMemberDefinition { insn -> insn.opcode == Opcodes.ILOAD })
            addMember("b", SimpleMemberDefinition { insn -> insn.opcode == Opcodes.ILOAD })
        }
        val sink = Sink()
        val matched = match(method, "a >= b", Opcodes.IF_ICMPGE, pool, sink)

        assertTrue(matched)
        assertTrue(sink.decorations.containsKey(ExpressionDecorations.SIMPLE_OPERATION_ARGS))
        assertTrue(sink.decorations.containsKey(ExpressionDecorations.COMPARISON_INFO))
        assertEquals(Type.BOOLEAN_TYPE, sink.decorations[ExpressionDecorations.SIMPLE_OPERATION_RETURN_TYPE])
    }

    @Test
    fun `a zero comparison matches the expanded jump`() {
        val method = method("flag", "(Z)V") {
            val skip = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(JumpInsnNode(Opcodes.IFEQ, skip))
            add(skip)
            add(InsnNode(Opcodes.RETURN))
        }

        val pool = IdentifierPool().apply {
            addMember("flag", SimpleMemberDefinition { insn -> insn.opcode == Opcodes.ILOAD })
        }
        assertTrue(match(method, "flag == 0", Opcodes.IF_ICMPEQ, pool))
    }

    @Test
    fun `a capture records the produced type`() {
        val method = method("value", "()I", isStatic = false) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETFIELD, owner, "value", "I"))
            add(InsnNode(Opcodes.IRETURN))
        }

        val sink = Sink()
        assertTrue(match(method, "@(this.value)", Opcodes.GETFIELD, fieldPool(), sink))
        assertEquals(1, sink.captures.size)
        assertEquals(Type.INT_TYPE, sink.decorations[ExpressionDecorations.SIMPLE_EXPRESSION_TYPE])
    }

    // ---------- fixtures ----------

    /** The target class's own members, the way the engine will populate the pool. */
    private fun fieldPool(): IdentifierPool = IdentifierPool().apply {
        addMember("value", SimpleMemberDefinition { insn -> insn is FieldInsnNode && insn.name == "value" })
        addMember("other", SimpleMemberDefinition { insn -> insn is FieldInsnNode && insn.name == "other" })
    }

    private fun match(
        method: MethodNode,
        expression: String,
        opcode: Int,
        pool: IdentifierPool = IdentifierPool(),
        sink: Sink = Sink(),
    ): Boolean {
        val classNode = ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
        }
        val flows = FlowInterpreter.analyze(classNode, method, null)
        val node = flows.single { it.hasInsn(opcode) }
        val ctx = ExpressionContext(
            pool, sink, classNode, method, ExpressionContext.Type.MODIFY_EXPRESSION_VALUE, false,
        )
        return ExpressionParserFacade.parse(expression).matches(node, ctx)
    }

    private fun FlowValue.hasInsn(opcode: Int): Boolean =
        !isComplex && runCatching { getInsn().opcode == opcode }.getOrDefault(false)

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

    private class Sink : Expression.OutputSink {
        val captures = mutableListOf<FlowValue>()
        val decorations = mutableMapOf<String, Any?>()

        override fun capture(node: FlowValue, expr: Expression, ctx: ExpressionContext) {
            captures += node
        }

        override fun decorate(insn: AbstractInsnNode, key: String, value: Any?) {
            decorations[key] = value
        }

        override fun decorateInjectorSpecific(insn: AbstractInsnNode, key: String, value: Any?) {
            decorations[key] = value
        }
    }
}
