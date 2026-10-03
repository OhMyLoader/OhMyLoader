package org.ohmyloader.core.transformer.injection

import org.ohmyloader.api.OmlLog
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.transformer.TransformContext

/**
 * All the [DslValue]s carried by a payload (the actual/supplementary arguments the engine pushes on the stack).
 *
 * The validation layer needs to "look at every value in turn", but each payload kind names its field differently
 * (args / extras / bridgeArgs), so they are centralized here — when adding a payload kind, only this spot changes
 * and no validation is missed. Note that `Redirect` carries no values and `Raw` is written by the user directly,
 * so both are empty lists.
 */
internal fun Payload.dslValues(): List<DslValue> = when (this) {
    is Payload.StaticCall -> args
    is Payload.CheckCall -> args
    is Payload.CancellableReturn -> bridgeArgs
    is Payload.TransformReturn -> extras
    is Payload.ModifyArg -> extras
    is Payload.ModifyArgs -> extras
    is Payload.ModifyConstant -> extras
    is Payload.ModifyExpressionValue -> extras
    is Payload.ModifyVariable -> extras
    // For every HandlerCall form (entry- and value-modifying alike) the values all live in captures:
    // the value-modifying discriminator (which argument / slot) is a **scalar**, carried by index / slot, not a DslValue.
    is Payload.HandlerCall -> captures
    is Payload.Redirect -> emptyList()
    is Payload.Raw -> emptyList()
}

/**
 * Whether the payload uses local-variable reads ([DslValue.Local]).
 *
 * Dataflow analysis is a **whole-method-level** cost, so it only runs when actually needed — most rules
 * (notifications, return-value rewrites, constant rewrites…) never touch local variables and shouldn't pay for it.
 */
internal fun Payload.usesLocals(): Boolean = dslValues().any { it is DslValue.Local }

/**
 * The engine-side rule set: applies a [RuleSet] to target classes, and is responsible for startup self-checking.
 *
 * The rule **model** and the DSL both live in `org.ohmyloader.api.inject` — this file contains only what the
 * engine does: weaving rules into bytecode ([transform]) and surfacing "broken rule" problems early ([verify]).
 */

