package org.ohmyloader.core.transformer.injection

import org.ohmyloader.api.inject.RuleSet
import org.ohmyloader.core.transformer.IClassTransformer
import org.ohmyloader.core.transformer.IVerifiableTransformer
import org.ohmyloader.core.transformer.TransformContext

/**
 * Rule-based transformer base class: appliesTo/transform are entirely delegated to the rule set, and subclasses
 * only declare their rules and id. It also implements startup self-checking (see [InjectionSpec.verify]).
 *
 * The input is the DSL's output (a [RuleSet]); the engine-side container is wrapped up by this class itself.
 */
abstract class InjectingTransformer(
    /**
     * The rule set this transformer weaves. Exposed (read-only by convention) so the per-version
     * adapter tests can walk the live rules and assert the game jar still matches them — the
     * anchor shapes are then verified against real bytecode in exactly one place, the rule
     * declarations, instead of a hand-copied list in a test.
     */
    val rules: RuleSet,
    override val id: String,
    /** Static problems the frontend (e.g. the Mixin scanner) found while compiling the rules, folded into the same self-check report. */
    private val verifyExtras: List<String> = emptyList(),
) : IClassTransformer, IVerifiableTransformer {

    private val spec = InjectionSpec(rules)

    final override fun appliesTo(internalName: String): Boolean = spec.appliesTo(internalName)

    final override fun transform(context: TransformContext): Boolean = spec.transform(context)

    final override fun verify(loader: ClassLoader?): List<String> = verifyExtras + spec.verify(loader)
}
