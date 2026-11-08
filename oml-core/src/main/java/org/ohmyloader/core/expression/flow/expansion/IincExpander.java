package org.ohmyloader.core.expression.flow.expansion;

import org.ohmyloader.core.expression.flow.postprocessing.FlowPostProcessor;
import org.ohmyloader.core.expression.flow.ExpressionNode;
import org.ohmyloader.core.expression.flow.ExpressionTarget;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.utils.ExpressionASMUtils;
import org.ohmyloader.core.expression.utils.ExpressionAsm;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class IincExpander extends InsnExpander {
    @Override
    public void process(FlowValue node, FlowPostProcessor.OutputSink sink) {
        if (node.getInsn().getOpcode() != Opcodes.IINC) {
            return;
        }
        IincInsnNode iinc = (IincInsnNode) node.getInsn();
        FlowValue load = new FlowValue(Type.INT_TYPE, new VarInsnNode(Opcodes.ILOAD, iinc.var));
        registerComponent(load, Component.LOAD, iinc);
        FlowValue cst = new FlowValue(Type.INT_TYPE, ExpressionASMUtils.pushInt(iinc.incr));
        registerComponent(cst, Component.CST, iinc);
        FlowValue add = new FlowValue(Type.INT_TYPE, new InsnNode(Opcodes.IADD), load, cst);
        registerComponent(add, Component.ADD, iinc);

        node.setInsn(new VarInsnNode(Opcodes.ISTORE, iinc.var));
        node.setParents(add);
        registerComponent(node, Component.STORE, iinc);

        sink.registerFlow(load, cst, add);
    }

    @Override
    public void expand(ExpressionTarget target, ExpressionNode node, Expansion expansion) {
        IincInsnNode iinc = (IincInsnNode) node.getCurrentTarget();
        target.method.maxStack += 2;
        expandInsn(
                target, node,
                expansion.registerInsn(Component.LOAD, new VarInsnNode(Opcodes.ILOAD, iinc.var)),
                expansion.registerInsn(Component.CST, ExpressionASMUtils.pushInt(iinc.incr)),
                expansion.registerInsn(Component.ADD, new InsnNode(Opcodes.IADD)),
                expansion.registerInsn(Component.STORE, new VarInsnNode(Opcodes.ISTORE, iinc.var))
        );
    }

    private enum Component implements InsnComponent {
        LOAD, CST, ADD, STORE
    }
}
