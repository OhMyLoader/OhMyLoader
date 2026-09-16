package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.transformer.injection.PayloadEmitter.emitCancellableReturn
import org.ohmyloader.core.transformer.injection.PayloadEmitter.emitValue


// ---------- payload emitter: builds fresh instruction nodes for each anchor ----------

internal object PayloadEmitter {

    /** Internal name of `CallbackInfoReturnable` — used by `NEW`/`INVOKEVIRTUAL`/frames. */
    private const val CALLBACK_RETURNABLE = "org/ohmyloader/api/mixin/CallbackInfoReturnable"

    /** This type's JVM descriptor, for validating the tail of a bridge descriptor. */
    private const val CALLBACK_RETURNABLE_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfoReturnable;"

    /** How a bridge descriptor should end: the handle is the last parameter, returning void. */
    private const val CALLBACK_RETURNABLE_TAIL = "${CALLBACK_RETURNABLE_DESC})V"

    /** Internal name and descriptor of `CallbackInfo` (the void-target handle) — needed by HandlerCall. */
    private const val CALLBACK_INFO = "org/ohmyloader/api/mixin/CallbackInfo"
    private const val CALLBACK_INFO_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfo;"
    private const val CALLBACK_INFO_TAIL = "${CALLBACK_INFO_DESC})V"

