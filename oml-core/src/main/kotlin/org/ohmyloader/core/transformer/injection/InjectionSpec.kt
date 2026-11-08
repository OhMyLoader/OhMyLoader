package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.expression.flow.ExpressionTarget
import org.ohmyloader.core.expression.flow.expansion.InsnExpander
import org.ohmyloader.core.expression.flow.postprocessing.ArrayCreationInfo
import org.ohmyloader.core.expression.utils.ComparisonInfo
import org.ohmyloader.core.expression.utils.ComplexComparisonInfo
import org.ohmyloader.core.expression.utils.ExpressionDecorations
import org.ohmyloader.core.expression.utils.FlowDecorations
import org.ohmyloader.core.hook.LOCAL_REF
import org.ohmyloader.core.hook.LOCAL_REF_DESC
import org.ohmyloader.core.hook.LOCAL_REFS
import org.ohmyloader.core.transformer.TransformContext
import java.util.IdentityHashMap

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
    is Payload.CancelableReturn -> bridgeArgs
    is Payload.TransformReturn -> extras
    is Payload.ModifyArg -> extras
    is Payload.ModifyArgs -> extras
    is Payload.ModifyConstant -> extras
    is Payload.ModifyExpressionValue -> extras
    is Payload.ModifyReceiver -> extras
    is Payload.WrapWithCondition -> extras
    is Payload.WrapOperation -> extras
    is Payload.WrapMethod -> extras
    is Payload.ModifyVariable -> extras
    // For every HandlerCall form (entry- and value-modifying alike) the values all live in captures:
    // the value-modifying discriminator (which argument / slot) is a **scalar**, carried by index / slot, not a DslValue.
    is Payload.HandlerCall -> captures
    is Payload.Redirect -> extras
    is Payload.WrapBranch -> extras
    is Payload.Raw -> emptyList()
}

/**
 * Whether the payload uses local-variable reads ([DslValue.Local]).
 *
 * Dataflow analysis is a **whole-method-level** cost, so it only runs when actually needed — most rules
 * (notifications, return-value rewrites, constant rewrites…) never touch local variables and shouldn't pay for it.
 */
