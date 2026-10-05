package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.transformer.injection.InjectionVerifier.verifyPayload
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Startup self-check for injection rules — only checks decidable **without the target class**, i.e. the
 * rule's own self-consistency: can the handler's host class be resolved, does the method exist and is it
 * `static`; does a `CheckCall` handler return boolean; is a `TransformReturn` handler self-consistent
 * (first param type == return type, neither void); does the `Redirect` handler's method name exist (its
 * descriptor is only known once an anchor matches, so only the name is checked); is `This` used inside a
 * constructor (`<init>`'s HEAD — here `this` isn't initialized yet, so pushing it for a static call is
 * illegal); is the access-flag rule self-consistent (see [verifyAccessRule]).
 * **If it can't be resolved, report only, don't judge**: the handler may come from a class loaded later, so not
 * finding it must not be mistaken for an error. Conversely, **resolvable but genuinely absent** is a definite
 * configuration error — the caller decides whether to fail or warn.
 */
internal object InjectionVerifier {

    private const val CALLBACK_RETURNABLE_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfoReturnable;"

    private const val CALLBACK_INFO_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfo;"

    /** `Args` (the "all arguments" handle) descriptor. */
    private const val ARGS_DESC = "Lorg/ohmyloader/api/mixin/Args;"

    fun verifyRule(target: String, rule: MethodRule, loader: ClassLoader): List<String> {
        val where = "$target :: ${rule.selector.describe()}"
        val problems = mutableListOf<String>()

        // Cross-module properties don't smart-cast ⇒ hoist them into local variables first
        val requireCount = rule.require
        val expectCount = rule.expect
        if (requireCount != null && requireCount < 0) {
            problems += "$where — require must not be negative ($requireCount)"
        }
        if (expectCount != null && expectCount < 0) {
            problems += "$where — expect cannot be negative ($expectCount)"
        }
        if (rule.points.isEmpty()) {
            problems += "$where — the rule has no injection point (write atHead/atTail/beforeCall…)"
        }

        val isConstructor = rule.selector.names.contains("<init>")
        for ([point, payload] in rule.points) {
            problems += verifyAnchor(where, point, isConstructor)
            problems += verifyPayload(where, point, payload, isConstructor, loader)
        }
        return problems
    }

    /**
     * **Startup self-check** for access-flag rules: only rule self-consistency — the target class isn't loaded yet.
     *
     * Whether the member exists, is a nested class, or is an interface can only be decided with the target class;
     * those land at apply time ([AccessApplier]) and surface as "skip this member + don't count".
     */
    fun verifyAccessRule(target: String, rule: AccessRule): List<String> {
        val where = "$target :: ${rule.describe()}"
        val problems = mutableListOf<String>()

        val requireCount = rule.require
        val expectCount = rule.expect
        val allowCount = rule.allow
        if (requireCount != null && requireCount < 0) problems += "$where — require must not be negative ($requireCount)"
        if (expectCount != null && expectCount < 0) problems += "$where — expect cannot be negative ($expectCount)"
        if (allowCount != null && allowCount < 0) problems += "$where — allow cannot be negative ($allowCount)"

        if (rule.visibility == null && !rule.removeFinal) {
            problems += "$where — this rule neither changes visibility nor removes final, so it does nothing at all" +
                " (write makePublic() / makeProtected() / makePackagePrivate() / makePrivate() / removeFinal())"
        }
        if (rule.visibilities.size > 1) {
            problems += "$where — multiple visibilities declared in one rule (${rule.visibilities.joinToString { it.label }}): " +
                "only the last one declared, ${rule.visibility?.label}, takes effect, the earlier ones are mistakes left behind"
        }
        if (rule.kind != MemberKind.CLASS && rule.names.isEmpty()) {
            problems += "$where — the ${rule.kind.label} rule did not give any member name" +
                " (write field(\"name\") / methodAccess(\"name\"))"
        }
        if (rule.kind == MemberKind.METHOD && "<clinit>" in rule.names) {
            problems += "$where — <clinit> is invoked by the JVM itself, so its visibility is meaningless to any caller"
        }
        // A member name can never contain descriptor characters. If they appear, the descriptor was almost certainly
        // written as a **positional argument** (`field("proxy", "Ljava/net/Proxy;")` — `desc` comes after the vararg,
        // so the second positional arg is treated as another name) ⇒ the rule never matches any member, and silently.
        val misplaced = rule.names.filter { name -> name.any { it in "()[];/" } }
        if (misplaced.isNotEmpty()) {
            problems += "$where — ${misplaced.joinToString()} appeared in a member name: it looks like a **descriptor** " +
                "was written as a positional argument (the member name is vararg; the descriptor must be written as desc = \"…\")"
        }
        rule.desc?.let { problems += verifyAccessDesc(where, rule.kind, it) }
        return problems
    }

