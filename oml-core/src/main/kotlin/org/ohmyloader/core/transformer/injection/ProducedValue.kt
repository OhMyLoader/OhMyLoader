package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.InjectionPoint

/**
 * The **value produced** by an anchor instruction — the value left on top of the stack after that
 * instruction executes: the return value of a call, the read value of a field, or the constant itself.
 * `MODIFY_EXPR_VALUE` replaces exactly this (matching Mixin's `@ModifyExpressionValue`); `MODIFY_RETURN`
 * replaces the return value that `RETURN` consumes. The type is **resolved from the instruction itself**
 * (descriptor / opcode), without running dataflow analysis: the produced value's type is uniquely determined
 * by that instruction on every execution path, whereas dataflow degrades at branch merge points into a
 * category (references → `Object`, int family → `I`) — less precise.
 */
internal object ProducedValue {

    /**
     * The type of the value produced by [anchor]; null = it does not produce a **usable** value
     * (reason in [whyFruitless]).
     *
     * `NEW` is a special case: it pushes a **not-yet-initialized** reference that cannot be passed to any method —
     * that requires waiting until the `<init>` call (writng the anchor as `afterCall(…, "<init>", …)`).
     */
    fun typeOf(anchor: AbstractInsnNode): Type? = when (anchor) {
        is MethodInsnNode -> Type.getReturnType(anchor.desc).takeIf { it != Type.VOID_TYPE }
        is InvokeDynamicInsnNode -> Type.getReturnType(anchor.desc).takeIf { it != Type.VOID_TYPE }

        is FieldInsnNode -> when (anchor.opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> Type.getType(anchor.desc)
            else -> null
        }

        is InsnNode -> when (anchor.opcode) {
            Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
            Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.ARRAYLENGTH,
                -> Type.INT_TYPE

            Opcodes.LCONST_0, Opcodes.LCONST_1 -> Type.LONG_TYPE
            Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> Type.FLOAT_TYPE
            Opcodes.DCONST_0, Opcodes.DCONST_1 -> Type.DOUBLE_TYPE
            // ACONST_NULL's "type" is the null type, which cannot be declared as a handler parameter
            else -> null
        }

        is IntInsnNode -> when (anchor.opcode) {
            Opcodes.BIPUSH, Opcodes.SIPUSH -> Type.INT_TYPE
            Opcodes.NEWARRAY -> Type.getType(arrayDescriptor(anchor.operand))
            else -> null
        }

        is LdcInsnNode -> ldcType(anchor.cst)

        is TypeInsnNode -> when (anchor.opcode) {
            // After CHECKCAST the stack top is the cast type; INSTANCEOF produces an int; ANEWARRAY produces an array
            Opcodes.CHECKCAST -> Type.getObjectType(anchor.desc)
            Opcodes.INSTANCEOF -> Type.INT_TYPE
            Opcodes.ANEWARRAY -> Type.getType("[L${anchor.desc};")
            else -> null
        }

        else -> null
    }

    /** The human-readable reason when [anchor] produces no value (diagnostic text). */
    fun whyFruitless(anchor: AbstractInsnNode): String = when (anchor) {
        is MethodInsnNode if Type.getReturnType(anchor.desc) == Type.VOID_TYPE ->
            "${anchor.name}${anchor.desc} returns void, thus produces no value (use ModifyArg/ModifyArgs to change its arguments)"

        is InvokeDynamicInsnNode if Type.getReturnType(anchor.desc) == Type.VOID_TYPE ->
            "this invokedynamic (${anchor.name}) returns void, thus produces no value"

        is FieldInsnNode if anchor.opcode in
            setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC) ->
            "field write ${anchor.name} produces no value"

        is TypeInsnNode if anchor.opcode == Opcodes.NEW ->
            "NEW pushes a **not-yet-initialized** reference that cannot be passed to any method; " +
                "to obtain that new object write the anchor after its <init> call"

        is InsnNode if anchor.opcode == Opcodes.ACONST_NULL ->
            "a null literal has the null type, which cannot be declared as a handler parameter"

