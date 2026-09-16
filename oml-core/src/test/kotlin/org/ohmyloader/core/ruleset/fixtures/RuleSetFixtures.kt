package org.ohmyloader.core.ruleset.fixtures

import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.api.inject.*

/**
 * Fixtures for rule-set scanning: **only** bundled into a temp jar in tests, never auto-discovered.
 *
 * They are compiled solely so there is real bytecode to scan — discovery reads bytecode and
 * evaluation uses reflection, so both phases need real classes to run. Each fixture corresponds to
 * one scenario the scanner must distinguish.
 */

/** Normal: yields a single rule. */
@RuleSource("fixture-ok")
object OkRules : RuleSetProvider {
    override fun rules(): RuleSet = injection {
        classTarget("omltest/Target") {
            method("probe", desc = "()V") {
                atHead { call("org/ohmyloader/core/OMLCore", "noop", "()V") }
                require(1)
            }
        }
    }
}

/** Missing interface: declares `@RuleSource` but does not implement [RuleSetProvider]. */
@RuleSource("fixture-no-interface")
object NoInterfaceRules

/**
 * References a class outside the allow-list.
 *
 * An ASM type stands in for a "game class": the criterion is "is it under the allow-list prefix",
 * which is independent of which particular library it is — real game classes cannot be compiled
 * into the test, and the game jar is not on the test classpath.
 */
@RuleSource("fixture-game-ref")
object GameRefRules : RuleSetProvider {
    @Suppress("unused")
    private val forbidden: ClassNode? = null

    override fun rules(): RuleSet = injection {
        classTarget("omltest/Target") {
            method("probe", desc = "()V") { atHead { call("a/b/C", "d", "()V") } }
        }
    }
}

/**
 * Merge source.
 *
 * It is **allowed** to reference classes outside the allow-list — it moves into the target class,
 * so it is supposed to use game classes. Validation therefore targets only rule classes, not merge
 * sources; this fixture pins that difference (its field is an ASM type).
 */
class MergePatch {
    @Suppress("unused")
    private val forbidden: ClassNode? = null

    fun onProbe() {
    }
}

/** Declares merging [MergePatch] into the target class and can call its handler via the merged form. */
@RuleSource("fixture-merge")
object MergeRules : RuleSetProvider {
    override fun rules(): RuleSet = injection {
        classTarget("omltest/Target") {
            merge("org/ohmyloader/core/ruleset/fixtures/MergePatch", id = "patch")
            method("probe", desc = "()V") {
                atHead {
                    mergedCall("onProbe", "()V", kind = HandlerKind.INJECT)
                }
            }
        }
    }
}

/** Evaluation failure: the rule set itself throws while building. */
@RuleSource("fixture-throws")
object ThrowingRules : RuleSetProvider {
    override fun rules(): RuleSet = error("rule-set construction failed (fixture)")
}
