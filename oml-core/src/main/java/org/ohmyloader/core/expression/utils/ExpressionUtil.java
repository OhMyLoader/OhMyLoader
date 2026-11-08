package org.ohmyloader.core.expression.utils;

import org.ohmyloader.core.expression.ast.expressions.CapturingExpression;
import org.ohmyloader.core.expression.ast.expressions.Expression;

public class ExpressionUtil {
    public static Expression skipCapturesDown(Expression expr) {
        while (expr instanceof CapturingExpression) {
            expr = ((CapturingExpression) expr).expression;
        }
        return expr;
    }
}
