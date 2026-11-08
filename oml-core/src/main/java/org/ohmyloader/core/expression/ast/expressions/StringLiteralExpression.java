package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Type;

public class StringLiteralExpression extends SimpleExpression {
    public final String value;
    private final Integer charValue;

    public StringLiteralExpression(ExpressionSource src, String value) {
        super(src);
        this.value = value;
        if (value.length() == 1) {
            this.charValue = (int) value.charAt(0);
        } else {
            this.charValue = null;
        }
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        Object cst = ExpressionASMUtils.getConstant(node.getInsn());
        if (cst == null) {
            return false;
        }
        return cst.equals(value) || (node.typeMatches(Type.CHAR_TYPE) && cst.equals(charValue));
    }
}
