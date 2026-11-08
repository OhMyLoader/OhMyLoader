package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;

public class CapturingExpression extends SimpleExpression {
    public final Expression expression;

    public CapturingExpression(ExpressionSource src, Expression expression) {
        super(src);
        this.expression = expression;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        boolean matches = expression.matches(node, ctx);
        if (matches) {
            expression.capture(node, ctx);
        }
        return matches;
    }
}
