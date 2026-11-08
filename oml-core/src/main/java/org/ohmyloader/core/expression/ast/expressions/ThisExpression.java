package org.ohmyloader.core.expression.ast.expressions;

import org.ohmyloader.core.expression.ExpressionSource;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.VarInsnNode;

public class ThisExpression extends SimpleExpression {
    public ThisExpression(ExpressionSource src) {
        super(src);
    }

    @Override
    protected boolean matchesImpl(FlowValue node, ExpressionContext ctx) {
        if (ctx.isStatic) {
            return false;
        }
        return node.getInsn().getOpcode() == Opcodes.ALOAD && ((VarInsnNode) node.getInsn()).var == 0;
    }
}
