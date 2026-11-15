package org.ohmyloader.api26_3

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ItemLike
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.RegistrationPhase

/**
 * The creative-tab manager: declarative tab registration, cross-mod ordering, and integration
 * with the client-side pagination. A mod declares its tabs during the registry-declaration phase;
 * the manager registers them and, once **every** mod has declared, resolves the display order
 * from the declared [Builder.after] / [Builder.before] constraints (a topological sort over the
 * full flowing-tab set, unconstrained tabs keeping their registration order). The client
 * pagination reads that resolved order.
 *
 * Ids are plain paths under the declaring mod's namespace. Ordering constraints reference full
 * registry ids (`"minecraft:ingredients"`, `"othermod:tab"`); constraints pointing at
 * right-aligned special tabs (hotbar / search / operator / inventory) or at unknown tabs are
 * ignored - the pinned specials do not consume page slots, so ordering against them is
 * meaningless.
 */
object OMLCreativeTabs {

    /** One declared tab: identity, presentation, display items, ordering constraints. */
    class Builder internal constructor(private val namespace: String, private val path: String) {
        /** The tab's display name. */
        var title: Component? = null

        /** The tab's icon supplier, evaluated per icon render. */
        var icon: (() -> ItemStack)? = null

        /** Full registry ids this tab must render **after** (`"minecraft:ingredients"`). */
        fun after(vararg ids: String) {
            after += ids.map(::toRegistryId)
        }

        /** Full registry ids this tab must render **before**. */
        fun before(vararg ids: String) {
            before += ids.map(::toRegistryId)
        }

        /** The tab's display items, re-evaluated on every tab rebuild / search refresh. */
        fun display(items: () -> List<ItemLike>) {
            displayItems = items
        }

        internal val after = mutableListOf<Identifier>()
        internal val before = mutableListOf<Identifier>()
        internal var displayItems: (() -> List<ItemLike>)? = null

        private fun toRegistryId(raw: String): Identifier =
            if (raw.contains(':')) Identifier.parse(raw) else Identifier.fromNamespaceAndPath(namespace, raw)

        internal fun validate() {
            require(title != null) { "creative tab '$namespace:$path' is missing a title" }
            require(icon != null) { "creative tab '$namespace:$path' is missing an icon" }
            require(displayItems != null) { "creative tab '$namespace:$path' is missing display items" }
        }
    }

