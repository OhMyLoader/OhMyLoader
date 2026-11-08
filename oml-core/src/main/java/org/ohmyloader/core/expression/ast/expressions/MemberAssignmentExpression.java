package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;

public class MemberAssignmentExpression extends Expression {
    public final Expression receiver;
    public final MemberIdentifier name;
    public final Expression value;

    public MemberAssignmentExpression(ExpressionSource src, Expression receiver, MemberIdentifier name, Expression value) {
        super(src);
        this.receiver = receiver;
        this.name = name;
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        AbstractInsnNode insn = node.getInsn();
        return insn.getOpcode() == Opcodes.PUTFIELD
                && name.matches(ctx.pool, node) && inputsMatch(node, ctx, receiver, value);
    }
}
