package org.ohmyloader.core.expression.flow;

import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A target instruction plus the decorations the expression engine attaches to it. MixinExtras uses
 * Mixin's {@code InjectionNode} for the same purpose; OML has no such node model, so this thin
 * wrapper carries the instruction across compound-instruction expansion and keeps the decorations
 * the injector reads afterwards.
 */
public final class ExpressionNode {
    private AbstractInsnNode insn;
    private boolean replaced;
    private Map<String, Object> decorations;

    ExpressionNode(AbstractInsnNode insn) {
        this.insn = insn;
    }

    /** The instruction currently standing in for this node (after any expansion replacement). */
    public AbstractInsnNode getCurrentTarget() {
        return insn;
    }

    void setCurrentTarget(AbstractInsnNode insn) {
        this.insn = insn;
        this.replaced = true;
    }

    /** Whether the original instruction has been replaced during expansion. */
    public boolean isReplaced() {
        return replaced;
    }

    public <V> void decorate(String key, V value) {
        if (decorations == null) {
            decorations = new HashMap<>();
        }
        decorations.put(key, value);
    }

    public boolean hasDecoration(String key) {
        return decorations != null && decorations.get(key) != null;
    }

    @SuppressWarnings("unchecked")
    public <V> V getDecoration(String key) {
        return (V) (decorations == null ? null : decorations.get(key));
    }

    public Map<String, Object> getDecorations() {
        return decorations == null ? Collections.emptyMap() : decorations;
    }
}
