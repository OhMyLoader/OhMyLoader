package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.OmlLog
import org.ohmyloader.adapter.v26_3.ModAssetInjector.resolveResource
import org.ohmyloader.core.OMLCore
import org.ohmyloader.core.adapter.Refl
import org.ohmyloader.content.ModAssetIndex
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy

/**
 * 26.3 mod asset injection: a reflection-`Proxy` `PackResources` mounted into `PackRepository` (no compile-time game dependency). A shape mismatch here does not throw — it reads as "the mod silently has no assets", so every reflection point is written against the live jar shape.
 * 26.3 hard facts honored here: `PackLocationInfo`/`PackSelectionConfig` live in `net.minecraft.server.packs` (not `.repository`); one `ResourcesSupplier` with two independent entries (`openMetadata` + `openResources`); the `pack` metadata section is mandatory (a null one fails `readMetaAndCreate`) and its format must be copied from `WorldVersion.packVersion(PackType)` (an int became a value object, so it cannot be guessed); a `PackResources` *is* a `PackMetadataResources` and its `getResource`/`getNamespaces`/`listResources` take a `PackType`; item assets are `items/<id>.json` *definitions*, not models — the vanilla jar ships no `models/item/<id>.json` at all, and textures live under `textures/block/`.
 * The pack serves **everything the mod jars ship under `assets/`** — 26.3 discovers sounds, blockstates, item definitions, particles, fonts and shaders by *listing directories*, so a block-only whitelist would make a mod's sounds, lang, GUI textures and atlases invisible — under unfiltered namespaces (a mod overriding `assets/minecraft/…` must win: the pack sits at `Pack.Position.TOP`).
 * Resolution priority: real jar resource > synthesized model JSON > miss (synthesizing over a real asset would replace what the author actually wrote). Injection point: the head of `PackRepository.openAllSelected` — both resource-reload paths (the `Minecraft` constructor's inlined `reload()` and F3+T / the pack screen's `reloadResourcePacks`) reach it after the options selection is applied, so the pack cannot be overwritten by later flow.
 */
object ModAssetInjector {

    private const val PACK_ID = "oml_mod_resources"
    private const val PACK_NAME = "OML Mod Resources"

    private var injected = false

    /**
     * Idempotent injection: mount the mod resource pack into PackRepository's sources and add it
     * to the selection list. Called by [EventBridge.onPackRepositoryReload] at the head of
     * `PackRepository.openAllSelected()`. `PackRepository.sources` is a `Set` immutable in
     * practice, so it is replaced wholesale with a `HashSet` carrying the extra source.
     * The re-selection (`reload()` + `addPack()`) runs on **every** reload on purpose: the options
     * load and the resource pack screen rewrite the selection list, and a reload we are not part
     * of would silently drop this pack; `addPack` on an already-selected id is a no-op, so this
     * stays idempotent.
     */
    @Synchronized
    fun ensureInjected(repo: Any) {
        val loader = OMLCore.gameClassLoader()
        if (!injected) {
            injected = true
            try {
                val pack = createPack(loader) ?: return
                val source = createRepositorySource(loader, pack)

                val sourcesField = Refl.field(repo, "sources")

                @Suppress("UNCHECKED_CAST")
                val sources = HashSet<Any>(sourcesField.get(repo) as Collection<Any>)
                sources.add(source)
                sourcesField.set(repo, sources)
            } catch (t: Throwable) {
                OmlLog.error("ModAssets", "injection failed", t)
            }
        }
        try {
            repo.javaClass.getMethod("reload").invoke(repo)
            repo.javaClass.getMethod("addPack", String::class.java).invoke(repo, PACK_ID)
        } catch (t: Throwable) {
            OmlLog.error("ModAssets", "re-selection failed", t)
        }
    }

