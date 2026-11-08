package org.ohmyloader.core.expression.utils;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Small ASM helpers the expression engine needs; replaces the Mixin utility methods it was ported from. */
public final class ExpressionAsm {
    private ExpressionAsm() {
    }

    /** A readable instruction description for error messages. */
    public static String describe(AbstractInsnNode insn) {
        return insn.getClass().getSimpleName() + "(opcode=" + insn.getOpcode() + ")";
    }

    /** Whether the method has no implicit {@code this}. */
    public static boolean isStatic(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0;
    }

    /**
     * The constant a constant-loading instruction pushes, or {@code null} when it is not a constant
     * load. {@code ACONST_NULL} is reported as {@code null} too — callers that care about null use the
     * opcode, exactly like the Mixin helper this replaces.
     */
    public static Object getConstant(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc) {
            return ldc.cst;
        }
        if (insn instanceof IntInsnNode intInsn
                && (intInsn.getOpcode() == Opcodes.BIPUSH || intInsn.getOpcode() == Opcodes.SIPUSH)) {
            return intInsn.operand;
        }
        if (insn instanceof InsnNode) {
            int opcode = insn.getOpcode();
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                return opcode - Opcodes.ICONST_0;
            }
            switch (opcode) {
                case Opcodes.LCONST_0:
                    return 0L;
                case Opcodes.LCONST_1:
                    return 1L;
                case Opcodes.FCONST_0:
                    return 0f;
                case Opcodes.FCONST_1:
                    return 1f;
                case Opcodes.FCONST_2:
                    return 2f;
                case Opcodes.DCONST_0:
                    return 0.0;
                case Opcodes.DCONST_1:
                    return 1.0;
                default:
                    return null;
            }
        }
        return null;
    }

    /** {@code ch} repeated {@code count} times. */
    public static String repeat(char ch, int count) {
        return String.valueOf(ch).repeat(Math.max(0, count));
    }
}
