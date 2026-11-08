package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.TypeIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.LMFInfo;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.FlowDecorations;
import org.objectweb.asm.Type;

public class ConstructorReferenceExpression extends SimpleExpression {
    public final TypeIdentifier type;

    public ConstructorReferenceExpression(ExpressionSource src, TypeIdentifier type) {
        super(src);
        this.type = type;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        LMFInfo info = node.getDecoration(FlowDecorations.LMF_INFO);
        if (info == null || info.type != LMFInfo.Type.INSTANTIATION) {
            return false;
        }
        return type.matches(ctx.pool, Type.getObjectType(info.impl.getOwner()));
    }
}
