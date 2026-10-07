package org.ohmyloader.adapter.common

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import org.ohmyloader.api.registry.OMLRegistry
import org.ohmyloader.api.registry.OMLRegistryProvider

/**
 * Registry and tag reads over the game's built-in registries: every read goes straight to the table and
 * nothing is cached, so a mod's own content reads back exactly like a vanilla entry, from any thread.
 *
 * The views resolve on first read, not when the adapter installs this provider: loader initialization runs
 * before the game's own bootstrap, and touching `BuiltInRegistries` from that early is the failure the
 * game reports itself.
 */
object OMLRegistryBridge : OMLRegistryProvider {
    override val blocks: OMLRegistry by lazy { RegistryView(BuiltInRegistries.BLOCK, Registries.BLOCK) }
    override val items: OMLRegistry by lazy { RegistryView(BuiltInRegistries.ITEM, Registries.ITEM) }
    override val mobEffects: OMLRegistry by lazy { RegistryView(BuiltInRegistries.MOB_EFFECT, Registries.MOB_EFFECT) }
    override val sounds: OMLRegistry by lazy { RegistryView(BuiltInRegistries.SOUND_EVENT, Registries.SOUND_EVENT) }
    override val entityTypes: OMLRegistry by lazy { RegistryView(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE) }
}

/**
 * A tag is addressed by its registry key, and the key is passed in rather than read from the registry:
 * the game's own accessor returns a wildcard, which Kotlin cannot use to build a `TagKey`.
 */
private class RegistryView<T : Any>(
    private val registry: Registry<T>,
    private val registryKey: ResourceKey<Registry<T>>,
) : OMLRegistry {
    override val ids: List<String> get() = registry.keySet().map { it.toString() }

    override fun contains(id: String): Boolean =
        GameProjections.identifier(id)?.let(registry::containsKey) ?: false

    /** An unregistered tag id has no members; the game's registries would otherwise answer with their default. */
    override fun tagged(tagId: String): List<String> {
        val location = GameProjections.identifier(tagId) ?: return emptyList()
        return registry.getTagOrEmpty(TagKey.create(registryKey, location))
            .mapNotNull { holder -> registry.getKey(holder.value())?.toString() }
    }

    override fun tagsOf(id: String): List<String> {
        val location = GameProjections.identifier(id) ?: return emptyList()
        val value = registry.getOptional(location).orElse(null) ?: return emptyList()
        return registry.wrapAsHolder(value).tags().map { it.location().toString() }.toList()
    }
}
