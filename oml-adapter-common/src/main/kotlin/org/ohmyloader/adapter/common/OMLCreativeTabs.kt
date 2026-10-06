package org.ohmyloader.adapter.common

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ItemLike
import org.ohmyloader.core.OMLCore

/**
 * The shared `oml:main` creative tab, auto-populated with every materialized mod item. Registered
 * at the registry freeze point, after vanilla's own tabs exist and while the registry still accepts
 * writes; `CreativeModeTabs.tabs()` streams the registry dynamically, so no further hook is needed
 * for the tab to show up in the creative screen.
 */
object OMLCreativeTabs {

    /**
     * The tab's grid position is a hard constraint, not presentation: `CreativeModeTabs.validate()`
     * runs after this registration and throws on any duplicate (row, column). Vanilla occupies
     * columns 0–6 of both rows; [CreativeTabPositionTest] fails the build the day column 9 is taken.
     */
    internal val TAB_ROW = CreativeModeTab.Row.TOP
    internal const val TAB_COLUMN = 9

    fun register(itemLikes: List<ItemLike>) {
        if (itemLikes.isEmpty()) return
        val tab = CreativeModeTab.builder(TAB_ROW, TAB_COLUMN)
            .title(Component.translatable("itemGroup.oml.main"))
            .icon { ItemStack(itemLikes.first()) }
            .displayItems { _, output ->
                // `Output` is a protected nested type in the game jar's InnerClasses attribute, so
                // Kotlin cannot name it or reference its members; the receiver is driven through
                // reflection, picking the ItemLike overload by parameter type.
                val accept = output.javaClass.methods.first {
                    it.name == "accept" && it.parameterCount == 1 &&
                        it.parameterTypes[0].name == "net.minecraft.world.level.ItemLike"
                }.apply { isAccessible = true }
                itemLikes.forEach { accept.invoke(output, it) }
            }
            .build()
        val key = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath("oml", "main"),
        )
        // The registry handle and the static register entry point are resolved reflectively: naming
        // the `Registry` type here drags its serialization-library supertypes (com.mojang.serialization)
        // onto the adapter's compile classpath, which only the game supplies at run time.
        val loader = OMLCore.gameClassLoader()
        val tabRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
            .getField("CREATIVE_MODE_TAB").get(null)
        val register = Class.forName("net.minecraft.core.Registry", true, loader).methods
            .filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.name == "register" && it.parameterCount == 3
            }
            .first { it.parameterTypes[1] == key.javaClass }
        register.invoke(null, tabRegistry, key, tab)
    }
}