class InjectionSpec internal constructor(
    private val ruleSet: RuleSet,
) {
    /** All the classes this spec cares about, for the fast check in [org.ohmyloader.core.transformer.IClassTransformer.appliesTo]. */
    val targets: Set<String> = ruleSet.targets

    fun appliesTo(internalName: String): Boolean = internalName in targets

    /**
     * Startup self-check: surfaces "broken rule" problems early. Returns the issue list; empty = pass.
     *
     * Only performs checks that don't require the target class (handler existence/shape/static, require sanity, etc.),
     * see [InjectionVerifier].
     *
     * @param classLoader the runtime loader — must be `OMLClassLoader`, otherwise runtime-generated
     *   handlers (like Mixin bridge classes) can't be resolved and the self-check degrades to "skip validation".
     *   Defaults to this class's loader (sufficient for tests / when there is no custom loader).
     */
    fun verify(classLoader: ClassLoader? = null): List<String> {
        val loader = classLoader ?: javaClass.classLoader
        val problems = mutableListOf<String>()
        for ([target, rules] in ruleSet.classes) {
            for (rule in rules.methods) problems += InjectionVerifier.verifyRule(target, rule, loader)
            for (rule in rules.accessRules) problems += InjectionVerifier.verifyAccessRule(target, rule)
        }
        return problems
    }

    /** Applies all rules to a single class; returns whether any change occurred. */
    fun transform(context: TransformContext): Boolean {
        val classRules = ruleSet.classes[context.internalName] ?: return false
        var changed = false

        // Access flags land first: they are **declaration-level**, and body injection and class merging both build
        // on the final flags. Only flag bits change, no instructions, so no stack frames are involved; the class is
        // still fully rewritten on write-back, but the bytes differ only in those flag bits.
        for (rule in classRules.accessRules) {
            val result = AccessApplier.apply(context.node, rule)
            result.problems.forEach { OmlLog.error("Injection", it) }
            reportHits(
                "${context.internalName} :: ${rule.describe()}",
                rule.require, rule.expect, rule.allow, rule.optional, result.hits,
                notes = result.problems,
            )
            if (result.changes.isNotEmpty()) changed = true
        }

        for (rule in classRules.methods) {
            val named = context.node.methods.filter { rule.selector.matches(it.name, it.desc) }
            // Abstract/native methods have no instruction body (`instructions.size() == 0`), so injection has nothing to target
            val injectable = named.filter { it.instructions.size() > 0 }
            var hits = 0
            for (method in injectable) {
                hits += applyToMethod(context.internalName, context.node.superName, method, rule.points)
            }
            reportHits(
                "${context.internalName} :: ${rule.selector.describe()}",
                rule.require, rule.expect, rule.allow, rule.optional, hits,
                // The diagnosis scans the instruction table, so it is only computed when this rule actually needs to report
                diagnosis = if (hits == 0) MissDiagnosis.explain(
                    context.node,
                    rule,
                    named,
                    injectable
                ) else emptyList(),
            )
            if (hits > 0) changed = true
        }
        return changed
    }

    /**
     * `require(n)`: **at least** n times, throwing [InjectionError] when fewer (hard failure);
     * `allow(n)`: **at most** n times, throwing [InjectionError] when more; `expect(n)`: expects exactly n
     * times, a mismatch only warns; none declared: warn on 0 hits (silenced by `optional()`). Names and
     * semantics match Mixin's `@Inject(require/allow/expect = n)` — `require` is a **lower bound**, not
     * "exactly"; to express "exactly n times", write `allow(n)` too.
     * A miss is hard-failable because a wrongly written rule (name/descriptor/anchor mismatch) does not
     * crash the game — it just silently skips doing one thing, the hardest class of bug to catch.
     * [notes] gives the reasons "some targets were skipped"; they hit both stderr and the exception message,
     * since a hard failure aborts startup and the exception message is the one the caller is guaranteed to see.
     */
    private fun reportHits(
        where: String,
        require: Int?,
        expect: Int?,
        allow: Int?,
        optional: Boolean,
        hits: Int,
        notes: List<String> = emptyList(),
        diagnosis: List<String> = emptyList(),
    ) {
        val block = notesBlock(notes, diagnosis)
        when {
            require != null && hits < require ->
                throw InjectionError(
                    "Rule hit count too low: $where declares require=$require (minimum), but got $hits$block"
                )

            allow != null && hits > allow ->
                throw InjectionError(
                    "Rule hit count exceeded: $where declares allow=$allow (maximum), but got $hits" +
                        " (the selector may be written too broadly, hitting an unexpected place)$block"
                )

            expect != null && hits != expect ->
                OmlLog.error("Injection", "hit count does not match expect: $where expects $expect, but got $hits$block")

            require == null && allow == null && expect == null && hits == 0 && !optional ->
                OmlLog.error(
                    "Injection",
                    "rule hit nothing: $where" +
                        " (if intentional, mark optional() to silence; if it must hit, mark require(1) to hard-fail)$block"
                )
        }
    }

    /**
     * Appends the notes ("some targets were skipped" reasons) and diagnosis ("why nothing matched", see [MissDiagnosis]),
     * indented, to the end of a message.
     *
     * Line-like indentation — one rule may carry several anchors, and the diagnosis itself is multi-line.
     */
    private fun notesBlock(notes: List<String>, diagnosis: List<String>): String {
        val all = notes + diagnosis
        if (all.isEmpty()) return ""
        return "\n" + all.joinToString("\n") { entry -> entry.lines().joinToString("\n") { "  $it" } }
    }

    /**
     * Inserts the injection block — first making the "stack frame covering the anchor" declare the local variables
     * it reads (see [FrameRepair]).
     *
     * Returns `false` = the frame neither declared that slot nor was it written between the frame and the anchor,
     * and dataflow cannot infer a type: in that case **do not insert** and explain why. Better to not inject than to
     * produce a class that fails verification — that would fail the whole class load at a much higher cost (and the
     * hit count decreases by one, which is then reported by require/expect).
     */
    private fun insertBlock(
        method: MethodNode,
        anchor: AbstractInsnNode,
        block: InsnList,
        after: Boolean,
        frames: MethodFrames?,
    ): Boolean {
        when (val repair = FrameRepair.ensure(method, anchor, block, after, frames)) {
            is FrameRepair.Result.Nothing -> Unit

            is FrameRepair.Result.Fixed -> OmlLog.error(
                "Injection",
                "the stack frame at the injection point did not declare locals ${repair.slots.joinToString()}," +
                    " restored from dataflow: ${method.name}${method.desc}"
            )

            is FrameRepair.Result.Failed -> {
                OmlLog.error(
                    "Injection",
                    "injection skipped: the stack frame at ${method.name}${method.desc} cannot carry this code — " +
                        repair.reason
                )
                return false
            }
        }
        if (after) method.instructions.insert(anchor, block)
        else method.instructions.insertBefore(anchor, block)
        return true
    }

    private fun applyToMethod(
        owner: String,
        superName: String?,
        method: MethodNode,
        points: List<Pair<InjectionPoint, Payload>>,
    ): Int {
        var inserted = 0
        // Dataflow analysis is a whole-method-level cost; all injection points in one method share a single copy
        // (lazy: only runs when a local-variable read is actually needed; see where AnchorLocals is consumed)
        val frames = MethodFrames(owner, method)
        for ([point, payload] in points) {
            // Collect all anchors first, then process each: processing doesn't affect the node references of other anchors
            val anchors = AnchorResolver.resolve(point, method, owner, superName, frames)
            if (anchors.isEmpty()) {
                OmlLog.error(
                    "Injection",
                    "anchor not found: ${AnchorResolver.describe(point)} in " +
                        "${owner.substringAfterLast('/')}.${method.name}${method.desc}"
                )
                continue
            }
            val needsLocals = payload.usesLocals() || payload is Payload.ModifyVariable
            for (anchor in anchors) {
                val locals = if (needsLocals) frames.at(anchor, point.after) else null
                when (payload) {
                    is Payload.CheckCall -> {
                        if (!point.isMethodHead()) {
                            OmlLog.error(
                                "Injection",
                                "CheckCall only supports method-entry anchors (HEAD / CTOR_HEAD), " +
                                    "currently ${AnchorResolver.describe(point)}: ${method.name}"
                            )
                            continue
                        }
                        if (org.objectweb.asm.Type.getReturnType(method.desc) != org.objectweb.asm.Type.VOID_TYPE) {
                            OmlLog.error(
                                "Injection",
                                "CheckCall only supports void targets: ${method.name}${method.desc}"
                            )
                            continue
                        }
                        val list = PayloadEmitter.emit(payload, method, owner, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames)) continue
                        inserted++
                    }

                    is Payload.CancellableReturn -> {
                        if (!point.isMethodHead()) {
                            OmlLog.error(
                                "Injection",
                                "CancellableReturn only supports method-entry anchors (HEAD / CTOR_HEAD), " +
                                    "currently ${AnchorResolver.describe(point)}: ${method.name}"
                            )
                            continue
                        }
                        if (org.objectweb.asm.Type.getReturnType(method.desc) == org.objectweb.asm.Type.VOID_TYPE) {
                            OmlLog.error(
                                "Injection",
                                "CancellableReturn requires a non-void target (for a void target use CheckCall): " +
                                    "${method.name}${method.desc}"
                            )
                            continue
                        }
                        val list = PayloadEmitter.emit(payload, method, owner, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames)) continue
                        inserted++
                    }

                    is Payload.ModifyArg -> {
                        if (anchor !is MethodInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyArg must be a method call, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}"
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyArg(payload, method, owner, anchor, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames)) continue
                        inserted++
                    }

                    is Payload.ModifyArgs -> {
                        if (anchor !is MethodInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyArgs must be a method call, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}"
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyArgs(payload, method, owner, anchor, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames)) continue
                        inserted++
                    }

                    is Payload.ModifyVariable -> {
                        // The anchor must be a "local-variable write/read instruction": the value being changed lives
                        // in that slot; at any other anchor (say a call) there is no "which variable" to speak of.
                        if (anchor !is VarInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyVariable must be a local-variable write/read instruction" +
                                    " (STORE / LOAD), currently ${AnchorResolver.describe(point)}: ${method.name}"
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyVariable(payload, method, owner, anchor, locals)
                            ?: continue
                        // Store sets after=true (write after the write), Load sets after=false (write before the read) —
                        // both landing points are answered by the anchor itself; handled uniformly here
                        if (!insertBlock(method, anchor, list, point.after, frames)) continue
                        inserted++
                    }

                    is Payload.HandlerCall -> {
                        // Value-modifying kinds have anchor requirements **completely identical** to their external
                        // static form — only "where the handler lives and how it's called" changes; the anchor
                        // semantics don't change one bit. So dispatch on kind is reused here, differing only in landing.
                        when (payload.kind) {
                            HandlerKind.INJECT -> {
                                // Entry kind: the anchor is carried by the rule itself (HEAD / CTOR_HEAD, etc.),
                                // and the landing goes through the common branch
                                val list = PayloadEmitter.emit(payload, method, owner, locals) ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_ARG -> {
                                if (anchor !is MethodInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_ARG) must be a method call, currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}"
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyArg(payload, method, owner, anchor, locals)
                                    ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_ARGS -> {
                                if (anchor !is MethodInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_ARGS) must be a method call, currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}"
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyArgs(payload, method, owner, anchor, locals)
                                    ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_CONST -> {
                                // The constant is already on the stack: its type is given by that loading
                                // instruction, verified against the handler's declared type
                                val value = ProducedValue.requireReadable(
                                    "@ModifyConstant", point, anchor, method
                                )
                                val list = PayloadEmitter.emitInPlaceHandler(
                                    payload, "@ModifyConstant", value, method, owner, locals
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_RETURN -> {
                                // Return value: the value is consumed by `RETURN` itself, so the anchor is
                                // right before `RETURN` — the stack top already is the return value, and
                                // `insertBlock(… point.after = false)` lands exactly there.
                                val core = point.core
                                if (core !is InjectionPoint.Return && core !is InjectionPoint.FinalReturn) {
                                    throw InjectionError(
                                        "[injection] the anchor of @ModifyReturnValue must be RETURN (every return)" +
                                            " or TAIL (the last return): currently " +
                                            AnchorResolver.describe(point)
                                    )
                                }
                                val value = org.objectweb.asm.Type.getReturnType(method.desc)
                                if (value == org.objectweb.asm.Type.VOID_TYPE) {
                                    throw InjectionError(
                                        "[injection] the target method of @ModifyReturnValue returns void, no return value to modify: " +
                                            "${method.name}${method.desc} (for entry types use @Inject)"
                                    )
                                }
                                val list = PayloadEmitter.emitInPlaceHandler(
                                    payload, "@ModifyReturnValue", value, method, owner, locals
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_EXPR_VALUE -> {
                                // The expression value: its type and whether it is on the stack are both determined by that instruction
                                val value = ProducedValue.requireReadable(
                                    "@ModifyExpressionValue", point, anchor, method
                                )
                                val list = PayloadEmitter.emitInPlaceHandler(
                                    payload, "@ModifyExpressionValue", value, method, owner, locals
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_VAR -> {
                                if (anchor !is VarInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_VAR) must be a local-variable" +
                                            " write/read instruction (STORE / LOAD), currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}"
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyVariable(payload, method, owner, anchor, locals)
                                    ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames)) continue
                                inserted++
                            }

                            HandlerKind.REDIRECT -> {
                                // Redirects an **instance** call (merged form).
                                //
                                // The key difference from external static `@Redirect`: the handler lives in the target
                                // class, invoked with `INVOKEVIRTUAL`, so that receiver **must be the target instance**;
                                // whereas the receiver on the stack at the anchor is the object being replaced (e.g. `Foo`).
                                // These are different things ⇒ can't just "in-place change who's called"; must, like
                                // `@ModifyArgs`: collect the original args in reverse into temp slots → push `this` →
                                // push the args → call the handler. The handler's return value is the whole call's result,
                                // so it's still "value-modifying", no handle needed.
                                if (anchor !is MethodInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(REDIRECT) must be a method call, currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}"
                                    )
                                    continue
                                }
                                if (anchor.opcode == Opcodes.INVOKESTATIC) {
                                    throw InjectionError(
                                        "[injection] HandlerCall(REDIRECT) is only for **instance calls**: " +
                                            "${anchor.owner}.${anchor.name} is a static call, the handler is an instance method / " +
                                            "has no receiver to receive it (use a static handler instead)"
                                    )
                                }
                                // Parameter list = (the replaced call's receiver, the original args…[, captures…]).
                                //
                                // Why is the receiver `anchor.owner` rather than the target class: the handler's `this`
                                // is the target instance (called from the injection point via `INVOKEVIRTUAL`) and
                                // **no longer needs** to be declared in the parameter list; but "the receiver of the
                                // call being replaced" is something only the handler can obtain — it needs it to
                                // perform that call on its behalf. These are two different values; don't mix them.
                                // Captures push after the original args, so only the **prefix** is validated.
                                val expected = listOf(org.objectweb.asm.Type.getObjectType(anchor.owner)) +
                                    org.objectweb.asm.Type.getArgumentTypes(anchor.desc).toList()
                                val actual = org.objectweb.asm.Type.getArgumentTypes(payload.desc).toList()
                                if (actual.size < expected.size || actual.take(expected.size) != expected) {
                                    throw InjectionError(
                                        "[injection] the handler parameter list of HandlerCall(REDIRECT) must start with " +
                                            "\"receiver + the replaced call's arguments\": expected " +
                                            "${expected.joinToString(", ") { it.descriptor }} " +
                                            "actual ${payload.desc} (${method.name}${method.desc})"
                                    )
                                }
                                val list = PayloadEmitter.emitRedirect(
                                    payload.owner, payload.method, payload.desc, payload.captures,
                                    method, owner, anchor, locals,
                                )
                                if (!insertBlock(method, anchor, list, after = false, frames)) continue
                                method.instructions.remove(anchor)
                                inserted++
                            }
                        }
                    }

                    is Payload.ModifyConstant -> {
                        val value = ProducedValue.requireReadable("ModifyConstant", point, anchor, method)
                        val list = PayloadEmitter.emitInPlaceValue(
                            "ModifyConstant", payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, value = value, method = method, owner = owner, locals = locals,
                        ) ?: continue
                        if (!insertBlock(method, anchor, list, point.after, frames)) continue
                        inserted++
                    }

                    is Payload.ModifyExpressionValue -> {
                        val value = ProducedValue.requireReadable("ModifyExpressionValue", point, anchor, method)
                        val list = PayloadEmitter.emitInPlaceValue(
                            "ModifyExpressionValue", payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, value = value, method = method, owner = owner, locals = locals,
                        ) ?: continue
                        if (!insertBlock(method, anchor, list, point.after, frames)) continue
                        inserted++
                    }

                    is Payload.Redirect -> {
                        // Redirect: replace the entire **single** matched call instruction with a static handler
                        // (descriptor identical to the original call, i.e. the handler is a static mirror of the original method)
                        if (anchor !is MethodInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of Redirect must be a method call, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}"
                            )
                            continue
                        }
                        if (anchor.name == "<init>") {
                            OmlLog.error("Injection", "Redirect cannot be applied to a constructor call: $anchor")
                            continue
                        }
                        // Static call: the descriptor is taken as-is. **For an instance call the receiver must be folded into the
                        // parameter list** — on the stack it's [receiver, args…], so after switching to INVOKESTATIC the
                        // receiver must become the first parameter, otherwise only the args get consumed and the stack is off.
                        val staticCall = anchor.opcode == Opcodes.INVOKESTATIC
                        val desc = if (staticCall) {
                            anchor.desc
                        } else {
                            "(${org.objectweb.asm.Type.getObjectType(anchor.owner).descriptor}${anchor.desc.substring(1)}"
                        }
                        val replacement = MethodInsnNode(
                            Opcodes.INVOKESTATIC, payload.handlerOwner, payload.handlerMethod, desc, false
                        )
                        val block = InsnList().apply { add(replacement) }
                        if (!insertBlock(method, anchor, block, after = true, frames)) continue
                        method.instructions.remove(anchor)
                        inserted++
                    }

                    else -> {
                        val list =
                            PayloadEmitter.emit(payload, method, owner, locals) ?: continue // validation failed, skip
                        if (!insertBlock(method, anchor, list, point.after, frames)) continue
                        inserted++
                    }
                }
            }
        }
        return inserted
    }
}
