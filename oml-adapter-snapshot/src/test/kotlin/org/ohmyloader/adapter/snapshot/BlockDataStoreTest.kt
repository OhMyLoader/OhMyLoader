package org.ohmyloader.adapter.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persistent store's payload framing, exercised as a pure encode/decode roundtrip: the game's
 * ValueOutput/ValueInput are thin wrappers around these two calls, and the interesting failure
 * modes (type fidelity, dirty tracking, unknown-tag refusal) all live here.
 */
class BlockDataStoreTest {

    @Test
    fun `values of every supported type survive the roundtrip`() {
        val store = OMLBlockDataStore()
        store.putBoolean("flag", true)
        store.putInt("progress", 42)
        store.putLong("big", -9000000000L)
        store.putFloat("speed", 1.5f)
        store.putDouble("precise", 3.25)
        store.putString("name", "value with\nnewlines and ünïcode")

        val restored = OMLBlockDataStore().also { it.decode(store.encode()) }
        assertEquals(true, restored.getBoolean("flag"))
        assertEquals(42, restored.getInt("progress"))
        assertEquals(-9000000000L, restored.getLong("big"))
        assertEquals(1.5f, restored.getFloat("speed"))
        assertEquals(3.25, restored.getDouble("precise"))
        assertEquals("value with\nnewlines and ünïcode", restored.getString("name"))
    }

    @Test
    fun `a decode over an existing store replaces its content`() {
        val store = OMLBlockDataStore()
        store.putInt("stale", 1)
        val fresh = OMLBlockDataStore().also { it.putString("k", "v") }
        store.decode(fresh.encode())

        assertNull(store.getString("stale-and-absent"))
        assertEquals(0, store.getInt("stale"), "the stale entry must be gone after decode")
        assertEquals("v", store.getString("k"))
    }

    @Test
    fun `writes mark the store dirty and consumeDirty is one-shot`() {
        val store = OMLBlockDataStore()
        assertFalse(store.consumeDirty(), "an untouched store is clean")
        store.putInt("k", 1)
        assertTrue(store.consumeDirty())
        assertFalse(store.consumeDirty(), "the second consume must not re-report the same write")
    }

    @Test
    fun `an unknown type tag is a loud corruption, not a silent skip`() {
        // hand-framed payload: count=1, writeUTF("k") = 0x0001 'k', then a tag byte no version wrote
        val corrupted = java.util.Base64.getEncoder()
            .encodeToString(byteArrayOf(0, 0, 0, 1, 0, 1, 'k'.code.toByte(), 99))
        val store = OMLBlockDataStore()
        val error = kotlin.test.assertFailsWith<IllegalStateException> { store.decode(corrupted) }
        assertTrue("unknown type tag" in error.message!!)
    }
}