internal fun Payload.usesLocals(): Boolean =
    dslValues().any { it is DslValue.Local || it is DslValue.RefLocal }

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

        // Before any mutation: two rules that would both replace the same instruction cannot both run,
        // and after the first one does the second silently misses. Name both instead.
        reportExclusiveConflicts(context, classRules)

        // One dataflow cache per method node, shared by every rule touching that method (see MethodFrames)
        val frameCache = HashMap<MethodNode, MethodFrames>()

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

        // One shared-ref state per method, reused by every rule touching that method.
        val shareStates = HashMap<MethodNode, ShareState>()
        // Whole-method wraps run last: their body move must see every ordinary injection already applied.
        for (rule in classRules.methods.sortedBy { if (it.isWholeMethodWrap()) 1 else 0 }) {
            val named = targetMethods(context.node, rule.selector)
            // Abstract/native methods have no instruction body (`instructions.size() == 0`), so injection has nothing to target
            val injectable = named.filter { it.instructions.size() > 0 }
            var hits = 0
            for (method in injectable) {
                // The dataflow cache hangs on the method, not the rule: several rules hitting the same
                // method share one MethodFrames, re-analyzed only when a rule actually mutated the list
                val prepared = prepareShares(method, rule.points, shareStates)
                val frames = frameCache.getOrPut(method) { MethodFrames(context.internalName, method) }
                hits += applyToMethod(
                    context.internalName, context.node.superName, context.node, method, prepared.points, frames,
                    prepared.knownShares,
                    shareStates[method]?.slots?.isNotEmpty() == true,
                )
            }
            reportHits(
                "${context.internalName} :: ${rule.selector.describe()}",
                rule.require, rule.expect, rule.allow, rule.optional, hits,
                // The diagnosis scans the instruction table, so it is only computed when this rule actually needs to report
                diagnosis = if (hits == 0) MissDiagnosis.explain(
                    context.node,
                    rule,
                    named,
                    injectable,
                ) else emptyList(),
            )
            if (hits > 0) changed = true
        }

        // Shared-ref prologues go in last: every HEAD block is already placed before the method's original
        // first instruction, and inserting the initializer at the head keeps it ahead of all of them.
        for ((method, state) in shareStates) {
            if (state.newSlots.isEmpty()) continue
            method.instructions.insert(state.init)
            declareShareSlots(method, state.newSlots)
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
                    "Rule hit count too low: $where declares require=$require (minimum), but got $hits$block",
                )

            allow != null && hits > allow ->
                throw InjectionError(
                    "Rule hit count exceeded: $where declares allow=$allow (maximum), but got $hits" +
                        " (the selector may be written too broadly, hitting an unexpected place)$block",
                )

            expect != null && hits != expect ->
                OmlLog.error(
                    "Injection",
                    "hit count does not match expect: $where expects $expect, but got $hits$block",
                )

            require == null && allow == null && expect == null && hits == 0 && !optional ->
                OmlLog.error(
                    "Injection",
                    "rule hit nothing: $where" +
                        " (if intentional, mark optional() to silence; if it must hit, mark require(1) to hard-fail)$block",
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
    /** The instruction shapes a WrapOperation can wrap (NEW is handled separately). */
    private fun isWrapOperationAnchor(anchor: AbstractInsnNode): Boolean =
        anchor is MethodInsnNode || anchor is FieldInsnNode || anchor is TypeInsnNode ||
            anchor is InsnNode || anchor is IntInsnNode || anchor is MultiANewArrayInsnNode

    /** The instruction-producing payloads that *replace* what they anchor on, so only one rule can own a site. */
    private enum class Exclusive { INSTRUCTION, WHOLE_METHOD }

    /**
     * The methods a selector targets. A lambda selector names the **enclosing** method; the target is the
     * implementation method its `LambdaMetafactory` invokedynamic points at (same class only). [ordinal]
     * picks the Nth distinct implementation in enclosing-method order.
     */
    private fun targetMethods(classNode: ClassNode, selector: MethodSelector): List<MethodNode> {
        val lambda = selector.lambda ?: return classNode.methods.filter { selector.matches(it.name, it.desc) }
        val outers = classNode.methods.filter {
            it.name in selector.names && (selector.desc == null || selector.desc == it.desc)
        }
        val implementations = LinkedHashMap<String, MethodNode>()
        for (outer in outers) {
            for (insn in outer.instructions.toArray()) {
                if (insn !is InvokeDynamicInsnNode) continue
                if (insn.bsm.owner != LAMBDA_METAFACTORY || insn.bsm.name !in LAMBDA_BSMS) continue
                if (Type.getReturnType(insn.desc).descriptor != "L${lambda.interfaceInternal};") continue
                val impl = insn.bsmArgs.getOrNull(1) as? Handle ?: continue
                if (impl.tag !in LAMBDA_HANDLE_TAGS) continue
                if (impl.owner != classNode.name) continue
                val method = classNode.methods.firstOrNull { it.name == impl.name && it.desc == impl.desc } ?: continue
                implementations.putIfAbsent("${impl.name}${impl.desc}", method)
            }
        }
        return listOfNotNull(implementations.values.toList().getOrNull(lambda.ordinal))
    }

    private companion object {
        private const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"
        private val LAMBDA_BSMS = setOf("metafactory", "altMetafactory")
        private val LAMBDA_HANDLE_TAGS = setOf(
            Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESPECIAL,
            Opcodes.H_INVOKEINTERFACE, Opcodes.H_NEWINVOKESPECIAL,
        )
    }    private fun exclusiveKind(payload: Payload): Exclusive? = when (payload) {
        is Payload.Redirect, is Payload.WrapOperation, is Payload.WrapBranch -> Exclusive.INSTRUCTION
        is Payload.WrapMethod -> Exclusive.WHOLE_METHOD
        is Payload.HandlerCall -> when (payload.kind) {
            HandlerKind.REDIRECT, HandlerKind.WRAP_OPERATION, HandlerKind.WRAP_BRANCH -> Exclusive.INSTRUCTION
            HandlerKind.WRAP_METHOD -> Exclusive.WHOLE_METHOD
            else -> null
        }

        else -> null
    }

    private fun claimLabel(rule: org.ohmyloader.api.inject.MethodRule, payload: Payload): String {
        fun owner(owner: String, method: String) = "${owner.replace('/', '.')}.$method"
        val what = when (payload) {
            is Payload.Redirect -> "Redirect → ${owner(payload.handlerOwner, payload.handlerMethod)}"
            is Payload.WrapOperation -> "WrapOperation → ${owner(payload.owner, payload.method)}"
            is Payload.WrapBranch -> "WrapBranch → ${owner(payload.owner, payload.method)}"
            is Payload.WrapMethod -> "WrapMethod → ${owner(payload.owner, payload.method)}"
            is Payload.HandlerCall -> "${payload.kind} → ${owner(payload.owner, payload.method)}"
            else -> payload.javaClass.simpleName
        }
        return "$what   [rule: ${rule.selector.describe()}]"
    }

    /**
     * Reports two rules that would both replace the same instruction (or the same whole method body).
     * Resolution runs on the **untouched** method: once the first rule replaces the instruction, the second
     * rule's anchor no longer exists, so without this pass the loser only shows up as a hit-count miss.
     * Only exclusive payloads are tracked; notification-style payloads may legally stack.
     */
    private fun reportExclusiveConflicts(context: TransformContext, classRules: ClassRules) {
        val byInstruction = IdentityHashMap<MethodNode, MutableMap<AbstractInsnNode, MutableList<String>>>()
        val byMethod = HashMap<MethodNode, MutableList<String>>()
        val frames = HashMap<MethodNode, MethodFrames>()
        val conflicts = mutableListOf<String>()
        for (rule in classRules.methods) {
            val named = targetMethods(context.node, rule.selector).filter { it.instructions.size() > 0 }
            for (method in named) {
                for ((point, payload) in rule.points) {
                    when (exclusiveKind(payload)) {
                        Exclusive.INSTRUCTION -> {
                            val anchors = when (point) {
                                is InjectionPoint.Expression -> ExpressionAnchors.resolve(
                                    context.node, method, point, payload,
                                    ExpressionAnchors.contextTypeOf(payload),
                                ).instructions

                                else -> AnchorResolver.resolve(
                                    point, context.node, method, context.internalName, context.node.superName,
                                    frames.getOrPut(method) { MethodFrames(context.internalName, method) },
                                )
                            }
                            val claim = claimLabel(rule, payload)
                            val claims = byInstruction.getOrPut(method) { IdentityHashMap() }
                            for (anchor in anchors) {
                                val list = claims.getOrPut(anchor) { mutableListOf() }
                                if (list.isNotEmpty()) {
                                    conflicts += "${context.internalName}.${method.name}${method.desc} " +
                                        "at ${AnchorResolver.describe(point)}:\n" +
                                        (list + claim).joinToString("\n") { "    - $it" }
                                }
                                list += claim
                            }
                        }

                        Exclusive.WHOLE_METHOD -> {
                            val claim = claimLabel(rule, payload)
                            val list = byMethod.getOrPut(method) { mutableListOf() }
                            if (list.isNotEmpty()) {
                                conflicts += "${context.internalName}.${method.name}${method.desc} " +
                                    "(whole method body):\n" +
                                    (list + claim).joinToString("\n") { "    - $it" }
                            }
                            list += claim
                        }

                        null -> Unit
                    }
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            throw InjectionError(
                "[injection] conflicting exclusive injections — two rules cannot both replace/own the same " +
                    "instruction or method body: only the first would run and the second would silently miss. " +
                    "Combine them into one handler, or anchor the second rule on the first handler's call:\n" +
                    conflicts.joinToString("\n"),
            )
        }
    }

    /** The comparison decoration an expression match attached to [anchor], if any. */
    private fun comparisonDecoration(
        decorations: Map<AbstractInsnNode, Map<String, Any?>>,
        anchor: AbstractInsnNode,
    ): ComparisonInfo? = decorations[anchor]?.get(ExpressionDecorations.COMPARISON_INFO) as? ComparisonInfo

    /**
     * The value type an expression capture produces, when the matcher recorded it. Arithmetic and other
     * instructions that `ProducedValue` cannot classify (it only knows calls/fields/constants) are typed
     * by the matched expression itself.
     */
    private fun expressionValueType(
        point: InjectionPoint,
        decorations: Map<AbstractInsnNode, Map<String, Any?>>,
        anchor: AbstractInsnNode,
    ): Type? {
        if (point.core !is InjectionPoint.Expression) return null
        val type = decorations[anchor]?.get(ExpressionDecorations.SIMPLE_EXPRESSION_TYPE) as? Type ?: return null
        // The matcher's "any int-like constant" placeholder is an int on the stack
        return if (type.descriptor == "Lint-like;") Type.INT_TYPE else type
    }

    /**
     * Expands the compound instruction a captured expression belongs to and returns the component this
     * injector targets. Expansion happens at most once per compound, so a second rule on the same
     * instruction reuses the rewritten form.
     */
    private fun expandExpressionAnchor(
        point: InjectionPoint,
        resolution: ExpressionAnchors.Resolution?,
        anchor: AbstractInsnNode,
        payload: Payload,
        frames: MethodFrames,
    ): AbstractInsnNode {
        if (point.core !is InjectionPoint.Expression || resolution == null) return anchor
        val node = resolution.target.getInjectionNode(anchor) ?: return anchor
        if (!node.hasDecoration(ExpressionDecorations.EXPANSION_INFO)) return anchor
        val expanded = InsnExpander.doExpansion(node, resolution.target, payload)
        frames.invalidate()
        return expanded.currentTarget
    }

    /**
     * Where the value an expression produces actually comes into existence. A `NEW` produces an
     * **uninitialized** reference, so the value is only usable after its `<init>` call (a missing `DUP` is
     * inserted and the handler's result popped again); an array literal's value is complete after its last
     * store. Every other capture is the producing instruction itself.
     *
     * Returns the anchor and whether the handler's result must be discarded.
     */
    private fun expressionValueAnchor(
        point: InjectionPoint,
        resolution: ExpressionAnchors.Resolution?,
        decorations: Map<AbstractInsnNode, Map<String, Any?>>,
        anchor: AbstractInsnNode,
        method: MethodNode,
        frames: MethodFrames,
    ): Pair<AbstractInsnNode, Boolean>? {
        if (point.core !is InjectionPoint.Expression || resolution == null) return anchor to false
        val creation = decorations[anchor]?.get(FlowDecorations.ARRAY_CREATION_INFO) as? ArrayCreationInfo
        if (creation != null) {
            return creation.initialized.getNode(resolution.target).currentTarget to false
        }
        if (anchor is TypeInsnNode && anchor.opcode == Opcodes.NEW) {
            val init = findInitCall(anchor)
            if (init == null) {
                OmlLog.error(
                    "Injection",
                    "injection skipped: no <init> call follows the NEW captured by the expression: ${method.name}",
                )
                return null
            }
            val duped = nextReal(anchor)?.opcode == Opcodes.DUP
            if (!duped) {
                method.instructions.insert(anchor, InsnNode(Opcodes.DUP))
                frames.invalidate()
            }
            return init to !duped
        }
        return anchor to false
    }

    /** The `<init>` call paired with [new], matching nested `NEW`s by depth. */
    private fun findInitCall(new: TypeInsnNode): MethodInsnNode? {
        var depth = 0
        var node: AbstractInsnNode? = new
        while (node != null) {
            when {
                node is TypeInsnNode && node.opcode == Opcodes.NEW -> depth++
                node is MethodInsnNode && node.opcode == Opcodes.INVOKESPECIAL && node.name == "<init>" -> {
                    depth--
                    if (depth == 0) return node.takeIf { it.owner == new.desc }
                }
            }
            node = node.next
        }
        return null
    }

    private fun nextReal(node: AbstractInsnNode): AbstractInsnNode? {
        var next = node.next
        while (next != null && (next is LabelNode || next is LineNumberNode || next is FrameNode)) {
            next = next.next
        }
        return next
    }

    /**
     * The jump whose branch the comparison feeds: the matched instruction itself for a plain jump, or the
     * `IFxx` after an `lcmp`/`fcmp`/`dcmp` for a complex comparison.
     */
    private fun resolveComparisonJump(
        comparison: ComparisonInfo,
        anchor: AbstractInsnNode,
        method: MethodNode,
    ): JumpInsnNode? = if (comparison is ComplexComparisonInfo) {
        comparison.getJumpInsn(ExpressionTarget(method))
    } else {
        anchor as? JumpInsnNode
    }

    private fun org.ohmyloader.api.inject.MethodRule.isWholeMethodWrap(): Boolean =
        points.any { (_, payload) ->
            payload is Payload.WrapMethod ||
                (payload is Payload.HandlerCall && payload.kind == HandlerKind.WRAP_METHOD)
        }

    private fun insertBlock(
        method: MethodNode,
        anchor: AbstractInsnNode,
        block: InsnList,
        after: Boolean,
        frames: MethodFrames?,
        knownLocals: Map<Int, Any> = emptyMap(),
    ): Boolean {
        when (val repair = FrameRepair.ensure(method, anchor, block, after, frames, knownLocals)) {
            is FrameRepair.Result.Nothing -> Unit

            is FrameRepair.Result.Fixed -> OmlLog.error(
                "Injection",
                "the stack frame at the injection point did not declare locals ${repair.slots.joinToString()}," +
                    " restored from dataflow: ${method.name}${method.desc}",
            )

            is FrameRepair.Result.Failed -> {
                OmlLog.error(
                    "Injection",
                    "injection skipped: the stack frame at ${method.name}${method.desc} cannot carry this code — " +
                        repair.reason,
                )
                return false
            }
        }
        if (after) method.instructions.insert(anchor, block)
        else method.instructions.insertBefore(anchor, block)
        // The table is addressed by instruction index — every mutation must drop it (see MethodFrames.invalidate)
        frames?.invalidate()
        return true
    }

    /** A `@Share` identity: the carrier-chosen namespace plus the author-chosen id. */
    private data class ShareKey(val namespace: String, val id: String)

    private fun Payload.shareKeys(): List<ShareKey> =
        dslValues().filterIsInstance<DslValue.SharedRef>().map { ShareKey(it.namespace, it.id) }

    private fun DslValue.rewriteShare(slots: Map<ShareKey, Int>): DslValue =
        if (this is DslValue.SharedRef) {
            val slot = slots[ShareKey(namespace, id)]
                ?: throw InjectionError("[injection] internal: no shared-ref slot for $namespace/$id")
            // A plain local read would be resolved by the dataflow analysis, which cannot see a prologue
            // that is inserted after the emission; RefSlot reads the fixed slot directly.
            DslValue.RefSlot(slot = slot, type = LOCAL_REF_DESC)
        } else {
            this
        }

    private fun Payload.rewriteShares(slots: Map<ShareKey, Int>): Payload = when (this) {
        is Payload.StaticCall -> copy(args = args.map { it.rewriteShare(slots) })
        is Payload.CheckCall -> copy(args = args.map { it.rewriteShare(slots) })
        is Payload.CancelableReturn -> copy(bridgeArgs = bridgeArgs.map { it.rewriteShare(slots) })
        is Payload.HandlerCall -> copy(captures = captures.map { it.rewriteShare(slots) })
        is Payload.TransformReturn -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.ModifyArg -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.ModifyArgs -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.ModifyConstant -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.ModifyExpressionValue -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.ModifyReceiver -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.ModifyVariable -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.WrapOperation -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.WrapMethod -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.WrapWithCondition -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.Redirect -> copy(extras = extras.map { it.rewriteShare(slots) })
        is Payload.WrapBranch -> copy(extras = extras.map { it.rewriteShare(slots) })
        else -> this
    }

    /**
     * Allocates one `LocalRef` local per distinct `@Share` key used by [points], initializes them at the
     * method head, and declares the slots in every StackMapTable frame. Each SharedRef capture is then
     * rewritten to a plain read of its slot.
     *
     * The slot's type must be exactly `LocalRef` (the engine's local resolver demands identical reference
     * descriptors), so the initializer calls a factory whose return type is the interface.
     */
    /**
     * Per-method shared-ref state: the key→slot map, the prologue being accumulated, and the slots that
     * still need a frame declaration. The prologue is inserted at the end of [transform], so it cannot
     * steal the HEAD anchor and sits ahead of every HEAD block.
     */
    private class ShareState(
        val slots: MutableMap<ShareKey, Int> = mutableMapOf(),
        val init: InsnList = InsnList(),
        val newSlots: MutableList<Int> = mutableListOf(),
    )

    private class PreparedShares(
        val points: List<Pair<InjectionPoint, Payload>>,
        val knownShares: Map<Int, Any>,
    )

    private fun prepareShares(
        method: MethodNode,
        points: List<Pair<InjectionPoint, Payload>>,
        shareStates: MutableMap<MethodNode, ShareState>,
    ): PreparedShares {
        val keys = points.flatMap { (_, payload) -> payload.shareKeys() }.distinct()
        if (keys.isEmpty()) return PreparedShares(points, emptyMap())
        val state = shareStates.getOrPut(method) { ShareState() }
        for (key in keys) {
            if (key in state.slots) continue
            val slot = PayloadEmitter.allocLocalSlot(method)
            method.maxLocals = maxOf(method.maxLocals, slot + 1)
            state.slots[key] = slot
            state.newSlots += slot
            state.init.add(
                MethodInsnNode(Opcodes.INVOKESTATIC, LOCAL_REFS, "create", "()$LOCAL_REF_DESC", false),
            )
            state.init.add(VarInsnNode(Opcodes.ASTORE, slot))
        }
        val rewritten = points.map { (point, payload) -> point to payload.rewriteShares(state.slots) }
        val known = keys.mapNotNull { key -> state.slots[key]?.let { slot -> slot to LOCAL_REF } }.toMap()
        return PreparedShares(rewritten, known)
    }

    /**
     * The share slots are live from the method head; every existing frame must declare them (as `LocalRef`),
     * otherwise a frame between the initializer and the use resets the slot to `TOP` in the verifier's eyes.
     */
    private fun declareShareSlots(method: MethodNode, slots: List<Int>) {
        val max = slots.max()
        for (node in method.instructions) {
            if (node !is FrameNode) continue
            val locals = node.local?.toMutableList() ?: mutableListOf()
            while (locals.size <= max) locals.add(Opcodes.TOP)
            for (slot in slots) locals[slot] = LOCAL_REF
            node.local = locals
        }
    }

    private fun applyToMethod(
        owner: String,
        superName: String?,
        classNode: ClassNode,
        method: MethodNode,
        points: List<Pair<InjectionPoint, Payload>>,
        frames: MethodFrames,
        knownShares: Map<Int, Any>,
        hasShares: Boolean,
    ): Int {
        var inserted = 0
        for ([point, payload] in points) {
            // Collect all anchors first, then process each: processing doesn't affect the node references of other anchors
            var expressionResolution: ExpressionAnchors.Resolution? = null
            var expressionDecorations: Map<AbstractInsnNode, Map<String, Any?>> = emptyMap()
            val anchors = when (point) {
                is InjectionPoint.Expression -> {
                    val resolution = ExpressionAnchors.resolve(
                        classNode, method, point, payload, ExpressionAnchors.contextTypeOf(payload),
                    )
                    expressionResolution = resolution
                    expressionDecorations = resolution.decorations
                    resolution.instructions
                }

                else -> AnchorResolver.resolve(point, classNode, method, owner, superName, frames)
            }
            if (anchors.isEmpty()) {
                OmlLog.error(
                    "Injection",
                    "anchor not found: ${AnchorResolver.describe(point)} in " +
                        "${owner.substringAfterLast('/')}.${method.name}${method.desc}",
                )
                continue
            }
            val needsLocals = payload.usesLocals() || payload is Payload.ModifyVariable
            for (rawAnchor in anchors) {
                // A compound instruction is expanded once, when its match is actually injected: the anchor
                // becomes the part this capture referred to (e.g. the toString call of a concatenation)
                val anchor = expandExpressionAnchor(point, expressionResolution, rawAnchor, payload, frames)
                val effectiveAfter = point.after ||
                    (point.core is InjectionPoint.Expression && payload is Payload.ModifyExpressionValue)
                val locals = if (needsLocals) frames.at(anchor, effectiveAfter) else null
                when (payload) {
                    is Payload.CheckCall -> {
                        if (!point.isMethodHead() && !point.isAfterCallAnchor()) {
                            OmlLog.error(
                                "Injection",
                                "CheckCall supports method-entry anchors (HEAD / CTOR_HEAD) and after-call " +
                                    "anchors, currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        if (org.objectweb.asm.Type.getReturnType(method.desc) != org.objectweb.asm.Type.VOID_TYPE) {
                            OmlLog.error(
                                "Injection",
                                "CheckCall only supports void targets: ${method.name}${method.desc}",
                            )
                            continue
                        }
                        // After a void call the stack is empty, so the short-circuit RETURN is stack-legal; the
                        // continuation frame comes from dataflow (the method's entry frame would misstate the
                        // locals once the body has stored any). Deriving it can fail (unreachable anchor /
                        // analysis limit) — refusing beats emitting a frame the verifier rejects at class load.
                        val continuationLocals =
                            if (point.isMethodHead()) {
                                null
                            } else {
                                val call = anchor as? MethodInsnNode
                                when {
                                    call == null ||
                                        org.objectweb.asm.Type.getReturnType(call.desc) !=
                                        org.objectweb.asm.Type.VOID_TYPE -> {
                                        OmlLog.error(
                                            "Injection",
                                            "CheckCall after a call needs a void method-call anchor: " +
                                                "${method.name}${method.desc}",
                                        )
                                        continue
                                    }

                                    else -> frames.frameLocalsAt(anchor, after = true) ?: run {
                                        OmlLog.error(
                                            "Injection",
                                            "injection skipped: the local state after the anchored call in " +
                                                "${method.name}${method.desc} cannot be derived (dataflow failed)",
                                        )
                                        continue
                                    }
                                }
                            }
                        val list =
                            PayloadEmitter.emitCheckCall(payload, method, owner, locals, continuationLocals)
                                ?: continue
                        if (!insertBlock(method, anchor, list, after = !point.isMethodHead(), frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.CancelableReturn -> {
                        if (!point.isMethodHead()) {
                            OmlLog.error(
                                "Injection",
                                "CancelableReturn only supports method-entry anchors (HEAD / CTOR_HEAD), " +
                                    "currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        if (org.objectweb.asm.Type.getReturnType(method.desc) == org.objectweb.asm.Type.VOID_TYPE) {
                            OmlLog.error(
                                "Injection",
                                "CancelableReturn requires a non-void target (for a void target use CheckCall): " +
                                    "${method.name}${method.desc}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emit(payload, method, owner, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.ModifyArg -> {
                        if (anchor !is MethodInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyArg must be a method call, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyArg(payload, method, owner, anchor, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.ModifyArgs -> {
                        if (anchor !is MethodInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyArgs must be a method call, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyArgs(payload, method, owner, anchor, locals) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.ModifyVariable -> {
                        // The anchor must be a "local-variable write/read instruction": the value being changed lives
                        // in that slot; at any other anchor (say a call) there is no "which variable" to speak of.
                        if (anchor !is VarInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyVariable must be a local-variable write/read instruction" +
                                    " (STORE / LOAD), currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyVariable(payload, method, owner, anchor, locals)
                            ?: continue
                        // Store sets after=true (write after the write), Load sets after=false (write before the read) —
                        // both landing points are answered by the anchor itself; handled uniformly here
                        if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.ModifyReceiver -> {
                        if (anchor !is MethodInsnNode && anchor !is FieldInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of ModifyReceiver must be an instance call or field access, " +
                                    "currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitModifyReceiver(
                            payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, method = method, owner = owner, anchor = anchor, locals = locals,
                        ) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.WrapWithCondition -> {
                        if (anchor !is MethodInsnNode && anchor !is FieldInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of WrapWithCondition must be a call or field write, " +
                                    "currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitWrapWithCondition(
                            payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, method = method, owner = owner, anchor = anchor,
                            frames = frames, locals = locals,
                        ) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.WrapOperation -> {
                        if (point.after) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of WrapOperation must be before the operation, " +
                                    "currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        // A comparison has no single operation instruction; it is wrapped as an
                        // Operation<Boolean> re-evaluating the comparison (complex cmps are still out)
                        val comparison = comparisonDecoration(expressionDecorations, anchor)
                        val comparisonJump = comparison?.let { resolveComparisonJump(it, anchor, method) }
                        if (comparison != null && comparisonJump != null) {
                            if (comparison is ComplexComparisonInfo) {
                                OmlLog.error(
                                    "Injection",
                                    "WrapOperation on a complex comparison (lcmp/fcmp/dcmp) is not supported yet: " +
                                        method.name,
                                )
                                continue
                            }
                            val list = PayloadEmitter.emitWrapComparison(
                                payload.owner, payload.method, payload.desc, payload.extras,
                                instance = false, comparison, classNode, method, owner, comparisonJump, locals,
                            ) ?: continue
                            if (!insertBlock(method, comparisonJump, list, after = false, frames, knownShares)) continue
                            method.instructions.remove(comparisonJump)
                            frames.invalidate()
                            inserted++
                            continue
                        }
                        if (anchor is TypeInsnNode && anchor.opcode == Opcodes.NEW) {
                            val edit = PayloadEmitter.emitWrapNewOperation(
                                payload.owner, payload.method, payload.desc, payload.extras,
                                instance = false, classNode = classNode, method = method, owner = owner,
                                newInsn = anchor, locals = locals,
                            ) ?: continue
                            if (!insertBlock(method, edit.initInsn, edit.block, after = false, frames, knownShares)) continue
                            method.instructions.remove(edit.initInsn)
                            method.instructions.set(edit.newInsn, InsnNode(Opcodes.ACONST_NULL))
                            frames.invalidate()
                            inserted++
                            continue
                        }
                        if (!isWrapOperationAnchor(anchor)) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of WrapOperation must be a call, field access, type check or " +
                                    "array instruction, currently ${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitWrapOperation(
                            payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, classNode = classNode, method = method, owner = owner,
                            anchor = anchor, locals = locals,
                        ) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        method.instructions.remove(anchor)
                        frames.invalidate()
                        inserted++
                    }

                    is Payload.WrapMethod -> {
                        if (hasShares) {
                            throw InjectionError(
                                "[injection] @Share and @WrapMethod cannot be combined on " +
                                    "${method.name}${method.desc}: the body move would carry the ref reads " +
                                    "into a different frame",
                            )
                        }
                        if (MethodWrapper.wrap(
                                classNode, method, owner, payload.owner, payload.method, payload.desc,
                                payload.extras, instance = false,
                            )
                        ) {
                            inserted++
                        }
                        // The body was moved; anchors collected for this rule are stale.
                        return inserted
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
                                if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_ARG -> {
                                if (anchor !is MethodInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_ARG) must be a method call, currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyArg(payload, method, owner, anchor, locals)
                                    ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_ARGS -> {
                                if (anchor !is MethodInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_ARGS) must be a method call, currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyArgs(payload, method, owner, anchor, locals)
                                    ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_CONST -> {
                                // The constant is already on the stack: its type is given by that loading
                                // instruction, verified against the handler's declared type
                                val value = ProducedValue.requireReadable(
                                    "@ModifyConstant", point, anchor, method,
                                )
                                val list = PayloadEmitter.emitInPlaceHandler(
                                    payload, "@ModifyConstant", value, method, owner, locals,
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
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
                                            AnchorResolver.describe(point),
                                    )
                                }
                                val value = org.objectweb.asm.Type.getReturnType(method.desc)
                                if (value == org.objectweb.asm.Type.VOID_TYPE) {
                                    throw InjectionError(
                                        "[injection] the target method of @ModifyReturnValue returns void, no return value to modify: " +
                                            "${method.name}${method.desc} (for entry types use @Inject)",
                                    )
                                }
                                val list = PayloadEmitter.emitInPlaceHandler(
                                    payload, "@ModifyReturnValue", value, method, owner, locals,
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_EXPR_VALUE -> {
                                // A comparison match carries a ComparisonInfo: its boolean value does not exist
                                // yet and is materialized around the jump before the handler runs
                                val comparison = comparisonDecoration(expressionDecorations, anchor)
                                val comparisonJump = comparison?.let { resolveComparisonJump(it, anchor, method) }
                                if (comparison != null && comparisonJump != null) {
                                    val list = PayloadEmitter.emitModifiedComparison(
                                        payload.owner, payload.method, payload.desc, payload.captures,
                                        instance = true, comparison, method, owner, comparisonJump, locals, frames,
                                    ) ?: continue
                                    if (!insertBlock(method, comparisonJump, list, after = true, frames, knownShares)) continue
                                    inserted++
                                    continue
                                }
                                // The expression value: the capture's recorded type, or the instruction's own
                                val valueAnchor = expressionValueAnchor(
                                    point, expressionResolution, expressionDecorations, anchor, method, frames,
                                ) ?: continue
                                val value = expressionValueType(point, expressionDecorations, anchor)
                                    ?: ProducedValue.requireReadable(
                                        "@ModifyExpressionValue", point, valueAnchor.first, method,
                                    )
                                val list = PayloadEmitter.emitInPlaceHandler(
                                    payload, "@ModifyExpressionValue", value, method, owner, locals,
                                ) ?: continue
                                if (valueAnchor.second) list.add(InsnNode(Opcodes.POP))
                                val after = point.after || point.core is InjectionPoint.Expression
                                if (!insertBlock(method, valueAnchor.first, list, after, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_VAR -> {
                                if (anchor !is VarInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_VAR) must be a local-variable" +
                                            " write/read instruction (STORE / LOAD), currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyVariable(payload, method, owner, anchor, locals)
                                    ?: continue
                                if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.MODIFY_RECEIVER -> {
                                if (anchor !is MethodInsnNode && anchor !is FieldInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(MODIFY_RECEIVER) must be an instance call or field access, " +
                                            "currently ${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitModifyReceiver(
                                    payload.owner, payload.method, payload.desc, payload.captures,
                                    instance = true, method = method, owner = owner, anchor = anchor, locals = locals,
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.WRAP_WITH_CONDITION -> {
                                if (anchor !is MethodInsnNode && anchor !is FieldInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(WRAP_WITH_CONDITION) must be a call or field write, " +
                                            "currently ${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitWrapWithCondition(
                                    payload.owner, payload.method, payload.desc, payload.captures,
                                    instance = true, method = method, owner = owner, anchor = anchor,
                                    frames = frames, locals = locals,
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                inserted++
                            }

                            HandlerKind.WRAP_BRANCH -> {
                                if (anchor !is JumpInsnNode) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(WRAP_BRANCH) must be a conditional jump, " +
                                            "currently ${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitWrapBranch(
                                    payload.owner, payload.method, payload.desc, payload.captures,
                                    instance = true, method = method, owner = owner, jump = anchor, locals = locals,
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                method.instructions.remove(anchor)
                                frames.invalidate()
                                inserted++
                            }

                            HandlerKind.WRAP_METHOD -> {
                                if (hasShares) {
                                    throw InjectionError(
                                        "[injection] @Share and @WrapMethod cannot be combined on " +
                                            "${method.name}${method.desc}: the body move would carry the ref " +
                                            "reads into a different frame",
                                    )
                                }
                                if (MethodWrapper.wrap(
                                        classNode, method, owner, payload.owner, payload.method, payload.desc,
                                        payload.captures, instance = true,
                                    )
                                ) {
                                    inserted++
                                }
                                return inserted
                            }

                            HandlerKind.WRAP_OPERATION -> {
                                if (point.after) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(WRAP_OPERATION) must be before the operation, " +
                                            "currently ${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val comparison = comparisonDecoration(expressionDecorations, anchor)
                                val comparisonJump = comparison?.let { resolveComparisonJump(it, anchor, method) }
                                if (comparison != null && comparisonJump != null) {
                                    if (comparison is ComplexComparisonInfo) {
                                        OmlLog.error(
                                            "Injection",
                                            "WrapOperation on a complex comparison (lcmp/fcmp/dcmp) is not " +
                                                "supported yet: ${method.name}",
                                        )
                                        continue
                                    }
                                    val list = PayloadEmitter.emitWrapComparison(
                                        payload.owner, payload.method, payload.desc, payload.captures,
                                        instance = true, comparison, classNode, method, owner, comparisonJump, locals,
                                    ) ?: continue
                                    if (!insertBlock(method, comparisonJump, list, after = false, frames, knownShares)) continue
                                    method.instructions.remove(comparisonJump)
                                    frames.invalidate()
                                    inserted++
                                    continue
                                }
                                if (anchor is TypeInsnNode && anchor.opcode == Opcodes.NEW) {
                                    val edit = PayloadEmitter.emitWrapNewOperation(
                                        payload.owner, payload.method, payload.desc, payload.captures,
                                        instance = true, classNode = classNode, method = method, owner = owner,
                                        newInsn = anchor, locals = locals,
                                    ) ?: continue
                                    if (!insertBlock(method, edit.initInsn, edit.block, after = false, frames, knownShares)) continue
                                    method.instructions.remove(edit.initInsn)
                                    method.instructions.set(edit.newInsn, InsnNode(Opcodes.ACONST_NULL))
                                    frames.invalidate()
                                    inserted++
                                    continue
                                }
                                if (!isWrapOperationAnchor(anchor)) {
                                    OmlLog.error(
                                        "Injection",
                                        "the anchor of HandlerCall(WRAP_OPERATION) must be a call, field access, " +
                                            "type check or array instruction, currently " +
                                            "${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                val list = PayloadEmitter.emitWrapOperation(
                                    payload.owner, payload.method, payload.desc, payload.captures,
                                    instance = true, classNode = classNode, method = method, owner = owner,
                                    anchor = anchor, locals = locals,
                                ) ?: continue
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                method.instructions.remove(anchor)
                                frames.invalidate()
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
                                            "${AnchorResolver.describe(point)}: ${method.name}",
                                    )
                                    continue
                                }
                                if (anchor.opcode == Opcodes.INVOKESTATIC) {
                                    throw InjectionError(
                                        "[injection] HandlerCall(REDIRECT) is only for **instance calls**: " +
                                            "${anchor.owner}.${anchor.name} is a static call, the handler is an instance method / " +
                                            "has no receiver to receive it (use a static handler instead)",
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
                                            "actual ${payload.desc} (${method.name}${method.desc})",
                                    )
                                }
                                val list = PayloadEmitter.emitRedirect(
                                    payload.owner, payload.method, payload.desc, payload.captures,
                                    method, owner, anchor, locals,
                                )
                                if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                                method.instructions.remove(anchor)
                                frames.invalidate()
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
                        if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.ModifyExpressionValue -> {
                        // A comparison match: materialize the boolean, let the handler rewrite it, then branch
                        val comparison = comparisonDecoration(expressionDecorations, anchor)
                        val comparisonJump = comparison?.let { resolveComparisonJump(it, anchor, method) }
                        if (comparison != null && comparisonJump != null) {
                            val list = PayloadEmitter.emitModifiedComparison(
                                payload.owner, payload.method, payload.desc, payload.extras,
                                instance = false, comparison, method, owner, comparisonJump, locals, frames,
                            ) ?: continue
                            if (!insertBlock(method, comparisonJump, list, after = true, frames, knownShares)) continue
                            inserted++
                            continue
                        }
                        val valueAnchor = expressionValueAnchor(
                            point, expressionResolution, expressionDecorations, anchor, method, frames,
                        ) ?: continue
                        val value = expressionValueType(point, expressionDecorations, anchor)
                            ?: ProducedValue.requireReadable("ModifyExpressionValue", point, valueAnchor.first, method)
                        val list = PayloadEmitter.emitInPlaceValue(
                            "ModifyExpressionValue", payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, value = value, method = method, owner = owner, locals = locals,
                        ) ?: continue
                        if (valueAnchor.second) list.add(InsnNode(Opcodes.POP))
                        val after = point.after || point.core is InjectionPoint.Expression
                        if (!insertBlock(method, valueAnchor.first, list, after, frames, knownShares)) continue
                        inserted++
                    }

                    is Payload.Redirect -> {
                        // Redirect: replace the entire **single** matched call instruction with a static handler
                        // (descriptor identical to the original call, i.e. the handler is a static mirror of the original method)
                        if (anchor !is MethodInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of Redirect must be a method call, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}",
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
                        // With sugar the payload carries the bridge descriptor (extras + handler id included).
                        val staticCall = anchor.opcode == Opcodes.INVOKESTATIC
                        val desc = payload.desc ?: if (staticCall) {
                            anchor.desc
                        } else {
                            "(${org.objectweb.asm.Type.getObjectType(anchor.owner).descriptor}${anchor.desc.substring(1)}"
                        }
                        val block = InsnList()
                        val writeBacks = mutableListOf<PayloadEmitter.SugarWriteBack>()
                        if (payload.extras.isNotEmpty() &&
                            !PayloadEmitter.emitValues(block, payload.extras, method, locals, writeBacks)
                        ) {
                            continue
                        }
                        block.add(
                            MethodInsnNode(Opcodes.INVOKESTATIC, payload.handlerOwner, payload.handlerMethod, desc, false),
                        )
                        PayloadEmitter.emitWriteBacks(block, writeBacks)
                        if (!insertBlock(method, anchor, block, after = true, frames, knownShares)) continue
                        method.instructions.remove(anchor)
                        frames.invalidate()
                        inserted++
                    }

                    is Payload.WrapBranch -> {
                        if (anchor !is JumpInsnNode) {
                            OmlLog.error(
                                "Injection",
                                "the anchor of WrapBranch must be a conditional jump, currently " +
                                    "${AnchorResolver.describe(point)}: ${method.name}",
                            )
                            continue
                        }
                        val list = PayloadEmitter.emitWrapBranch(
                            payload.owner, payload.method, payload.desc, payload.extras,
                            instance = false, method = method, owner = owner, jump = anchor, locals = locals,
                        ) ?: continue
                        if (!insertBlock(method, anchor, list, after = false, frames, knownShares)) continue
                        method.instructions.remove(anchor)
                        frames.invalidate()
                        inserted++
                    }

                    else -> {
                        val list =
                            PayloadEmitter.emit(payload, method, owner, locals) ?: continue // validation failed, skip
                        if (!insertBlock(method, anchor, list, point.after, frames, knownShares)) continue
                        inserted++
                    }
                }
            }
        }
        return inserted
    }
}
