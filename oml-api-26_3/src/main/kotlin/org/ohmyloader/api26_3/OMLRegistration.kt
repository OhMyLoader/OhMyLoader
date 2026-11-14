package org.ohmyloader.api26_3

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import org.ohmyloader.api.RegistrationPhase

/**
 * Registration helpers for [org.ohmyloader.api.OMLRegistryInitializer.onDeclareRegistries] and
 * client registration, replacing the boilerplate (and the 26.3 privatization workarounds) a mod
 * would otherwise reimplement per project. Ids are plain paths (`"iron_chest"`): the namespace is
 * the declaring mod's id, so two mods registering `"chest"` cannot collide.
 *
 * The declare-phase helpers refuse to run outside `onDeclareRegistries` — outside the phase the
 * vanilla registries are frozen and the namespace would be a guess. The client helpers run from
 * `OMLModInitializer.onInitialize` guarded to the client side; they reach client-only entry
 * points through reflection because 26.3 privatized them (`MenuScreens.register`,
 * `BlockEntityRenderers.register`, the `MenuType` constructor) and Fabric mods reach them
 * through access wideners OML does not carry — the bridges live here so every mod shares one
 * maintained copy instead of growing its own.
 */
object OMLRegistration {

    private fun namespace(): String =
        RegistrationPhase.currentModId()
            ?: throw IllegalStateException(
                "registration helpers are only callable from OMLRegistryInitializer.onDeclareRegistries",
            )

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(RegistrationPhase.currentModId()!!, path)

    // ---------------------------------------------------------------------------------------------
    // Declare-phase registration
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a block under the declaring mod's namespace and replays the state registration
     * vanilla performs in its own static init: every state goes into
     * `Block.BLOCK_STATE_REGISTRY` (network/serialization ids) and receives `initCache()`
     * (occlusion shapes — without it the chunk renderer NPEs when the block is placed). [id] is a
     * plain path; [properties] are completed with the block's resource key before [factory] runs.
     */
    fun registerBlock(id: String, properties: BlockBehaviour.Properties, factory: (BlockBehaviour.Properties) -> Block): Block {
        val identifier = id(id)
        val block = factory(properties.setId(ResourceKey.create(Registries.BLOCK, identifier)))
        Registry.register(BuiltInRegistries.BLOCK, identifier, block)
        for (state in block.stateDefinition.possibleStates) {
            Block.BLOCK_STATE_REGISTRY.add(state)
            state.initCache()
        }
        return block
    }

    /** Registers an item under the declaring mod's namespace, the resource key pre-applied to [properties]. */
    fun registerItem(id: String, properties: Item.Properties, factory: (Item.Properties) -> Item): Item {
        val identifier = id(id)
        val item = factory(properties.setId(ResourceKey.create(Registries.ITEM, identifier)))
        Registry.register(BuiltInRegistries.ITEM, identifier, item)
        return item
    }

