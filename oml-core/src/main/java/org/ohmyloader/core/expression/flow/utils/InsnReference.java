package org.ohmyloader.core.expression.flow.utils;

import org.ohmyloader.core.expression.flow.ExpressionNode;
import org.ohmyloader.core.expression.flow.ExpressionTarget;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.expansion.InsnExpander;
import org.objectweb.asm.tree.AbstractInsnNode;

/**
 * Keeps track of a particular instruction throughout expansion: either the node's current target
 * (registered as an expansion step when the flow value is part of a compound instruction) or the
 * instruction itself.
 */
public class InsnReference {
    private AbstractInsnNode insn;

    public InsnReference(FlowValue flow) {
        if (flow.isComplex()) {
            throw new IllegalArgumentException("Cannot create a reference to a complex flow");
        }
        if (InsnExpander.hasExpansion(flow)) {
            InsnExpander.addExpansionStep(flow, node -> this.insn = node.getCurrentTarget());
        } else {
            this.insn = flow.getInsn();
        }
    }

    public ExpressionNode getNode(ExpressionTarget target) {
        if (insn == null) {
            throw new UnsupportedOperationException("This flow value has not yet been expanded!");
        }
        ExpressionNode node = target.getInjectionNode(insn);
        if (node != null) {
            return node;
        }
        if (!target.method.instructions.contains(insn)) {
            throw new IllegalArgumentException("This insn is not present in " + target.method.name);
        }
        return target.addInjectionNode(insn);
    }
}
