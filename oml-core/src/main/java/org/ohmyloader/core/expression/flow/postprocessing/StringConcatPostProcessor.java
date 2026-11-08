package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.utils.FlowDecorations;
import org.ohmyloader.core.expression.flow.FlowValue.Edge;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class StringConcatPostProcessor implements FlowPostProcessor {
    private static final String STRING_BUILDER = Type.getInternalName(StringBuilder.class);

    @Override
    public void process(FlowValue node, OutputSink sink) {
        FlowValue firstAppend = getFirstAppend(node);
        if (firstAppend == null) {
            return;
        }
        List<FlowValue> appendCalls = new ArrayList<>();
        FlowValue currentAppend = firstAppend;
        FlowValue toStringCall;
        while (true) {
            appendCalls.add(currentAppend);
            Collection<Edge> next = currentAppend.getNext();
            if (next.size() != 1) {
                return;
            }
            Edge child = next.iterator().next();
            if (isAppendCall(child)) {
                currentAppend = child.value();
                continue;
            }
            if (isToStringCall(child)) {
                toStringCall = child.value();
                break;
            }
            return;
        }
        if (appendCalls.size() < 2) {
            // Not a concatenation.
            return;
        }
        decorateConcat(appendCalls, toStringCall);
    }

    private void decorateConcat(List<FlowValue> appendCalls, FlowValue toStringCall) {
        FlowValue initialComponent = appendCalls.get(0).getInput(1);
        for (int i = 1; i < appendCalls.size() - 1; i++) {
            appendCalls.get(i).decorate(
                    FlowDecorations.STRING_CONCAT_INFO,
                    new StringConcatInfo(
                            i == 1,
                            true,
                            initialComponent,
                            toStringCall
                    )
            );
        }
        toStringCall.decorate(
                FlowDecorations.STRING_CONCAT_INFO,
                new StringConcatInfo(
                        appendCalls.size() == 2,
                        false,
                        initialComponent,
                        toStringCall
                )
        );
    }

    private FlowValue getFirstAppend(FlowValue node) {
        InstantiationInfo instantiation = node.getDecoration(FlowDecorations.INSTANTIATION_INFO);
        if (instantiation == null || !instantiation.type.getInternalName().equals(STRING_BUILDER)) {
            return null;
        }
        if (!isEmptyInit(instantiation.initCall)) {
            return null;
        }
        if (node.getNext().size() != 1) {
            return null;
        }
        Edge firstAppend = node.getNext().iterator().next();
        if (isAppendCall(firstAppend)) {
            return firstAppend.value();
        }
        return null;
    }

    private boolean isEmptyInit(FlowValue call) {
        return ((MethodInsnNode) call.getInsn()).desc.equals("()V");
    }

    private boolean isAppendCall(Edge child) {
        if (child.index() != 0) {
            return false;
        }
        AbstractInsnNode insn = child.value().getInsn();
        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) insn;
        return call.owner.equals(STRING_BUILDER) && call.name.equals("append") && Type.getArgumentTypes(call.desc).length == 1;
    }

    private boolean isToStringCall(Edge child) {
        if (child.index() != 0) {
            return false;
        }
        AbstractInsnNode insn = child.value().getInsn();
        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) insn;
        return call.owner.equals(STRING_BUILDER) && call.name.equals("toString");
    }
}
