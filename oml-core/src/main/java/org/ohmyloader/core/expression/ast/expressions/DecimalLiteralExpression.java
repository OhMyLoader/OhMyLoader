package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Type;

public class DecimalLiteralExpression extends SimpleExpression {
    public final double value;

    public DecimalLiteralExpression(ExpressionSource src, double value) {
        super(src);
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        if (!node.typeMatches(Type.FLOAT_TYPE) && !node.typeMatches(Type.DOUBLE_TYPE)) {
            return false;
        }
        Object cst = ExpressionASMUtils.getConstant(node.getInsn());
        if (cst == null) {
            return false;
        }
        return (cst instanceof Float || cst instanceof Double) && String.valueOf(value).equals(cst.toString());
    }
}