    /** Registers a block entity type under the declaring mod's namespace. */
    fun <T : BlockEntity> registerBlockEntityType(id: String, type: BlockEntityType<T>): BlockEntityType<T> {
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(id), type)
        return type
    }

    /**
     * Registers a menu type whose menus are built by [factory] (`syncId`, player inventory).
     * 26.3 privatized the `MenuType` constructor (and its `MenuSupplier` interface), so the
     * instance is assembled through one reflective bridge — run once per menu type, here, before
     * the registries freeze.
     */
    fun <M : AbstractContainerMenu> registerMenuType(id: String, factory: (Int, Inventory) -> M): MenuType<M> {
        val supplierClass = MenuType::class.java.declaredClasses.first { it.simpleName == "MenuSupplier" }
        val supplier = java.lang.reflect.Proxy.newProxyInstance(
            supplierClass.classLoader,
            arrayOf(supplierClass),
        ) { _, method, args ->
            when (method.name) {
                "create" -> factory(args[0] as Int, args[1] as Inventory)
                "toString" -> "OMLRegistration menu supplier"
                else -> null
            }
        }
        val constructor = MenuType::class.java.getDeclaredConstructor(supplierClass, FeatureFlagSet::class.java)
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val menuType = constructor.newInstance(supplier, FeatureFlags.VANILLA_SET) as MenuType<M>
        Registry.register(BuiltInRegistries.MENU, id(id), menuType)
        return menuType
    }

    /**
     * Registers a creative tab in the declaring mod's namespace and returns its key (used by
     * `Item.Properties`-level unlocks and `/give`-adjacent tooling, rarely by the mod itself).
     *
     * The (row, column) slot cannot be picked by reading the vanilla tabs — they are still
     * unbound holders at declare time — so the column is derived from the registry size, which
     * assumes vanilla splits its tabs evenly across the two rows (26.3: seven and seven, so the
     * tab lands at the bottom row's next free column).
     */
    fun registerCreativeTab(
        id: String,
        title: Component,
        icon: () -> ItemStack,
        displayItems: () -> List<ItemLike>,
    ): ResourceKey<CreativeModeTab> {
        val column = BuiltInRegistries.CREATIVE_MODE_TAB.size() / 2
        val tab = CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, column)
            .title(title)
            .icon(icon)
            .displayItems(displayItemsGenerator(displayItems))
            .build()
        val key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id(id))
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key.identifier(), tab)
        return key
    }

    /**
     * `CreativeModeTab.Output` is not accessible from Kotlin, so the display generator cannot be
     * written as a lambda against it - a proxy implements the public `DisplayItemsGenerator` and
     * calls `Output.accept` reflectively (the tab rebuilds its display a handful of times per
     * screen open).
     */
    private fun displayItemsGenerator(items: () -> List<ItemLike>): CreativeModeTab.DisplayItemsGenerator {
        val iface = CreativeModeTab.DisplayItemsGenerator::class.java
        val itemLike = ItemLike::class.java
        @Suppress("UNCHECKED_CAST")
        return java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "accept") {
                val output = args[1]
                val accept = output.javaClass.methods.first {
                    it.name == "accept" && it.parameterCount == 1 && it.parameterTypes[0] == itemLike
                }
                accept.isAccessible = true
                for (item in items()) accept.invoke(output, item)
            }
            null
        } as CreativeModeTab.DisplayItemsGenerator
    }

    // ---------------------------------------------------------------------------------------------
    // Client registration (from onInitialize, guarded to the client side)
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers the screen factory for [menuType] (the vanilla `MenuScreens.register` path,
     * privatized in 26.3). [factory] receives the menu, the player inventory and the title —
     * the same shape the vanilla `ScreenConstructor` hands its screen.
     */
    fun <M : AbstractContainerMenu, U : net.minecraft.client.gui.screens.Screen> registerMenuScreen(
        menuType: MenuType<M>,
        factory: (M, Inventory, Component) -> U,
    ) {
        val constructorClass = net.minecraft.client.gui.screens.MenuScreens::class.java.declaredClasses
            .first { it.simpleName == "ScreenConstructor" }
        val constructor = java.lang.reflect.Proxy.newProxyInstance(
            constructorClass.classLoader,
            arrayOf(constructorClass),
        ) { _, method, args ->
            when (method.name) {
                "create" ->
                    factory(args[0] as M, args[1] as Inventory, args[2] as Component)
                "fromPacket" -> {
                    // The interface's default: build the menu from the window id, then open.
                    val mc = args[2] as net.minecraft.client.Minecraft
                    val player = mc.player ?: return@newProxyInstance null
                    val menu = (args[1] as MenuType<M>).create(args[3] as Int, player.inventory)
                    val screen = factory(menu, player.inventory, args[0] as Component)
                    player.containerMenu = (screen as net.minecraft.client.gui.screens.inventory.MenuAccess<*>).menu
                    mc.gui.setScreen(screen)
                    null
                }
                else -> null
            }
        }
        val register = net.minecraft.client.gui.screens.MenuScreens::class.java.declaredMethods
            .first { it.name == "register" }
        register.isAccessible = true
        register.invoke(null, menuType, constructor)
    }

    /**
     * Registers the block entity renderer for [blockEntityType] (the vanilla
     * `BlockEntityRenderers.register` path, privatized in 26.3).
     */
    fun <T : BlockEntity, S : net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState>
        registerBlockEntityRenderer(
            blockEntityType: BlockEntityType<T>,
            factory: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) ->
                net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T, S>,
        ) {
        val provider = object : net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider<T, S> {
            override fun create(
                context: net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context,
            ): net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T, S> = factory(context)
        }
        val register = net.minecraft.client.renderer.blockentity.BlockEntityRenderers::class.java.declaredMethods
            .first { it.name == "register" }
        register.isAccessible = true
        register.invoke(null, blockEntityType, provider)
    }
}