    /**
     * Descriptor-form checking.
     *
     * A mistyped descriptor doesn't raise any error, it just **never matches** any member — exactly the
     * "changed the wrong thing but didn't crash" kind of failure, so it's blocked here: fields want a type
     * descriptor, methods want a method descriptor, classes want no descriptor.
     */
    private fun verifyAccessDesc(where: String, kind: MemberKind, desc: String): List<String> = when (kind) {
        MemberKind.CLASS -> listOf("$where — a class rule does not accept a descriptor ($desc)")

        MemberKind.FIELD -> when (val type = runCatching { Type.getType(desc) }.getOrNull()) {
            null -> listOf("$where — field descriptor cannot be parsed: $desc (e.g. I / Ljava/net/Proxy;)")
            else -> when {
                type.sort == Type.VOID -> listOf("$where — field descriptor cannot be void: $desc")
                type.sort == Type.METHOD -> listOf("$where — a field wants a type descriptor, but a method descriptor was written: $desc")
                type.descriptor != desc ->
                    listOf("$where — non-canonical field descriptor: $desc (canonical form is ${type.descriptor})")

                else -> emptyList()
            }
        }

        MemberKind.METHOD -> {
            // `Type.getMethodType` only wraps the string as-is into a sort=METHOD object, **without parsing**
            // (`getMethodType("I")` quietly gives sort=METHOD, descriptor="I"),
            // so a real parse is required here: getArgumentTypes follows descriptor syntax and throws on errors.
            val canonical = runCatching {
                val args = Type.getArgumentTypes(desc)
                val ret = Type.getReturnType(desc)
                Type.getMethodDescriptor(ret, *args)
            }.getOrNull()
            if (canonical != desc) {
                listOf("$where — method descriptor cannot be parsed or is non-canonical: $desc (e.g. ()V / (I)Ljava/lang/String;)")
            } else {
                emptyList()
            }
        }
    }

    /**
     * Self-consistency of the anchor itself.
     *
     * Only the "independent of the target method" kind can be decided here: whether the ordinal is negative,
     * whether `CONSTANT`'s value type can be matched, whether `NEW` gives a qualifier, and whether an entry-class
     * anchor is used in a constructor.
     */
    private fun verifyAnchor(where: String, point: InjectionPoint, isConstructor: Boolean): List<String> {
        val problems = mutableListOf<String>()
        val ordinal = when (point) {
            is InjectionPoint.Return -> point.ordinal
            is InjectionPoint.Call -> point.ordinal
            is InjectionPoint.FieldAccess -> point.ordinal
            is InjectionPoint.NewInstance -> point.ordinal
            is InjectionPoint.Constant -> point.ordinal
            is InjectionPoint.Store -> point.ordinal
            is InjectionPoint.Load -> point.ordinal
            else -> null
        }
        if (ordinal != null && ordinal < 0) {
            problems += "$where — ordinal of ${AnchorResolver.describe(point)} cannot be negative ($ordinal)" +
                " (omit it to hit all)"
        }
        when (point) {
            is InjectionPoint.Constant -> {
                val value = point.value
                val ok = value == null ||
                    value is Int || value is Long || value is Float ||
                    value is Double || value is String || value is Type
                if (!ok) {
                    problems += "$where — CONSTANT only supports Int/Long/Float/Double/String/" +
                        "org.objectweb.asm.Type/null, actual ${value.javaClass.name}"
                }
            }

            is InjectionPoint.NewInstance -> {
                if (point.owner == null && point.desc == null) {
                    problems += "$where — NEW gave no qualifier (owner and desc are both null), " +
                        "it would hit every new in the method — give at least an owner"
                }
            }

            is InjectionPoint.FieldAccess -> {
                val allowed = setOf(
                    Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.GETSTATIC, Opcodes.PUTSTATIC,
                )
                if (point.opcode != null && point.opcode !in allowed) {
                    problems += "$where — FIELD opcode can only be GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC, " +
                        "actual ${point.opcode}"
                }
            }

            // Local-variable read/write anchors: structurally identical to fields, sharing the same judgment
            is InjectionPoint.Store -> problems += verifyVarAnchor(
                where, point.index, point.type, point.localOrdinal, point.argsOnly,
            )

            is InjectionPoint.Load -> problems += verifyVarAnchor(
                where, point.index, point.type, point.localOrdinal, point.argsOnly,
            )

            else -> Unit
        }
        // In a constructor, "method head"-style short-circuit payloads are only legal with CTOR_HEAD (see earlyReturnProblem)
        if (isConstructor && point is InjectionPoint.Head) {
            problems += "$where — do not use the HEAD anchor in a constructor: it runs before super()/this(), " +
                "when this is not yet initialized; if the injected block uses this it will VerifyError. Use CTOR_HEAD instead"
        }
        return problems
    }

