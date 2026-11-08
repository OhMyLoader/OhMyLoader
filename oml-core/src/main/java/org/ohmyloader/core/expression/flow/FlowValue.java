package org.ohmyloader.core.expression.flow;

import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One value flowing through the method: a producer instruction plus the values it consumes. The
 * graph is built by {@link FlowInterpreter} and then matched against the parsed expression; a value
 * with no instruction is either a control-flow merge ({@link ComplexFlowValue}) or unknown
 * ({@link DummyFlowValue}).
 *
 * Ported from MixinExtras (MIT); the edge list uses a small local record instead of commons-lang's
 * {@code Pair}.
 */
public class FlowValue implements Value {
    /** An outgoing edge: the consumer value and the input slot this value feeds. */
    public record Edge(FlowValue value, int index) {
    }

    private final Type type;
    private AbstractInsnNode insn;
    protected FlowValue[] parents;
    private final Set<Edge> next = new HashSet<>(1);
    private boolean nextIsReady;
    private Map<String, Object> decorations = null;

    public FlowValue(Type type, AbstractInsnNode insn, FlowValue... parents) {
        this.type = type;
        this.insn = insn;
        this.parents = parents;
    }

    public void addChild(FlowValue value, int index) {
        if (nextIsReady) {
            return;
        }
        next.add(new Edge(value, index));
    }

    public void finish() {
        for (int i = 0; i < parents.length; i++) {
            parents[i].addChild(this, i);
        }
    }

    public void onFinished() {
        nextIsReady = true;
    }

    private void markNextDirty() {
        nextIsReady = false;
        next.clear();
    }

    @Override
    public int getSize() {
        return type.getSize();
    }

    public Type getType() {
        return type;
    }

    public AbstractInsnNode getInsn() {
        return insn;
    }

    public Set<Edge> getNext() {
        return next;
    }

    public FlowValue getInput(int index) {
        return parents[index];
    }

    public int inputCount() {
        return parents.length;
    }

    public void setInsn(AbstractInsnNode insn) {
        this.insn = insn;
    }

    public void setParents(FlowValue... parents) {
        for (FlowValue parent : this.parents) {
            parent.markNextDirty();
        }
        this.parents = parents;
    }

    public void setParent(int index, FlowValue value) {
        parents[index].markNextDirty();
        parents[index] = value;
    }

    public void removeParent(int index) {
        List<FlowValue> remaining = new ArrayList<>(Arrays.asList(parents));
        remaining.remove(index);
        setParents(remaining.toArray(FlowValue[]::new));
    }

    public FlowValue mergeWith(FlowValue other, FlowContext ctx) {
        if (this.equals(other)) {
            return this;
        }
        if (other instanceof ComplexFlowValue) {
            return other.mergeWith(this, ctx);
        }
        if (this.isTypeKnown() && other.isTypeKnown()) {
            return new DummyFlowValue(ExpressionASMUtils.getCommonSupertype(ctx, getType(), other.getType()));
        }
        return new ComplexFlowValue(getSize(), new HashSet<>(Arrays.asList(this, other)), ctx);
    }

    public void mergeInputs(FlowValue[] newInputs, FlowContext ctx) {
        for (int i = 0; i < parents.length; i++) {
            parents[i] = parents[i].mergeWith(newInputs[i], ctx);
        }
    }

    private boolean isTypeKnown() {
        return type != null;
    }

    public boolean isComplex() {
        return insn == null;
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

    public boolean typeMatches(Type desiredType) {
        if (ExpressionASMUtils.isIntLike(desiredType) && getType().equals(ExpressionASMUtils.INTLIKE_TYPE)) {
            return true;
        }
        return getType().equals(desiredType);
    }
}
