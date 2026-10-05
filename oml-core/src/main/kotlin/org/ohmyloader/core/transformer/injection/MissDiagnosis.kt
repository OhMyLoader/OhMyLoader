package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.api.inject.MethodRule
import org.ohmyloader.api.inject.MethodSelector
import org.ohmyloader.core.transformer.injection.MissDiagnosis.MAX_SAMPLES

/**
 * The "why" when a rule **does not match**.
 * "No match" only states the outcome, not the cause, and the cause is decidable — all of it is visible in the
 * target class's own bytecode, without running dataflow or loading other classes. Three common causes:
 * 1. **The selector didn't match**: method name right but descriptor differs (an overload difference), or vice
 *    versa, or the name doesn't exist at all;
 * 2. **Every selector match has no method body** (`abstract` / `native`) — injection inserts code into the body;
 * 3. **Anchor drift**: the method exists, but the call/field/constant/slot is absent from it — a changed member
 *    layout, a wrong owner, or exclusion by a `Sliced` search window.
 * This judgment runs only after a miss is **already established** (it scans the instruction table, worst case
 * "whole method × number of rules"); on the normal path it is never reached.
 */
internal object MissDiagnosis {

    /** How many items to list at most in examples — a diagnosis must be readable, not dump the whole method. */
    private const val MAX_SAMPLES = 5

    /**
     * @param named methods matched by the selector (may lack a body)
     * @param injectable those of them **with a body** (the objects injection actually acts on)
     */
    fun explain(
        node: ClassNode,
        rule: MethodRule,
        named: List<MethodNode>,
        injectable: List<MethodNode>,
    ): List<String> = when {
        named.isEmpty() -> listOf(selectorProblem(node, rule.selector))

        injectable.isEmpty() -> listOf(
            "None of the ${named.size} matched methods has a method body (abstract / native): " +
                named.joinToString(", ") { "${it.name}${it.desc}" } +
                " — injection inserts code into the body; abstract methods have nothing to insert into",
        )

        else -> rule.points.map { [point, _] -> anchorProblem(node, injectable, point) }
    }

    // ---------- selector didn't match ----------

    private fun selectorProblem(node: ClassNode, selector: MethodSelector): String {
        // `<clinit>` is never an injection target, so don't count it when listing "how many methods it has"
        val all = node.methods.filter { it.name != "<clinit>" }
        val wantedNames = selector.names.joinToString("/")
        val sameName = all.filter { it.name in selector.names }
        val desc = selector.desc
        val sameDesc = if (desc == null) emptyList() else all.filter { it.desc == desc }
        return when {
            sameName.isNotEmpty() ->
                "Selector did not match: the class has a method named $wantedNames, but none of the descriptors match — " +
                    "actual: ${sameName.joinToString(", ") { "${it.name}${it.desc}" }}" +
                    (desc?.let { " (the rule writes desc=$it)" } ?: "")

            sameDesc.isNotEmpty() ->
                "Selector did not match: the class has ${sameDesc.size} method(s) with descriptor $desc, " +
                    "but none of the names is among $wantedNames — " +
                    "actual: ${sameDesc.joinToString(", ") { "${it.name}${it.desc}" }}"

            else ->
                "Selector did not match: the class has no method named $wantedNames" +
                    if (all.isEmpty()) " (it has no methods — is the target class right?)"
                    else " (it has ${all.size} method(s): " +
                        all.take(MAX_SAMPLES).joinToString(", ") { "${it.name}${it.desc}" } + "…)"
        }
    }

    // ---------- anchor drift ----------

    private fun anchorProblem(node: ClassNode, methods: List<MethodNode>, point: InjectionPoint): String {
        val where = "anchor ${AnchorResolver.describe(point)}"
        val inMethods = " (within ${methods.joinToString(", ") { "${it.name}${it.desc}" }})"
        return when (point) {
            is InjectionPoint.Sliced ->
                "$where did not hit. Its search window is " +
                    "${point.from?.let(AnchorResolver::describe) ?: "method head"} … " +
                    "${point.to?.let(AnchorResolver::describe) ?: "method tail"}$inMethods — " +
                    "the anchor is not in the window; if it truly exists, from/to do not enclose it. Window contents: " +
                    anchorProblem(node, methods, point.inner)

            is InjectionPoint.Call -> "$where did not hit$inMethods: " + callDrift(node, methods, point)

            is InjectionPoint.FieldAccess -> "$where did not hit$inMethods: " + fieldDrift(methods, point)

            is InjectionPoint.Constant -> "$where did not hit$inMethods: the method's constants are " + constantsHint(
                methods,
            )

            is InjectionPoint.NewInstance -> "$where did not hit$inMethods: " + newHint(node, methods, point)

            is InjectionPoint.Store ->
                "$where did not hit$inMethods: the only **writes** to locals are " + varHint(methods, store = true)

            is InjectionPoint.Load ->
                "$where did not hit$inMethods: the only **reads** from locals are " + varHint(methods, store = false)

            is InjectionPoint.Return -> returnProblem(where, methods, point.ordinal)

            InjectionPoint.ConstructorHead ->
                if (methods.any { it.name == "<init>" }) {
                    "$where did not hit$inMethods: none of the constructors has a `super()`/`this()` delegation call"
                } else {
                    // On non-constructors CTOR_HEAD degrades to the method head and never misses from being a non-constructor
                    "$where did not hit: since the class has no constructor, the engine degrades the anchor to the method head — " +
                        "it must hit in theory; please report this together with the engine log"
                }

            InjectionPoint.Head, InjectionPoint.FinalReturn ->
                "$where did not hit: it must hit when the method has a body — please report this together with the engine log"
        }
    }

