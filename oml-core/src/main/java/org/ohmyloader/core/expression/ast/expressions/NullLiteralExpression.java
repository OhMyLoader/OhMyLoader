package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;

public class NullLiteralExpression extends SimpleExpression {
    public NullLiteralExpression(ExpressionSource src) {
        super(src);
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        return node.getInsn().getOpcode() == Opcodes.ACONST_NULL;
    }
}