    /**
     * Self-consistency of the local-variable read/write anchors ([InjectionPoint.Store] / [`Load`][InjectionPoint.Load]).
     *
     * Both anchors have structurally identical fields, so they share one judgment — one change applies to both.
     */
    private fun verifyVarAnchor(
        where: String,
        index: Int?,
        type: String?,
        localOrdinal: Int?,
        argsOnly: Boolean,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (index != null && index < 0) {
            problems += "$where — the local-variable slot cannot be negative (index=$index)"
        }
        if (localOrdinal != null && localOrdinal < 0) {
            problems += "$where — localOrdinal cannot be negative ($localOrdinal; 0 = the first slot matching the type)"
        }
        if (localOrdinal != null && type == null) {
            problems += "$where — localOrdinal means \"the Nth slot matching a type\", so type must be written too"
        }
        if (localOrdinal != null && index != null) {
            problems += "$where — index already pins the slot, so localOrdinal is redundant (only one can be given)"
        }
        if (type != null) {
            val parsed = runCatching { Type.getType(type) }.getOrNull()
            if (parsed == null || parsed == Type.VOID_TYPE || parsed.sort == Type.METHOD) {
                problems += "$where — type is not a legal value-type descriptor: \"$type\""
            }
        }
        if (index == null && type == null && !argsOnly) {
            problems += "$where — the local-variable read/write anchor gave no qualifier (none of index / type / argsOnly written), " +
                "it would hit **every** read/write in the method — unless you truly want all of them, write at least one" +
                " (when you want all of them, pin the hit count with require/allow)"
        }
        return problems
    }

    /** Expression anchors that produce a value: calls / field reads / constant loads. */
    private fun isExpressionAnchor(core: InjectionPoint): Boolean =
        core is InjectionPoint.Call || core is InjectionPoint.FieldAccess || core is InjectionPoint.Constant