        else -> "this instruction produces no value (only these are usable: non-void calls, field reads, constant loads)"
    }

    /**
     * Whether the value produced by the anchor instruction is truly **on the stack top** at the anchor location.
     *
     * Only two locations hold: **after the anchor** (`afterCall` / `afterField` / `afterConstant`), and
     * **just before `RETURN`** (the return value is consumed by `RETURN` itself). At any other location
     * (`HEAD`, `beforeCall`, `STORE`…) the stack top is not that value — inserting `INVOKESTATIC (T)T` there
     * would consume something else entirely.
     */
    fun isValueOnStack(point: InjectionPoint): Boolean = when (val core = point.core) {
        is InjectionPoint.Return -> true
        is InjectionPoint.FinalReturn -> true
        is InjectionPoint.Call -> point.after
        is InjectionPoint.FieldAccess -> point.after
        is InjectionPoint.Constant -> point.after
        else -> false
    }

    /**
     * Validates that the stack top at the anchor equals this value, and returns its type. Throws
     * [InjectionError] when it does not hold: this is a **provably** broken rule (the value is not on the
     * stack, or the instruction simply produces no value), so failing immediately is far better than inserting
     * code that consumes something else — the latter would only surface as a `VerifyError` when the class is
     * defined. It first asks "what does this instruction produce" and only then "is that value on the stack":
     * the former gives more specific diagnostics (`NEW`, `void` calls each have their own message), and only
     * it can answer the `NEW` case where the instruction produces something, but an unusable reference.
     */
    fun requireReadable(rule: String, point: InjectionPoint, anchor: AbstractInsnNode, method: MethodNode): Type {
        val value = typeOf(anchor) ?: throw InjectionError(
            "[injection] anchor of $rule produces no usable value: ${whyFruitless(anchor)} (${method.name}${method.desc})"
        )
        if (!isValueOnStack(point)) {
            throw InjectionError(
                "[injection] the anchor of $rule must land where the value has **already been produced**: afterCall / afterField / afterConstant, " +
                    "or atReturn (the return value is consumed by RETURN itself). Currently it is ${
                        AnchorResolver.describe(
                            point
                        )
                    } — " +
                    "the stack top there is not yet that value (${method.name}${method.desc})"
            )
        }
        return value
    }

    /**
     * Whether two types are equivalent **on the stack**.
     *
     * `boolean`/`byte`/`char`/`short`/`int` are the same verification type in bytecode (each occupies one slot and
     * is handled as int), so they are mutually equivalent; reference types are compared **exactly** by descriptor —
     * assignability requires a class hierarchy, and the engine currently runs inside `findClass`, so loading other
     * classes would re-enter (see `LocalFrames`).
     */
    fun sameValueType(a: Type, b: Type): Boolean =
        a.sort in INT_FAMILY && b.sort in INT_FAMILY || a == b

    private val INT_FAMILY = setOf(Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT)

    private fun ldcType(cst: Any?): Type? = when (cst) {
        is Int -> Type.INT_TYPE
        is Long -> Type.LONG_TYPE
        is Float -> Type.FLOAT_TYPE
        is Double -> Type.DOUBLE_TYPE
        is String -> Type.getObjectType("java/lang/String")
        // `ldc <Type>` pushes a **Class object**, not an ASM Type — the handler must likewise declare Class
        is Type -> Type.getObjectType("java/lang/Class")
        is org.objectweb.asm.Handle -> Type.getObjectType("java/lang/invoke/MethodHandle")
        is org.objectweb.asm.ConstantDynamic -> runCatching { Type.getType(cst.descriptor) }.getOrNull()
        else -> null
    }

    /** `NEWARRAY` operand (primitive type code) → array descriptor. */
    private fun arrayDescriptor(operand: Int): String = when (operand) {
        Opcodes.T_BOOLEAN -> "[Z"
        Opcodes.T_CHAR -> "[C"
        Opcodes.T_FLOAT -> "[F"
        Opcodes.T_DOUBLE -> "[D"
        Opcodes.T_BYTE -> "[B"
        Opcodes.T_SHORT -> "[S"
        Opcodes.T_INT -> "[I"
        Opcodes.T_LONG -> "[J"
        else -> "[Ljava/lang/Object;"
    }
}
