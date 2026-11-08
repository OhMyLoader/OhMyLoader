package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.MethodCallType;
import org.ohmyloader.core.expression.point.ExpressionContext;

import java.util.List;

public class SuperCallExpression extends SimpleExpression {
    public final MemberIdentifier name;
    public final List<Expression> arguments;

    public SuperCallExpression(ExpressionSource src, MemberIdentifier name, List<Expression> arguments) {
        super(src);
        this.name = name;
        this.arguments = arguments;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        if (!MethodCallType.SUPER.matches(node)) {
            return false;
        }
        if (!name.matches(ctx.pool, node)) {
            return false;
        }
        return inputsMatch(node, ctx, ctx.allowIncompleteListInputs, arguments.toArray(new Expression[0]));
    }
}
