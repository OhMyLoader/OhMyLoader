package org.ohmyloader.core.expression.utils;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.ExpressionTarget;
import org.ohmyloader.core.expression.flow.utils.InsnReference;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class ComplexComparisonInfo extends ComparisonInfo {
    private final InsnReference jumpInsn;
    private final int jumpOpcode;

    public ComplexComparisonInfo(int comparison, FlowValue node, Type input, FlowValue jump, boolean jumpOnTrue) {
        super(comparison, node, input, jumpOnTrue);
        this.jumpInsn = new InsnReference(jump);
        this.jumpOpcode = jump.getInsn().getOpcode();
    }

    @Override
    public int copyJump(InsnList insns) {
        insns.add(new InsnNode(comparison));
        return jumpOpcode;
    }

    @Override
    public JumpInsnNode getJumpInsn(ExpressionTarget target) {
        return (JumpInsnNode) jumpInsn.getNode(target).getCurrentTarget();
    }

    @Override
    public void cleanup(ExpressionTarget target) {
        target.replaceNode(getJumpInsn(target), new InsnNode(Opcodes.NOP));
    }
}