    /**
     * Shape check for merged (instance) value-modifying handlers. [valueOffset] is always 0: after the
     * handler is merged into the target class it's an **instance method**, the receiver is `this` provided
     * by the injection point's `INVOKEVIRTUAL` and **doesn't enter the parameter list**, so the value
     * parameter starts at 0 (the only exception is `@Redirect`, whose first parameter is the **receiver of
     * the replaced call**, see the REDIRECT branch of [verifyPayload]). Putting the receiver into the
     * parameter list fails silently: validation passes against the **declared** reference slot while the
     * engine infers a value, and only at class definition does it explode as a `VerifyError` — so these
     * offsets must match the engine's.
     */
    private fun instanceModifyShapeProblem(
        where: String,
        annotation: String,
        desc: String,
        valueOffset: Int,
        valueArity: Int,
        valueDesc: String? = null,
    ): List<String> {
        val type = runCatching { Type.getMethodType(desc) }.getOrNull()
            ?: return listOf("$where — handler descriptor of $annotation cannot be parsed: $desc")
        val args = type.argumentTypes
        val problems = mutableListOf<String>()
        if (args.size < valueOffset + valueArity) {
            problems += "$where — too few handler parameters for $annotation: the value needs $valueArity parameters" +
                " (the receiver is provided by `this` and not written into the parameter list): $desc"
            return problems
        }
        val value = args[valueOffset]
        if (type.returnType == Type.VOID_TYPE && annotation != "@ModifyArgs") {
            problems += "$where — the handler of $annotation must not return void (it must return the modified value): $desc"
        }
        // Shape: the type at the value position must equal the return type (@ModifyArgs excepted: returns void, value position is Object[])
        if (annotation == "@ModifyArgs") {
            if (value.descriptor != "[Ljava/lang/Object;") {
                problems += "$where — the first parameter of an instance-form @ModifyArgs handler must be Object[]" +
                    " (the engine passes the raw array and reads it back from the same array after the call): $desc"
            }
        } else if (value != type.returnType) {
            problems += "$where — parameter ${valueOffset + 1} (the value) of $annotation's instance handler must equal the " +
                "return type: expected ${type.returnType.className}, actual ${value.className} ($desc)"
        }
        if (valueDesc != null && value.descriptor != valueDesc) {
            problems += "$where — the value-parameter type of $annotation should be $valueDesc, actual ${value.descriptor}"
        }
        return problems
    }

    /** Extra constraints on entry-style short-circuit payloads: in a constructor they must land on CTOR_HEAD (an early return before the delegating call is illegal). */
    private fun earlyReturnProblem(
        where: String,
        point: InjectionPoint,
        isConstructor: Boolean,
        kind: String,
    ): List<String> =
        if (isConstructor && point is InjectionPoint.Head) {
            listOf(
                "$where — $kind cannot use HEAD in a constructor: returning early before super()/this() is illegal bytecode, " +
                    "use CTOR_HEAD (after the delegating call) instead",
            )
        } else {
            emptyList()
        }

    private fun verifyPayload(
        where: String,
        point: InjectionPoint,
        payload: Payload,
        isConstructor: Boolean,
        loader: ClassLoader,
    ): List<String> {
        val problems = mutableListOf<String>()
        when (payload) {
            is Payload.StaticCall -> {
                problems += checkHandler(where, "StaticCall", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.HandlerCall -> {
                // With **class merging** the handler lives inside the target class and can't be resolved at compile
                // time (the target isn't loaded yet), so existence isn't checked here — its shape is guaranteed by the
                // scanning phase (annotation shape) + the merge phase (the method isn't renamed).
                when (payload.kind) {
                    HandlerKind.INJECT -> {
                        if (payload.variant != HandlerVariant.NOTIFY && !point.isMethodHead()) {
                            problems += "$where — the CANCELLABLE/RETURNABLE forms of HandlerCall only support method-entry anchors" +
                                " (HEAD / CTOR_HEAD), now ${AnchorResolver.describe(point)}"
                        }
                        val handleDesc = if (payload.variant == HandlerVariant.RETURNABLE) {
                            CALLBACK_RETURNABLE_DESC
                        } else {
                            CALLBACK_INFO_DESC
                        }
                        if (!payload.desc.endsWith("$handleDesc)V")) {
                            problems += "$where — the descriptor of HandlerCall must end ${handleDesc})V" +
                                " (captured arguments + handle, returning void): actual ${payload.desc}"
                        }
                    }

                    // Value-modifying kinds have anchor requirements **identical one-by-one** to their external static form
                    // (anchor semantics unchanged), while the handler shape gains one receiver — dispatch on kind below reuses the same rule.
                    HandlerKind.MODIFY_ARG -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        if (point.core !is InjectionPoint.Call) {
                            problems += "$where — the @At of @ModifyArg must be a method call (INVOKE), " +
                                "now ${AnchorResolver.describe(point)}"
                        }
                        problems += instanceModifyShapeProblem(where, "@ModifyArg", payload.desc, 0, 1)
                    }

                    HandlerKind.MODIFY_ARGS -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        if (point.core !is InjectionPoint.Call) {
                            problems += "$where — the @At of @ModifyArgs must be a method call (INVOKE), " +
                                "now ${AnchorResolver.describe(point)}"
                        }
                        problems += instanceModifyShapeProblem(
                            where,
                            "@ModifyArgs",
                            payload.desc,
                            0,
                            1,
                            "[Ljava/lang/Object;",
                        )
                    }

                    HandlerKind.MODIFY_CONST -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        problems += instanceModifyShapeProblem(where, "@ModifyConstant", payload.desc, 0, 1)
                    }

                    HandlerKind.MODIFY_VAR -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        if (point.core !is InjectionPoint.Store && point.core !is InjectionPoint.Load) {
                            problems += "$where — the @At of @ModifyVariable must be STORE (after the write) or " +
                                "LOAD (before the read), now ${AnchorResolver.describe(point)}"
                        }
                        problems += instanceModifyShapeProblem(where, "@ModifyVariable", payload.desc, 0, 1)
                    }

                    HandlerKind.MODIFY_RETURN -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        val core = point.core
                        if (core !is InjectionPoint.Return && core !is InjectionPoint.FinalReturn) {
                            problems += "$where — the @At of @ModifyReturnValue must be RETURN (every return) " +
                                "or TAIL (the last return) — the return value is consumed there by RETURN itself, " +
                                "now ${AnchorResolver.describe(point)}"
                        }
                        problems += instanceModifyShapeProblem(where, "@ModifyReturnValue", payload.desc, 0, 1)
                    }

                    HandlerKind.MODIFY_EXPR_VALUE -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        val core = point.core
                        if (!isExpressionAnchor(core)) {
                            problems += "$where — the @At of @ModifyExpressionValue must **produce a value** " +
                                "expression (INVOKE / FIELD / CONSTANT), currently ${AnchorResolver.describe(point)}"
                        } else if (!point.after) {
                            problems += "$where — @ModifyExpressionValue takes the value the expression **produces**, " +
                                "so the anchor must be after it (when the value is not yet on the stack there is nothing to change): " +
                                "currently ${AnchorResolver.describe(point)}"
                        }
                        problems += instanceModifyShapeProblem(where, "@ModifyExpressionValue", payload.desc, 0, 1)
                    }

