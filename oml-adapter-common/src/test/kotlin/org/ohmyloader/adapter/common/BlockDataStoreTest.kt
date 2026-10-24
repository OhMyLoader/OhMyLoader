package org.ohmyloader.adapter.common

import org.ohmyloader.api.content.OMLDataItemStack
import kotlin.test.*

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
        store.putItemStack("output", OMLDataItemStack("techmod:copper_plate", 3))

        val restored = OMLBlockDataStore().also { it.decode(store.encode()) }
        assertEquals(true, restored.getBoolean("flag"))
        assertEquals(42, restored.getInt("progress"))
        assertEquals(-9000000000L, restored.getLong("big"))
        assertEquals(1.5f, restored.getFloat("speed"))
        assertEquals(3.25, restored.getDouble("precise"))
        assertEquals("value with\nnewlines and ünïcode", restored.getString("name"))
        assertEquals(OMLDataItemStack("techmod:copper_plate", 3), restored.getItemStack("output"))
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
    fun `a stored default and an absent key are different answers`() {
        val store = OMLBlockDataStore()
        assertFalse(store.hasKey("progress"), "nothing was written yet")
        store.putInt("progress", 0)
        assertTrue(store.hasKey("progress"), "a stored zero is a stored value")
        assertEquals(setOf("progress"), store.keys())

        store.putString("owner", "ruby")
        assertEquals(listOf("progress", "owner"), store.keys().toList(), "keys keep write order")

        assertTrue(store.remove("progress"))
        assertFalse(store.remove("progress"), "removing what is already gone says so")
        assertFalse(store.hasKey("progress"))
        assertEquals(7, store.getInt("progress", 7), "the absent key falls back to the caller's default")
        assertEquals(setOf("owner"), store.keys())

        // the removal has to reach the save, or the key returns on the next load
        assertTrue(store.consumeDirty(), "remove() is a write")
        val restored = OMLBlockDataStore().also { it.decode(store.encode()) }
        assertEquals(setOf("owner"), restored.keys())

        val copy = store.keys()
        store.putInt("extra", 1)
        assertEquals(setOf("owner"), copy, "the caller's set is a copy, not the live map's view")
    }

    @Test
    fun `an unknown type tag is a loud corruption, not a silent skip`() {
        // hand-framed payload: count=1, writeUTF("k") = 0x0001 'k', then a tag byte no version wrote
        val corrupted = java.util.Base64.getEncoder()
            .encodeToString(byteArrayOf(0, 0, 0, 1, 0, 1, 'k'.code.toByte(), 99))
        val store = OMLBlockDataStore()
        val error = assertFailsWith<IllegalStateException> { store.decode(corrupted) }
        assertTrue("unknown type tag" in error.message!!)
    }
}
