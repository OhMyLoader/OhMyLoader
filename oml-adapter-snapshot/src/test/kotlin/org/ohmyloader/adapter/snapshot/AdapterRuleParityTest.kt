package org.ohmyloader.adapter.snapshot

import org.ohmyloader.api.inject.AccessRule
import org.ohmyloader.api.inject.Payload
import org.ohmyloader.api.inject.RuleSet
import kotlin.test.Test
import kotlin.test.assertEquals
import org.ohmyloader.adapter.v26_3.MinecraftHookTransformer as StableClientHooks
import org.ohmyloader.adapter.v26_3.ServerHookTransformer as StableServerHooks

/**
 * The snapshot adapter is a deliberate copy of the stable one — it exists so the eventual 26.4 adapter
 * is a small diff instead of a rewrite, which only holds while the two stay in step.
 *
 * Each side's `HookShapeTest` checks its own rules against its own game jar, so neither one notices a
 * change that was applied to a single side: the 26_3 fix passes, the snapshot copy keeps the old (now
 * wrong) rule, and both are green. This test compares the two **rule surfaces**, and a difference must
 * be either mirrored or recorded in [ACCEPTED_DIFFERENCES] — the point is that divergence is a
 * decision, never an oversight.
 *
 * Comments and version strings are not part of the surface: the rule data is what the engine executes.
 */
class AdapterRuleParityTest {

    @Test
    fun `the client rule surface is the same in both adapters`() =
        assertParity(surface(StableClientHooks().rules), surface(MinecraftHookTransformer().rules), "client")

    @Test
    fun `the server rule surface is the same in both adapters`() =
        assertParity(surface(StableServerHooks().rules), surface(ServerHookTransformer().rules), "server")

    private fun assertParity(stable: List<String>, snapshot: List<String>, side: String) {
        val stableCounts = stable.groupingBy { it }.eachCount()
        val snapshotCounts = snapshot.groupingBy { it }.eachCount()
        val differing = (stableCounts.keys + snapshotCounts.keys)
            .filter { it !in ACCEPTED_DIFFERENCES && stableCounts[it] != snapshotCounts[it] }
            .sorted()
            .map { "$it (26_3=${stableCounts[it] ?: 0}, snapshot=${snapshotCounts[it] ?: 0})" }
        assertEquals(
            emptyList(),
            differing,
            "$side rule surface differs between the stable and snapshot adapters — mirror the change in " +
                "the other adapter, or record it in ACCEPTED_DIFFERENCES with the reason",
        )
    }

    /** One line per rule, holding only what must match: target, selector, anchor, payload. */
    private fun surface(rules: RuleSet): List<String> = buildList {
        for ([target, classRules] in rules.classes) {
            for (rule in classRules.methods) {
                val anchors = rule.points.joinToString(",") { [point, payload] ->
                    "${normalize(point.toString())}->${describe(payload)}"
                }
                add(
                    "method $target ${
                        rule.selector.names.sorted().joinToString("|")
                    }${rule.selector.desc.orEmpty()}${
                        policy(
                            rule.require,
                            rule.expect,
                            rule.allow,
                            rule.optional,
                        )
                    } [$anchors]",
                )
            }
            for (rule in classRules.accessRules) add("access $target ${describe(rule)}")
        }
        for (merge in rules.merges) add("merge ${merge.targetInternal} <- ${merge.sourceInternal}")
    }

    private fun describe(rule: AccessRule): String =
        "${rule.kind} ${rule.names.sorted().joinToString("|")}${rule.desc.orEmpty()} " +
            rule.visibilities.joinToString(",") + if (rule.removeFinal) ",removeFinal" else "" +
            policy(rule.require, rule.expect, rule.allow, rule.optional)

    /** The match-count policy is part of the surface: `require(1)` on one side only is exactly the drift to catch. */
    private fun policy(require: Int?, expect: Int?, allow: Int?, optional: Boolean): String =
        listOfNotNull(
            require?.let { " require=$it" },
            expect?.let { " expect=$it" },
            allow?.let { " allow=$it" },
            " optional=$optional".takeIf { optional },
        ).joinToString("")

    /**
     * [Payload.Raw] carries a lambda, whose `toString` is an identity hash — that half cannot be
     * compared textually, so the surface records that such a rule exists and leaves its body to the
     * side's own shape test.
     */
    private fun describe(payload: Payload): String =
        if (payload is Payload.Raw) "Raw" else normalize(payload.toString())

    /** The package segment (`v26_3` / `snapshot`) and the version id are the two intended differences. */
    private fun normalize(text: String): String =
        text.replace("v26_3", "ADAPTER")
            .replace("snapshot", "ADAPTER")
            .replace("26.4-snapshot-2", "VERSION")
            .replace("26.3", "VERSION")

    private companion object {
        /**
         * Rule-surface lines allowed to differ, each for a genuine version-shape difference. Empty on
         * purpose: the two adapters declare the same rules today, and an entry here must never be used
         * to silence drift.
         */
        val ACCEPTED_DIFFERENCES: Set<String> = emptySet()
    }
}
