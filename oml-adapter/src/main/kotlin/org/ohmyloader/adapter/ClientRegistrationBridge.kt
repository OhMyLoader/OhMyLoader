package org.ohmyloader.adapter

import net.minecraft.client.core.registries.BuiltInClientRegistries
import net.minecraft.client.core.registries.ClientRegistries
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.inventory.MenuType
import org.ohmyloader.api.OMLRegistration

/**
 * The game-side half of [OMLRegistration]'s client helpers. The client's own registries
 * (`BuiltInClientRegistries`) are built and frozen by `ClientBootstrap` before the client — and
 * with it every mod's `onInitialize` — is constructed, so a mod cannot write into them. The
 * helpers therefore keep their entries in [OMLRegistration], and this bridge answers the game's
 * lookups with them first, falling back to the vanilla registry.
 *
 * Both entry points are referenced from [ClientRegistrationTransformer].
 */
object ClientRegistrationBridge {

    /**
     * `MenuScreens.getConstructor` replacement: the mod's screen factory for [menuType], or the
     * vanilla registry's. The vanilla path keys the registry by the menu type's registry key, so
     * the lookup is rebuilt here exactly as `MenuScreens.menuTypeKey` does it.
     */
    @JvmStatic
    fun menuScreenConstructor(menuType: MenuType<*>): MenuScreens.ScreenConstructor<*, *>? =
        OMLRegistration.menuScreenOrNull(menuType) ?: vanillaMenuScreen(menuType)

    private fun vanillaMenuScreen(menuType: MenuType<*>): MenuScreens.ScreenConstructor<*, *>? {
        val id = BuiltInRegistries.MENU.getKey(menuType) ?: return null
        return BuiltInClientRegistries.MENU_SCREEN.getValue(ResourceKey.create(ClientRegistries.MENU_SCREEN, id))
    }

    /**
     * `BlockEntityRenderers.createEntityRenderers`'s registry read, redirected: the mod's provider
     * for [key], or the registry's own. Called for every block entity type on every resource
     * reload, so a renderer registered by a mod survives reloads without writing into the frozen
     * registry.
     */
    @JvmStatic
    fun blockEntityProvider(registry: Registry<*>, key: ResourceKey<*>): Any? =
        OMLRegistration.blockEntityRendererOrNull(key) ?: lookup(registry, key)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> lookup(registry: Registry<T>, key: ResourceKey<*>): T? =
        registry.getValue(key as ResourceKey<T>)
}
