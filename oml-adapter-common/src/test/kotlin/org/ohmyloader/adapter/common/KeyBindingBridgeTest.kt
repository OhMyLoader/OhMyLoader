package org.ohmyloader.adapter.common

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent
import org.ohmyloader.core.client.KeyBindingDeclarations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The key-binding bridge against the game's real `KeyMapping`: a declaration becomes a live mapping
 * (game name, shared category, key resolution) and a synthesized `KeyEvent` dispatches the
 * registered callback exactly once — including through `EventBridge`'s action gate, since a
 * non-press action must never fire. Declaration ids are unique per test because the declaration
 * collection is a process-wide singleton.
 */
class KeyBindingBridgeTest {

    @Test
    fun `a declaration becomes a live mapping under the oml category`() {
        KeyBindingDeclarations.entries += KeyBindingDeclarations.Entry("kb_shape", "probe", "key.keyboard.k") {}
        val (mapping, _) = OMLKeyBindingsBridge.createPending().single()
        assertEquals("key.kb_shape.probe", mapping.name)
        assertEquals("oml:main", mapping.category.id().toString())
        assertEquals("key.keyboard.k", mapping.defaultKey.name)
    }

    @Test
    fun `an unresolvable key name degrades to an unbound mapping`() {
        // getKey(String) throws NumberFormatException on a non-numeric suffix after a known prefix;
        // the bridge must map that to InputConstants.UNKNOWN, not fail startup.
        KeyBindingDeclarations.entries += KeyBindingDeclarations.Entry("kb_bad", "probe", "key.keyboard.nope") {}
        val (mapping, _) = OMLKeyBindingsBridge.createPending().single()
        assertTrue(mapping.isUnbound, "a bad default key must leave the mapping unbound")
    }

    @Test
    fun `a matching key event fires the callback exactly once`() {
        var presses = 0
        KeyBindingDeclarations.entries += KeyBindingDeclarations.Entry("kb_press", "probe", "key.keyboard.k") {
            presses++
        }
        val (mapping, _) = OMLKeyBindingsBridge.createPending().single()

        // matches() compares the mapping's key value against the record's first component.
        OMLKeyBindingsBridge.dispatchPress(KeyEvent(mapping.defaultKey.value, 0, 0))
        assertEquals(1, presses)
        // A different key is not ours to consume.
        OMLKeyBindingsBridge.dispatchPress(KeyEvent(mapping.defaultKey.value + 1, 0, 0))
        assertEquals(1, presses)
    }

    @Test
    fun `a non-press action never dispatches`() {
        var presses = 0
        KeyBindingDeclarations.entries += KeyBindingDeclarations.Entry("kb_gate", "probe", "key.keyboard.k") {
            presses++
        }
        val (mapping, _) = OMLKeyBindingsBridge.createPending().single()
        // Headless: Minecraft.getInstance() is null, so the screen gate is vacuous and the action
        // gate alone is under test. A real press uses action 1 (SDLEventHandler's press encoding).
        EventBridge.onKeyPress(0L, InputConstants.RELEASE, KeyEvent(mapping.defaultKey.value, 0, 0))
        EventBridge.onKeyPress(0L, -1, KeyEvent(mapping.defaultKey.value, 0, 0))
        assertEquals(0, presses)
        EventBridge.onKeyPress(0L, InputConstants.PRESS, KeyEvent(mapping.defaultKey.value, 0, 0))
        assertEquals(1, presses)
    }
}
