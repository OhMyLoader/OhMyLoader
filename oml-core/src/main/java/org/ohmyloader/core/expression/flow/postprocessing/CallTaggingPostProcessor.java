package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.utils.ExpressionAsm;
import org.ohmyloader.core.expression.utils.FlowDecorations;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class CallTaggingPostProcessor implements FlowPostProcessor {
    private final Type currentType;
    private final boolean isStatic;

    public CallTaggingPostProcessor(ClassNode classNode, MethodNode methodNode) {
        this.currentType = Type.getObjectType(classNode.name);
        this.isStatic = ExpressionAsm.isStatic(methodNode);
    }

    @Override
    public void process(FlowValue node, OutputSink sink) {
        MethodCallType type = getType(node);
        if (type == null) {
            return;
        }
        node.decorate(FlowDecorations.METHOD_CALL_TYPE, type);
        if (type == MethodCallType.SUPER) {
            sink.markAsSynthetic(node.getInput(0)); // not a real `this`
            node.removeParent(0);
        }
    }

    private MethodCallType getType(FlowValue node) {
        if (!(node.getInsn() instanceof MethodInsnNode)) {
            return null;
        }
        MethodInsnNode call = (MethodInsnNode) node.getInsn();
        switch (call.getOpcode()) {
            case Opcodes.INVOKEINTERFACE:
            case Opcodes.INVOKEVIRTUAL:
                return MethodCallType.NORMAL;
            case Opcodes.INVOKESTATIC:
                return MethodCallType.STATIC;
            case Opcodes.INVOKESPECIAL:
                if (call.name.equals("<init>")) {
                    return null;
                }
                if (call.owner.equals(currentType.getInternalName())) {
                    return MethodCallType.NORMAL;
                }
                if (isLoadThis(node.getInput(0))) {
                    return MethodCallType.SUPER;
                }
                // We have no clue what this is, fall through:
        }
        return null;
    }

    private boolean isLoadThis(FlowValue node) {
        if (isStatic || node.isComplex() || node.getInsn().getOpcode() != Opcodes.ALOAD) {
            return false;
        }
        VarInsnNode load = (VarInsnNode) node.getInsn();
        return load.var == 0;
    }
}
