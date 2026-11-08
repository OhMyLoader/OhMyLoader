package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionDecorations;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Type;

public abstract class SimpleExpression extends Expression {
    public SimpleExpression(ExpressionSource src) {
        super(src);
    }

    @Override
    public void capture(FlowValue node, ExpressionContext ctx) {
        Type type = node.getType();
        if (type.equals(ExpressionASMUtils.BOTTOM_TYPE)) {
            type = ExpressionASMUtils.OBJECT_TYPE;
        }
        if (!type.equals(Type.VOID_TYPE)) {
            ctx.decorate(node.getInsn(), ExpressionDecorations.SIMPLE_EXPRESSION_TYPE, type);
        }
        super.capture(node, ctx);
    }
}
