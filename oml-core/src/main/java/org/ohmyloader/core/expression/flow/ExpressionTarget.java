package org.ohmyloader.core.expression.flow;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The minimal "injection target" the expression engine needs: a method, the node table keyed by the
 * original instruction, local-slot allocation and instruction replacement. MixinExtras uses Mixin's
 * {@code Target} here; OML's engine creates one of these per method it resolves expressions against.
 */
public final class ExpressionTarget {
    public final MethodNode method;
    private final Map<AbstractInsnNode, ExpressionNode> nodes = new IdentityHashMap<>();

    public ExpressionTarget(MethodNode method) {
        this.method = method;
    }

    public ExpressionNode addInjectionNode(AbstractInsnNode insn) {
        return nodes.computeIfAbsent(insn, ExpressionNode::new);
    }

    /** The node registered for this instruction, or {@code null} when none was created yet. */
    public ExpressionNode getInjectionNode(AbstractInsnNode insn) {
        return nodes.get(insn);
    }

    /** Allocates [size] consecutive local slots at the end of the method's locals. */
    public int allocateLocals(int size) {
        int slot = method.maxLocals;
        method.maxLocals += size;
        return slot;
    }

    /** Local-variable debug entries are not needed for correctness; the slot table is what matters. */
    @SuppressWarnings("unused")
    public void addLocalVariable(int index, String name, String descriptor) {
    }

    /** Replaces [old] in the method and records the replacement on its node. */
    public void replaceNode(AbstractInsnNode old, AbstractInsnNode replacement) {
        ExpressionNode node = nodes.get(old);
        method.instructions.set(old, replacement);
        if (node != null) {
            node.setCurrentTarget(replacement);
        }
    }
}
