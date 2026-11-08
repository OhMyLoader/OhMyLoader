package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.LMFInfo;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.FlowDecorations;

public class FreeMethodReferenceExpression extends SimpleExpression {
    public final MemberIdentifier name;

    public FreeMethodReferenceExpression(ExpressionSource src, MemberIdentifier name) {
        super(src);
        this.name = name;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        LMFInfo info = node.getDecoration(FlowDecorations.LMF_INFO);
        if (info == null || info.type != LMFInfo.Type.FREE_METHOD) {
            return false;
        }
        return name.matches(ctx.pool, node);
    }
}
