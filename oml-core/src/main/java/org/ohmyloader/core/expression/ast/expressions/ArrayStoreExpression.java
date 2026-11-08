package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionDecorations;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class ArrayStoreExpression extends Expression {
    public final Expression arr;
    public final Expression index;
    public final Expression value;

    public ArrayStoreExpression(ExpressionSource src, Expression arr, Expression index, Expression value) {
        super(src);
        this.arr = arr;
        this.index = index;
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        switch (node.getInsn().getOpcode()) {
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.AASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                return inputsMatch(node, ctx, arr, index, value);
        }
        return false;
    }

    @Override
    public void capture(FlowValue node, ExpressionContext ctx) {
        Type arrayType = node.getInput(0).getType();
        ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_ARGS, new Type[]{arrayType, Type.INT_TYPE, ExpressionASMUtils.getInnerType(arrayType)});
        ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_RETURN_TYPE, Type.VOID_TYPE);
        ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_OPERATION_PARAM_NAMES, new String[]{"array", "index", "value"});
        super.capture(node, ctx);
    }
}
