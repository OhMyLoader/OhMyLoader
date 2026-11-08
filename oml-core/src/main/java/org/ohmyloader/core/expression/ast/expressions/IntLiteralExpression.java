package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Type;

public class IntLiteralExpression extends SimpleExpression {
    public final long value;

    public IntLiteralExpression(ExpressionSource src, long value) {
        super(src);
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        if (!node.typeMatches(Type.INT_TYPE) && !node.typeMatches(Type.LONG_TYPE)) {
            return false;
        }
        Object cst = ExpressionASMUtils.getConstant(node.getInsn());
        if (cst == null) {
            return false;
        }
        return (cst instanceof Integer || cst instanceof Long) && ((Number) cst).longValue() == value;
    }
}
