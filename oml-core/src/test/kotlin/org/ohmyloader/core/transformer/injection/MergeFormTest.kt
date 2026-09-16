package org.ohmyloader.core.transformer.injection

import org.ohmyloader.api.inject.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The merge-form **declaration**: `merge(source)` + `mergedCall(handler)`.
 *
 * This layer only cares about "what the model recorded": which target class a merge source is
 * attached to, and what owner the merge-form handler should get. "Where the source class's
 * bytecode comes from and whether it is read correctly" is covered by `ModRuleSetsTest` (that part
 * needs a real jar), and "whether `this` is the target instance after merging" is proven by the
 * testmod's real-machine acceptance line. The owner is pinned on purpose: after merging, the handler
 * **lives inside the target class**, so the injection point must reference the target class name —
 * deriving it by the DSL from "which target class is in play" avoids an author-typed name that only
 * blows up at runtime (`NoSuchMethodError`).
 */
class MergeFormTest {

    private val target = "omltest/Target"

    @Test
    fun `merge records the source against the class being opened`() {
        val rules = injection {
            classTarget(target) { merge("omltest/Patch", id = "patch") }
        }
        val source = rules.merges.single()
        assertEquals(target, source.targetInternal)
        assertEquals("omltest/Patch", source.sourceInternal)
        assertEquals("patch", source.id)
    }

    @Test
    fun `mergedCall points the handler at the target class instead of the patch`() {
        val rules = injection {
            classTarget(target) {
                merge("omltest/Patch")
                method("m", desc = "()V") {
                    atHead { mergedCall("onProbe", "()V") }
                }
            }
        }
        val payload = rules.classes.getValue(target).methods.single().points.single().second
        payload as Payload.HandlerCall
        assertEquals(
            target,
            payload.owner,
            "the merged handler lives in the target class, so owner must be the target class"
        )
        assertEquals("onProbe", payload.method)
        assertEquals(HandlerKind.INJECT, payload.kind)
        assertEquals(HandlerVariant.NOTIFY, payload.variant)
    }

    @Test
    fun `mergedCall still takes the same shape as the merged handler form`() {
        val rules = injection {
            classTarget(target) {
                method("m", desc = "()I") {
                    atTail {
                        mergedCall(
                            method = "onValue", desc = "(I)I",
                            kind = HandlerKind.MODIFY_RETURN,
                        )
                    }
                }
            }
        }
        val payload = rules.classes.getValue(target).methods.single().points.single().second
        payload as Payload.HandlerCall
        assertEquals(HandlerKind.MODIFY_RETURN, payload.kind)
        assertEquals("(I)I", payload.desc)
    }

    @Test
    fun `the same source for the same target is merged once`() {
        val one = injection { classTarget(target) { merge("omltest/Patch") } }
        val two = injection { classTarget(target) { merge("omltest/Patch") } }
        val other = injection { classTarget(target) { merge("omltest/Other") } }
        // Merging twice would make every second one collide on names (the members already exist),
        // so duplicates are resolved by "target + source"
        assertEquals(1, one.merge(two).merges.size)
        assertEquals(2, one.merge(other).merges.size)
    }

    @Test
    fun `merging rule sets keeps both sides' merges and rules`() {
        val one = injection {
            classTarget(target) {
                merge("omltest/Patch")
                method("a", desc = "()V") { atHead { omlCall("x") } }
            }
        }
        val two = injection {
            classTarget(target) {
                method("b", desc = "()V") { atHead { omlCall("y") } }
            }
        }
        val merged = one.merge(two)
        assertEquals(2, merged.classes.getValue(target).methods.size, "rules on the same target should accumulate")
        val kept = merged.merges.single()
        assertEquals(target, kept.targetInternal)
        assertEquals("omltest/Patch", kept.sourceInternal)
        assertEquals(1, RuleSet.EMPTY.merge(one).merges.size, "the empty rule set must not swallow merge declarations")
        assertEquals(1, one.merge(RuleSet.EMPTY).merges.size)
    }
}