    /**
     * Build a real Pack. `Pack.readMetaAndCreate` asks our supplier for metadata
     * (`openMetadata` -> `getMetadataSection`), derives PackCompatibility from the
     * `InclusiveRange<PackFormat>` we synthesize, and returns null if the section is missing.
     */
    private fun createPack(loader: ClassLoader): Any? = try {
        val packClass = Class.forName("net.minecraft.server.packs.repository.Pack", true, loader)
        // 26.3: both of these are top-level in net.minecraft.server.packs, not in .repository
        val locationClass = Class.forName("net.minecraft.server.packs.PackLocationInfo", true, loader)
        val selectionClass = Class.forName("net.minecraft.server.packs.PackSelectionConfig", true, loader)
        val componentClass = Class.forName("net.minecraft.network.chat.Component", true, loader)
        val packSourceClass = Class.forName("net.minecraft.server.packs.repository.PackSource", true, loader)
        val packTypeClass = Class.forName("net.minecraft.server.packs.PackType", true, loader)
        val positionClass = Class.forName($$"net.minecraft.server.packs.repository.Pack$Position", true, loader)
        val supplierClass =
            Class.forName($$"net.minecraft.server.packs.repository.Pack$ResourcesSupplier", true, loader)
        val packResourcesClass = Class.forName("net.minecraft.server.packs.PackResources", true, loader)
        val optional = java.util.Optional::class.java

        val clientResources = packTypeClass.getField("CLIENT_RESOURCES").get(null)

        // PackLocationInfo(PACK_ID, Component.literal(name), PackSource.BUILT_IN, Optional.empty())
        val title = componentClass.getMethod("literal", String::class.java).invoke(null, PACK_NAME)
        val location = locationClass.getConstructor(
            String::class.java, componentClass, packSourceClass, optional
        ).newInstance(
            PACK_ID,
            title,
            packSourceClass.getField("BUILT_IN").get(null),
            optional.getMethod("empty").invoke(null)
        )

        // PackSelectionConfig(false, Pack.Position.TOP, false): not required, on top, not fixed —
        // i.e. a normal user-selectable pack that wins over the vanilla lower layers.
        val selection = selectionClass.getConstructor(
            Boolean::class.javaPrimitiveType, positionClass, Boolean::class.javaPrimitiveType
        ).newInstance(false, positionClass.getField("TOP").get(null), false)

        val resources = createPackResources(loader, packResourcesClass, location, packTypeClass, clientResources)

        val supplier = Proxy.newProxyInstance(loader, arrayOf(supplierClass)) { proxy, method, args ->
            when (method.name) {
                // In 26.3 a PackResources *is* the metadata carrier, so the same object answers both.
                "openMetadata" -> resources
                "openResources" -> java.util.stream.Stream.of(resources)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLResourcesSupplier@" + System.identityHashCode(proxy)
                else -> null
            }
        }

        val pack = packClass.getMethod(
            "readMetaAndCreate", locationClass, supplierClass, packTypeClass, selectionClass
        ).invoke(null, location, supplier, clientResources, selection)
        if (pack == null) {
            OmlLog.warn("ModAssets", "readMetaAndCreate returned null (pack metadata rejected)")
        }
        pack
    } catch (t: Throwable) {
        OmlLog.error("ModAssets", "creating resource pack failed", t)
        null
    }

