package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.expression.utils.ComparisonInfo
import org.ohmyloader.core.expression.utils.ComplexComparisonInfo
import org.ohmyloader.core.hook.*
import org.ohmyloader.core.transformer.injection.PayloadEmitter.emitCancelableReturn
import org.ohmyloader.core.transformer.injection.PayloadEmitter.emitValue


// ---------- payload emitter: builds fresh instruction nodes for each anchor ----------

internal object PayloadEmitter {

    /** How a bridge descriptor should end: the handle is the last parameter, returning void. */
    private const val CALLBACK_RETURNABLE_TAIL = "$CALLBACK_RETURNABLE_DESC)V"

    /** The reference placeholder for values whose precise type the anchor does not declare (jump operands). */
    private val OBJECT_TYPE: Type = Type.getObjectType("java/lang/Object")

    /**
     * Emits the payload instructions; returns `null` when **this injection cannot be completed** (reason already
     * printed), and the caller should skip.
     *
     * [locals] is the local-variable snapshot at the anchor. Only payloads with [usesLocals] true receive it —
     * the rest get `null`, and the engine won't run dataflow analysis for them.
     */
    fun emit(payload: Payload, method: MethodNode, owner: String, locals: AnchorLocals?): InsnList? = when (payload) {
        is Payload.StaticCall -> {
            // Argument types vs handler descriptor: both sides are statically known, so a mismatch is a definite rule error
            validateArgTypes(payload.desc, payload.args, 0, method, owner, locals, "StaticCall")
            val list = InsnList()
            val writeBacks = mutableListOf<SugarWriteBack>()
            if (!emitValues(list, payload.args, method, locals, writeBacks)) return null
            list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
            emitWriteBacks(list, writeBacks)
            list
        }

        is Payload.TransformReturn -> {
            val ret = Type.getReturnType(method.desc)
            val handlerRet = Type.getReturnType(payload.desc)
            val handlerArgs = Type.getArgumentTypes(payload.desc)
            val shapeOk = handlerRet == ret &&
                (ret == Type.VOID_TYPE || (handlerArgs.isNotEmpty() && handlerArgs[0] == ret))
            if (!shapeOk) {
                OmlLog.error(
                    "Injection",
                    "TransformReturn handler shape mismatch: target ${method.name}${method.desc} " +
                        "returns $ret; handler should be shaped ($ret[, extras...])$ret, actual ${payload.desc}",
                )
                return null
            }
            // extras push after the return value, mapping to the descriptor's parameters from the 2nd on
            validateArgTypes(payload.desc, payload.extras, 1, method, owner, locals, "TransformReturn.extras")
            val list = InsnList()
            // This anchor is a return instruction, so the stack top is already the return value R: extras push on
            // top of R, so the argument order is exactly (R, extras...)
            val writeBacks = mutableListOf<SugarWriteBack>()
            if (!emitValues(list, payload.extras, method, locals, writeBacks)) return null
            list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
            emitWriteBacks(list, writeBacks)
            list
        }

        is Payload.CheckCall -> {
            validateArgTypes(payload.desc, payload.args, 0, method, owner, locals, "CheckCall")
            emitCheckCall(payload, method, owner, locals, null)
        }

        is Payload.Redirect -> error("Redirect substitutes the instruction directly in the engine and does not go through emit")
        is Payload.WrapBranch -> error("WrapBranch substitutes the jump directly in the engine and does not go through emit")
        is Payload.CancelableReturn -> emitCancelableReturn(payload, method, owner, locals)

        // Value-modifying instance handlers: **no handle** — the handler's return value IS the conclusion.
        // They go through the same emission logic as the same-named external static payloads, except the call
        // switches from INVOKESTATIC to INVOKEVIRTUAL.
        is Payload.HandlerCall -> when (payload.kind) {
            HandlerKind.INJECT -> emitHandlerCall(payload, method, owner, locals)

            // The other forms need the anchor instruction to compute (whether/which type of value is on the stack
            // is decided by that instruction), so applyToMethod calls emitModify* / emitInPlaceHandler directly.
            // REDIRECT is even more radical: it rewrites that call instruction in place; the anchor IS the instruction being replaced.
            HandlerKind.MODIFY_ARG, HandlerKind.MODIFY_ARGS, HandlerKind.MODIFY_VAR,
            HandlerKind.MODIFY_CONST, HandlerKind.MODIFY_RETURN, HandlerKind.MODIFY_EXPR_VALUE,
            HandlerKind.MODIFY_RECEIVER, HandlerKind.WRAP_WITH_CONDITION, HandlerKind.WRAP_BRANCH,
            HandlerKind.WRAP_OPERATION, HandlerKind.WRAP_METHOD, HandlerKind.REDIRECT,
                -> error("HandlerCall(${payload.kind}) needs the anchor instruction and is handled directly by the engine, not through emit")
        }

        // Anchor-related: needs the anchor instruction to compute (whether/which type of value is on the stack is
        // decided by that instruction), so applyToMethod calls emitModify* / emitInPlaceValue directly
        is Payload.ModifyArg, is Payload.ModifyArgs, is Payload.ModifyVariable,
        is Payload.ModifyConstant, is Payload.ModifyExpressionValue, is Payload.ModifyReceiver,
        is Payload.WrapWithCondition, is Payload.WrapOperation,
            ->
            error("${payload.javaClass.simpleName} needs the anchor instruction; the engine calls emitModify*/emitInPlaceValue directly")

        is Payload.WrapMethod -> error("WrapMethod applies to the whole method and is handled by MethodWrapper")

        is Payload.Raw -> InsnList().apply { payload.build(this, method, owner) }
    }

