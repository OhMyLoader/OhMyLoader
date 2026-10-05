package org.ohmyloader.adapter.v26_3

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import org.ohmyloader.core.client.KeyBindingDeclarations

/**
 * Applies collected mod key bindings to the live game. The append cannot happen at mod init or
 * `Options` construction time — `Options` is built before mod declarations exist, and the array is
 * replaced from here under the access rule that removed `keyMappings`' final bit (the field is
 * still compile-time final, so the write goes through reflection). Application runs lazily on the
 * first client tick.
 */
object OMLKeyBindingsBridge {

    /** Bound lazily on first use: `Category.register` throws on a duplicate id. */
    private val category: KeyMapping.Category by lazy {
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("oml", "main"))
    }

    private var appliedCount = 0
    private val bound = mutableListOf<Pair<KeyMapping, () -> Unit>>()

    private val keyMappingsField by lazy {
        Options::class.java.getField("keyMappings").apply { isAccessible = true }
    }

    /**
     * Creates mappings for every declaration not yet applied and queues them for dispatch — no
     * game instance needed, so tests exercise it headless. An unresolvable key name degrades to
     * `UNKNOWN` (an unbound mapping): the constructor resolves `(type, value)` through the same
     * cached `Type.getOrCreate` that `InputConstants.UNKNOWN` itself comes from, so the degraded
     * key is the UNKNOWN instance and `isUnbound()` holds.
     */
    internal fun createPending(): List<Pair<KeyMapping, () -> Unit>> {
        val fresh = KeyBindingDeclarations.entries.drop(appliedCount)
        appliedCount = KeyBindingDeclarations.entries.size
        val created = fresh.map { decl ->
            val key = runCatching { InputConstants.getKey(decl.defaultKey) }
                .getOrDefault(InputConstants.UNKNOWN)
            KeyMapping("key.${decl.modId}.${decl.id}", key.type, key.value, category) to decl.onPress
        }
        bound += created
        return created
    }

    /** Appends every pending mapping to the live options; called at the head of each client tick. */
    fun applyPending() {
        val mc = Minecraft.getInstance() ?: return
        val created = createPending()
        if (created.isEmpty()) return
        val field = keyMappingsField
        val current = field.get(mc.options) as Array<KeyMapping>
        field.set(mc.options, current + created.map { it.first }.toTypedArray())
    }

    /** Dispatches a key event to every OML mapping it matches, in declaration order. */
    fun dispatchPress(event: KeyEvent) {
        for ((mapping, onPress) in bound) {
            if (mapping.matches(event)) onPress()
        }
    }
}
