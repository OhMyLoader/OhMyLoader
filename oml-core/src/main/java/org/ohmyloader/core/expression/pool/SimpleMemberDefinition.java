package org.ohmyloader.core.expression.pool;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.postprocessing.LMFInfo;
import org.ohmyloader.core.expression.utils.FlowDecorations;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;

public interface SimpleMemberDefinition extends MemberDefinition {
    boolean matches(AbstractInsnNode insn);

    default boolean matches(Handle handle) {
        return false;
    }

    @Override
    default boolean matches(FlowValue node) {
        LMFInfo lmfInfo = node.getDecoration(FlowDecorations.LMF_INFO);
        if (lmfInfo != null) {
            return matches(lmfInfo.impl);
        }
        return matches(node.getInsn());
    }
}
