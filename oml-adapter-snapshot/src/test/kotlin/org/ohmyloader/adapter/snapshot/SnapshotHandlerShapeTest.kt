package org.ohmyloader.adapter.snapshot

import org.ohmyloader.adapter.common.MinecraftHookTransformer
import org.ohmyloader.adapter.common.NetworkCompressionTransformer
import org.ohmyloader.adapter.common.NetworkPayloadTransformer
import org.ohmyloader.adapter.common.ServerHookTransformer
import org.ohmyloader.core.transformer.IVerifiableTransformer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup injection self-check, driven from a test, for the snapshot line's own rule sets. The
 * shared [org.ohmyloader.adapter.common.HandlerShapeTest] walks the 26.3 facts this module's
 * reference build compiles against — the snapshot facts name handlers that exist only here
 * ([SnapshotEventBridge.onRegistryFreeze]) and descriptors only this line's jar declares, so a
 * one-side-only edit has to fail here, not at game startup.
 */
class SnapshotHandlerShapeTest {

    @Test
    fun `every handler the snapshot rules name exists with the descriptor they push`() {
        val transformers = listOf(
            MinecraftHookTransformer(idPrefix = "snapshot", shapes = snapshotHookShapes),
            ServerHookTransformer(idPrefix = "snapshot", shapes = snapshotHookShapes),
            NetworkCompressionTransformer(idPrefix = "snapshot"),
            NetworkPayloadTransformer(idPrefix = "snapshot"),
        ).filterIsInstance<IVerifiableTransformer>()
        assertEquals(4, transformers.size, "a rule set stopped carrying the signature check")
        val problems = transformers.flatMap { it.verify(javaClass.classLoader) }
        assertTrue(
            problems.isEmpty(),
            "the snapshot rules name broken handlers:\n${problems.joinToString("\n")}",
        )
    }
}
