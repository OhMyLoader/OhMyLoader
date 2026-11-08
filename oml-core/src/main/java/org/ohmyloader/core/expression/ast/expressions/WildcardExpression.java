package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;

public class WildcardExpression extends SimpleExpression {
    public WildcardExpression(ExpressionSource src) {
        super(src);
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        return true;
    }
}
