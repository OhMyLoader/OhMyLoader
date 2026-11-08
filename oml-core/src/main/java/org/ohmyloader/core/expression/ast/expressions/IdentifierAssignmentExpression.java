package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.MemberIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;

public class IdentifierAssignmentExpression extends Expression {
    public final MemberIdentifier identifier;
    public final Expression value;

    public IdentifierAssignmentExpression(ExpressionSource src, MemberIdentifier identifier, Expression value) {
        super(src);
        this.identifier = identifier;
        this.value = value;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        switch (node.getInsn().getOpcode()) {
            case Opcodes.ISTORE:
            case Opcodes.LSTORE:
            case Opcodes.FSTORE:
            case Opcodes.DSTORE:
            case Opcodes.ASTORE:
            case Opcodes.PUTSTATIC:
                return identifier.matches(ctx.pool, node) && inputsMatch(node, ctx, value);
        }
        return false;
    }
}
