package org.ohmyloader.core.expression.flow.expansion;

import org.ohmyloader.core.expression.flow.ExpressionNode;
import org.ohmyloader.core.expression.flow.ExpressionTarget;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.FlowPostProcessor;
import org.ohmyloader.core.expression.point.ExpressionContext;
import org.ohmyloader.core.expression.utils.ExpressionDecorations;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Base class for **compound instruction** expansions (iinc, unary comparisons, string concatenation):
 * they synthesize flow values so the expression can match parts of a compound instruction, and
 * rewrite that instruction on demand into the pieces the injector targets.
 *
 * MixinExtras tracks one interested `InjectionInfo` per expansion step. An OML expression anchor has
 * exactly one payload, so the interest key is just an opaque object (the payload/rule) and
 * injector-specific decorations collapse into ordinary decorations.
 */
public abstract class InsnExpander implements FlowPostProcessor {
    private static final String INSN_COMPONENT = "expandedInsnComponent";
    private static final String EXPANSION = "expansion";

    private final Map<AbstractInsnNode, Expansion> expansions = new IdentityHashMap<>();

    @Override
    public abstract void process(FlowValue node, FlowPostProcessor.OutputSink sink);

    public abstract void expand(ExpressionTarget target, ExpressionNode node, Expansion expansion);

    protected final void registerComponent(FlowValue node, InsnComponent component, AbstractInsnNode compound) {
        node.decorate(INSN_COMPONENT, component);
        expansions.computeIfAbsent(compound, Expansion::new);
        node.decorate(EXPANSION, expansions.get(compound));
    }

    protected final void expandInsn(ExpressionTarget target, ExpressionNode node, AbstractInsnNode... insns) {
        InsnList insnList = new InsnList();
        for (AbstractInsnNode insn : insns) {
            insnList.add(insn);
        }
        AbstractInsnNode insn = node.getCurrentTarget();
        target.method.instructions.insert(insn, insnList);
        target.replaceNode(insn, dummyInsn());
    }

    protected static InsnNode dummyInsn() {
        return new InsnNode(Opcodes.NOP);
    }

    public static Expansion prepareExpansion(
            FlowValue node, ExpressionTarget target, Object injector, ExpressionContext ctx) {
        if (!hasExpansion(node)) {
            return null;
        }
        checkSupportsExpansion(ctx.type);
        Expansion expansion = node.getDecoration(EXPANSION);
        AbstractInsnNode compoundInsn = expansion.compound;
        ExpressionNode compoundNode = target.addInjectionNode(compoundInsn);
        if (!compoundNode.hasDecoration(ExpressionDecorations.EXPANSION_INFO)) {
            compoundNode.decorate(ExpressionDecorations.EXPANSION_INFO, expansion);
        }
        expansion.registerInterest(injector, node.getDecoration(INSN_COMPONENT));
        return expansion;
    }

    public static ExpressionNode doExpansion(ExpressionNode node, ExpressionTarget target, Object injector) {
        Expansion expansion = node.getDecoration(ExpressionDecorations.EXPANSION_INFO);
        if (expansion == null) {
            return node;
        }
        expansion.doExpansion(target, node);
        return target.addInjectionNode(expansion.getTargetInsn(injector));
    }

    private static void checkSupportsExpansion(ExpressionContext.Type type) {
        switch (type) {
            case SLICE:
            case INJECT:
            case MODIFY_VARIABLE:
                // Tolerate, they don't care about their target instruction.
                return;
            case MODIFY_EXPRESSION_VALUE:
            case WRAP_OPERATION:
                // Supported.
                return;
            default:
                throw new UnsupportedOperationException(
                        "Expression context type " + type + " does not support compound instructions!"
                );
        }
    }

    public static AbstractInsnNode getRepresentative(FlowValue node) {
        Expansion expansion = node.getDecoration(EXPANSION);
        if (expansion != null) {
            return expansion.compound;
        }
        return node.getInsn();
    }

    public static boolean hasExpansion(FlowValue node) {
        return node.hasDecoration(EXPANSION);
    }

    public static void addExpansionStep(FlowValue node, Consumer<ExpressionNode> step) {
        Expansion expansion = node.getDecoration(EXPANSION);
        expansion.addExpansionStep(node.getDecoration(INSN_COMPONENT), step);
    }

    public class Expansion {
        private final Map<Object, InsnComponent> interests = new IdentityHashMap<>();
        private final Map<InsnComponent, List<Consumer<ExpressionNode>>> expansionSteps = new HashMap<>();
        private final Map<InsnComponent, AbstractInsnNode> expandedInsns = new HashMap<>();
        private boolean expanded = false;
        public final AbstractInsnNode compound;

        public Expansion(AbstractInsnNode compound) {
            this.compound = compound;
        }

        public void registerInterest(Object injector, InsnComponent component) {
            if (interests.put(injector, component) != null) {
                throw new UnsupportedOperationException(
                        "The same injector should not target multiple parts of a compound instruction!"
                );
            }
        }

        public void decorate(Object injector, String key, Object value) {
            addExpansionStep(interests.get(injector), node -> node.decorate(key, value));
        }

        public void decorateInjectorSpecific(Object injector, String key, Object value) {
            // One payload per expression anchor: injector-specific and generic decorations are the same store
            decorate(injector, key, value);
        }

        public Set<InsnComponent> registeredInterests() {
            return new HashSet<>(interests.values());
        }

        void addExpansionStep(InsnComponent component, Consumer<ExpressionNode> step) {
            expansionSteps.computeIfAbsent(component, k -> new ArrayList<>()).add(step);
        }

        void doExpansion(ExpressionTarget target, ExpressionNode node) {
            if (expanded) {
                return;
            }
            expanded = true;
            InsnExpander.this.expand(target, node, this);
            for (Map.Entry<InsnComponent, List<Consumer<ExpressionNode>>> steps : expansionSteps.entrySet()) {
                ExpressionNode newNode = target.addInjectionNode(expandedInsns.get(steps.getKey()));
                for (Consumer<ExpressionNode> step : steps.getValue()) {
                    step.accept(newNode);
                }
            }
            expansionSteps.clear();
        }

        AbstractInsnNode getTargetInsn(Object injector) {
            return expandedInsns.get(interests.get(injector));
        }

        protected AbstractInsnNode registerInsn(InsnComponent component, AbstractInsnNode insn) {
            expandedInsns.put(component, insn);
            return insn;
        }
    }

    public interface InsnComponent {
    }
}
