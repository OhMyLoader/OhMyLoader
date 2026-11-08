package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Type;

public class BooleanLiteralExpression extends SimpleExpression {
    public final boolean value;

    public BooleanLiteralExpression(ExpressionSource src, boolean value) {
        super(src);
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        if (!node.typeMatches(Type.BOOLEAN_TYPE)) {
            return false;
        }
        Object cst = ExpressionASMUtils.getConstant(node.getInsn());
        if (cst == null) {
            return false;
        }
        return cst.equals(value ? 1 : 0);
    }
}
