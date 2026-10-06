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
 * The game classes resolve because the module's tests carry the 26.3 jar. Both version adapters build
 * their transformers from these same rule objects, so one walk covers both version lines.
 */
class HandlerShapeTest {

    private val ruleSets: List<Any> = listOf(
        MinecraftHookTransformer(idPrefix = "test"),
        ServerHookTransformer(idPrefix = "test"),
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
