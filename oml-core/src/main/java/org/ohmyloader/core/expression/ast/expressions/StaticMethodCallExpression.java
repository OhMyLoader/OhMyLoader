package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.MethodCallType;
import org.ohmyloader.core.expression.point.ExpressionContext;

import java.util.List;

public class StaticMethodCallExpression extends SimpleExpression {
    public final MemberIdentifier name;
    public final List<Expression> arguments;

    public StaticMethodCallExpression(ExpressionSource src, MemberIdentifier name, List<Expression> arguments) {
        super(src);
        this.name = name;
        this.arguments = arguments;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        return MethodCallType.STATIC.matches(node)
                && name.matches(ctx.pool, node)
                && inputsMatch(node, ctx, ctx.allowIncompleteListInputs, arguments.toArray(new Expression[0]));
    }
}
