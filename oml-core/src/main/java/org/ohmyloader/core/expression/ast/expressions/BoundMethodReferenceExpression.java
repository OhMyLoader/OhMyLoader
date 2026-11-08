package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.LMFInfo;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.FlowDecorations;

public class BoundMethodReferenceExpression extends SimpleExpression {
    public final Expression receiver;
    public final MemberIdentifier name;

    public BoundMethodReferenceExpression(ExpressionSource src, Expression receiver, MemberIdentifier name) {
        super(src);
        this.receiver = receiver;
        this.name = name;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        LMFInfo info = node.getDecoration(FlowDecorations.LMF_INFO);
        if (info == null || info.type != LMFInfo.Type.BOUND_METHOD || !name.matches(ctx.pool, node)) {
            return false;
        }
        ctx.reportPartialMatch(node, this);
        return receiver.matches(node.getInput(0), ctx);
    }
}
