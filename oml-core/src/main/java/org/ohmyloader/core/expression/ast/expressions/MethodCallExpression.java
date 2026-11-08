package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.MethodCallType;
import org.ohmyloader.core.expression.point.ExpressionContext;


import java.util.List;

public class MethodCallExpression extends SimpleExpression {
    public final Expression receiver;
    public final MemberIdentifier name;
    public final List<Expression> arguments;

    public MethodCallExpression(ExpressionSource src, Expression receiver, MemberIdentifier name, List<Expression> arguments) {
        super(src);
        this.receiver = receiver;
        this.name = name;
        this.arguments = arguments;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        if (!MethodCallType.NORMAL.matches(node)) {
            return false;
        }
        if (!name.matches(ctx.pool, node)) {
            return false;
        }
        Expression[] inputs = new Expression[arguments.size() + 1];
        inputs[0] = receiver;
        for (int i = 0; i < arguments.size(); i++) {
            inputs[i + 1] = arguments.get(i);
        }
        return inputsMatch(node, ctx, ctx.allowIncompleteListInputs, inputs);
    }
}