    /** The call is in another method, or owner/descriptor don't match — the two kinds of drift are explained separately. */
    private fun callDrift(node: ClassNode, methods: List<MethodNode>, point: InjectionPoint.Call): String {
        val wanted = point.name
        val here = methods.flatMap { it.instructions.toArray().toList() }
            .filterIsInstance<MethodInsnNode>()
            .filter { wanted == null || it.name == wanted }
            .distinctBy { "${it.owner}.${it.name}${it.desc}" }
        if (here.isNotEmpty()) {
            return "The method has a same-name call, but the owner / descriptor does not match — actual: " +
                sample(here.map { "${it.owner}.${it.name}${it.desc}" })
        }
        // Same class, hung on a different method? This is the classic "anchor is right, wrong method was chosen"
        val elsewhere = node.methods
            .filter { candidate -> methods.none { it === candidate } }
            .filter { method -> method.instructions.any { it is MethodInsnNode && (wanted == null || it.name == wanted) } }
        if (elsewhere.isNotEmpty()) {
            return "The class does have that call, but it is in **another method**: " +
                elsewhere.joinToString(", ") { "${it.name}${it.desc}" }
        }
        return if (wanted == null) "The method has no calls at all" else "The method has no call named $wanted; the method's calls are " +
            sample(
                methods.flatMap { it.instructions.toArray().toList() }
                    .filterIsInstance<MethodInsnNode>()
                    .distinctBy { "${it.owner}.${it.name}${it.desc}" }
                    .map { "${it.owner}.${it.name}${it.desc}" },
            )
    }

    private fun fieldDrift(methods: List<MethodNode>, point: InjectionPoint.FieldAccess): String {
        val wanted = point.name
        val accesses = methods.flatMap { it.instructions.toArray().toList() }
            .filterIsInstance<FieldInsnNode>()
        val sameName = accesses.filter { wanted == null || it.name == wanted }
            .distinctBy { "${it.owner}.${it.name}${it.desc}" }
        if (sameName.isNotEmpty()) {
            return "The method has a same-name field access, but the owner / descriptor (or opcode) does not match — actual: " +
                sample(sameName.map { "${it.owner}.${it.name} ${it.desc}" })
        }
        return "The method's field accesses are " +
            sample(
                accesses.distinctBy { "${it.owner}.${it.name}${it.desc}" }
                    .map { "${it.owner}.${it.name} ${it.desc}" },
            )
    }

    private fun constantsHint(methods: List<MethodNode>): String =
        sample(methods.flatMap { AnchorResolver.constantTexts(it) }.distinct())

    private fun newHint(node: ClassNode, methods: List<MethodNode>, point: InjectionPoint.NewInstance): String {
        val news = methods.asSequence().flatMap { it.instructions.toArray().toList() }
            .filterIsInstance<TypeInsnNode>()
            .filter { it.opcode == Opcodes.NEW }
            .map { it.desc }
            .distinct().toList()
        if (news.isEmpty()) {
            return "The method has no `new`" +
                if (point.desc != null) " (the rule also gave desc=${point.desc}, which requires a matching <init> right after the NEW)" else ""
        }
        return "The method `new`s " + sample(news) +
            if (point.desc != null) " (the rule also gave desc=${point.desc}, which requires a matching <init> right after the NEW)" else ""
    }

    private fun varHint(methods: List<MethodNode>, store: Boolean): String {
        val ops = if (store) Opcodes.ISTORE..Opcodes.ASTORE else Opcodes.ILOAD..Opcodes.ALOAD
        val slots = methods.asSequence().flatMap { it.instructions.toArray().toList() }
            .filterIsInstance<VarInsnNode>()
            .filter { it.opcode in ops }
            .map { it.`var` }
            .distinct()
            .sorted().toList()
        return if (slots.isEmpty()) "none" else "slot " + slots.joinToString(", ")
    }

    private fun returnProblem(where: String, methods: List<MethodNode>, ordinal: Int?): String {
        val counts = methods.joinToString(", ") { "${it.name}${it.desc} has ${returnCount(it)} returns" }
        return if (ordinal != null) "$where did not hit: the rule wants the #$ordinal (zero-based) return, but $counts"
        else "$where did not hit: $counts"
    }

    private fun returnCount(method: MethodNode): Int =
        method.instructions.toArray().count { it.opcode in Opcodes.IRETURN..Opcodes.RETURN }

    /** Lists at most [MAX_SAMPLES] items; gives a total count beyond that. */
    private fun sample(values: List<String>): String = when {
        values.isEmpty() -> "(none)"
        values.size <= MAX_SAMPLES -> values.joinToString(", ")
        else -> values.take(MAX_SAMPLES).joinToString(", ") + " and ${values.size} more"
    }
}
