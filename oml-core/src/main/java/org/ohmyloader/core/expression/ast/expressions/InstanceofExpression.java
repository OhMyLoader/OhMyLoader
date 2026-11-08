package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.ast.identifiers.TypeIdentifier;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public class InstanceofExpression extends SimpleExpression {
    public final Expression expression;
    public final TypeIdentifier type;

    public InstanceofExpression(ExpressionSource src, Expression expression, TypeIdentifier type) {
        super(src);
        this.expression = expression;
        this.type = type;
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        AbstractInsnNode insn = node.getInsn();
        if (insn.getOpcode() != Opcodes.INSTANCEOF) {
            return false;
        }
        Type checkType = Type.getObjectType(((TypeInsnNode) insn).desc);
        return type.matches(ctx.pool, checkType) && inputsMatch(node, ctx, expression);
    }
}
