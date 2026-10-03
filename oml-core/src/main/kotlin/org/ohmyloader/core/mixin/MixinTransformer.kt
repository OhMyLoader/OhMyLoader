package org.ohmyloader.core.mixin

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.diagnostics.Diagnostics
import org.ohmyloader.core.transformer.IClassTransformer
import org.ohmyloader.core.transformer.IVerifiableTransformer
import org.ohmyloader.core.transformer.TransformContext
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The unified transformer for the Mixin front-end: **merge first, then inject** — a hard order, not
 * a preference. [ClassMerger] swaps the method body of `@Overwrite` methods while injection inserts
 * code inside a method; if injection ran before merging, the merge would discard the already-injected
 * method body wholesale and the injection would vanish silently. The merge decides what the method
 * body looks like; the injection decides what to insert into it.
 * Both kinds of changes share one self-check report ([verify]): problems found during merging
 * (`@Shadow` not found, `@Overwrite` conflicts, constructor snippets containing non-mergeable
 * instructions...) are handed to the startup self-check together with injection-era problems, and
 * the default is to `fail`.
 */
internal class MixinTransformer(
    override val id: String,
    private val merges: List<ClassMerger.MixinClass>,
    private val injection: InjectingTransformer?,
    private val verifyExtras: List<String>,
) : IClassTransformer, IVerifiableTransformer {

    private val mergeTargets: Set<String> = merges.mapTo(mutableSetOf()) { it.targetInternal }

    /** All merge problems that have occurred (including each mixin's static problems) -- collected for the startup self-check. */
    private val problems = mutableListOf<String>()

    /** Cross-mixin `@Overwrite` ledger: the same target method overwritten by two mixins must be reported as a conflict. */
    private val overwritten = mutableMapOf<String, String>()

    override fun appliesTo(internalName: String): Boolean =
        internalName in mergeTargets || injection?.appliesTo(internalName) == true

    override fun transform(context: TransformContext): Boolean {
        var changed = false

        // 1) Merge: may add members to the target class or replace method bodies
        val here = merges.filter { it.targetInternal == context.internalName }
        if (here.isNotEmpty()) {
            val mergeStarted = System.nanoTime()
            var fields = 0
            var methods = 0
            var overwrites = 0
            var shadows = 0
            var renamed = 0
            var accessors = 0
            var invokers = 0
            var constructors = 0
            for (mixin in here) {
                val result = ClassMerger.merge(mixin, context.node, overwritten)
                fields += result.fields
                methods += result.methods
                overwrites += result.overwrites
                shadows += result.shadows
                renamed += result.renamed
                accessors += result.accessors
                invokers += result.invokers
                constructors += result.constructors
                problems += result.problems
                if (result.problems.isNotEmpty()) {
                    // Merge problems make the merged bytecode **definitely wrong** (`@Shadow` pointing to a
                    // non-existent member ⇒ a runtime NoSuchFieldError; `@Overwrite` conflict ⇒ two mods
                    // fighting over the same method…), so the hard rule "provable rule error = hard
                    // failure" applies: fail this class load instead of putting knowingly broken bytecode
                    // into the game.
                    result.problems.forEach { OmlLog.error("Mixin", "merge problem: $it") }
                    throw InjectionError(
                        "Mixin class merge failed (${mixin.className} → ${context.internalName}):\n" +
                            result.problems.joinToString("\n")
                    )
                }
                if (result.changed) changed = true
            }
            // A one-line acceptably-verifiable merge report: E2E relies on it to confirm "the merge really
            // happened on real game classes", and "`@Shadow` checks passed for n" also proves OML's
            // member namespace matches the real game classes
            OmlLog.info(
                "Mixin",
                "class merge: ${context.internalName} ← ${here.size} mixin(s)" +
                    " (added $fields field(s) / $methods method(s), @Overwrite $overwrites," +
                    " @Shadow validated $shadows, collision auto-renamed $renamed" +
                    (if (accessors > 0) ", @Accessor synthesized $accessors" else "") +
                    (if (invokers > 0) ", @Invoker synthesized $invokers" else "") +
                    (if (constructors > 0) ", constructors merged $constructors" else "") + ")"
            )
            Diagnostics.recordPhase("merge", System.nanoTime() - mergeStarted)
        }

        // 2) Inject: operates on the method bodies **after** merging
        if (injection != null && injection.appliesTo(context.internalName)) {
            val injectStarted = System.nanoTime()
            changed = injection.transform(context) || changed
            Diagnostics.recordPhase("inject", System.nanoTime() - injectStarted)
        }
        return changed
    }

    override fun verify(loader: ClassLoader?): List<String> =
        verifyExtras + problems + (injection?.verify(loader) ?: emptyList())
}