    /**
     * Dynamic PackResources proxy. Mod assets only ever declare OML mod domains, never
     * minecraft/realms, and only CLIENT_RESOURCES (this injector serves the client resource reload).
     */
    private fun createPackResources(
        loader: ClassLoader,
        packResourcesClass: Class<*>,
        location: Any,
        packTypeClass: Class<*>,
        clientResources: Any,
    ): Any {
        val ioSupplierClass = Class.forName("net.minecraft.server.packs.resources.IoSupplier", true, loader)
        val metadataClass =
            Class.forName("net.minecraft.server.packs.metadata.pack.PackMetadataSection", true, loader)
        val componentClass = Class.forName("net.minecraft.network.chat.Component", true, loader)

        // One metadata section per declared section type: the client repo asks with CLIENT_TYPE,
        // the (integrated or dedicated) server repo with SERVER_TYPE — the two formats are
        // different numbers, and a pack mounted into both repositories must answer both.
        // FALLBACK_TYPE (any other caller) gets the client-format section.
        // 26.3 renamed the server side of the PackType enum: SERVER_RESOURCES -> SERVER_DATA.
        val sectionTypes = metadataTypes(metadataClass)
        val clientType = metadataClass.getField("CLIENT_TYPE").get(null)
        val serverType = metadataClass.getField("SERVER_TYPE").get(null)
        val fallbackType = metadataClass.getField("FALLBACK_TYPE").get(null)
        val serverData = packTypeClass.getField("SERVER_DATA").get(null)
        val clientSection = createMetadataSection(loader, metadataClass, componentClass, packTypeClass, clientResources)
        val serverSection = createMetadataSection(loader, metadataClass, componentClass, packTypeClass, serverData)
        val metadataByType = mapOf(
            clientType to clientSection,
            serverType to serverSection,
            fallbackType to clientSection,
        )
        check(metadataByType.size == 3 && metadataByType.keys.containsAll(sectionTypes)) {
            "unexpected PackMetadataSection type set: $sectionTypes"
        }

        return Proxy.newProxyInstance(loader, arrayOf(packResourcesClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLPackResources@" + System.identityHashCode(proxy)
                "packId" -> PACK_ID
                "knownPackInfo" -> java.util.Optional.empty<Any>()
                // getNamespaces(PackType): every namespace the mod jars ship assets for (including
                // `minecraft` when a mod overrides vanilla assets) plus the mod ids themselves —
                // computed by oml-content's ModAssetIndex. 26.3 passes the pack type in, but a mod jar
                // holds `assets/` only, so the answer is the same for either type.
                "getNamespaces" -> {
                    val base = assetIndex().namespaces()
                    // declared ores merge into vanilla biome files, so the minecraft namespace must
                    // be listed for the datapack reload to even look inside it
                    if (MinecraftContentRegistry.oreTargetedBiomes().isNotEmpty() && "minecraft" !in base) {
                        base + "minecraft"
                    } else {
                        base
                    }
                }
                // no root file: pack.mcmeta is answered through getMetadataSection, not read as a file
                "getRootResource" -> null
                "location" -> location
                "close" -> null
                "getResource" -> {
                    // Both pack types are served: assets/ for the client reload, data/ (recipes,
                    // loot tables, tags...) for the server reload — the integrated server that
                    // loads singleplayer datapacks asks with SERVER_RESOURCES from the same
                    // repository pipeline.
                    val identifier = args[1]
                    val namespace = identifier.javaClass.getMethod("getNamespace").invoke(identifier) as String
                    val path = identifier.javaClass.getMethod("getPath").invoke(identifier) as String
                    resolveResource(namespace, path, loader)?.let { bytes ->
                        ioSupplierOf(ioSupplierClass, loader, bytes)
                    }
                }

                // The section is asked for by *type object*, so identity comparison against the
                // three PackMetadataSection types is the match. Everything else (FeatureFlags,
                // Overlays) answers null, which vanilla treats as "use defaults".
                "getMetadataSection" -> metadataByType[args[0]]

                "listResources" -> {
                    emitListedResources(
                        args[1] as String, args[2] as String, args[3],
                        loader, ioSupplierClass
                    )
                    null
                }

                else -> null
            }
        }
    }

    /**
     * Synthesize the `pack` metadata section. 26.3 replaced the int pack format with
     * `InclusiveRange<PackFormat>`, and the value has to be the format the *running* version reports
     * (`WorldVersion.packVersion(PackType)`) — a hard-coded number would be judged incompatible and
     * the pack would be rejected as "incompatible" instead of loaded.
     */
    private fun createMetadataSection(
        loader: ClassLoader,
        metadataClass: Class<*>,
        componentClass: Class<*>,
        packTypeClass: Class<*>,
        clientResources: Any,
    ): Any {
        val sharedConstants = Class.forName("net.minecraft.SharedConstants", true, loader)
        val version = sharedConstants.getMethod("getCurrentVersion").invoke(null)
        val format = version.javaClass.getMethod("packVersion", packTypeClass).invoke(version, clientResources)
        val rangeClass = Class.forName("net.minecraft.util.InclusiveRange", true, loader)
        // single-argument InclusiveRange(T) == min == max, i.e. exactly the running version
        val range = rangeClass.getConstructor(Comparable::class.java).newInstance(format)
        val description = componentClass.getMethod("literal", String::class.java).invoke(null, PACK_NAME)
        return metadataClass.getConstructor(componentClass, rangeClass).newInstance(description, range)
    }

    /** The three `pack` section types: CLIENT_TYPE / SERVER_TYPE / FALLBACK_TYPE. */
    private fun metadataTypes(metadataClass: Class<*>): List<Any> =
        listOf("CLIENT_TYPE", "SERVER_TYPE", "FALLBACK_TYPE").mapNotNull { name ->
            runCatching { metadataClass.getField(name).get(null) }.getOrNull()
        }

    @Volatile
    private var index: ModAssetIndex? = null

