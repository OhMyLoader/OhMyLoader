package org.ohmyloader.adapter.common

import org.ohmyloader.core.transformer.IVerifiableTransformer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup injection self-check, driven from a test: every handler the live rule sets name must
 * exist on this module's classpath with exactly the descriptor the rule pushes. The most common way to
 * break a rule is an argument-list edit on one side only — the game's own startup check aborts on it,
 * and this test makes that a build failure instead of a real-machine run that never boots.
 *
 * The game classes resolve because the module's tests carry the 26.3 jar. The 26.3 adapter builds
 * its transformers from these same facts, so this walk covers that line; the tracked snapshot line
 * branches through [HookShapes] onto handlers its own module carries, and its walk lives there.
 */
class HandlerShapeTest {

    // The handover shape must be the one whose handlers live on THIS module's classpath: this
    // walk asserts every named handler resolves locally, and COMMANDS_INSTANCE names
    // V26_3EventBridge, unreachable from here. The real-jar side of that shape is covered by
    // RuleApplyTest (which needs the 26.3 targets) and the 26.3 adapter's own HookShapeTest.
    private val handoverTestShapes = testHookShapes.copy(commandHandover = CommandHandover.PARSE_BOX)

    private val ruleSets: List<Any> = listOf(
        MinecraftHookTransformer(idPrefix = "test", shapes = handoverTestShapes),
        ServerHookTransformer(idPrefix = "test", shapes = handoverTestShapes),
        NetworkCompressionTransformer(idPrefix = "test"),
        NetworkPayloadTransformer(idPrefix = "test"),
    )

    @Test
    fun `every handler the live rules name exists with the descriptor they push`() {
        val transformers = ruleSets.filterIsInstance<IVerifiableTransformer>()
        assertEquals(ruleSets.size, transformers.size, "a rule set stopped carrying the signature check")
        val problems = transformers.flatMap { it.verify(javaClass.classLoader) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