                    HandlerKind.REDIRECT -> {
                        if (payload.variant != HandlerVariant.NOTIFY) {
                            problems += "$where — a value-modifying handler must not call back the handle (variant should be NOTIFY): ${payload.desc}"
                        }
                        val call = point.core as? InjectionPoint.Call
                        if (call == null) {
                            problems += "$where — the @At of @Redirect must be a method call (INVOKE / INVOKE_ASSIGN), " +
                                "currently ${AnchorResolver.describe(point)}"
                        } else if (call.isStaticCall()) {
                            problems += "$where — the @Redirect target is a **static call** " +
                                "${call.owner}.${call.name}${call.desc}: an instance handler has no receiver to receive it, " +
                                "write the handler as static"
                        } else if (call.owner != null && call.desc != null) {
                            // Parameter list = the replaced call's **receiver** + its arguments (+ optional captures).
                            //
                            // Note the receiver is `call.owner` (the replaced object), **not** the target class:
                            // the handler's `this` is the target instance, while "the receiver of the replaced call"
                            // is something only the handler can obtain — it needs it to perform that call on its behalf.
                            // The return type stays unchanged.
                            val expected = listOf(Type.getObjectType(call.owner)) +
                                Type.getArgumentTypes(call.desc).toList()
                            val actual = runCatching { Type.getArgumentTypes(payload.desc).toList() }
                                .getOrNull()
                            if (actual == null) {
                                problems += "$where — handler descriptor of @Redirect cannot be parsed: ${payload.desc}"
                            } else if (actual.size < expected.size || actual.take(expected.size) != expected) {
                                problems += "$where — the parameter list of an instance @Redirect handler must start with " +
                                    "\"receiver + the replaced call's arguments\": expected " +
                                    "${expected.joinToString(", ") { it.descriptor }}, " +
                                    "actual ${payload.desc}"
                            }
                        }
                    }
                }
            }

            is Payload.CheckCall -> {
                if (!point.isMethodHead()) {
                    problems += "$where — CheckCall only supports method-entry anchors (HEAD / CTOR_HEAD), " +
                        "currently ${AnchorResolver.describe(point)}"
                }
                problems += earlyReturnProblem(where, point, isConstructor, "CheckCall")
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc != null && desc.returnType != Type.BOOLEAN_TYPE) {
                    problems += "$where — the handler of CheckCall must return boolean, actual ${desc.returnType.className}"
                }
                problems += checkHandler(where, "CheckCall", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.TransformReturn -> {
                if (!point.isReturnAnchor()) {
                    problems += "$where — TransformReturn needs a return anchor (RETURN / TAIL), " +
                        "currently ${AnchorResolver.describe(point)}"
                }
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc != null) {
                    val args = desc.argumentTypes
                    val ret = desc.returnType
                    if (ret == Type.VOID_TYPE) {
                        problems += "$where — the handler of TransformReturn must not return void (it must return the processed value)"
                    } else if (args.isEmpty() || args[0] != ret) {
                        problems += "$where — the handler of TransformReturn must be shaped (R[, extras])R, " +
                            "the first parameter should be the return type ${ret.className}, actual ${args.firstOrNull()?.className ?: "no parameters"} (desc=${payload.desc})"
                    }
                }
                problems += checkHandler(where, "TransformReturn", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.CancellableReturn -> {
                if (!point.isMethodHead()) {
                    problems += "$where — CancellableReturn only supports method-entry anchors (HEAD / CTOR_HEAD), " +
                        "currently ${AnchorResolver.describe(point)}"
                }
                problems += earlyReturnProblem(where, point, isConstructor, "CancellableReturn")
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc == null) {
                    problems += "$where — the bridge descriptor of CancellableReturn cannot be parsed: ${payload.desc}"
                } else {
                    if (desc.returnType != Type.VOID_TYPE) {
                        problems += "$where — the bridge of CancellableReturn should return void (the short-circuit is generated by the engine), " +
                            "actual return ${desc.returnType.className}"
                    }
                    val last = desc.argumentTypes.lastOrNull()
                    if (last == null || last.descriptor != CALLBACK_RETURNABLE_DESC) {
                        problems += "$where — the last parameter of the CancellableReturn bridge descriptor must be " +
                            "CallbackInfoReturnable, actual ${last?.className ?: "no parameters"} (desc=${payload.desc})"
                    } else if (desc.argumentTypes.size < 2 ||
                        desc.argumentTypes[desc.argumentTypes.size - 2] != Type.INT_TYPE
                    ) {
                        problems += "$where — the CancellableReturn bridge descriptor should take an int (handler id) right before the handle, " +
                            "actual desc=${payload.desc}"
                    }
                }
                problems += checkHandler(
                    where,
                    "CancellableReturn",
                    payload.owner,
                    payload.method,
                    payload.desc,
                    loader,
                )
            }

            is Payload.ModifyArg -> {
                if (point.core !is InjectionPoint.Call) {
                    problems += "$where — ModifyArg requires a method-call anchor (beforeCall/afterCall), " +
                        "now ${AnchorResolver.describe(point)}"
                }
                if (payload.index < -1) {
                    problems += "$where — ModifyArg's index cannot be less than -1 (-1 = auto-locate by type), actual ${payload.index}"
                }
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc == null) {
                    problems += "$where — the handler descriptor of ModifyArg cannot be parsed: ${payload.desc}"
                } else {
                    if (desc.returnType == Type.VOID_TYPE) {
                        problems += "$where — the handler of ModifyArg must return a value (the modified argument), cannot be void"
                    }
                    if (desc.argumentTypes.size <= payload.extras.size) {
                        problems += "$where — the handler of ModifyArg must have at least one parameter (the modified argument)"
                    }
                }
                problems += checkHandler(where, "ModifyArg", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.ModifyArgs -> {
                if (point.core !is InjectionPoint.Call) {
                    problems += "$where — ModifyArgs requires a method-call anchor (beforeCall/afterCall), " +
                        "now ${AnchorResolver.describe(point)}"
                }
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc == null) {
                    problems += "$where — the handler descriptor of ModifyArgs cannot be parsed: ${payload.desc}"
                } else {
                    if (desc.returnType != Type.VOID_TYPE) {
                        problems += "$where — the handler of ModifyArgs must return void (the engine reads the modified arguments back), " +
                            "actual ${desc.returnType.className}"
                    }
                    if (desc.argumentTypes.firstOrNull()?.descriptor != ARGS_DESC) {
                        problems += "$where — the first parameter of ModifyArgs' handler must be $ARGS_DESC (the argument list), " +
                            "actual ${desc.argumentTypes.firstOrNull()?.className ?: "no parameters"}"
                    }
                }
                problems += checkHandler(where, "ModifyArgs", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.ModifyConstant -> {
                val core = point.core
                if (core !is InjectionPoint.Constant || !core.after) {
                    problems += "$where — ModifyConstant requires an afterConstant(...) anchor" +
                        " (only after the constant is loaded is the value on the stack, ready for the handler), now ${
                            AnchorResolver.describe(
                                point,
                            )
                        }"
                }
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc == null) {
                    problems += "$where — the handler descriptor of ModifyConstant cannot be parsed: ${payload.desc}"
                } else if (desc.returnType == Type.VOID_TYPE) {
                    problems += "$where — the handler of ModifyConstant cannot return void (it must return the modified value)"
                } else if (desc.argumentTypes.firstOrNull() != desc.returnType) {
                    problems += "$where — the handler of ModifyConstant must be shaped (T[, extras...])T: " +
                        "the first parameter should equal the return type ${desc.returnType.className}"
                }
                problems += checkHandler(where, "ModifyConstant", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.ModifyExpressionValue -> {
                val core = point.core
                if (!isExpressionAnchor(core)) {
                    problems += "$where — ModifyExpressionValue requires an expression anchor that **produces a value**" +
                        " (afterCall / afterField / afterConstant), now ${AnchorResolver.describe(point)}"
                } else if (!point.after) {
                    problems += "$where — ModifyExpressionValue changes the value the expression **produces**, " +
                        "so the anchor must come after it (at beforeXxx the value is not yet on the stack): now ${
                            AnchorResolver.describe(
                                point,
                            )
                        }"
                }
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc == null) {
                    problems += "$where — the handler descriptor of ModifyExpressionValue cannot be parsed: ${payload.desc}"
                } else if (desc.returnType == Type.VOID_TYPE) {
                    problems += "$where — the handler of ModifyExpressionValue cannot return void (it must return the modified value)"
                } else if (desc.argumentTypes.firstOrNull() != desc.returnType) {
                    problems += "$where — the handler of ModifyExpressionValue must be shaped (T[, extras...])T: " +
                        "the first parameter should equal the return type ${desc.returnType.className}"
                }
                problems += checkHandler(
                    where, "ModifyExpressionValue", payload.owner, payload.method, payload.desc, loader,
                )
            }

            is Payload.ModifyVariable -> {
                val core = point.core
                if (core !is InjectionPoint.Store && core !is InjectionPoint.Load) {
                    problems += "$where — ModifyVariable requires a local-variable write/read anchor" +
                        " (afterStore / beforeLoad), now ${AnchorResolver.describe(point)}"
                }
                val desc = runCatching { Type.getMethodType(payload.desc) }.getOrNull()
                if (desc == null) {
                    problems += "$where — the handler descriptor of ModifyVariable cannot be parsed: ${payload.desc}"
                } else if (desc.returnType == Type.VOID_TYPE) {
                    problems += "$where — the handler of ModifyVariable cannot return void (it must return the modified value)"
                } else if (desc.argumentTypes.firstOrNull() != desc.returnType) {
                    problems += "$where — the handler of ModifyVariable must be shaped (T[, extras...])T: " +
                        "the first parameter should equal the return type ${desc.returnType.className} " +
                        "(the value type is declared by the return type — the engine uses it to pick ILOAD/ALOAD… and to choose the local by type)"
                }
                problems += checkHandler(where, "ModifyVariable", payload.owner, payload.method, payload.desc, loader)
            }

            is Payload.Redirect -> {
                // The descriptor must equal the replaced call's, which is only known once an anchor matches — so only the name is checked here
                val handlerClass = resolve(payload.handlerOwner, loader)
                if (handlerClass == null) {
                    problems += noteUnresolved(where, "Redirect", payload.handlerOwner)
                } else if (allMethods(handlerClass, payload.handlerMethod, null).isEmpty()) {
                    problems += "$where — the handler method of Redirect does not exist: " +
                        "${
                            payload.handlerOwner.replace(
                                '/',
                                '.',
                            )
                        }.${payload.handlerMethod} (no descriptor with that name)"
                }
            }

            is Payload.Raw -> Unit // escape hatch: no static judgment
        }

        if (isConstructor) {
            val usesThis = payload.dslValues().any { it is DslValue.This }
            // CTOR_HEAD is the only safe landing point: it sits after super()/this(), where this is already initialized
            if (usesThis && point !is InjectionPoint.ConstructorHead) {
                problems += "$where — This cannot be used in a constructor: `this` is uninitialized before super()/this(), " +
                    "pushing it for a static call yields illegal bytecode (VerifyError). " +
                    "To use this in a constructor, switch the anchor to CTOR_HEAD"
            }
        }

        // Local-variable reads: only the **statically decidable** part can be checked here — non-negative slot,
        // valid type descriptor, "at least one of index / type". Whether that slot or type actually exists requires
        // dataflow analysis (runtime, per-method); see AnchorLocals / PayloadEmitter.emitValue.
        val locals = payload.dslValues().filterIsInstance<DslValue.Local>()
        for ((index, type1, ordinal) in locals) {
            if (index == null && type1 == null) {
                problems += "$where — Local must give at least one of index or type (otherwise the local variable cannot be located)"
            }
            if (index != null && index < 0) {
                problems += "$where — Local's slot cannot be negative (index=${index})"
            }
            if (ordinal < 0) {
                problems += "$where — Local's ordinal cannot be negative (ordinal=${ordinal})"
            }
            if (type1 != null) {
                val type = runCatching { Type.getType(type1) }.getOrNull()
                if (type == null || type == Type.VOID_TYPE || type.sort == Type.METHOD) {
                    problems += "$where — Local's type is not a legal value-type descriptor: \"$type1\""
                }
            }
        }
        return problems
    }

    /** Handler existence + static judgment. When the host class can't be resolved, leave only one hint. */
    private fun checkHandler(
        where: String,
        kind: String,
        owner: String,
        name: String,
        desc: String,
        loader: ClassLoader,
    ): List<String> {
        val clazz = resolve(owner, loader) ?: return listOf(noteUnresolved(where, kind, owner))
        val matching = allMethods(clazz, name, desc)
        if (matching.isNotEmpty()) {
            // Note: Kotlin's @JvmStatic generates an instance method **and** a static bridge method with the same name/descriptor,
            // and declaredMethods' order isn't guaranteed — so what's judged is whether "the one bindable by INVOKESTATIC
            // exists", not just the first match.
            return if (matching.any { Modifier.isStatic(it.modifiers) }) {
                emptyList()
            } else {
                listOf("$where — the handler of $kind must be static: ${clazz.name}.$name$desc (injected via INVOKESTATIC)")
            }
        }
        val byName = allMethods(clazz, name, null)
        return if (byName.isEmpty()) {
            listOf("$where — the handler of $kind does not exist: ${clazz.name}.$name$desc")
        } else {
            listOf(
                "$where — the descriptor of $kind's handler does not match: expected $desc, " +
                    "actually present ${byName.map { Type.getMethodDescriptor(it) }.distinct().joinToString(", ")}",
            )
        }
    }

    /** Host class can't be resolved — only a hint, not judged as an error (may load later). */
    private fun noteUnresolved(where: String, kind: String, owner: String): String =
        "$where — the host class of the $kind handler is not loaded yet, skipping verification: ${
            owner.replace(
                '/',
                '.',
            )
        }"

    private fun resolve(owner: String, loader: ClassLoader): Class<*>? =
        try {
            Class.forName(owner.replace('/', '.'), false, loader)
        } catch (_: Throwable) {
            null
        }

    /** Collects matching methods up the superclass chain; when [desc] is null, collect by name only (for diagnostics). */
    private fun allMethods(clazz: Class<*>, name: String, desc: String?): List<Method> {
        val found = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != name) continue
                if (desc != null && Type.getMethodDescriptor(method) != desc) continue
                found += method
            }
            current = current.superclass
        }
        return found
    }
}