    /**
     * The asset index over the loaded mod jars, built once at the first asset query (after the mod
     * scan). Jar indexing, namespace computation and directory enumeration are version-independent
     * and live in oml-content's [ModAssetIndex]; this file owns only the 26.3 pack wiring and asset
     * shapes on top of it.
     */
    private fun assetIndex(): ModAssetIndex {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            return OMLCore.assetIndex().also { index = it }
        }
    }

    /** Drops the cached index so the next query re-scans the mod jars and pack archives. */
    internal fun invalidateIndex() {
        synchronized(this) { index = null }
    }

    /**
     * Byte resolution for one file: real jar resource > synthesized JSON > null. The jar step goes
     * through the loader (the mod jars are on its search path), so it also serves assets in the
     * `minecraft` namespace, i.e. resource-pack-style overrides.
     */
    private fun resolveResource(namespace: String, path: String, loader: ClassLoader): ByteArray? {
        if (!isDataPath(path)) {
            val direct = loader.getResource("assets/$namespace/$path")
            if (direct != null) {
                return direct.openStream().use { it.readBytes() }
            }
        }
        // data/ answers first from collected OML declarations (recipes, loot), then from jars.
        // The server reload reads datapacks from the same pack repository as the client, so one
        // pack object serves both sides. Jar datapack entries are served for every namespace —
        // a mod can ship data/minecraft/... overrides just like assets ones.
        if (isDataPath(path)) {
            datapackBytes(namespace, path, loader)?.let { return it }
            val dataDirect = loader.getResource("data/$namespace/$path")
            if (dataDirect != null) {
                return dataDirect.openStream().use { it.readBytes() }
            }
        }
        // synthesize model JSON only for OML asset domains, never minecraft/realms
        if (namespace in OMLCore.assetDomainIds()) {
            val json = when {
                path.startsWith("blockstates/") && path.endsWith(".json") -> {
                    val id = path.removePrefix("blockstates/").removeSuffix(".json")
                    // the variant key for attribute-less blocks is still the empty string; the model
                    // reference carries the namespace
                    """{"variants":{"":{"model":"$namespace:block/$id"}}}"""
                }

                path.startsWith("models/block/") && path.endsWith(".json") -> {
                    val id = path.removePrefix("models/block/").removeSuffix(".json")
                    """{"parent":"minecraft:block/cube_all","textures":{"all":"$namespace:block/$id"}}"""
                }

                // 26.3 item asset: an item *definition*, not a model. The vanilla jar has no
                // models/item/<id>.json at all, so only this shape is ever read. A block(-item)
                // definition points at the synthesized block model; a pure item (declared via
                // declareItem or a TOML pack, no block with the same id) points at its own
                // synthesized handheld model below.
                path.startsWith("items/") && path.endsWith(".json") -> {
                    val id = path.removePrefix("items/").removeSuffix(".json")
                    val model =
                        if (id in blockIdsCached(namespace, loader)) "$namespace:block/$id" else "$namespace:item/$id"
                    """{"model":{"type":"minecraft:model","model":"$model"}}"""
                }

                // The model a pure item's definition references. Only synthesized for ids without
                // a block of the same name — block(-item)s reuse their cube_all block model.
                path.startsWith("models/item/") && path.endsWith(".json") -> {
                    val id = path.removePrefix("models/item/").removeSuffix(".json")
                    """{"parent":"minecraft:item/handheld","textures":{"layer0":"$namespace:item/$id"}}"""
                }

                else -> null
            }
            if (json != null) {
                return json.toByteArray(Charsets.UTF_8)
            }
        }
        return null
    }

    /**
     * A datapack path: everything outside the client-only asset directories. The server reload
     * asks for `recipe/`, `loot_table/`, `tags/`, ... — this gate keeps the client-only synthesis
     * (blockstates / models / item definitions) from also being offered to the server reload.
     */
    private fun isDataPath(path: String): Boolean =
        path.startsWith("recipe/") || path.startsWith("loot_table/") || path.startsWith("tags/") ||
            path.startsWith("worldgen/") || path.startsWith("advancement/") ||
            path.startsWith("predicate/") || path.startsWith("loot_modifiers/")

    /**
     * Bytes for one datapack file. Recipes and loot tables come from OML content declarations
     * (collected via [org.ohmyloader.api.content.ContentRegistry.declareSmelting] /
     * `declareBlockDrop` and translated at the registry freeze point), matching the vanilla
     * datapack formats so the game's own reload paths parse them.
     */
    private fun datapackBytes(namespace: String, path: String, loader: ClassLoader): ByteArray? {
        val contentRegistry = MinecraftContentRegistry
        return when {
            path.startsWith("recipe/") && path.endsWith(".json") -> {
                val id = path.removePrefix("recipe/").removeSuffix(".json")
                contentRegistry.recipeJsonFor(namespace, id)
            }

            path.startsWith("loot_table/blocks/") && path.endsWith(".json") -> {
                val id = path.removePrefix("loot_table/blocks/").removeSuffix(".json")
                contentRegistry.lootJsonFor(namespace, id)
            }

            path.startsWith("worldgen/feature/") && path.endsWith(".json") -> {
                val id = path.removePrefix("worldgen/feature/").removeSuffix(".json")
                contentRegistry.oreFeatureJsonFor(namespace, id)
            }

            path.startsWith("worldgen/placed_feature/") && path.endsWith(".json") -> {
                val id = path.removePrefix("worldgen/placed_feature/").removeSuffix(".json")
                contentRegistry.orePlacedJsonFor(namespace, id)
            }

            // declared ores merge into vanilla biomes: the pack's whole-file override wins over the
            // jar's original (which this same function would otherwise serve as the fallback below)
            namespace == "minecraft" && path.startsWith("worldgen/biome/") && path.endsWith(".json") -> {
                val biome = path.removePrefix("worldgen/biome/").removeSuffix(".json")
                contentRegistry.mergedBiomeJsonFor(biome)
            }

            else -> null
        }?.toByteArray(Charsets.UTF_8)
    }

    private fun ioSupplierOf(ioSupplierClass: Class<*>, loader: ClassLoader, bytes: ByteArray): Any =
        Proxy.newProxyInstance(loader, arrayOf(ioSupplierClass)) { proxy, method, args ->
            when (method.name) {
                "get" -> ByteArrayInputStream(bytes)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLIoSupplier@" + System.identityHashCode(proxy)
                else -> null
            }
        }

    /**
     * listResources implementation: "what does this pack have under `<namespace>/<directory>`". The directory argument is whatever the
     * caller passes to the resource manager — `FileToIdConverter.json("blockstates")`, `.json("items")` (item definitions),
     * `Sound.SOUND_LISTER`, fonts, shaders, textures… — so it is matched as a **prefix over the asset index** instead of against a list of
     * known directories: whatever a mod ships in the requested directory is emitted, keeping sounds, lang, fonts, shaders, particles and
     * custom atlas directories visible without this file knowing they exist. Synthesized block assets (which exist in no jar) are layered
     * on top, only for mod domains. The output is invoked through the `java.util.function.BiConsumer` interface, not the lambda's concrete
     * class — its signature after erasure may not be findable.
     */
    private fun emitListedResources(
        namespace: String,
        path: String,
        output: Any,
        loader: ClassLoader,
        ioSupplierClass: Class<*>,
    ) {
        val accept = java.util.function.BiConsumer::class.java
            .getMethod("accept", Any::class.java, Any::class.java)
        val identifierClass = try {
            Class.forName("net.minecraft.resources.Identifier", true, loader)
        } catch (t: Throwable) {
            OmlLog.error("ModAssets", "unable to load Identifier class", t)
            return
        }
        val identifierOf = identifierClass.getMethod("fromNamespaceAndPath", String::class.java, String::class.java)
        val emitted = HashSet<String>()

        fun emit(fullPath: String) {
            if (!emitted.add(fullPath)) return
            val bytes = resolveResource(namespace, fullPath, loader) ?: return
            val identifier = identifierOf.invoke(null, namespace, fullPath)
            accept.invoke(output, identifier, ioSupplierOf(ioSupplierClass, loader, bytes))
        }

        // declared ore generation: the mod namespace's feature files, and the merged vanilla biome
        // files under minecraft — the datapack loader only reads what a pack lists
        if (isDataPath(path)) {
            val dir = path.trimEnd('/')
            if (namespace == "minecraft" && (dir == "worldgen/biome" || dir.startsWith("worldgen/biome/"))) {
                MinecraftContentRegistry.oreTargetedBiomes().forEach { emit("worldgen/biome/$it.json") }
            }
            if (dir == "worldgen/feature" || dir.startsWith("worldgen/feature/")) {
                MinecraftContentRegistry.oreGenKeysFor(namespace).forEach { emit("worldgen/feature/$it.json") }
            }
            if (dir == "worldgen/placed_feature" || dir.startsWith("worldgen/placed_feature/")) {
                MinecraftContentRegistry.oreGenKeysFor(namespace).forEach { emit("worldgen/placed_feature/$it.json") }
            }
        }

        // jar-shipped entries: the core index matches the requested directory as a prefix over the
        // indexed keys, so whatever the mod actually ships there is emitted
        assetIndex().listUnder(namespace, path).forEach(::emit)
        if (namespace in OMLCore.assetDomainIds()) {
            val blockIds = blockIdsCached(namespace, loader).toList()
            val itemOnlyIds = itemOnlyIdsOf(namespace, loader)
            when (path) {
                "blockstates" -> blockIds.forEach { emit("blockstates/$it.json") }
                // the model bakery discovers models by a *recursive* listing of "models", so pure
                // item models must ride along here as well as under the explicit "models/item" ask
                "models" -> {
                    blockIds.forEach { emit("models/block/$it.json") }
                    itemOnlyIds.forEach { emit("models/item/$it.json") }
                }

                "models/block" -> blockIds.forEach { emit("models/block/$it.json") }
                "models/item" -> itemOnlyIds.forEach { emit("models/item/$it.json") }
                "items" -> (blockIds + itemOnlyIds).forEach { emit("items/$it.json") }
                "textures/block" -> blockIds.forEach { emit("textures/block/$it.png") }
                "textures/item" -> blockIds.forEach { emit("textures/item/$it.png") }
                // datapack discovery: recipes / loot tables collected from content declarations
                "recipe" -> MinecraftContentRegistry.recipeIdsFor(namespace).forEach { emit("recipe/$it.json") }
                "loot_table/blocks" -> MinecraftContentRegistry.lootIdsFor(namespace)
                    .forEach { emit("loot_table/blocks/$it.json") }
            }
        }
    }

    /**
     * Collect the ids registered under the given namespace in one of `BuiltInRegistries`' fields.
     * 26.3's `Registry.keySet()` returns `Set<Identifier>` directly, so each element already
     * carries namespace and path through `getNamespace()` / `getPath()` — there is no unwrapping
     * step in between.
     */
    private fun registryIdsOf(namespace: String, loader: ClassLoader, registryField: String): List<String> = try {
        val builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
        val registry = builtin.getField(registryField).get(null)
        val keySet = registry.javaClass.getMethod("keySet").invoke(registry) as Set<*>
        keySet.mapNotNull { identifier ->
            if (identifier == null) return@mapNotNull null
            val ns = identifier.javaClass.getMethod("getNamespace").invoke(identifier) as String
            if (ns == namespace) identifier.javaClass.getMethod("getPath").invoke(identifier) as String else null
        }
    } catch (t: Throwable) {
        OmlLog.error("ModAssets", "querying the $registryField registry failed", t)
        emptyList()
    }

    private val blockIdCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * Block ids per namespace, cached: [resolveResource] consults them per asset request (block
     * model vs. item model for an item definition), which is reflection-heavy. The registry is
     * frozen before any asset query, so the set cannot go stale within a run.
     */
    private fun blockIdsCached(namespace: String, loader: ClassLoader): Set<String> =
        blockIdCache.computeIfAbsent(namespace) { registryIdsOf(it, loader, "BLOCK").toSet() }

    /** Pure item ids: registered as items with no block of the same id — no blockstate/model fallback, so they need their own synthesized definition + model. */
    private fun itemOnlyIdsOf(namespace: String, loader: ClassLoader): List<String> =
        registryIdsOf(namespace, loader, "ITEM").filter { it !in blockIdsCached(namespace, loader) }

    private fun createRepositorySource(loader: ClassLoader, pack: Any): Any {
        val sourceClass = Class.forName("net.minecraft.server.packs.repository.RepositorySource", true, loader)
        return Proxy.newProxyInstance(loader, arrayOf(sourceClass)) { proxy, method, args ->
            when (method.name) {
                "loadPacks" -> {
                    val consumer = args[0]
                    // after type erasure Consumer<Pack> always has accept(Object); use the interface
                    // method directly to avoid failing to find it on the lambda's concrete class
                    java.util.function.Consumer::class.java
                        .getMethod("accept", Any::class.java)
                        .invoke(consumer, pack)
                }

                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLRepositorySource@" + System.identityHashCode(proxy)
                else -> null
            }
        }
    }
}
