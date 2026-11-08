package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;

public class ThrowExpression extends Expression {
    public final Expression value;

    public ThrowExpression(ExpressionSource src, Expression value) {
        super(src);
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        return node.getInsn().getOpcode() == Opcodes.ATHROW && inputsMatch(node, ctx, value);
    }
}