    /**
     * Declares a creative tab under the declaring mod's namespace and registers it. The display
     * order is resolved once all mods have declared; until then the tab sits at its registration
     * position. Returns the tab's registry key.
     */
    fun declare(path: String, block: Builder.() -> Unit): ResourceKey<CreativeModeTab> {
        val namespace = RegistrationPhase.currentModId()
            ?: throw IllegalStateException(
                "creative tabs are only declarable from OMLRegistryInitializer.onDeclareRegistries",
            )
        val builder = Builder(namespace, path).apply(block)
        builder.validate()
        require(declarations.none { it.key.namespace == namespace && it.key.path == path }) {
            "creative tab '$namespace:$path' declared twice"
        }

        val key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(namespace, path))
        // Slot uniqueness: every managed tab claims the bottom row's next column past vanilla's
        // seven - the client pagination repositions all flowing tabs anyway, so the registered
        // column only has to be unique for the freeze validation.
        val slotColumn = 7 + declarations.size
        val tab = CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, slotColumn)
            .title(builder.title!!)
            .icon(builder.icon!!)
            .displayItems(displayItemsGenerator(builder.displayItems!!))
            .build()
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key.identifier(), tab)

        declarations.add(Declaration(key.identifier(), builder.after, builder.before))
        if (completionHooked.compareAndSet(false, true)) {
            RegistrationPhase.onAllDeclarationsComplete { resolveOrder() }
        }
        return key
    }

    private class Declaration(
        val key: Identifier,
        val after: List<Identifier>,
        val before: List<Identifier>,
    )

    /**
     * `CreativeModeTab.Output` is not accessible from Kotlin, so the display generator cannot be
     * written as a lambda against it - a proxy implements the public `DisplayItemsGenerator` and
     * calls `Output.accept` reflectively (the tab rebuilds its display on every screen open).
     * The resolved `Method` is cached per output class: the rebuild happens on every screen open
     * and search refresh, and a full `getMethods()` scan per rebuild is wasted reflection.
     */
    private fun displayItemsGenerator(items: () -> List<ItemLike>): CreativeModeTab.DisplayItemsGenerator {
        val iface = CreativeModeTab.DisplayItemsGenerator::class.java
        val itemLike = net.minecraft.world.level.ItemLike::class.java
        @Suppress("UNCHECKED_CAST")
        return java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "accept") {
                val output = args[1]
                val accept = acceptMethods.computeIfAbsent(output.javaClass) { type ->
                    type.methods.first {
                        it.name == "accept" && it.parameterCount == 1 && it.parameterTypes[0] == itemLike
                    }.apply { isAccessible = true }
                }
                for (item in items()) accept.invoke(output, item)
            }
            null
        } as CreativeModeTab.DisplayItemsGenerator
    }

    private val acceptMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()

    private val declarations = mutableListOf<Declaration>()
    private val completionHooked = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * The keys of the vanilla right-aligned special tabs (hotbar / search / operator / inventory)
     * - the pinned tabs that sit outside the paging. Read reflectively: the fields are private,
     * and reading a ResourceKey never dereferences the (still unbound at declare time) holders.
     */
    private val pinnedKeys: Set<Identifier> by lazy {
        listOf("HOTBAR", "SEARCH", "OP_BLOCKS", "INVENTORY").mapNotNull { name ->
            runCatching {
                val field = CreativeModeTabs::class.java.getDeclaredField(name)
                field.isAccessible = true
                (field.get(null) as ResourceKey<*>).identifier()
            }.getOrNull()
        }.toSet()
    }

    /**
     * The resolved flowing-tab key order - constraints applied, registration order for
     * unconstrained tabs and for any cycle remainder. Computed once, at the completion of the
     * declaration phase, entirely on keys: the creative-mode-tab holders are still unbound at
     * this point, and dereferencing one throws.
     */
    @Volatile
    var resolvedKeyOrder: List<Identifier>? = null
        private set

    private fun resolveOrder() {
        // listElements() streams the byId list - registration order, which for the vanilla tabs
        // is their own display order. registryKeySet() would NOT do: it wraps a hash map's
        // keySet, and its order scrambles the vanilla tabs the moment the manager is active.
        // Holder references answer key() without touching the (still unbound) values.
        val flowing: MutableList<Identifier> = BuiltInRegistries.CREATIVE_MODE_TAB.listElements()
            .map { it.key().identifier() }
            .filter { it !in pinnedKeys }
            .toList()
            .toMutableList()
        val registrationOrder = flowing.toList()

        val predecessors = HashMap<Identifier, MutableSet<Identifier>>()
        fun edge(from: Identifier, to: Identifier) {
            if (from == to || from !in registrationOrder.toSet() || to !in registrationOrder.toSet()) {
                return
            }
            predecessors.getOrPut(to) { mutableSetOf() }.add(from)
        }
        for (declaration in declarations) {
            val key = declaration.key
            declaration.after.forEach { edge(it, key) }
            declaration.before.forEach { edge(key, it) }
        }

        // Deterministic topological sort: always emit the first (in registration order) tab whose
        // predecessors have all been emitted; a cycle falls back to registration order for the
        // remainder rather than wedging the game.
        val emitted = HashSet<Identifier>()
        val resolved = ArrayList<Identifier>(flowing.size)
        while (flowing.isNotEmpty()) {
            val next = flowing.firstOrNull { key ->
                predecessors[key].orEmpty().all { it in emitted }
            }
            if (next == null) {
                OmlLog.warn("OMLCreativeTabs", "creative tab ordering has a cycle; appending the rest in registration order")
                resolved.addAll(flowing)
                break
            }
            flowing.remove(next)
            emitted.add(next)
            resolved.add(next)
        }

        resolvedKeyOrder = resolved
    }
}