    /** Internal name and descriptor of `Args` (the "all arguments" handle). */
    private const val ARGS = "org/ohmyloader/api/mixin/Args"
    private const val ARGS_DESC = "Lorg/ohmyloader/api/mixin/Args;"

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
            if (!emitValues(list, payload.args, method, locals)) return null
            list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
            list
        }

        is Payload.TransformReturn -> {
            val ret = Type.getReturnType(method.desc)
            val handlerRet = Type.getReturnType(payload.desc)
            val handlerArgs = Type.getArgumentTypes(payload.desc)
            val shapeOk = handlerRet == ret &&
                (ret == Type.VOID_TYPE || (handlerArgs.isNotEmpty() && handlerArgs[0] == ret))
            if (!shapeOk) {
                System.err.println(
                    "[injection] TransformReturn handler shape mismatch: target ${method.name}${method.desc} " +
                        "returns $ret; handler should be shaped ($ret[, extras...])$ret, actual ${payload.desc}"
                )
                return null
            }
            // extras push after the return value, mapping to the descriptor's parameters from the 2nd on
            validateArgTypes(payload.desc, payload.extras, 1, method, owner, locals, "TransformReturn.extras")
            val list = InsnList()
            // This anchor is a return instruction, so the stack top is already the return value R: extras push on
            // top of R, so the argument order is exactly (R, extras...)
            if (!emitValues(list, payload.extras, method, locals)) return null
            list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
            list
        }

        is Payload.CheckCall -> {
            validateArgTypes(payload.desc, payload.args, 0, method, owner, locals, "CheckCall")
            val list = InsnList()
            if (!emitValues(list, payload.args, method, locals)) return null
            list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))
            val label = LabelNode()
            list.add(JumpInsnNode(Opcodes.IFEQ, label))
            list.add(InsnNode(Opcodes.RETURN))
            list.add(label)
            list.add(entryFrame(owner, method))
            list
        }

        is Payload.Redirect -> error("Redirect substitutes the instruction directly in the engine and does not go through emit")
        is Payload.CancellableReturn -> emitCancellableReturn(payload, method, owner, locals)

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
            HandlerKind.REDIRECT,
                -> error("HandlerCall(${payload.kind}) needs the anchor instruction and is handled directly by the engine, not through emit")
        }

        // Anchor-related: needs the anchor instruction to compute (whether/which type of value is on the stack is
        // decided by that instruction), so applyToMethod calls emitModify* / emitInPlaceValue directly
        is Payload.ModifyArg, is Payload.ModifyArgs, is Payload.ModifyVariable,
        is Payload.ModifyConstant, is Payload.ModifyExpressionValue ->
            error("${payload.javaClass.simpleName} needs the anchor instruction; the engine calls emitModify*/emitInPlaceValue directly")

        is Payload.Raw -> InsnList().apply { payload.build(this, method, owner) }
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
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}"
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
                    " (decided by the anchor): actual ${valueParam?.className ?: "no parameters"} ($handlerDesc)"
            )
        }
        if (handler.returnType != value) {
            throw InjectionError(
                "[injection] the handler of $rule must return ${value.className} (the value's type, to be put back on the stack): " +
                    "actual ${handler.returnType.className} ($handlerDesc)"
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
        if (!emitValues(list, extras, method, locals)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            )
        )
        return list
    }

    /**
     * Directly calls the "handler merged into the target class": `this` is the target instance.
     *
     * Nearly the same skeleton as [emitCancellableReturn], with two differences:
     * 1. Push **`ALOAD 0` (the target instance)** first, then the capture arguments; the call is `INVOKEVIRTUAL`
     *    (not a static bridge);
     * 2. The handle `CallbackInfo` / `CallbackInfoReturnable` is created by the engine and passed in.
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
                    "the handler cannot get this (the target instance) — use a static handler (StaticCall/CheckCall) then"
            )
        }
        val ret = Type.getReturnType(method.desc)
        when (payload.variant) {
            HandlerVariant.NOTIFY -> Unit
            HandlerVariant.CANCELLABLE -> if (ret != Type.VOID_TYPE) {
                throw InjectionError(
                    "[injection] the CANCELLABLE form is only for void targets (for non-void use RETURNABLE): " +
                        "${method.name}${method.desc} returns ${ret.className}"
                )
            }

            HandlerVariant.RETURNABLE -> if (ret == Type.VOID_TYPE) {
                throw InjectionError(
                    "[injection] the RETURNABLE form requires a non-void target (for a void target use CANCELLABLE): " +
                        "${method.name}${method.desc}"
                )
            }
        }
        val handleDesc = if (payload.variant == HandlerVariant.RETURNABLE) CALLBACK_RETURNABLE_DESC
        else CALLBACK_INFO_DESC
        if (!payload.desc.endsWith("$handleDesc)V")) {
            throw InjectionError(
                "[injection] the HandlerCall descriptor must end with " + handleDesc + ")V" +
                    " (captured arguments + handle, returning void): ${method.name}${method.desc} uses ${payload.desc}"
            )
        }
        // desc params = capture args + trailing handle ⇒ the pushed captures only map to the first n-1
        validateArgTypes(payload.desc, payload.captures, 0, method, owner, locals, "HandlerCall", tailSkip = 1)

        // The handle **must be created for all three variants**: the handler's parameter list always ends with it
        // (a notify-style `CallbackInfo` is mandatory too). Whether to "read it back" is the only differentiator —
        // only cancelable/returnable variants need to read back the cancel flag.
        val readsBack = payload.variant != HandlerVariant.NOTIFY
        val cancellableHandle = payload.variant != HandlerVariant.NOTIFY
        val internal = if (payload.variant == HandlerVariant.RETURNABLE) CALLBACK_RETURNABLE else CALLBACK_INFO
        val slot = allocLocalSlot(method)
        method.maxLocals = maxOf(method.maxLocals, slot + 1)
        val list = InsnList()

        list.add(TypeInsnNode(Opcodes.NEW, internal))
        list.add(InsnNode(Opcodes.DUP))
        list.add(LdcInsnNode(method.name))
        list.add(InsnNode(if (cancellableHandle) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, internal, "<init>", "(Ljava/lang/String;Z)V", false))
        list.add(VarInsnNode(Opcodes.ASTORE, slot))

        list.add(VarInsnNode(Opcodes.ALOAD, 0)) // receiver (target instance) — it isn't an argument
        if (!emitValues(list, payload.captures, method, locals)) return null
        list.add(VarInsnNode(Opcodes.ALOAD, slot)) // the handle is an argument
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, payload.owner, payload.method, payload.desc, false))

        if (!readsBack) return list

        val cont = LabelNode()
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, internal, "getCanceled", "()Z", false))
        list.add(JumpInsnNode(Opcodes.IFEQ, cont))

        if (payload.variant == HandlerVariant.CANCELLABLE) {
            list.add(InsnNode(Opcodes.RETURN))
            list.add(cont)
            list.add(entryFrame(owner, method, extraLocals = mapOf(slot to CALLBACK_INFO)))
        } else {
            val accessor = returnAccessor(ret)
            list.add(VarInsnNode(Opcodes.ALOAD, slot))
            list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_RETURNABLE, accessor.name, accessor.desc, false))
            if (accessor.needsCast) list.add(TypeInsnNode(Opcodes.CHECKCAST, ret.internalName))
            list.add(InsnNode(returnOpcode(ret)))
            list.add(cont)
            list.add(entryFrame(owner, method, extraLocals = mapOf(slot to CALLBACK_RETURNABLE)))
        }
        return list
    }

    /**
     * The full return-value short-circuit sequence: create the `CallbackInfoReturnable` handle into a local
     * slot, call the handler through the `INVOKESTATIC` bridge as `(captures…, id, cir)`, and if `canceled`
     * return `cir.getReturnValueX()` (typed accessor: no unboxing, no null branch); otherwise continue with
     * the original method body. The handle, together with `id`, is pushed by the engine (per
     * [Payload.CancellableReturn]'s [desc]), because the engine must read back `canceled` and the return
     * value after the call.
     */
    private fun emitCancellableReturn(
        payload: Payload.CancellableReturn,
        method: MethodNode,
        owner: String,
        locals: AnchorLocals?,
    ): InsnList? {
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE) {
            System.err.println("[injection] CancellableReturn requires a non-void target: ${method.name}${method.desc}")
            return null
        }
        if (!payload.desc.endsWith(CALLBACK_RETURNABLE_TAIL)) {
            throw InjectionError(
                "[injection] the bridge descriptor of CancellableReturn must end with ${CALLBACK_RETURNABLE_DESC})V" +
                    " (captured arguments, id, and callback handle pushed in order): ${method.name}${method.desc} uses ${payload.desc}"
            )
        }
        // bridgeArgs are all the args the engine pushes (captures + id); the descriptor has one extra trailing handle,
        // which the engine pushes itself via ALOAD and doesn't take part in this type comparison — so skip the last 1.
        validateArgTypes(payload.desc, payload.bridgeArgs, 0, method, owner, locals, "CancellableReturn", tailSkip = 1)

        val slot = allocLocalSlot(method)
        // Make maxLocals cover the new slot (COMPUTE_MAXS recomputes it on write-back, but this avoids depending on it)
        method.maxLocals = maxOf(method.maxLocals, slot + 1)
        val list = InsnList()

        // var cir = new CallbackInfoReturnable("<target method name>", true)
        list.add(TypeInsnNode(Opcodes.NEW, CALLBACK_RETURNABLE))
        list.add(InsnNode(Opcodes.DUP))
        list.add(LdcInsnNode(method.name))
        list.add(InsnNode(Opcodes.ICONST_1)) // cancellable: only cancelable injection points walk this path
        list.add(MethodInsnNode(Opcodes.INVOKESPECIAL, CALLBACK_RETURNABLE, "<init>", "(Ljava/lang/String;Z)V", false))
        list.add(VarInsnNode(Opcodes.ASTORE, slot))

        // handler(captures…, id, cir)  — bridgeArgs order matches the desc params
        if (!emitValues(list, payload.bridgeArgs, method, locals)) return null
        list.add(VarInsnNode(Opcodes.ALOAD, slot))
        list.add(MethodInsnNode(Opcodes.INVOKESTATIC, payload.owner, payload.method, payload.desc, false))

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
        list.add(InsnNode(returnOpcode(ret)))

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

    private fun returnOpcode(type: Type): Int = when (type.sort) {
        Type.BOOLEAN, Type.BYTE, Type.CHAR,
        Type.SHORT, Type.INT,
            -> Opcodes.IRETURN

        Type.LONG -> Opcodes.LRETURN
        Type.FLOAT -> Opcodes.FRETURN
        Type.DOUBLE -> Opcodes.DRETURN
        else -> Opcodes.ARETURN
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
     *   `CancellableReturn`'s trailing handler id and callback handle are pushed separately by the engine)
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
                    "pushes ${args.size} but the descriptor requires ${expected.size - headSkip - tailSkip}"
            )
        }
        args.forEachIndexed { i, value ->
            val actual = stackTypeOf(value, method, owner, locals) ?: return@forEachIndexed
            val want = expected[i + headSkip]
            val problem = mismatch(actual, want)
            if (problem != null) {
                throw InjectionError(
                    "[injection] $what argument type does not match the handler descriptor: argument ${i + headSkip + 1} of ${method.name}${method.desc} calling $desc " +
                        "expects ${want.className}, actually pushed ${actual.className} ($problem; $value)"
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

            is DslValue.IntVal -> Type.INT_TYPE
            is DslValue.LongVal -> Type.LONG_TYPE
            is DslValue.Str -> Type.getType("Ljava/lang/String;")
            is DslValue.Cls -> Type.getType("Ljava/lang/Class;")
            is DslValue.Null -> null
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
            System.err.println(
                "[injection] the target call ${invoke.owner}.${invoke.name}${invoke.desc} of ModifyArg has no arguments: ${method.name}"
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
                    "${targetArgs[index].className}, handler returns ${handlerRet.className} ($handlerDesc)"
            )
        }
        if (singleArgMode) {
            if (handlerArgs[0] != targetArgs[index]) {
                throw InjectionError(
                    "[injection] the handler parameter type of ModifyArg's single-argument mode must equal the type of the modified argument: " +
                        "expected ${targetArgs[index].className}, actual ${handlerArgs[0].className} ($handlerDesc)"
                )
            }
        } else {
            if (argCount != targetArgs.size) {
                throw InjectionError(
                    "[injection] the handler value-parameter count of ModifyArg's multi-argument mode must equal the target call's argument count: " +
                        "${invoke.owner}.${invoke.name}${invoke.desc} has ${targetArgs.size} " +
                        "but the handler provides $argCount ($handlerDesc)"
                )
            }
            for (i in targetArgs.indices) {
                if (handlerArgs[i] != targetArgs[i]) {
                    throw InjectionError(
                        "[injection] ModifyArg multi-argument mode parameter ${i + 1} type mismatch: expected " +
                            "${targetArgs[i].className}, actual ${handlerArgs[i].className} ($handlerDesc)"
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
        for (value in extras) emitValue(list, value, method, locals)
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            )
        )
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
        for (value in extras) emitValue(list, value, method, locals)
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, handlerOwner, handlerMethod, handlerDesc, false))
        return list
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
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}"
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
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}"
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
            System.err.println(
                "[injection] the target call ${invoke.owner}.${invoke.name}${invoke.desc} of ModifyArgs has no arguments: ${method.name}"
            )
            return null
        }
        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val ret = Type.getReturnType(handlerDesc)
        if (ret != Type.VOID_TYPE) {
            throw InjectionError(
                "[injection] the handler of ModifyArgs must return void (the modified arguments are read back by the engine): $handlerDesc"
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
                }
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
        for (value in extras) emitValue(list, value, method, locals)
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            )
        )

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
                "[injection] a value-modifying handler must not call back the handle (HandlerVariant must be NOTIFY): ${payload.desc}"
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
                "[injection] the handler of ModifyVariable must not return void (it must return the new value): $handlerDesc"
            )
        }
        if (handler.argumentTypes.isEmpty() || handler.argumentTypes[0] != type) {
            throw InjectionError(
                "[injection] the handler of ModifyVariable must be shaped (T[, captures...])T: " +
                    "the first parameter should equal the return type ${type.className}, actual " +
                    "${handler.argumentTypes.firstOrNull()?.className ?: "no parameters"} ($handlerDesc)"
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
                    " ($handlerDesc)"
            )
        }
        // The write-back **widens** that slot's type to the type declared by the handler (the JVM frames count the value
        // stored by `ASTORE`). If later code still uses the variable as its original type, class definition fails
        // with `VerifyError` — a constraint of the target code itself (same as Mixin), but spelling it out ahead of
        // time saves a lot of debugging.
        if (actual != null && actual.descriptor != type.descriptor &&
            (type.sort == Type.OBJECT || type.sort == Type.ARRAY)
        ) {
            System.err.println(
                "[injection] ModifyVariable writes ${type.descriptor} back to slot $slot (originally ${actual.descriptor}): " +
                    "that slot's type in the following code is widened to ${type.descriptor}; if it is still used as the original type it will VerifyError. " +
                    "(${method.name}${method.desc}; make the handler return the original type, or confirm the code after no longer uses it as the original type)"
            )
        }

        val list = InsnList()
        if (instance) list.add(VarInsnNode(Opcodes.ALOAD, 0)) // receiver (target instance)
        list.add(VarInsnNode(Boxing.loadOpcode(type), slot))
        if (!emitValues(list, extras, method, locals)) return null
        list.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner, handlerMethod, handlerDesc, false,
            )
        )
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
                        "${invoke.owner}.${invoke.name}${invoke.desc} has only ${targetArgs.size} arguments"
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
            }
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
            0, emptyArray<Any>()
        )
    }

    /** Emits the value-loading instructions one by one; voids the whole payload if any value cannot be loaded (returns false). */
    private fun emitValues(list: InsnList, values: List<DslValue>, method: MethodNode, locals: AnchorLocals?): Boolean {
        for (value in values) {
            if (!emitValue(list, value, method, locals)) return false
        }
        return true
    }

    /**
     * Emits one value-loading instruction.
     *
     * Returns `false` = this value **cannot be loaded** (reason already printed), and the caller must abandon the
     * whole injection — pushing one argument fewer would produce a `VerifyError` at class definition, which is far
     * harder to trace than "skip this injection + hit-count warning" (`require(n)` can turn it into an explicit
     * startup failure).
     */
    private fun emitValue(list: InsnList, value: DslValue, method: MethodNode, locals: AnchorLocals?): Boolean {
        when (value) {
            is DslValue.This -> {
                if ((method.access and Opcodes.ACC_STATIC) != 0) {
                    System.err.println("[injection] This can only be used in instance methods, skipped: ${method.name}${method.desc}")
                    return false
                }
                list.add(VarInsnNode(Opcodes.ALOAD, 0))
            }

            is DslValue.Arg -> {
                val slot = argumentSlot(method, value.index) ?: run {
                    System.err.println("[injection] argument index out of bounds: ${method.name}${method.desc} arg=${value.index}")
                    return false
                }
                list.add(VarInsnNode(loadOpcode(method, value.index), slot))
            }

            is DslValue.Local -> {
                val frame = locals?.frame()
                if (frame == null) {
                    System.err.println(
                        "[injection] cannot read the local variable at the injection point ($value): ${method.name}${method.desc} — " +
                            (locals?.explain()
                                ?: "this payload did not request a local-variable snapshot (engine internal error)")
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
                                    "${method.name}${method.desc} $value — ${resolution.reason}"
                            )
                        }
                        System.err.println(
                            "[injection] local-variable read skipped: ${method.name}${method.desc} $value — ${resolution.reason}"
                        )
                        return false
                    }
                }
            }

            is DslValue.IntVal -> list.add(LdcInsnNode(value.value))
            is DslValue.LongVal -> list.add(LdcInsnNode(value.value))
            is DslValue.Str -> list.add(LdcInsnNode(value.value))
            is DslValue.Cls -> list.add(LdcInsnNode(Type.getObjectType(value.internalName)))
            is DslValue.Null -> list.add(InsnNode(Opcodes.ACONST_NULL))
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
