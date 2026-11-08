package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionDecorations;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class ArrayAccessExpression extends SimpleExpression {
    public final Expression arr;
    public final Expression index;

    public ArrayAccessExpression(ExpressionSource src, Expression arr, Expression index) {
        super(src);
        this.arr = arr;
        this.index = index;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        switch (node.getInsn().getOpcode()) {
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.AALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
                return inputsMatch(node, ctx, arr, index);
        }
        return false;
    }

    @Override
    public void capture(FlowValue node, ExpressionContext ctx) {
        ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_ARGS, new Type[]{node.getInput(0).getType(), Type.INT_TYPE});
        ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_RETURN_TYPE, node.getType());
        ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_PARAM_NAMES, new String[]{"array", "index"});
        super.capture(node, ctx);
    }
}