    /**
     * The void short-circuit sequence: call the static handler `(captures…)Z`, and on `true` return
     * from the whole method immediately; otherwise continue with the original body.
     *
     * [continuationLocals] is the frame the instruction after the injected block must see: `null`
     * (method-entry anchors) synthesizes the method's entry frame, which is implicit in the frame
     * table; an after-call anchor passes the dataflow-derived locals of the instruction following the
     * call (see [MethodFrames.frameLocalsAt]) — once the body has stored locals, the entry frame no
     * longer describes that point and a wrong frame fails verification at class load.
     */
    fun emitCheckCall(
        payload: Payload.CheckCall,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
        continuationLocals: Array<Any>?,
    ): InsnList? {
        val list = InsnList()
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, payload.args, method, locals, writeBacks)) return null
        list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
        emitWriteBacks(list, writeBacks)
        val label = LabelNode()
        list.add(JumpInsnNode(Opcodes.IFEQ, label))
        list.add(InsnNode(Opcodes.RETURN))
        list.add(label)
        list.add(
            if (continuationLocals == null) entryFrame(owner, method)
            else FrameNode(
                Opcodes.F_NEW,
                continuationLocals.size,
                continuationLocals,
                0,
                emptyArray(),
            ),
        )
        return list
    }

    /**
     * The value-modifying [Payload.HandlerCall] instance form of [emitInPlaceValue].
     *
     * The three "the value is already on the stack" forms (constant / return value / expression value) share it:
     * they differ only in **where the value's type comes from** and the name in diagnostics ([rule]).
     */
    fun emitInPlaceHandler(
        payload: Payload.HandlerCall,
        rule: String,
        value: Type,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
    ): InsnList? {
        if (payload.variant != HandlerVariant.NOTIFY) {
            throw InjectionError(
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}",
            )
        }
        return emitInPlaceValue(
            rule, payload.owner, payload.method, payload.desc, payload.captures, true, value, method, owner, locals,
        )
    }

    /**
     * Modifies **the value already on the stack**: hands it to the handler and replaces it with the return value.
     * The value is there because the caller anchored where the value is already produced (after `afterConstant` /
     * `afterCall` / `afterField`, before `atReturn`).
     * The static form is a single `INVOKESTATIC` (the value is the first argument); the instance form must stash
     * the value into a temp slot first — `INVOKEVIRTUAL`'s receiver must push **below** the args, so a bare
     * `ALOAD 0` would make receiver and value swap in the handler's eyes (with matching types it won't error,
     * it just silently computes wrong). [value] comes from the **anchor instruction** (or the target's return
     * type), not the handler's declaration — so a mismatched declared type is reported immediately instead of
     * surfacing as a `VerifyError` at class definition.
     */
    fun emitInPlaceValue(
        rule: String,
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        value: Type,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
    ): InsnList? {
        val handler = Type.getMethodType(handlerDesc)
        val valueParam = handler.argumentTypes.firstOrNull()
        if (valueParam == null || !ProducedValue.sameValueType(valueParam, value)) {
            throw InjectionError(
                "[injection] the handler's first parameter type of $rule must be **the value's type** ${value.className}" +
                    " (decided by the anchor): actual ${valueParam?.className ?: "no parameters"} ($handlerDesc)",
            )
        }
        if (handler.returnType != value) {
            throw InjectionError(
                "[injection] the handler of $rule must return ${value.className} (the value's type, to be put back on the stack): " +
                    "actual ${handler.returnType.className} ($handlerDesc)",
            )
        }
        validateArgTypes(handlerDesc, extras, 1, method, owner, locals, "$rule.extras")
        val list = InsnList()
        if (instance) {
            val slot = allocLocalSlot(method, value.size)
            list.add(VarInsnNode(Boxing.storeOpcode(value), slot))
            list.add(VarInsnNode(Opcodes.ALOAD, 0))
            list.add(VarInsnNode(Boxing.loadOpcode(value), slot))
        }
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        return list
    }

    /**
     * Directly calls the "handler merged into the target class": `this` is the target instance.
     *
     * Nearly the same skeleton as [emitCancelableReturn], with two differences:
     * 1. Push **`ALOAD 0` (the target instance)** first, then the capture arguments; the call is `INVOKEVIRTUAL`
     *    (not a static bridge);
     * 2. The handle `HookContext` / `HookValue` is created by the engine and passed in.
     *
     * Static target methods are rejected: the whole point of [HandlerCall] is handing `this` to the handler.
     */
    private fun emitHandlerCall(
        payload: Payload.HandlerCall,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
    ): InsnList? {
        if ((method.access and Opcodes.ACC_STATIC) != 0) {
            throw InjectionError(
                "[injection] HandlerCall can only be injected into **instance methods**: ${method.name}${method.desc} is a static method, " +
                    "the handler cannot get this (the target instance) — use a static handler (StaticCall/CheckCall) then",
            )
        }
        val ret = Type.getReturnType(method.desc)
        when (payload.variant) {
            HandlerVariant.NOTIFY -> Unit
            HandlerVariant.CANCELABLE -> if (ret != Type.VOID_TYPE) {
                throw InjectionError(
                    "[injection] the CANCELABLE form is only for void targets (for non-void use RETURNABLE): " +
                        "${method.name}${method.desc} returns ${ret.className}",
                )
            }

            HandlerVariant.RETURNABLE -> if (ret == Type.VOID_TYPE) {
                throw InjectionError(
                    "[injection] the RETURNABLE form requires a non-void target (for a void target use CANCELABLE): " +
                        "${method.name}${method.desc}",
                )
            }
        }
        val handleDesc = if (payload.variant == HandlerVariant.RETURNABLE) CALLBACK_RETURNABLE_DESC
        else CALLBACK_INFO_DESC
        if (!payload.desc.endsWith("$handleDesc)V")) {
            throw InjectionError(
                "[injection] the HandlerCall descriptor must end with " + handleDesc + ")V" +
                    " (captured arguments + handle, returning void): ${method.name}${method.desc} uses ${payload.desc}",
            )
        }
        // desc params = capture args + trailing handle ⇒ the pushed captures only map to the first n-1
        validateArgTypes(payload.desc, payload.captures, 0, method, owner, locals, "HandlerCall", tailSkip = 1)

        // The handle **must be created for all three variants**: the handler's parameter list always ends with it
        // (a notify-style `HookContext` is mandatory too). Whether to "read it back" is the only differentiator —
        // only cancelable/returnable variants need to read back the cancel flag.
        val readsBack = payload.variant != HandlerVariant.NOTIFY
        val cancelableHandle = payload.variant != HandlerVariant.NOTIFY
        val internal = if (payload.variant == HandlerVariant.RETURNABLE) CALLBACK_RETURNABLE else CALLBACK_INFO
        val slot = allocLocalSlot(method)
        method.maxLocals = maxOf(method.maxLocals, slot + 1)
        val list = InsnList()

        list.add(TypeInsnNode(Opcodes.NEW, internal))
        list.add(InsnNode(Opcodes.DUP))
        list.add(LdcInsnNode(method.name))
        list.add(InsnNode(if (cancelableHandle) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, internal, "<init>", "(Ljava/lang/String;Z)V", false))
        list.add(VarInsnNode(Opcodes.ASTORE, slot))

        list.add(VarInsnNode(Opcodes.ALOAD, 0)) // receiver (target instance) — it isn't an argument
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, payload.captures, method, locals, writeBacks)) return null
        list.add(VarInsnNode(Opcodes.ALOAD, slot)) // the handle is an argument
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, payload.owner, payload.method, payload.desc, false))
        emitWriteBacks(list, writeBacks)

        if (!readsBack) return list

        val cont = LabelNode()
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, internal, "getCanceled", "()Z", false))
        list.add(JumpInsnNode(Opcodes.IFEQ, cont))

        if (payload.variant == HandlerVariant.CANCELABLE) {
            list.add(InsnNode(Opcodes.RETURN))
            list.add(cont)
            list.add(entryFrame(owner, method, extraLocals = mapOf(slot to CALLBACK_INFO)))
        } else {
            val accessor = returnAccessor(ret)
            list.add(VarInsnNode(Opcodes.ALOAD, slot))
            list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_RETURNABLE, accessor.name, accessor.desc, false))
            if (accessor.needsCast) list.add(TypeInsnNode(Opcodes.CHECKCAST, ret.internalName))
            list.add(InsnNode(Boxing.returnOpcode(ret)))
            list.add(cont)
            list.add(entryFrame(owner, method, extraLocals = mapOf(slot to CALLBACK_RETURNABLE)))
        }
        return list
    }

    /**
     * The full return-value short-circuit sequence: create the `HookValue` handle into a local
     * slot, call the handler through the `INVOKESTATIC` bridge as `(captures…, id, cir)`, and if `canceled`
     * return `cir.getReturnValueX()` (typed accessor: no unboxing, no null branch); otherwise continue with
     * the original method body. The handle, together with `id`, is pushed by the engine (per
     * [Payload.CancelableReturn]'s [desc]), because the engine must read back `canceled` and the return
     * value after the call.
     */
    private fun emitCancelableReturn(
        payload: Payload.CancelableReturn,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
    ): InsnList? {
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE) {
            OmlLog.error("Injection", "CancelableReturn requires a non-void target: ${method.name}${method.desc}")
            return null
        }
        if (!payload.desc.endsWith(CALLBACK_RETURNABLE_TAIL)) {
            throw InjectionError(
                "[injection] the bridge descriptor of CancelableReturn must end with ${CALLBACK_RETURNABLE_DESC})V" +
                    " (captured arguments, id, and callback handle pushed in order): ${method.name}${method.desc} uses ${payload.desc}",
            )
        }
        // bridgeArgs are all the args the engine pushes (captures + id); the descriptor has one extra trailing handle,
        // which the engine pushes itself via ALOAD and doesn't take part in this type comparison — so skip the last 1.
        validateArgTypes(payload.desc, payload.bridgeArgs, 0, method, owner, locals, "CancelableReturn", tailSkip = 1)

        val slot = allocLocalSlot(method)
        // Make maxLocals cover the new slot (COMPUTE_MAXS recomputes it on write-back, but this avoids depending on it)
        method.maxLocals = maxOf(method.maxLocals, slot + 1)
        val list = InsnList()

        // var cir = new HookValue("<target method name>", true)
        list.add(TypeInsnNode(Opcodes.NEW, CALLBACK_RETURNABLE))
        list.add(InsnNode(Opcodes.DUP))
        list.add(LdcInsnNode(method.name))
        list.add(InsnNode(Opcodes.ICONST_1)) // cancelable: only cancelable injection points walk this path
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, CALLBACK_RETURNABLE, "<init>", "(Ljava/lang/String;Z)V", false))
        list.add(VarInsnNode(Opcodes.ASTORE, slot))

        // handler(captures…, id, cir)  — bridgeArgs order matches the desc params
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, payload.bridgeArgs, method, locals, writeBacks)) return null
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
        emitWriteBacks(list, writeBacks)

        // if (!cir.canceled) skip the short circuit
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_RETURNABLE, "getCanceled", "()Z", false))
        val cont = LabelNode()
        list.add(JumpInsnNode(Opcodes.IFEQ, cont))

        // return cir.getReturnValueX()
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        val accessor = returnAccessor(ret)
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_RETURNABLE, accessor.name, accessor.desc, false))
        if (accessor.needsCast) list.add(TypeInsnNode(Opcodes.CHECKCAST, ret.internalName))
        list.add(InsnNode(Boxing.returnOpcode(ret)))

        list.add(cont)
        list.add(entryFrame(owner, method, extraLocals = mapOf(slot to CALLBACK_RETURNABLE)))
        return list
    }

    /** Return-value accessors: primitives use the typed accessor (no unboxing); references go through `getReturnValue` + CHECKCAST. */
    private data class ReturnAccessor(val name: String, val desc: String, val needsCast: Boolean)

    private fun returnAccessor(type: Type): ReturnAccessor = when (type.sort) {
        Type.BOOLEAN -> ReturnAccessor("getReturnValueZ", "()Z", false)
        Type.BYTE -> ReturnAccessor("getReturnValueB", "()B", false)
        Type.CHAR -> ReturnAccessor("getReturnValueC", "()C", false)
        Type.SHORT -> ReturnAccessor("getReturnValueS", "()S", false)
        Type.INT -> ReturnAccessor("getReturnValueI", "()I", false)
        Type.LONG -> ReturnAccessor("getReturnValueJ", "()J", false)
        Type.FLOAT -> ReturnAccessor("getReturnValueF", "()F", false)
        Type.DOUBLE -> ReturnAccessor("getReturnValueD", "()D", false)
        else -> ReturnAccessor("getReturnValue", "()Ljava/lang/Object;", true)
    }

    /**
     * Allocates a new local-variable slot that is "guaranteed not to collide", **and records it into `maxLocals`**.
     *
     * `maxLocals` would theoretically be the answer, but under the tree API it may not be populated (e.g. a
     * hand-built `MethodNode`), so it's combined with the "this + params" lower bound — allocating too small would
     * **overwrite `this`**. "Allocating" is not just "querying": the slot must be immediately occupied, otherwise
     * the next injection in the same method gets the same slot and two unrelated values overlap in one cell
     * (a `VerifyError: Bad local variable type` at class definition). The contract is "each call on the same
     * method gets its own slot", regardless of call order.
     * @param size how many slots this local occupies (`long`/`double` is 2)
     */
    internal fun allocLocalSlot(method: MethodNode, size: Int = 1): Int {
        val withThis = Type.getArgumentsAndReturnSizes(method.desc) shr 2 // includes implicit this
        val minSlots = if ((method.access and Opcodes.ACC_STATIC) != 0) withThis - 1 else withThis
        val slot = maxOf(method.maxLocals, minSlots)
        method.maxLocals = maxOf(method.maxLocals, slot + size)
        return slot
    }

    /**
     * Validates that pushed argument types match the handler descriptor's parameters.
     * @param headSkip number of params at the **start** of the descriptor that this push doesn't cover (e.g.
     *   `TransformReturn`'s first param is the return value already on the stack; only from the 2nd param on does
     *   [args] correspond)
     * @param tailSkip number of params at the **end** of the descriptor that this push doesn't cover (e.g.
     *   `CancelableReturn`'s trailing handler id and callback handle are pushed separately by the engine)
     * **Only throws when provably illegal**: a count mismatch or a primitive/reference mix is always illegal;
     * whether one reference is a supertype of another needs the class hierarchy, and this runs inside
     * `findClass`, so loading other classes is unsuitable (re-entry). That case is left to the JVM verifier,
     * which reports it clearly as a `VerifyError` at class definition — no silent failure.
     */
    private fun validateArgTypes(
        desc: String,
        args: List<DslValue>,
        headSkip: Int,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
        what: String,
        tailSkip: Int = 0,
    ) {
        val expected = Type.getArgumentTypes(desc)
        if (args.size != expected.size - headSkip - tailSkip) {
            throw InjectionError(
                "[injection] $what argument count does not match the handler descriptor: ${method.name}${method.desc} calls $desc, " +
                    "pushes ${args.size} but the descriptor requires ${expected.size - headSkip - tailSkip}",
            )
        }
        args.forEachIndexed { i, value ->
            val actual = stackTypeOf(value, method, owner, locals) ?: return@forEachIndexed
            val want = expected[i + headSkip]
            val problem = mismatch(actual, want)
            if (problem != null) {
                throw InjectionError(
                    "[injection] $what argument type does not match the handler descriptor: argument ${i + headSkip + 1} of ${method.name}${method.desc} calling $desc " +
                        "expects ${want.className}, actually pushed ${actual.className} ($problem; $value)",
                )
            }
        }
    }

    /** Returns non-null for something that is **provably illegal**; null = legal or not statically decidable. */
    private fun mismatch(actual: Type, want: Type): String? {
        val actualRef = actual.sort == Type.OBJECT || actual.sort == Type.ARRAY
        val wantRef = want.sort == Type.OBJECT || want.sort == Type.ARRAY
        return when {
            actualRef != wantRef -> "a reference type and a primitive type cannot be interchanged"
            actualRef -> null // upcasting between references is legal; downcasting needs `checkcast` — leave it to the JVM verifier
            stackType(actual) != stackType(want) -> "primitive types are incompatible"
            else -> null
        }
    }

    /** On the JVM stack `boolean`/`byte`/`char`/`short` are all carried as `int`; unify before comparing. */
    private fun stackType(type: Type): Type = when (type.sort) {
        Type.BOOLEAN, Type.BYTE,
        Type.CHAR, Type.SHORT,
        Type.INT,
            -> Type.INT_TYPE

        else -> type
    }

    /**
     * The statically decidable argument type; `null` = cannot decide (e.g. a `Null` constant), skip validation.
     *
     * [DslValue.Local] is decided in two ways: if `type` is given, use it (statically known); if only `index` is
     * given, ask the dataflow analysis (with [locals] empty ⇒ cannot decide).
     * Note that this only checks **whether the type is wide enough**; whether the slot itself exists is guarded by
     * [emitValue].
     */
    private fun stackTypeOf(
        value: DslValue,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
    ): Type? =
        when (value) {
            is DslValue.This ->
                if ((method.access and Opcodes.ACC_STATIC) != 0) null
                else Type.getObjectType(owner)

            is DslValue.Arg -> Type.getArgumentTypes(method.desc).getOrNull(value.index)

            is DslValue.Local -> {
                if (value.type != null) {
                    runCatching { Type.getType(value.type) }.getOrNull()
                } else {
                    val frame = locals?.frame() ?: return null
                    val resolution = frame.resolve(value, (method.access and Opcodes.ACC_STATIC) != 0)
                    (resolution as? LocalResolution.Found)?.type
                }
            }

            is DslValue.RefLocal -> Type.getType(value.refType)
            is DslValue.SharedRef -> Type.getType(value.type)
            is DslValue.RefSlot -> Type.getType(value.type)
            is DslValue.IntVal -> Type.INT_TYPE
            is DslValue.LongVal -> Type.LONG_TYPE
            is DslValue.Str -> Type.getType("Ljava/lang/String;")
            is DslValue.Cls -> Type.getType("Ljava/lang/Class;")
            is DslValue.Null -> null
            is DslValue.CancelableHandle -> Type.getType(value.descriptor)
        }

    /**
     * Rewrites one argument in place.
     * The target call's arguments are on the stack right now and the one being modified may not be on top
     * (other arguments piled below it), so: **stash all arguments into temp locals in reverse order** (so each
     * is on the stack top when stored), which leaves the receiver on the stack and clears the arguments →
     * rewrite the [payload.index]-th one in a local → push them all back in normal order. Straight-line
     * code throughout: no branches, therefore **no new stack frame**; only touches locals, therefore
     * **zero boxing**. The arguments before the index-th one are stored too (Mixin leaves them untouched and
     * emits fewer instructions), in exchange for **fully symmetric store/load**.
     */
    fun emitModifyArg(
        payload: Payload.ModifyArg,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList? = emitModifyArg(
        handlerOwner = payload.owner,
        handlerMethod = payload.method,
        handlerDesc = payload.desc,
        handlerIndex = payload.index,
        extras = payload.extras,
        instance = false,
        method = method,
        owner = owner,
        invoke = invoke,
        locals = locals,
    )

    /**
     * The **shared implementation** of "rewrite the N-th argument" (shared by [Payload.ModifyArg] and
     * [Payload.HandlerCall] + [HandlerKind.MODIFY_ARG]).
     *
     * When [instance] is true, the `INVOKESTATIC bridge` is replaced by `INVOKEVIRTUAL <target>.<handler>` and an
     * `ALOAD 0` is pushed first as the **receiver**. The handler's parameter list is identical in both forms
     * (value params + capture args, receiver excluded) — the only differences are "where the handler lives" and
     * "whether `this` is the target instance".
     */
    internal fun emitModifyArg(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        handlerIndex: Int,
        extras: List<DslValue>,
        instance: Boolean,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        val targetArgs = Type.getArgumentTypes(invoke.desc)
        if (targetArgs.isEmpty()) {
            OmlLog.error(
                "Injection",
                "the target call ${invoke.owner}.${invoke.name}${invoke.desc} of ModifyArg has no arguments: ${method.name}",
            )
            return null
        }

        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        // Parameter list = value params + capture args, **receiver excluded**: the instance form's receiver is supplied by
        // `INVOKEVIRTUAL`, appearing as `this` (local slot 0) inside the handler. This is exactly Mixin's convention,
        // so both forms share the whole "shape validation", differing only in the call instruction.
        //
        // **Why the receiver can't go into the parameter list**: once the handler is merged into the target class it is
        // an **instance method**; `(Ltarget;I)I` would make the JVM treat slot 1 as "the first declared param
        // (reference)", while the engine pushes an int ⇒ at definition a `VerifyError: Bad local variable type`
        // (slot 1 declared as reference but holds an int).
        val argCount = handlerArgs.size - extras.size
        val singleArgMode = argCount == 1

        val index = resolveArgIndex(handlerIndex, handlerDesc, targetArgs, invoke, method)

        if (handlerRet != targetArgs[index]) {
            throw InjectionError(
                "[injection] the handler return type of ModifyArg must equal the type of the modified argument: " +
                    "${invoke.owner}.${invoke.name}${invoke.desc} argument ${index + 1} is " +
                    "${targetArgs[index].className}, handler returns ${handlerRet.className} ($handlerDesc)",
            )
        }
        if (singleArgMode) {
            if (handlerArgs[0] != targetArgs[index]) {
                throw InjectionError(
                    "[injection] the handler parameter type of ModifyArg's single-argument mode must equal the type of the modified argument: " +
                        "expected ${targetArgs[index].className}, actual ${handlerArgs[0].className} ($handlerDesc)",
                )
            }
        } else {
            if (argCount != targetArgs.size) {
                throw InjectionError(
                    "[injection] the handler value-parameter count of ModifyArg's multi-argument mode must equal the target call's argument count: " +
                        "${invoke.owner}.${invoke.name}${invoke.desc} has ${targetArgs.size} " +
                        "but the handler provides $argCount ($handlerDesc)",
                )
            }
            for (i in targetArgs.indices) {
                if (handlerArgs[i] != targetArgs[i]) {
                    throw InjectionError(
                        "[injection] ModifyArg multi-argument mode parameter ${i + 1} type mismatch: expected " +
                            "${targetArgs[i].className}, actual ${handlerArgs[i].className} ($handlerDesc)",
                    )
                }
            }
        }
        validateArgTypes(handlerDesc, extras, argCount, method, owner, locals, "ModifyArg.extras")

        val slots = storeArgs(method, targetArgs)
        val list = InsnList()
        for (i in targetArgs.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(targetArgs[i]), slots[i]))
        }

        // Instance form: `ALOAD 0` is the `INVOKEVIRTUAL` **receiver** (the handler's `this`),
        // not a parameter — so only one copy is pushed, followed directly by the target arguments.
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        if (singleArgMode) {
            list.add(VarInsnNode(Boxing.loadOpcode(targetArgs[index]), slots[index]))
        } else {
            for (i in targetArgs.indices) {
                list.add(VarInsnNode(Boxing.loadOpcode(targetArgs[i]), slots[i]))
            }
        }
        val writeBacks = mutableListOf<SugarWriteBack>()
        for (value in extras) emitValue(list, value, method, locals, writeBacks)
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        list.add(VarInsnNode(Boxing.storeOpcode(targetArgs[index]), slots[index]))

        for (i in targetArgs.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(targetArgs[i]), slots[i]))
        }
        return list
    }

    /**
     * **Redirects an instance call** to "an instance handler merged into the target class".
     * The stack at the anchor is `[receiver of the replaced call, args…]`. The handler's receiver (`this`) must
     * be the **target instance**, while the **replaced call's receiver** is a different value — the original
     * receiver must reach the handler as its first parameter (`@Redirect` means "the whole call is done by the
     * handler", and the handler needs the original receiver to make that call itself, so the Mixin handler
     * signature is exactly `(receiver, args…)R`; it is `ASTORE`d to a temp slot, **not** `POP`ped). The return
     * value is what the whole call would have produced ⇒ the caller's `INVOKEVIRTUAL` is deleted entirely and
     * replaced by this sequence. Like [emitModifyArg], this is **straight-line code** that adds no stack frame.
     */
    fun emitRedirect(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList {
        val targetArgs = Type.getArgumentTypes(invoke.desc)
        // Parameter list = original receiver + original args + captures…, so captures start at 1 + args.size
        validateArgTypes(handlerDesc, extras, 1 + targetArgs.size, method, owner, locals, "REDIRECT.captures")

        val slots = storeArgs(method, targetArgs)
        val receiverSlot = allocLocalSlot(method)
        method.maxLocals = maxOf(method.maxLocals, receiverSlot + 1)

        val list = InsnList()
        // Receiver is at the stack bottom, args above it: reverse STORE consumes only the args
        for (i in targetArgs.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(targetArgs[i]), slots[i]))
        }
        // The original receiver **must not be lost** — it is the handler's first parameter
        list.add(VarInsnNode(Opcodes.ASTORE, receiverSlot))
        list.add(VarInsnNode(Opcodes.ALOAD, 0)) // INVOKEVIRTUAL's receiver = the target instance
        list.add(VarInsnNode(Opcodes.ALOAD, receiverSlot))
        for (i in targetArgs.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(targetArgs[i]), slots[i]))
        }
        val writeBacks = mutableListOf<SugarWriteBack>()
        for (value in extras) emitValue(list, value, method, locals, writeBacks)
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, handlerOwner, handlerMethod, handlerDesc, false))
        emitWriteBacks(list, writeBacks)
        return list
    }

    /**
     * **Rewrites the receiver** of an instance call / field access (MixinExtras `@ModifyReceiver`). The stack
     * at the anchor is `[receiver, operands…]` (operands = the call's arguments, the field value for
     * `PUTFIELD`, or nothing for `GETFIELD`). The engine stashes them, calls a handler shaped
     * `(ReceiverType, operands…, extras…)ReceiverType`, and rebuilds `[newReceiver, operands…]` so the original
     * instruction — left in place — runs on the new receiver. Straight-line code, zero boxing, no new frame.
     */
    internal fun emitModifyReceiver(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        method: MethodNode,
        owner: String,
        anchor: AbstractInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        val receiverType: Type
        val operandTypes: Array<Type>
        when (anchor) {
            is MethodInsnNode -> {
                if (anchor.opcode == Opcodes.INVOKESTATIC) {
                    OmlLog.error(
                        "Injection",
                        "ModifyReceiver needs an instance call, not a static call: ${anchor.owner}.${anchor.name}${anchor.desc}",
                    )
                    return null
                }
                receiverType = Type.getObjectType(anchor.owner)
                operandTypes = Type.getArgumentTypes(anchor.desc)
            }

            is FieldInsnNode -> when (anchor.opcode) {
                Opcodes.GETFIELD -> {
                    receiverType = Type.getObjectType(anchor.owner)
                    operandTypes = emptyArray()
                }

                Opcodes.PUTFIELD -> {
                    receiverType = Type.getObjectType(anchor.owner)
                    operandTypes = arrayOf(Type.getType(anchor.desc))
                }

                else -> {
                    OmlLog.error(
                        "Injection",
                        "ModifyReceiver needs an instance field access (GETFIELD/PUTFIELD), not a static field: ${anchor.owner}.${anchor.name}",
                    )
                    return null
                }
            }

            else -> {
                OmlLog.error("Injection", "ModifyReceiver's anchor must be an instance call or field access: ${method.name}")
                return null
            }
        }

        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        val valueCount = 1 + operandTypes.size
        if (handlerRet != receiverType) {
            throw InjectionError(
                "[injection] the handler return type of ModifyReceiver must equal the receiver type: " +
                    "receiver is ${receiverType.className}, handler returns ${handlerRet.className} ($handlerDesc)",
            )
        }
        if (handlerArgs.size < valueCount + extras.size || handlerArgs[0] != receiverType) {
            throw InjectionError(
                "[injection] the handler of ModifyReceiver must be shaped (ReceiverType, operands…, extras…)ReceiverType: " +
                    "expected first parameter ${receiverType.className} and at least $valueCount value parameter(s), actual $handlerDesc",
            )
        }
        for (i in operandTypes.indices) {
            if (handlerArgs[1 + i] != operandTypes[i]) {
                throw InjectionError(
                    "[injection] ModifyReceiver operand ${i + 1} type mismatch: expected ${operandTypes[i].className}, " +
                        "actual ${handlerArgs[1 + i].className} ($handlerDesc)",
                )
            }
        }
        validateArgTypes(handlerDesc, extras, valueCount, method, owner, locals, "ModifyReceiver.extras")

        val slots = storeArgs(method, operandTypes)
        val receiverSlot = allocLocalSlot(method)
        method.maxLocals = maxOf(method.maxLocals, receiverSlot + 1)

        val list = InsnList()
        // Operands sit above the receiver: reverse STORE consumes them, leaving [receiver].
        for (i in operandTypes.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(operandTypes[i]), slots[i]))
        }
        list.add(VarInsnNode(Opcodes.ASTORE, receiverSlot))
        // Instance form: ALOAD 0 is the merged handler's `this`, not a parameter.
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        list.add(VarInsnNode(Opcodes.ALOAD, receiverSlot))
        for (i in operandTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(operandTypes[i]), slots[i]))
        }
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        // The handler returned the new receiver; re-seat it beneath the unchanged operands.
        list.add(VarInsnNode(Opcodes.ASTORE, receiverSlot))
        list.add(VarInsnNode(Opcodes.ALOAD, receiverSlot))
        for (i in operandTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(operandTypes[i]), slots[i]))
        }
        return list
    }

    /**
     * **Gates a void operation on a boolean handler** (MixinExtras `@WrapWithCondition`). The stack at the
     * anchor holds the value parameters (receiver + call args, or the field value for a write). The engine
     * stashes them, calls `(receiver?, operands…, extras…)Z`, and when the handler returns true reloads the
     * operands so the original instruction — left in place — runs; false falls through to a label placed
     * **after** the original. The before-block is returned for the caller to insert; the after-block (the
     * skip label + its stack frame) is inserted here because it must sit on the other side of the anchor.
     * Only void operations are supported: a non-void result would need a dummy value on the skip path.
     */
    internal fun emitWrapWithCondition(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        method: MethodNode,
        owner: String,
        anchor: AbstractInsnNode,
        frames: MethodFrames,
        locals: AnchorLocals?,
    ): InsnList? {
        val valueTypes: Array<Type>
        when (anchor) {
            is MethodInsnNode -> valueTypes = if (anchor.opcode == Opcodes.INVOKESTATIC) {
                Type.getArgumentTypes(anchor.desc)
            } else {
                arrayOf(Type.getObjectType(anchor.owner)) + Type.getArgumentTypes(anchor.desc)
            }

            is FieldInsnNode -> when (anchor.opcode) {
                Opcodes.PUTFIELD -> valueTypes =
                    arrayOf(Type.getObjectType(anchor.owner), Type.getType(anchor.desc))

                Opcodes.PUTSTATIC -> valueTypes = arrayOf(Type.getType(anchor.desc))
                else -> {
                    OmlLog.error(
                        "Injection",
                        "WrapWithCondition supports field writes only (PUTFIELD/PUTSTATIC), not reads: " +
                            "${anchor.owner}.${anchor.name}",
                    )
                    return null
                }
            }

            else -> {
                OmlLog.error("Injection", "WrapWithCondition's anchor must be a call or field write: ${method.name}")
                return null
            }
        }
        val resultType = when (anchor) {
            is MethodInsnNode -> Type.getReturnType(anchor.desc)
            else -> Type.VOID_TYPE
        }
        if (resultType != Type.VOID_TYPE) {
            // A non-void operation is fine only when its result is thrown away immediately: the true path
            // leaves the real result, the skip path pushes a default of the same type, and the POP/POP2
            // right after the anchor discards whichever one survives.
            val expected = if (resultType.size == 2) Opcodes.POP2 else Opcodes.POP
            val next = nextReal(anchor)
            if (next == null || next.opcode != expected) {
                OmlLog.error(
                    "Injection",
                    "WrapWithCondition supports void operations, or a non-void result discarded immediately by " +
                        "${if (resultType.size == 2) "POP2" else "POP"}; this anchor produces " +
                        "${resultType.className}: ${method.name}",
                )
                return null
            }
        }

        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        if (handlerArgs.size < valueTypes.size + extras.size) {
            throw InjectionError(
                "[injection] the handler of WrapWithCondition gives too few parameters: the operation has " +
                    "${valueTypes.size} value parameter(s) and the rule adds ${extras.size} extra(s), " +
                    "but the handler declares ${handlerArgs.size} ($handlerDesc)",
            )
        }
        for (i in valueTypes.indices) {
            if (handlerArgs[i] != valueTypes[i]) {
                throw InjectionError(
                    "[injection] WrapWithCondition value parameter ${i + 1} type mismatch: expected " +
                        "${valueTypes[i].className}, actual ${handlerArgs[i].className} ($handlerDesc)",
                )
            }
        }
        if (Type.getReturnType(handlerDesc) != Type.BOOLEAN_TYPE) {
            throw InjectionError(
                "[injection] the handler of WrapWithCondition must return boolean: ${handlerDesc}",
            )
        }
        validateArgTypes(handlerDesc, extras, valueTypes.size, method, owner, locals, "WrapWithCondition.extras")

        // The skip label is reached by IFEQ, so it needs a stack-map frame. Locals are the dataflow truth
        // after the original instruction (a call/field write does not change them); both paths leave the
        // stack empty, since only void operations are accepted.
        val endLocals = frames.frameLocalsAt(anchor, after = true) ?: run {
            OmlLog.error(
                "Injection",
                "injection skipped: the local state after the WrapWithCondition anchor cannot be derived " +
                    "(dataflow failed): ${method.name}",
            )
            return null
        }

        val slots = storeArgs(method, valueTypes)
        val list = InsnList()
        for (i in valueTypes.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(valueTypes[i]), slots[i]))
        }
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        for (i in valueTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(valueTypes[i]), slots[i]))
        }
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        val skip = LabelNode()
        list.add(JumpInsnNode(Opcodes.IFEQ, skip))
        // True: reload the operands and let the original instruction run.
        for (i in valueTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(valueTypes[i]), slots[i]))
        }

        val after = InsnList()
        if (resultType == Type.VOID_TYPE) {
            after.add(skip)
            after.add(FrameNode(Opcodes.F_NEW, endLocals.size, endLocals, 0, emptyArray<Any>()))
        } else {
            // True path: the original leaves the result on the stack; jump over the dummy.
            val end = LabelNode()
            after.add(JumpInsnNode(Opcodes.GOTO, end))
            after.add(skip)
            after.add(FrameNode(Opcodes.F_NEW, endLocals.size, endLocals, 0, emptyArray<Any>()))
            pushDefault(after, resultType)
            after.add(end)
            after.add(
                FrameNode(
                    Opcodes.F_NEW, endLocals.size, endLocals, 1,
                    arrayOf(frameType(resultType)),
                ),
            )
        }
        method.instructions.insert(anchor, after)
        frames.invalidate()
        return list
    }

    /** The zero/null value a gated non-void operation leaves on the skip path. */
    private fun pushDefault(list: InsnList, type: Type) {
        when (type.sort) {
            Type.LONG -> list.add(InsnNode(Opcodes.LCONST_0))
            Type.FLOAT -> list.add(InsnNode(Opcodes.FCONST_0))
            Type.DOUBLE -> list.add(InsnNode(Opcodes.DCONST_0))
            Type.OBJECT, Type.ARRAY -> list.add(InsnNode(Opcodes.ACONST_NULL))
            else -> list.add(InsnNode(Opcodes.ICONST_0))
        }
    }

    /** A [Type] as a `StackMapTable` value (primitive opcodes / reference internal name). */
    private fun frameType(type: Type): Any = when (type.sort) {
        Type.LONG -> Opcodes.LONG
        Type.FLOAT -> Opcodes.FLOAT
        Type.DOUBLE -> Opcodes.DOUBLE
        Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> Opcodes.INTEGER
        else -> type.internalName
    }

    /**
     * **Decides a conditional branch** (`@WrapBranch`). The jump's compared values are on the operand stack;
     * the engine appends [extras] and calls `(operands…, extras…)Z`, then replaces the jump with
     * `INVOKE…; IFNE <original target>`: true = take the branch, false = fall through.
     *
     * The static form consumes the values in place; the merged instance form must stash them first, because
     * `ALOAD 0` (the handler's `this`) has to go **below** them. Straight-line code either way — the branch
     * target keeps its existing frame, so no new stack-map frame is needed.
     */
    internal fun emitWrapBranch(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        method: MethodNode,
        owner: String,
        jump: JumpInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        val operandTypes = jumpOperandTypes(jump.opcode) ?: run {
            OmlLog.error(
                "Injection",
                "WrapBranch's anchor must be a conditional jump, actual opcode ${jump.opcode}: ${method.name}",
            )
            return null
        }
        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        if (handlerRet != Type.BOOLEAN_TYPE) {
            throw InjectionError(
                "[injection] the handler of WrapBranch must return boolean (whether the branch is taken): " +
                    handlerDesc,
            )
        }
        if (handlerArgs.size < operandTypes.size + extras.size) {
            throw InjectionError(
                "[injection] the handler of WrapBranch gives too few parameters: the jump compares " +
                    "${operandTypes.size} value(s) and the rule adds ${extras.size} extra(s), " +
                    "but the handler declares ${handlerArgs.size} ($handlerDesc)",
            )
        }
        for (i in operandTypes.indices) {
            val actual = handlerArgs[i]
            val ok = if (operandTypes[i] == Type.INT_TYPE) actual.isIntFamily else actual.isReference
            if (!ok) {
                throw InjectionError(
                    "[injection] WrapBranch operand ${i + 1} type mismatch: the jump compares " +
                        "${if (operandTypes[i] == Type.INT_TYPE) "an int" else "a reference"}, " +
                        "but the handler declares ${actual.className} ($handlerDesc)",
                )
            }
        }
        validateArgTypes(handlerDesc, extras, operandTypes.size, method, owner, locals, "WrapBranch.extras")

        val list = InsnList()
        if (instance) {
            val slots = storeArgs(method, operandTypes)
            for (i in operandTypes.indices.reversed()) {
                list.add(VarInsnNode(Boxing.storeOpcode(operandTypes[i]), slots[i]))
            }
            list.add(VarInsnNode(Opcodes.ALOAD, 0))
            for (i in operandTypes.indices) {
                list.add(VarInsnNode(Boxing.loadOpcode(operandTypes[i]), slots[i]))
            }
        }
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        list.add(JumpInsnNode(Opcodes.IFNE, jump.label))
        return list
    }

    /**
     * The values a conditional jump compares, in stack order: one int (`IFEQ`-family), two ints
     * (`IF_ICMP*`), one reference (`IFNULL`/`IFNONNULL`) or two references (`IF_ACMP*`).
     */
    internal fun jumpOperandTypes(opcode: Int): Array<Type>? = when (opcode) {        Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE ->
            arrayOf(Type.INT_TYPE)

        Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
        Opcodes.IF_ICMPLE -> arrayOf(Type.INT_TYPE, Type.INT_TYPE)

        Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> arrayOf(OBJECT_TYPE, OBJECT_TYPE)
        Opcodes.IFNULL, Opcodes.IFNONNULL -> arrayOf(OBJECT_TYPE)
        else -> null
    }

    private val Type.isIntFamily: Boolean
        get() = sort == Type.BOOLEAN || sort == Type.BYTE || sort == Type.CHAR ||
            sort == Type.SHORT || sort == Type.INT

    private val Type.isReference: Boolean
        get() = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * Materializes the **boolean value of a comparison** around its jump so a value-modifying handler can
     * rewrite it (MixinExtras' comparison handling). The original jump is retargeted to a local label; both
     * paths push the source-level comparison result (1 = the matched comparison holds, honoring
     * [ComparisonInfo.jumpOnTrue]); the handler rewrites the boolean, and the final conditional jump goes
     * to the original target. Straight-line from each label on, so the two new frames are derived from the
     * pre-jump dataflow state.
     */
    internal fun emitModifiedComparison(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        comparison: ComparisonInfo,
        method: MethodNode,
        owner: String,
        jump: JumpInsnNode,
        locals: AnchorLocals?,
        frames: MethodFrames,
    ): InsnList? {
        // A complex comparison's cmp instruction stays in place; the caller resolved the jump it feeds, and the
        // boolean materialization happens after that jump.
        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        if (handlerRet != Type.BOOLEAN_TYPE || handlerArgs.firstOrNull() != Type.BOOLEAN_TYPE) {
            throw InjectionError(
                "[injection] the handler of @ModifyExpressionValue on a comparison must be shaped " +
                    "(boolean, extras…)boolean, actual $handlerDesc",
            )
        }
        validateArgTypes(handlerDesc, extras, 1, method, owner, locals, "ModifyExpressionValue.extras")

        val frameLocals = frames.frameLocalsAt(jump, after = false) ?: run {
            OmlLog.error(
                "Injection",
                "injection skipped: the local state before the comparison jump cannot be derived " +
                    "(dataflow failed): ${method.name}",
            )
            return null
        }
        val originalTarget = jump.label
        val taken = LabelNode()
        val end = LabelNode()
        jump.label = taken

        val list = InsnList()
        list.add(InsnNode(if (comparison.jumpOnTrue) Opcodes.ICONST_0 else Opcodes.ICONST_1))
        list.add(JumpInsnNode(Opcodes.GOTO, end))
        list.add(taken)
        list.add(FrameNode(Opcodes.F_NEW, frameLocals.size, frameLocals, 0, emptyArray<Any>()))
        list.add(InsnNode(if (comparison.jumpOnTrue) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        list.add(end)
        list.add(
            FrameNode(
                Opcodes.F_NEW, frameLocals.size, frameLocals, 1, arrayOf<Any>(Opcodes.INTEGER),
            ),
        )
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        list.add(JumpInsnNode(if (comparison.jumpOnTrue) Opcodes.IFNE else Opcodes.IFEQ, originalTarget))
        return list
    }

    /**
     * **Wraps a comparison** with an `Operation<Boolean>` (`@WrapOperation` on an expression capturing a
     * comparison). The operands are stashed first, then the handler gets `(left, right, Operation<Boolean>,
     * extras…)boolean`; the operation re-evaluates the source-level comparison with whatever values the
     * handler passes, and the handler's result decides the branch (the original jump is removed by the caller).
     */
    internal fun emitWrapComparison(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        comparison: ComparisonInfo,
        classNode: ClassNode,
        method: MethodNode,
        owner: String,
        jump: JumpInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        // The matcher's "any int-like" placeholder is an int on the stack
        val input = if (comparison.input.descriptor == "Lint-like;") Type.INT_TYPE else comparison.input
        val operandTypes = arrayOf(input, input)
        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        val opIndex = operandTypes.size
        if (handlerArgs.size < opIndex + 1 + extras.size) {
            throw InjectionError(
                "[injection] the handler of @WrapOperation on a comparison gives too few parameters: " +
                    "$opIndex compared value(s), then $OPERATION_DESC, then ${extras.size} extra(s); " +
                    "the handler declares ${handlerArgs.size} ($handlerDesc)",
            )
        }
        for (i in operandTypes.indices) {
            if (handlerArgs[i] != operandTypes[i]) {
                throw InjectionError(
                    "[injection] WrapOperation comparison parameter ${i + 1} type mismatch: expected " +
                        "${operandTypes[i].className}, actual ${handlerArgs[i].className} ($handlerDesc)",
                )
            }
        }
        if (handlerArgs[opIndex].descriptor != OPERATION_DESC) {
            throw InjectionError(
                "[injection] the handler of @WrapOperation on a comparison must take $OPERATION_DESC right " +
                    "after the compared values: ${handlerDesc}",
            )
        }
        if (handlerRet != Type.BOOLEAN_TYPE) {
            throw InjectionError(
                "[injection] the handler of @WrapOperation on a comparison must return boolean: $handlerDesc",
            )
        }
        validateArgTypes(handlerDesc, extras, opIndex + 1, method, owner, locals, "WrapOperation.extras")

        val jumpOpcode = comparison.copyJump(InsnList())
        val slots = storeArgs(method, operandTypes)
        val list = InsnList()
        for (i in operandTypes.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(operandTypes[i]), slots[i]))
        }
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        for (i in operandTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(operandTypes[i]), slots[i]))
        }
        OperationSupport.makeComparisonOperation(
            classNode, list, operandTypes, jumpOpcode, comparison.jumpOnTrue,
        )
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        list.add(JumpInsnNode(if (comparison.jumpOnTrue) Opcodes.IFNE else Opcodes.IFEQ, jump.label))
        return list
    }

    /**
     * **Wraps an operation with an `Operation<R>`** (MixinExtras `@WrapOperation`). The stack at the anchor
     * holds the operation's values (receiver + call args, the field value for a write, or nothing for a
     * static read). The engine stashes them, creates an `Operation` whose bridge re-executes a clone of the
     * original instruction, and calls `(receiver?, operands…, Operation<R>, extras…)R`. The caller removes
     * the original instruction: the handler's return value is the operation's result now.
     */
    internal fun emitWrapOperation(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        classNode: ClassNode,
        method: MethodNode,
        owner: String,
        anchor: AbstractInsnNode,
        locals: AnchorLocals?,
        frames: MethodFrames,
    ): InsnList? {
        val argTypes: Array<Type>
        val returnType: Type
        when (anchor) {
            is MethodInsnNode -> {
                if (anchor.name == "<init>") {
                    OmlLog.error(
                        "Injection",
                        "WrapOperation cannot wrap a constructor call (the receiver is uninitialized); " +
                            "target the NEW instead: ${anchor.owner}",
                    )
                    return null
                }
                argTypes = if (anchor.opcode == Opcodes.INVOKESTATIC) {
                    Type.getArgumentTypes(anchor.desc)
                } else {
                    arrayOf(Type.getObjectType(anchor.owner)) + Type.getArgumentTypes(anchor.desc)
                }
                returnType = Type.getReturnType(anchor.desc)
            }

            is InvokeDynamicInsnNode -> {
                // A string-concatenation / lambda factory call site: the operands on the stack are the
                // bootstrap arguments and the Operation re-emits the same invokedynamic.
                argTypes = Type.getArgumentTypes(anchor.desc)
                returnType = Type.getReturnType(anchor.desc)
            }

            is FieldInsnNode -> when (anchor.opcode) {
                Opcodes.GETFIELD -> {
                    argTypes = arrayOf(Type.getObjectType(anchor.owner))
                    returnType = Type.getType(anchor.desc)
                }

                Opcodes.PUTFIELD -> {
                    argTypes = arrayOf(Type.getObjectType(anchor.owner), Type.getType(anchor.desc))
                    returnType = Type.VOID_TYPE
                }

                Opcodes.GETSTATIC -> {
                    argTypes = emptyArray()
                    returnType = Type.getType(anchor.desc)
                }

                Opcodes.PUTSTATIC -> {
                    argTypes = arrayOf(Type.getType(anchor.desc))
                    returnType = Type.VOID_TYPE
                }

                else -> {
                    OmlLog.error("Injection", "WrapOperation's anchor is not a field access: ${anchor.owner}.${anchor.name}")
                    return null
                }
            }

            is TypeInsnNode -> when (anchor.opcode) {
                Opcodes.CHECKCAST -> {
                    argTypes = arrayOf(Type.getObjectType("java/lang/Object"))
                    returnType = typeOfDescriptor(anchor.desc)
                }

                Opcodes.INSTANCEOF -> {
                    argTypes = arrayOf(Type.getObjectType("java/lang/Object"))
                    returnType = Type.BOOLEAN_TYPE
                }

                Opcodes.ANEWARRAY -> {
                    argTypes = arrayOf(Type.INT_TYPE)
                    returnType = arrayTypeForElement(anchor.desc)
                }

                else -> {
                    OmlLog.error(
                        "Injection",
                        "WrapOperation does not support ${anchor.opcode} on ${anchor.desc}: ${method.name}",
                    )
                    return null
                }
            }

            is InsnNode -> {
                val load = arrayLoad(anchor.opcode)
                val store = arrayStore(anchor.opcode)
                when {
                    load != null -> {
                        argTypes = arrayOf(load.array, Type.INT_TYPE)
                        returnType = load.element
                    }

                    store != null -> {
                        argTypes = arrayOf(store.array, Type.INT_TYPE, store.element)
                        returnType = Type.VOID_TYPE
                    }

                    else -> {
                        OmlLog.error("Injection", "WrapOperation does not support opcode ${anchor.opcode}: ${method.name}")
                        return null
                    }
                }
            }

            is IntInsnNode -> {
                val array = if (anchor.opcode == Opcodes.NEWARRAY) arrayTypeForAtype(anchor.operand) else null
                if (array == null) {
                    OmlLog.error("Injection", "WrapOperation does not support ${anchor.opcode}: ${method.name}")
                    return null
                }
                argTypes = arrayOf(Type.INT_TYPE)
                returnType = array
            }

            is MultiANewArrayInsnNode -> {
                argTypes = Array(anchor.dims) { Type.INT_TYPE }
                returnType = Type.getType(anchor.desc)
            }

            else -> {
                OmlLog.error("Injection", "WrapOperation's anchor must be a call, field access, type check or array instruction: ${method.name}")
                return null
            }
        }

        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        val opIndex = argTypes.size
        if (handlerArgs.size < opIndex + 1 + extras.size) {
            throw InjectionError(
                "[injection] the handler of WrapOperation gives too few parameters: the operation has $opIndex " +
                    "value parameter(s), then Operation<R>, then ${extras.size} extra(s); the handler declares " +
                    "${handlerArgs.size} ($handlerDesc)",
            )
        }
        for (i in argTypes.indices) {
            if (handlerArgs[i] != argTypes[i]) {
                throw InjectionError(
                    "[injection] WrapOperation value parameter ${i + 1} type mismatch: expected " +
                        "${argTypes[i].className}, actual ${handlerArgs[i].className} ($handlerDesc)",
                )
            }
        }
        if (handlerArgs[opIndex].descriptor != OPERATION_DESC) {
            throw InjectionError(
                "[injection] the handler of WrapOperation must take $OPERATION_DESC right " +
                    "after the operation's value parameters: actual ${handlerArgs[opIndex].className} ($handlerDesc)",
            )
        }
        if (handlerRet != returnType) {
            throw InjectionError(
                "[injection] the handler return type of WrapOperation must equal the operation's return type: " +
                    "operation returns ${returnType.className}, handler returns ${handlerRet.className} ($handlerDesc)",
            )
        }
        validateArgTypes(handlerDesc, extras, opIndex + 1, method, owner, locals, "WrapOperation.extras")

        val slots = storeArgs(method, argTypes)
        val list = InsnList()
        for (i in argTypes.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(argTypes[i]), slots[i]))
        }
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        for (i in argTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(argTypes[i]), slots[i]))
        }
        OperationSupport.makeOperation(classNode, list, argTypes, returnType, anchor)
        val writeBacks = mutableListOf<SugarWriteBack>()
        val cancelChecks = mutableListOf<CancelCheck>()
        if (!emitValues(list, extras, method, locals, writeBacks, cancelChecks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        if (!emitCancelChecks(list, cancelChecks, method, returnType, frames, anchor)) return null
        return list
    }

    /** An array load/store's stack types: the array and its element. */
    private data class ArrayOperation(val array: Type, val element: Type)

    private fun arrayLoad(opcode: Int): ArrayOperation? = when (opcode) {
        Opcodes.IALOAD -> ArrayOperation(Type.getType("[I"), Type.INT_TYPE)
        Opcodes.LALOAD -> ArrayOperation(Type.getType("[J"), Type.LONG_TYPE)
        Opcodes.FALOAD -> ArrayOperation(Type.getType("[F"), Type.FLOAT_TYPE)
        Opcodes.DALOAD -> ArrayOperation(Type.getType("[D"), Type.DOUBLE_TYPE)
        Opcodes.AALOAD -> ArrayOperation(Type.getType("[Ljava/lang/Object;"), Type.getObjectType("java/lang/Object"))
        Opcodes.BALOAD -> ArrayOperation(Type.getType("[B"), Type.BYTE_TYPE)
        Opcodes.CALOAD -> ArrayOperation(Type.getType("[C"), Type.CHAR_TYPE)
        Opcodes.SALOAD -> ArrayOperation(Type.getType("[S"), Type.SHORT_TYPE)
        else -> null
    }

    private fun arrayStore(opcode: Int): ArrayOperation? = when (opcode) {
        Opcodes.IASTORE -> ArrayOperation(Type.getType("[I"), Type.INT_TYPE)
        Opcodes.LASTORE -> ArrayOperation(Type.getType("[J"), Type.LONG_TYPE)
        Opcodes.FASTORE -> ArrayOperation(Type.getType("[F"), Type.FLOAT_TYPE)
        Opcodes.DASTORE -> ArrayOperation(Type.getType("[D"), Type.DOUBLE_TYPE)
        Opcodes.AASTORE -> ArrayOperation(Type.getType("[Ljava/lang/Object;"), Type.getObjectType("java/lang/Object"))
        Opcodes.BASTORE -> ArrayOperation(Type.getType("[B"), Type.BYTE_TYPE)
        Opcodes.CASTORE -> ArrayOperation(Type.getType("[C"), Type.CHAR_TYPE)
        Opcodes.SASTORE -> ArrayOperation(Type.getType("[S"), Type.SHORT_TYPE)
        else -> null
    }

    /** A `TypeInsnNode.desc` (internal name or array descriptor) as a [Type]. */
    private fun typeOfDescriptor(desc: String): Type =
        if (desc.startsWith("[")) Type.getType(desc) else Type.getObjectType(desc)

    /** `ANEWARRAY`'s element internal name → the created array type. */
    private fun arrayTypeForElement(element: String): Type =
        Type.getType("[${if (element.startsWith("[")) element else "L$element;"}")

    /** `NEWARRAY`'s `T_*` code → the created array type. */
    private fun arrayTypeForAtype(atype: Int): Type? = when (atype) {
        4 -> Type.getType("[Z")
        5 -> Type.getType("[C")
        6 -> Type.getType("[F")
        7 -> Type.getType("[D")
        8 -> Type.getType("[B")
        9 -> Type.getType("[S")
        10 -> Type.getType("[I")
        11 -> Type.getType("[J")
        else -> null
    }

    /** The three edits an instantiation wrap needs: the NEW to neutralize, the `<init>` to replace, the block. */
    internal class WrapNewEdit(
        val newInsn: TypeInsnNode,
        val initInsn: MethodInsnNode,
        val block: InsnList,
    )

    /**
     * **Wraps an instantiation** (MixinExtras `@WrapOperation` targeting `NEW`). The anchor is the `NEW`,
     * but the handler call must land on the matching `<init>` — the only point where the constructor's
     * arguments are on the stack. The NEW is neutralized to `ACONST_NULL`, so the two references the
     * original `NEW; DUP` left behind are ordinary nulls; the block consumes the arguments, calls
     * `(args…, Operation<ObjectType>)ObjectType`, and uses `DUP_X2` + three `POP`s to keep only the
     * handler's object. The `Operation` bridge re-emits `NEW; DUP; args; <init>` inside one straight-line
     * body, so the uninitialized reference never crosses a frame.
     *
     * Only the standard `NEW; DUP; args; <init>` shape is accepted; a NEW whose reference is not duped
     * (or whose `<init>` cannot be paired by depth) is rejected at injection time.
     */
    internal fun emitWrapNewOperation(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        classNode: ClassNode,
        method: MethodNode,
        owner: String,
        newInsn: TypeInsnNode,
        locals: AnchorLocals?,
    ): WrapNewEdit? {
        val next = nextReal(newInsn)
        if (next == null || next.opcode != Opcodes.DUP) {
            OmlLog.error(
                "Injection",
                "WrapOperation on NEW requires the standard NEW; DUP; args; <init> shape: ${method.name}",
            )
            return null
        }
        val init = findInit(newInsn) ?: run {
            OmlLog.error(
                "Injection",
                "WrapOperation on NEW could not pair it with an <init> of ${newInsn.desc}: ${method.name}",
            )
            return null
        }
        val argTypes = Type.getArgumentTypes(init.desc)
        val returnType = Type.getObjectType(newInsn.desc)
        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        val opIndex = argTypes.size
        if (handlerArgs.size < opIndex + 1 + extras.size) {
            throw InjectionError(
                "[injection] the handler of WrapOperation(NEW) gives too few parameters: the constructor has " +
                    "$opIndex argument(s), then Operation<R>, then ${extras.size} extra(s); the handler declares " +
                    "${handlerArgs.size} ($handlerDesc)",
            )
        }
        for (i in argTypes.indices) {
            if (handlerArgs[i] != argTypes[i]) {
                throw InjectionError(
                    "[injection] WrapOperation(NEW) constructor argument ${i + 1} type mismatch: expected " +
                        "${argTypes[i].className}, actual ${handlerArgs[i].className} ($handlerDesc)",
                )
            }
        }
        if (handlerArgs[opIndex].descriptor != OPERATION_DESC) {
            throw InjectionError(
                "[injection] the handler of WrapOperation(NEW) must take $OPERATION_DESC right after the " +
                    "constructor arguments: actual ${handlerArgs[opIndex].className} ($handlerDesc)",
            )
        }
        if (handlerRet != returnType) {
            throw InjectionError(
                "[injection] the handler return type of WrapOperation(NEW) must equal the constructed type: " +
                    "expected ${returnType.className}, actual ${handlerRet.className} ($handlerDesc)",
            )
        }
        validateArgTypes(handlerDesc, extras, opIndex + 1, method, owner, locals, "WrapOperation(NEW).extras")

        val slots = storeArgs(method, argTypes)
        val list = InsnList()
        // The stack here is [null, null, args…] (NEW was neutralized and DUP left the second null).
        for (i in argTypes.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(argTypes[i]), slots[i]))
        }
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        for (i in argTypes.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(argTypes[i]), slots[i]))
        }
        OperationSupport.makeOperation(
            classNode, list, argTypes, returnType, init,
            prefix = listOf(TypeInsnNode(Opcodes.NEW, newInsn.desc), InsnNode(Opcodes.DUP)),
        )
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        // [null, null, result] → [result]: duplicate the result below the two nulls, then drop them.
        list.add(InsnNode(Opcodes.DUP_X2))
        list.add(InsnNode(Opcodes.POP))
        list.add(InsnNode(Opcodes.POP))
        list.add(InsnNode(Opcodes.POP))
        return WrapNewEdit(newInsn, init, list)
    }

    /** The next executable instruction (skipping labels, line numbers and stack frames). */
    private fun nextReal(insn: AbstractInsnNode): AbstractInsnNode? {
        var node = insn.next
        while (node != null && (node is LabelNode || node is LineNumberNode || node is FrameNode)) {
            node = node.next
        }
        return node
    }

    /**
     * The `<init>` paired with [newNode], by the same depth rule the anchor resolver uses: each further
     * NEW of the same type opens a nesting level, each `<init>` of that type closes one; the one that
     * closes the initial level is this NEW's constructor.
     */
    private fun findInit(newNode: TypeInsnNode): MethodInsnNode? {
        var depth = 0
        var node: AbstractInsnNode? = newNode
        while (node != null) {
            if (node is TypeInsnNode && node.opcode == Opcodes.NEW && node.desc == newNode.desc) {
                depth++
            } else if (node is MethodInsnNode && node.opcode == Opcodes.INVOKESPECIAL &&
                node.name == "<init>" && node.owner == newNode.desc
            ) {
                depth--
                if (depth == 0) return node
            }
            node = node.next
        }
        return null
    }

    /**
     * The value-modifying [Payload.HandlerCall] → instance form, delegating to [emitModifyArg].
     *
     * Parameter-list validation lives here in exactly one place (the static form is also caught by the same spot) —
     * writing it twice would diverge. The first parameter is **not** validated as a receiver here: a merged handler's
     * receiver is simply `this`, never entering the parameter list; putting the receiver in the list would instead
     * make the JVM treat the "value" as a reference (`VerifyError`).
     */
    fun emitModifyArg(
        payload: Payload.HandlerCall,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        if (payload.variant != HandlerVariant.NOTIFY) {
            throw InjectionError(
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}",
            )
        }
        return emitModifyArg(
            handlerOwner = payload.owner,
            handlerMethod = payload.method,
            handlerDesc = payload.desc,
            handlerIndex = payload.index,
            extras = payload.captures,
            instance = true,
            method = method,
            owner = owner,
            invoke = invoke,
            locals = locals,
        )
    }


    /**
     * Grabs all arguments at once and rewrites them.
     *
     * Like [emitModifyArg] this is "store in reverse, load in order", but the middle step is:
     * box the arguments into an `Object[]` → wrap them in an `Args` → hand to the handler → unbox each back into the
     * temp locals (not rebuilding the stack by reading `Args` directly, so that `Args` is only read twice: once when
     * boxing, once when retrieving).
     */
    fun emitModifyArgs(
        payload: Payload.ModifyArgs,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList? = emitModifyArgs(
        handlerOwner = payload.owner,
        handlerMethod = payload.method,
        handlerDesc = payload.desc,
        extras = payload.extras,
        instance = false,
        method = method,
        owner = owner,
        invoke = invoke,
        locals = locals,
    )

    /** The value-modifying [Payload.HandlerCall] → instance form, delegating to [emitModifyArgs]. */
    fun emitModifyArgs(
        payload: Payload.HandlerCall,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        if (payload.variant != HandlerVariant.NOTIFY) {
            throw InjectionError(
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}",
            )
        }
        return emitModifyArgs(
            handlerOwner = payload.owner,
            handlerMethod = payload.method,
            handlerDesc = payload.desc,
            extras = payload.captures,
            instance = true,
            method = method,
            owner = owner,
            invoke = invoke,
            locals = locals,
        )
    }

    /**
     * The **shared implementation** of "grab all arguments at once" (shared by [Payload.ModifyArgs] and the
     * instance form). The instance form pushes a bare `Object[]` instead of `Args`: `Args` exists so **bridge
     * methods** can avoid copying the array once (a bridge is static and has no other context), while an
     * instance handler's `this` is already at hand and this path boxes anyway — an extra `Args` layer would
     * only add boxing cost. The sequence is therefore `Object[]` → handler, handler rewrites → engine reads
     * back from the **same array**.
     */
    internal fun emitModifyArgs(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        method: MethodNode,
        owner: String,
        invoke: MethodInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        val targetArgs = Type.getArgumentTypes(invoke.desc)
        if (targetArgs.isEmpty()) {
            OmlLog.error(
                "Injection",
                "the target call ${invoke.owner}.${invoke.name}${invoke.desc} of ModifyArgs has no arguments: ${method.name}",
            )
            return null
        }
        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val ret = Type.getReturnType(handlerDesc)
        if (ret != Type.VOID_TYPE) {
            throw InjectionError(
                "[injection] the handler of ModifyArgs must return void (the modified arguments are read back by the engine): $handlerDesc",
            )
        }
        // The two forms have different **value-param** descriptors (the instance form uses a bare `Object[]`),
        // but both start from the 0th parameter — the receiver never enters the parameter list (see
        // [emitModifyArg]'s note).
        val wantListDesc = if (instance) "[Ljava/lang/Object;" else ARGS_DESC
        if (handlerArgs.isEmpty() || handlerArgs[0].descriptor != wantListDesc) {
            throw InjectionError(
                if (instance) {
                    "[injection] an instance-form ModifyArgs handler must be shaped (Object[][, captures…])V: $handlerDesc"
                } else {
                    "[injection] the first parameter of the ModifyArgs handler must be $ARGS_DESC (the argument list): $handlerDesc"
                },
            )
        }
        validateArgTypes(handlerDesc, extras, 1, method, owner, locals, "ModifyArgs.extras")

        val argsSlot = allocLocalSlot(method)
        val slots = storeArgs(method, targetArgs, argsSlot + 1)
        method.maxLocals = maxOf(method.maxLocals, argsSlot + 1)

        val list = InsnList()
        for (i in targetArgs.indices.reversed()) {
            list.add(VarInsnNode(Boxing.storeOpcode(targetArgs[i]), slots[i]))
        }

        // Object[] of the (boxed) args
        list.add(IntInsnNode(Opcodes.BIPUSH, targetArgs.size))
        list.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        for (i in targetArgs.indices) {
            list.add(InsnNode(Opcodes.DUP))
            list.add(IntInsnNode(Opcodes.BIPUSH, i))
            list.add(VarInsnNode(Boxing.loadOpcode(targetArgs[i]), slots[i]))
            Boxing.box(targetArgs[i])?.let { box ->
                list.add(MethodInsnNode(Opcodes.INVOKESTATIC, box.owner, box.name, box.desc, false))
            }
            list.add(InsnNode(Opcodes.AASTORE))
        }
        // The array is stashed to a slot first (it will be used once more when constructing the `Args`); once the `Args`
        // is built, the same slot is reused.
        list.add(VarInsnNode(Opcodes.ASTORE, argsSlot))

        // The instance form hands the bare array straight to the handler (see this function's docs); the static form
        // wraps it in one more `Args` layer.
        val handleOwner: String
        val handleDesc: String
        if (instance) {
            handleOwner = ARGS
            handleDesc = "[Ljava/lang/Object;"
        } else {
            // new Args(array)
            //
            // Can't use `NEW; DUP_X1`: `DUP_X1` would move the **uninitialized** reference below, which the JVM
            // verifier forbids for uninitialized objects. Only the plainest `NEW; DUP; <push constructor args>;
            // INVOKESPECIAL` works, so the array is stashed to a local slot first, then reloaded as the constructor
            // argument.
            list.add(TypeInsnNode(Opcodes.NEW, ARGS))
            list.add(InsnNode(Opcodes.DUP))
            list.add(VarInsnNode(Opcodes.ALOAD, argsSlot))
            list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, ARGS, "<init>", "([Ljava/lang/Object;)V", false))
            list.add(VarInsnNode(Opcodes.ASTORE, argsSlot))
            handleOwner = ARGS
            handleDesc = ARGS_DESC
        }

        // Instance form: `ALOAD 0` is the `INVOKEVIRTUAL` **receiver** (the handler's `this`),
        // not in the parameter list — so it is followed directly by that `Object[]`.
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        list.add(VarInsnNode(Opcodes.ALOAD, argsSlot))
        val writeBacks = mutableListOf<SugarWriteBack>()
        for (value in extras) emitValue(list, value, method, locals, writeBacks)
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)

        // Read back: the values are in that `Object[]`, unbox each and return it to the temp slot
        for (i in targetArgs.indices) {
            list.add(VarInsnNode(Opcodes.ALOAD, argsSlot))
            if (!instance) list.add(FieldInsnNode(Opcodes.GETFIELD, ARGS, "values", "[Ljava/lang/Object;"))
            list.add(IntInsnNode(Opcodes.BIPUSH, i))
            list.add(InsnNode(Opcodes.AALOAD))
            emitUnbox(list, targetArgs[i])
            list.add(VarInsnNode(Boxing.storeOpcode(targetArgs[i]), slots[i]))
        }
        for (i in targetArgs.indices) {
            list.add(VarInsnNode(Boxing.loadOpcode(targetArgs[i]), slots[i]))
        }
        return list
    }

    /**
     * Rewrites a **local variable** (aligned with Mixin `@ModifyVariable`): `LOAD <slot>; [extras…];
     * INVOKESTATIC handler (T[, extras…])T; STORE <slot>`.
     * **No stack reshuffling is needed** — the fundamental difference from [emitModifyArg]: there the
     * argument may be buried mid-stack (so "stash into temp slots in reverse, push back in order"), whereas
     * here the target value lives in a **local variable** and is read and written back directly — naturally
     * straight-line code, adding no stack frame and performing no boxing. The value's type is declared by the
     * handler's **return type**, and the engine picks `ILOAD/LLOAD/FLOAD/DLOAD/ALOAD` accordingly (a handler
     * returning `Object` can therefore only be used with reference-typed variables, same as Mixin).
     */
    fun emitModifyVariable(
        payload: Payload.ModifyVariable,
        method: MethodNode,
        owner: String,
        access: VarInsnNode,
        locals: AnchorLocals?,
    ): InsnList? = emitModifyVariable(
        handlerOwner = payload.owner,
        handlerMethod = payload.method,
        handlerDesc = payload.desc,
        extras = payload.extras,
        instance = false,
        method = method,
        owner = owner,
        access = access,
        locals = locals,
    )

    /** The value-modifying [Payload.HandlerCall] → instance form, delegating to [emitModifyVariable]. */
    fun emitModifyVariable(
        payload: Payload.HandlerCall,
        method: MethodNode,
        owner: String,
        access: VarInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        if (payload.variant != HandlerVariant.NOTIFY) {
            throw InjectionError(
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}",
            )
        }
        return emitModifyVariable(
            handlerOwner = payload.owner,
            handlerMethod = payload.method,
            handlerDesc = payload.desc,
            extras = payload.captures,
            instance = true,
            method = method,
            owner = owner,
            access = access,
            locals = locals,
        )
    }

    /**
     * The **shared implementation** of "rewrite a local variable" (the [Payload.ModifyVariable] form and the
     * instance form share it).
     *
     * Both forms have the same parameter list (`(T[, captures…])T`) — the instance form's receiver is supplied by
     * `INVOKEVIRTUAL`, appearing as `this` inside the handler and **not in the parameter list**. They differ only
     * in the call instruction and whether `ALOAD 0` is pushed first.
     */
    internal fun emitModifyVariable(
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
        method: MethodNode,
        owner: String,
        access: VarInsnNode,
        locals: AnchorLocals?,
    ): InsnList? {
        val handler = Type.getMethodType(handlerDesc)
        val type = handler.returnType
        if (type == Type.VOID_TYPE) {
            throw InjectionError(
                "[injection] the handler of ModifyVariable must not return void (it must return the new value): $handlerDesc",
            )
        }
        if (handler.argumentTypes.isEmpty() || handler.argumentTypes[0] != type) {
            throw InjectionError(
                "[injection] the handler of ModifyVariable must be shaped (T[, captures...])T: " +
                    "the first parameter should equal the return type ${type.className}, actual " +
                    "${handler.argumentTypes.firstOrNull()?.className ?: "no parameters"} ($handlerDesc)",
            )
        }
        validateArgTypes(handlerDesc, extras, 1, method, owner, locals, "ModifyVariable.extras")

        // Type assertion: report only the **provably illegal** kind (handler declares `int`, slot is a reference).
        // Descriptor-level difference (String vs Object) is not reported — after the write point the slot's type may
        // have been **merged coarser** by another branch; that's not a rule error, and if it truly is wrong the JVM
        // verifier surfaces it clearly as a `VerifyError` at class definition (never silently).
        val slot = access.`var`
        val actual = locals?.frame()?.live(slot)
        if (actual != null && !LocalFrame.sameCategory(type, actual)) {
            throw InjectionError(
                "[injection] the value type of ModifyVariable does not match the local variable: ${method.name}${method.desc} " +
                    "slot $slot is ${actual.descriptor}, but the handler declared ${type.descriptor}" +
                    " ($handlerDesc)",
            )
        }
        // The write-back **widens** that slot's type to the type declared by the handler (the JVM frames count the value
        // stored by `ASTORE`). If later code still uses the variable as its original type, class definition fails
        // with `VerifyError` — a constraint of the target code itself (same as Mixin), but spelling it out ahead of
        // time saves a lot of debugging.
        if (actual != null && actual.descriptor != type.descriptor &&
            (type.sort == Type.OBJECT || type.sort == Type.ARRAY)
        ) {
            OmlLog.error(
                "Injection",
                "ModifyVariable writes ${type.descriptor} back to slot $slot (originally ${actual.descriptor}): " +
                    "that slot's type in the following code is widened to ${type.descriptor}; if it is still used as the original type it will VerifyError. " +
                    "(${method.name}${method.desc}; make the handler return the original type, or confirm the code after no longer uses it as the original type)",
            )
        }

        val list = InsnList()
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0)) // receiver (target instance)
        list.add(VarInsnNode(Boxing.loadOpcode(type), slot))
        val writeBacks = mutableListOf<SugarWriteBack>()
        if (!emitValues(list, extras, method, locals, writeBacks)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            ),
        )
        emitWriteBacks(list, writeBacks)
        list.add(VarInsnNode(Boxing.storeOpcode(type), slot))
        return list
    }

    /** Stack top is a boxed value: unbox to [type] (reference types just need `CHECKCAST`). */
    private fun emitUnbox(list: InsnList, type: Type) {
        val unbox = Boxing.unbox(type)
        if (unbox == null) {
            list.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
        } else {
            list.add(TypeInsnNode(Opcodes.CHECKCAST, unbox.owner))
            list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, unbox.owner, unbox.name, unbox.desc, false))
        }
    }

    /**
     * Determines the argument index to be modified.
     *
     * When [Payload.ModifyArg.index] is `-1`, locate by type automatically (aligning with Mixin): it is used when
     * exactly one argument of the call has a type equal to the handler's return type; zero or more than one both
     * error out — guessing the wrong position is far more dangerous than failing.
     */
    private fun resolveArgIndex(
        handlerIndex: Int,
        handlerDesc: String,
        targetArgs: Array<Type>,
        invoke: MethodInsnNode,
        method: MethodNode,
    ): Int {
        val ret = Type.getReturnType(handlerDesc)
        if (handlerIndex >= 0) {
            if (handlerIndex >= targetArgs.size) {
                throw InjectionError(
                    "[injection] index=$handlerIndex of ModifyArg is out of bounds: " +
                        "${invoke.owner}.${invoke.name}${invoke.desc} has only ${targetArgs.size} arguments",
                )
            }
            return handlerIndex
        }
        val matches = targetArgs.indices.filter { targetArgs[it] == ret }
        if (matches.size == 1) return matches[0]
        throw InjectionError(
            if (matches.isEmpty()) {
                "[injection] ModifyArg requests auto-locating the argument by type, but ${invoke.owner}.${invoke.name}${invoke.desc} " +
                    "has no argument of type ${ret.className} — give the index explicitly"
            } else {
                "[injection] ModifyArg auto-locates multiple candidates by type (positions ${matches.map { it + 1 }}, type " +
                    "${ret.className}) — give the index explicitly"
            },
        )
    }

    /** Allocates a temp local slot for each argument of the target call (returns "argument index → slot"). */
    private fun storeArgs(
        method: MethodNode,
        targetArgs: Array<Type>,
        fromSlot: Int = allocLocalSlot(method),
    ): IntArray {
        val slots = IntArray(targetArgs.size)
        var next = fromSlot
        for (i in targetArgs.indices) {
            slots[i] = next
            next += targetArgs[i].size
        }
        method.maxLocals = maxOf(method.maxLocals, next)
        return slots
    }

    /**
     * Synthesizes the method-entry stack frame (locals = this + each argument + [extraLocals], empty stack).
     * The method-entry frame is implicit and doesn't exist in the instruction stream, yet the branch target inserted
     * at HEAD needs an explicit frame, so it can only be synthesized this way.
     *
     * [extraLocals] are the extra local slots added by the injection block, with **real slot numbers** as keys
     * (see [allocLocalSlot]) — a slot number may exceed "this + parameters" count (other locals in the method body
     * pushed it higher), and the gaps in between must be filled with `TOP`, otherwise the frame positions shift as a
     * whole and misalign with the actual `ASTORE` landing points, failing directly with `VerifyError` at definition.
     */
    internal fun entryFrame(
        owner: String,
        method: MethodNode,
        extraLocals: Map<Int, Any> = emptyMap(),
    ): FrameNode {
        val locals = mutableListOf<Any>()
        if ((method.access and Opcodes.ACC_STATIC) == 0) locals.add(owner)
        for (type in Type.getArgumentTypes(method.desc)) {
            when (type.sort) {
                Type.LONG -> {
                    locals.add(Opcodes.LONG); locals.add(Opcodes.TOP)
                }

                Type.DOUBLE -> {
                    locals.add(Opcodes.DOUBLE); locals.add(Opcodes.TOP)
                }

                Type.FLOAT -> locals.add(Opcodes.FLOAT)
                Type.INT, Type.SHORT,
                Type.BYTE, Type.CHAR,
                Type.BOOLEAN,
                    -> locals.add(Opcodes.INTEGER)

                else -> locals.add(type.internalName)
            }
        }
        val lastSlot = extraLocals.keys.maxOrNull() ?: -1
        while (locals.size <= lastSlot) locals.add(Opcodes.TOP)
        for ([slot, value] in extraLocals) locals[slot] = value
        return FrameNode(
            Opcodes.F_NEW, locals.size, locals.toTypedArray(),
            0, emptyArray<Any>(),
        )
    }

    /** Emits the value-loading instructions one by one; voids the whole payload if any value cannot be loaded (returns false). */
    /** A mutable `@Local` capture's post-call write-back: ref slot → target local slot + its type. */
    internal class SugarWriteBack(
        val refSlot: Int,
        val localSlot: Int,
        val type: Type,
        val refType: String = LOCAL_REF_DESC,
    )

    /** A `@Cancellable` handle created for this injection: its slot holds the `HookContext`/`HookValue`. */
    internal class CancelCheck(val slot: Int)

    internal fun emitValues(
        list: InsnList,
        values: List<DslValue>,
        method: MethodNode,
        locals: AnchorLocals?,
        writeBacks: MutableList<SugarWriteBack>? = null,
        cancelChecks: MutableList<CancelCheck>? = null,
    ): Boolean {
        for (value in values) {
            if (!emitValue(list, value, method, locals, writeBacks, cancelChecks)) return false
        }
        return true
    }

    /**
     * Emits the write-back for every mutable `@Local` capture: the ref's current value, cast/unboxed per
     * the local's type, stored back into the local. The handler owns the ref during the call; after it
     * returns, the local is authoritative again.
     */
    internal fun emitWriteBacks(list: InsnList, writeBacks: List<SugarWriteBack>) {
        for (writeBack in writeBacks) {
            val typed = TYPED_REFS[writeBack.refType]
            list.add(VarInsnNode(Opcodes.ALOAD, writeBack.refSlot))
            if (typed != null) {
                list.add(
                    MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, typed.interfaceInternal, "getValue", typed.getterDesc, true,
                    ),
                )
            } else {
                list.add(
                    MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        LOCAL_REF,
                        "getValue",
                        Type.getMethodDescriptor(Type.getObjectType("java/lang/Object")),
                        true,
                    ),
                )
                when (writeBack.type.sort) {
                    Type.OBJECT, Type.ARRAY -> list.add(TypeInsnNode(Opcodes.CHECKCAST, writeBack.type.internalName))
                    else -> {
                        val unbox = Boxing.unbox(writeBack.type)
                            ?: throw IllegalStateException("no unboxing type for ${writeBack.type.className}")
                        list.add(TypeInsnNode(Opcodes.CHECKCAST, unbox.owner))
                        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, unbox.owner, unbox.name, unbox.desc, false))
                    }
                }
            }
            list.add(VarInsnNode(Boxing.storeOpcode(writeBack.type), writeBack.localSlot))
        }
    }

    /**
     * Appends the `@Cancellable` check after the handler call: when the handle is canceled, the operation's
     * result on the stack is dropped and the target method returns immediately (a void target exits, a
     * non-void one reads the value back out of its `HookValue`). The fall-through path needs a stack-map
     * frame because the `IFEQ` creates a new jump target; its locals are the dataflow truth at the anchor and
     * its stack is exactly the operation result the original instruction would have produced.
     */
    internal fun emitCancelChecks(
        list: InsnList,
        checks: List<CancelCheck>,
        method: MethodNode,
        stackType: Type,
        frames: MethodFrames,
        anchor: AbstractInsnNode,
    ): Boolean {
        if (checks.isEmpty()) return true
        if (checks.size > 1) {
            throw InjectionError(
                "[injection] only one @Cancellable parameter is allowed: ${method.name}${method.desc}",
            )
        }
        val frameLocals = frames.frameLocalsAt(anchor, after = false) ?: run {
            OmlLog.error(
                "Injection",
                "injection skipped: the local state before the @Cancellable anchor cannot be derived " +
                    "(dataflow failed): ${method.name}${method.desc}",
            )
            return false
        }
        val targetRet = Type.getReturnType(method.desc)
        val slot = checks[0].slot
        val notCancelled = LabelNode()
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        list.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "getCanceled", "()Z", false,
            ),
        )
        list.add(JumpInsnNode(Opcodes.IFEQ, notCancelled))
        if (stackType != Type.VOID_TYPE) {
            list.add(InsnNode(if (stackType.size == 2) Opcodes.POP2 else Opcodes.POP))
        }
        if (targetRet == Type.VOID_TYPE) {
            list.add(InsnNode(Opcodes.RETURN))
        } else {
            list.add(VarInsnNode(Opcodes.ALOAD, slot))
            when (targetRet.sort) {
                Type.BOOLEAN -> returnAccessor(list, "getReturnValueZ", "()Z")
                Type.BYTE -> returnAccessor(list, "getReturnValueB", "()B")
                Type.CHAR -> returnAccessor(list, "getReturnValueC", "()C")
                Type.SHORT -> returnAccessor(list, "getReturnValueS", "()S")
                Type.INT -> returnAccessor(list, "getReturnValueI", "()I")
                Type.LONG -> returnAccessor(list, "getReturnValueJ", "()J")
                Type.FLOAT -> returnAccessor(list, "getReturnValueF", "()F")
                Type.DOUBLE -> returnAccessor(list, "getReturnValueD", "()D")
                else -> {
                    returnAccessor(list, "getReturnValue", "()Ljava/lang/Object;")
                    list.add(TypeInsnNode(Opcodes.CHECKCAST, targetRet.internalName))
                }
            }
            list.add(InsnNode(targetRet.getOpcode(Opcodes.IRETURN)))
        }
        list.add(notCancelled)
        val stack = if (stackType == Type.VOID_TYPE) {
            emptyArray<Any>()
        } else {
            arrayOf<Any>(frameType(stackType))
        }
        list.add(FrameNode(Opcodes.F_NEW, frameLocals.size, frameLocals, stack.size, stack))
        return true
    }

    /** Reads one typed accessor off the `HookValue` that `ALOAD slot` just pushed. */
    private fun returnAccessor(list: InsnList, name: String, desc: String) {
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_RETURNABLE, name, desc, false))
    }

    /**
     * Emits one value-loading instruction.
     *
     * Returns `false` = this value **cannot be loaded** (reason already printed), and the caller must abandon the
     * whole injection — pushing one argument fewer would produce a `VerifyError` at class definition, which is far
     * harder to trace than "skip this injection + hit-count warning" (`require(n)` can turn it into an explicit
     * startup failure).
     */
    private fun emitValue(
        list: InsnList,
        value: DslValue,
        method: MethodNode,
        locals: AnchorLocals?,
        writeBacks: MutableList<SugarWriteBack>? = null,
        cancelChecks: MutableList<CancelCheck>? = null,
    ): Boolean {
        when (value) {
            is DslValue.This -> {
                if ((method.access and Opcodes.ACC_STATIC) != 0) {
                    OmlLog.error(
                        "Injection",
                        "This can only be used in instance methods, skipped: ${method.name}${method.desc}",
                    )
                    return false
                }
                list.add(VarInsnNode(Opcodes.ALOAD, 0))
            }

            is DslValue.Arg -> {
                val slot = argumentSlot(method, value.index) ?: run {
                    OmlLog.error(
                        "Injection",
                        "argument index out of bounds: ${method.name}${method.desc} arg=${value.index}",
                    )
                    return false
                }
                list.add(VarInsnNode(loadOpcode(method, value.index), slot))
            }

            is DslValue.Local -> {
                val frame = locals?.frame()
                if (frame == null) {
                    OmlLog.error(
                        "Injection",
                        "cannot read the local variable at the injection point ($value): ${method.name}${method.desc} — " +
                            (locals?.explain()
                                ?: "this payload did not request a local-variable snapshot (engine internal error)"),
                    )
                    return false
                }
                val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
                when (val resolution = frame.resolve(value, isStatic)) {
                    is LocalResolution.Found ->
                        list.add(VarInsnNode(Boxing.loadOpcode(resolution.type), resolution.slot))

                    is LocalResolution.Missing -> {
                        // A written index is an **assertion** on the slot: contradicting the dataflow analysis means
                        // the rule is wrong, so fail hard. Not finding it when only a type is given (letting the engine
                        // search) is legal — the rule may match several overloads and only some have that local — so
                        // skip by default and hand the decision to require/expect, unless `strict` is explicitly set
                        // (Mixin's CAPTURE_FAILHARD).
                        if (value.index != null || value.strict) {
                            throw InjectionError(
                                "[injection] local-variable read contradicts the dataflow analysis: " +
                                    "${method.name}${method.desc} $value — ${resolution.reason}",
                            )
                        }
                        OmlLog.error(
                            "Injection",
                            "local-variable read skipped: ${method.name}${method.desc} $value — ${resolution.reason}",
                        )
                        return false
                    }
                }
            }

            is DslValue.RefLocal -> {
                val frame = locals?.frame()
                if (frame == null) {
                    OmlLog.error(
                        "Injection",
                        "cannot read the local variable at the injection point ($value): ${method.name}${method.desc} — " +
                            (locals?.explain()
                                ?: "this payload did not request a local-variable snapshot (engine internal error)"),
                    )
                    return false
                }
                val resolution = frame.resolve(
                    DslValue.Local(value.index, value.type, value.ordinal, value.strict),
                    (method.access and Opcodes.ACC_STATIC) != 0,
                )
                val found = when (resolution) {
                    is LocalResolution.Found -> resolution
                    is LocalResolution.Missing -> {
                        if (value.index != null || value.strict) {
                            throw InjectionError(
                                "[injection] local-variable read contradicts the dataflow analysis: " +
                                    "${method.name}${method.desc} $value — ${resolution.reason}",
                            )
                        }
                        OmlLog.error(
                            "Injection",
                            "local-variable read skipped: ${method.name}${method.desc} $value — ${resolution.reason}",
                        )
                        return false
                    }
                }
                if (writeBacks == null) {
                    throw InjectionError(
                        "[injection] a mutable @Local (LocalRef) is only supported on @Inject payloads: " +
                            "${method.name}${method.desc}",
                    )
                }
                val typed = TYPED_REFS[value.refType]
                if (typed == null && value.refType != LOCAL_REF_DESC) {
                    throw InjectionError(
                        "[injection] unknown local ref type ${value.refType}: ${method.name}${method.desc}",
                    )
                }
                if (typed != null && !LocalFrame.compatible(typed.primitive, found.type)) {
                    throw InjectionError(
                        "[injection] typed local ref ${value.refType} expects a ${typed.primitive.className} local, " +
                            "but slot ${found.slot} is ${found.type.className}: ${method.name}${method.desc}",
                    )
                }
                val refSlot = allocLocalSlot(method)
                method.maxLocals = maxOf(method.maxLocals, refSlot + 1)
                if (typed != null) {
                    list.add(TypeInsnNode(Opcodes.NEW, typed.implInternal))
                    list.add(InsnNode(Opcodes.DUP))
                    list.add(VarInsnNode(Boxing.loadOpcode(found.type), found.slot))
                    list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, typed.implInternal, "<init>", typed.ctorDesc, false))
                } else {
                    list.add(TypeInsnNode(Opcodes.NEW, LOCAL_REF_IMPL))
                    list.add(InsnNode(Opcodes.DUP))
                    list.add(VarInsnNode(Boxing.loadOpcode(found.type), found.slot))
                    Boxing.box(found.type)?.let { box ->
                        list.add(MethodInsnNode(Opcodes.INVOKESTATIC, box.owner, box.name, box.desc, false))
                    }
                    list.add(
                        MethodInsnNode(
                            Opcodes.INVOKESPECIAL,
                            LOCAL_REF_IMPL,
                            "<init>",
                            Type.getMethodDescriptor(Type.VOID_TYPE, Type.getObjectType("java/lang/Object")),
                            false,
                        ),
                    )
                }
                list.add(VarInsnNode(Opcodes.ASTORE, refSlot))
                list.add(VarInsnNode(Opcodes.ALOAD, refSlot))
                writeBacks += SugarWriteBack(refSlot, found.slot, found.type, value.refType)
            }

            is DslValue.SharedRef -> throw InjectionError(
                "[injection] a @Share capture must be rewritten to the method's ref slot before emission: " +
                    "${method.name}${method.desc}",
            )

            is DslValue.RefSlot -> list.add(VarInsnNode(Opcodes.ALOAD, value.slot))

            is DslValue.IntVal -> list.add(LdcInsnNode(value.value))
            is DslValue.LongVal -> list.add(LdcInsnNode(value.value))
            is DslValue.Str -> list.add(LdcInsnNode(value.value))
            is DslValue.Cls -> list.add(LdcInsnNode(Type.getObjectType(value.internalName)))
            is DslValue.Null -> list.add(InsnNode(Opcodes.ACONST_NULL))

            is DslValue.CancelableHandle -> {
                if (cancelChecks == null) {
                    OmlLog.error(
                        "Injection",
                        "@Cancellable is only supported on @WrapOperation with a plain call, field, type-check " +
                            "or array anchor: ${method.name}${method.desc}",
                    )
                    return false
                }
                val targetRet = Type.getReturnType(method.desc)
                val expected = if (targetRet == Type.VOID_TYPE) CALLBACK_INFO_DESC else CALLBACK_RETURNABLE_DESC
                if (value.descriptor != expected) {
                    throw InjectionError(
                        "[injection] @Cancellable parameter type must be " +
                            "${if (targetRet == Type.VOID_TYPE) "HookContext" else "HookValue"} for a target " +
                            "returning ${targetRet.className}, actual ${Type.getType(value.descriptor).className}: " +
                            "${method.name}${method.desc}",
                    )
                }
                val handleType = Type.getType(value.descriptor)
                val slot = allocLocalSlot(method)
                list.add(TypeInsnNode(Opcodes.NEW, handleType.internalName))
                list.add(InsnNode(Opcodes.DUP))
                list.add(LdcInsnNode(method.name))
                list.add(InsnNode(Opcodes.ICONST_1))
                list.add(
                    MethodInsnNode(
                        Opcodes.INVOKESPECIAL, handleType.internalName, "<init>", "(Ljava/lang/String;Z)V", false,
                    ),
                )
                list.add(VarInsnNode(Opcodes.ASTORE, slot))
                list.add(VarInsnNode(Opcodes.ALOAD, slot))
                cancelChecks += CancelCheck(slot)
            }
        }
        return true
    }

    /** The local-variable slot of the [index]-th argument in the method descriptor (long/double take two slots; for instance methods slot 0 is `this`). */
    private fun argumentSlot(method: MethodNode, index: Int): Int? {
        val argumentTypes = Type.getArgumentTypes(method.desc)
        if (index >= argumentTypes.size) return null
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var slot = if (isStatic) 0 else 1
        for (i in 0 until index) {
            slot += if (argumentTypes[i].size == 2) 2 else 1
        }
        return slot
    }

    private fun loadOpcode(method: MethodNode, index: Int): Int {
        val type = Type.getArgumentTypes(method.desc)[index]
        return Boxing.loadOpcode(type)
    }

}
