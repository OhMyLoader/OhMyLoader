package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionDecorations;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;

public class MemberAccessExpression extends SimpleExpression {
    public final Expression receiver;
    public final MemberIdentifier name;

    public MemberAccessExpression(ExpressionSource src, Expression receiver, MemberIdentifier name) {
        super(src);
        this.receiver = receiver;
        this.name = name;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        AbstractInsnNode insn = node.getInsn();
        switch (insn.getOpcode()) {
            case Opcodes.GETFIELD:
            case Opcodes.ARRAYLENGTH:
                return name.matches(ctx.pool, node) && inputsMatch(node, ctx, receiver);
        }
        return false;
    }

    @Override
    public void capture(FlowValue node, ExpressionContext ctx) {
        if (node.getInsn().getOpcode() == Opcodes.ARRAYLENGTH) {
            ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_ARGS, new Type[]{node.getInput(0).getType()});
            ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_RETURN_TYPE, Type.INT_TYPE);
            ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_PARAM_NAMES, new String[]{"array", "index"});
        }
        super.capture(node, ctx);
    }
}
