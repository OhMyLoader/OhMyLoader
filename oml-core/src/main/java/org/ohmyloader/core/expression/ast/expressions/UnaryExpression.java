package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;


public class UnaryExpression extends SimpleExpression {
    public final Operator operator;
    public final Expression expression;

    public UnaryExpression(ExpressionSource src, Operator operator, Expression expression) {
        super(src);
        this.operator = operator;
        this.expression = expression;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        switch (operator) {
            case MINUS:
                switch (node.getInsn().getOpcode()) {
                    case Opcodes.INEG:
                    case Opcodes.LNEG:
                    case Opcodes.FNEG:
                    case Opcodes.DNEG:
                        return inputsMatch(node, ctx, expression);
                }
            case BITWISE_NOT:
                return new BinaryExpression(
                        src,
                        expression,
                        BinaryExpression.Operator.BITWISE_XOR,
                        new IntLiteralExpression(null, -1)
                ).matchesImpl(node, ctx);
        }
        return false;
    }

    public enum Operator {
        MINUS,
        BITWISE_NOT
    }
}
