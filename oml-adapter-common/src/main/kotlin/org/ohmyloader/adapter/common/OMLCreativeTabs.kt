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
     * The tab's grid position is a hard constraint on two counts. `CreativeModeTabs.validate()` throws on
     * any duplicate (row, column) and vanilla fills columns 0–6 of both rows, so [TAB_COLUMN] sits past
     * them; and the creative screen lays a right-aligned tab at `imageWidth - 27 * (7 - column) + 1`,
     * which puts column [TAB_COLUMN] immediately after the last vanilla button of the row.
     * [CreativeTabPositionTest] fails the build the day that slot is taken.
     */
    internal val TAB_ROW = CreativeModeTab.Row.TOP
    internal const val TAB_COLUMN = 7

    /**
     * The upper bound of the game's own tab-sprite clamp. Vanilla passes `sprites.length`, which lets a
     * column past vanilla's seven index one element out of the array; the rule on
     * `CreativeModeInventoryScreen.extractTabButton` routes that bound through here.
     */
    @JvmStatic
    fun spriteBound(bound: Int): Int = (bound - 1).coerceAtLeast(0)

    fun register(itemLikes: List<ItemLike>) {
        if (itemLikes.isEmpty()) return
        val tab = CreativeModeTab.builder(TAB_ROW, TAB_COLUMN)
            .alignedRight()
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
