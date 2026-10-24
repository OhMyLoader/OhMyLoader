package org.ohmyloader.core

import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.client.OMLKeyBindingProvider
import org.ohmyloader.api.client.OMLKeyBindingRegistry
import org.ohmyloader.api.command.OMLCommandDeclaration
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistryFactory
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.network.*
import org.ohmyloader.content.AbstractContentRegistry
import org.ohmyloader.content.TomlContentLoader
import org.ohmyloader.core.OMLCore.contentPackFiles
import org.ohmyloader.core.OMLCore.installLogFile
import org.ohmyloader.core.classloader.OMLClassLoader
import org.ohmyloader.core.client.KeyBindingDeclarations
import org.ohmyloader.core.command.CommandDeclarations
import org.ohmyloader.core.mixin.MixinScanner
import org.ohmyloader.core.mixin.OMLMixinRegistry
import org.ohmyloader.core.mod.ModContainer
import org.ohmyloader.core.mod.ModGraph
import org.ohmyloader.core.mod.ModScanner
import org.ohmyloader.core.network.PayloadDeclarations
import org.ohmyloader.core.ruleset.ModRuleSets
import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer
import org.ohmyloader.core.transformer.IVerifiableTransformer
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** The declaration entry inside a `.oml` content pack archive. */
private const val CONTENT_TOML = "content.toml"

object OMLCore {
    /**
     * OML's own version. The single source of truth is the `oml_version` property of the root
     * gradle.properties: the oml-core build stamps it into `/oml-core.properties` at resource
     * processing time and it is read back here, so the runtime banner and the published
     * coordinates can never drift apart. (Not a `const val` for exactly that reason — the value
     * is no longer a compile-time literal.)
     */
    val VERSION: String = loadStampedVersion()

    private fun loadStampedVersion(): String =
        OMLCore::class.java.getResourceAsStream("/oml-core.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")?.takeIf { it.isNotBlank() }
        } ?: "unknown"

    private lateinit var primaryLoader: OMLClassLoader
    private val loadedMods = mutableListOf<ModContainer>()
    private val modInstances = mutableMapOf<String, Any>()
    private var adapter: IAdapter? = null

    /** The local-variable-read demo hook prints only once (`runTick` is called every frame). */
    private val ticksLogged = AtomicBoolean(false)

    /** The local-variable-store demo hook likewise prints only once. */
    private val ticksStoreLogged = AtomicBoolean(false)

    /**
     * Product window title. Every place the game sets the window title (startup, world join,
     * window focus change) funnels through `Minecraft.updateTitle()` → `Window.setTitle()`, and the
     * adapter's hook rewrites that argument into [onWindowTitle] — so returning a constant here
     * brands the window regardless of which vanilla path triggered the update.
     */
    const val WINDOW_TITLE = "OhMyLoader"

    /** The title hook prints only once (focus changes would otherwise repeat it). */
    private val titleLogged = AtomicBoolean(false)

    /**
     * Game directory: the launcher expands the version JSON's `${game_directory}` into `--gameDir`;
     * defaults to the current working directory.
     *
     * This is where "version isolation" lands — when the launcher (or a manual setting) assigns a
     * separate game directory per version, every default path based on the game directory follows
     * it, so multiple versions installed under one game root each look at their own `mods/`.
     */
    private lateinit var gameDir: File

    @JvmStatic
    fun start(args: Array<String>) {
        installLogFile()
        printBanner()

        // Client and dedicated server are two separate entry paths, selected by `oml.side` (default = client)
        serverSide = System.getProperty("oml.side", "client").equals("server", ignoreCase = true)

        // The game directory is decided here: every default path based on it is read from this point on
        gameDir = resolveGameDir(args)

        // Parent loader = the "loader classpath" set up by the launcher (core / api / adapter / dependencies all live on this layer)
        val parent = Thread.currentThread().contextClassLoader

        // The mods directory defaults to `mods/` under the **game directory**: whichever version is
        // launched reads that version's directory, consistent with "version isolation".
        // The system property oml.mods.dir has higher priority — during development the two sides run
        // from different working directories (the server writes eula / server.properties / world under
        // run/server/), and it lets them share the single run/mods copy.
        val modsDir = System.getProperty("oml.mods.dir")?.let(::File) ?: File(gameDir, "mods")
        // The dependency graph is validated and ordered here, at the cheapest possible failure point:
        // no main loader built, no transformer run, no game class loaded. initMods reuses this exact
        // order later.
        val mods = ModGraph.order(ModScanner.scan(modsDir))
        loadedMods.addAll(mods)

        // The main loader is built before the adapter is discovered, and the adapter is discovered
        // *through it*: injected hook bytecode resolves adapter classes through the defining loader of
        // the game class it was injected into, so an adapter instance taken from the parent loader
        // would exist as two copies, each with its own adapter-side singletons — declarations
        // collected in one, materialization run against the other's empty state.
        val omlLoader = createMainLoader(parent, mods)
        val adapter = discoverAdapter(omlLoader)
        this.adapter = adapter
        // Installed before mod init: a mod may send during its own initialization.
        OMLNetwork.sender = adapter.createNetworkSender()

        val transformers = registerTransformers(adapter, omlLoader, mods)

        // All subsequent threads resolve classes through the OML main loader (placed before the
        // self-check: the self-check resolves handlers through it — the runtime-generated bridge
        // classes exist only within it)
        primaryLoader = omlLoader
        Thread.currentThread().contextClassLoader = omlLoader

        verifyInjectionRules(transformers)

        declareContent(adapter, modsDir)
        launch(adapter, args)
    }

    /**
     * Mirrors this process's console output into `-Doml.log.file=<path>`, when that property is set.
     *
     * A launcher does not always leave the child's stdout where a user can find it, and an automated
     * gate needs the evidence in an artifact it can upload. Installed before the banner so the whole
     * startup — including the injection-rule self-check — lands in the file. The game's own log4j file
     * is a different consumer and stays untouched: what is mirrored here is what this process writes to
     * stdout/stderr, which is where OML's own diagnostics go.
     */
    private fun installLogFile() {
        val path = System.getProperty("oml.log.file")?.takeIf { it.isNotBlank() } ?: return
        val file = File(path).absoluteFile
        runCatching {
            file.parentFile?.mkdirs()
            val mirror = PrintStream(FileOutputStream(file, true), true, Charsets.UTF_8)
            System.setOut(tee(System.out, mirror))
            System.setErr(tee(System.err, mirror))
        }.onFailure { OmlLog.error("OMLCore", "could not open the log file $file", it) }
    }

    /** Writes to the console and to [mirror]; see [installLogFile]. */
    private fun tee(console: PrintStream, mirror: PrintStream): PrintStream =
        PrintStream(
            object : OutputStream() {
                override fun write(b: Int) {
                    console.write(b)
                    mirror.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    console.write(b, off, len)
                    mirror.write(b, off, len)
                }

                override fun flush() {
                    console.flush()
                    mirror.flush()
                }
            },
            true,
            Charsets.UTF_8,
        )

    /**
     * Discovers the version adapter layer via SPI; core does not depend on any specific version.
     *
     * [loader] is the main loader, and adapter classes must be defined by it — that is what puts them
     * in the same loader as the game classes they reference (see [OMLClassLoader]'s `CHILD_FIRST_PREFIXES`)
     * and in the loader the injected hooks resolve them through. The two checks below turn the ways this
     * can break — an adapter defined by some other loader, hence a second copy of every adapter-side
     * singleton, and more than one adapter on the search path — into a startup error instead of content
     * that silently never materializes.
     *
     * A failed discovery throws: a startup that cannot proceed must be observable to the launcher (a
     * non-zero exit code with the reason on stderr), not look like a successful hand-over to a game
     * that then never starts.
     */
    internal fun discoverAdapter(loader: ClassLoader): IAdapter {
        // Providers are named, not instantiated: two adapters on the path is a packaging fault to report
        // by name, and running both adapters' initializers to discover it is not a harmless way to ask.
        val candidates = ServiceLoader.load(IAdapter::class.java, loader).stream().toList()
        if (candidates.isEmpty()) {
            val problem = "no IAdapter implementation found (check classpath and META-INF/services)"
            OmlLog.error("OMLCore", "Fatal: $problem")
            throw IllegalStateException(problem)
        }
        if (candidates.size > 1) {
            val problem = "the install layer carries ${candidates.size} IAdapter implementations (" +
                candidates.joinToString { it.type().name } + ") but exactly one belongs in lib/. The installer " +
                "packages the shared jars plus one adapter jar, so an extra one is a stale copy of an " +
                "earlier install; which copy would be used follows ServiceLoader order (the classpath, " +
                "then the library directory walk), and the wrong one transforms the game against another " +
                "version's member names."
            OmlLog.error("OMLCore", "Fatal: $problem")
            throw IllegalStateException(problem)
        }
        val adapter = candidates.first().get()
        if (adapter.javaClass.classLoader !== loader) {
            val problem = "the adapter ${adapter.javaClass.name} was defined by " +
                "${adapter.javaClass.classLoader} instead of the OML main loader ($loader). Adapter classes must sit " +
                "under org.ohmyloader.adapter. so the main loader defines them; otherwise every adapter-side singleton " +
                "exists twice and injected hooks see the copy that never received the declarations."
            OmlLog.error("OMLCore", "Fatal: $problem")
            throw IllegalStateException(problem)
        }
        // Which layer the run is on, in the log the CI evidence trail keeps: a wrong-adapter install must
        // be readable from the log alone, not inferred from which rules failed to hit.
        OmlLog.info("OMLCore", "adapter ${adapter.javaClass.name} (game version ${adapter.versionId})")
        return adapter
    }

    /** The OML main loader: search path = the game runtime (the loader classpath plus the version library directory), then the mod jars. */
    private fun createMainLoader(parent: ClassLoader, mods: List<ModContainer>): OMLClassLoader = OMLClassLoader(
        // Several @Mod entries may share one jar: the search path must hold it once
        (buildRuntimeUrls() + mods.distinctBy { it.file }.map { it.file.toURI().toURL() }).toTypedArray(),
        parent,
    )

    /**
     * Registers the side's bytecode transformers, then the mixin front end. Mod rule sets are loaded
     * only after read-only bytecode discovery and static verification (rule classes are loaded before
     * game classes, so they may only depend on JDK / Kotlin / oml-api and themselves).
     */
    private fun registerTransformers(
        adapter: IAdapter,
        omlLoader: OMLClassLoader,
        mods: List<ModContainer>,
    ): List<IClassTransformer> {
        val registered = mutableListOf<IClassTransformer>()
        val sideTransformers =
            if (serverSide) adapter.createServerTransformers() else adapter.createTransformers()
        for (transformer in sideTransformers) {
            omlLoader.registerTransformer(transformer)
            registered += transformer
        }

        OMLMixinRegistry.installClassLoader(omlLoader)
        val modRules = ModRuleSets.load(mods, omlLoader)
        val mixinTransformer = MixinScanner.createTransformer(
            mods, modRules.rules, modRules.merges, modRules.problems,
        )
        if (mixinTransformer != null) {
            omlLoader.registerTransformer(mixinTransformer)
            registered += mixinTransformer
        }
        return registered
    }

    /**
     * Content declaration phase: must complete before the game's main logic (and its registry freeze).
     *
     * Flat TOML content packs are loaded in this same window so packs and code mods share the identical
     * collection -> freeze materialization, and the asset injector treats the pack namespace like a mod
     * domain (see flatContentNamespaces / assetDomainIds). One broken pack is reported and skipped, never
     * allowed to take the others (or the game) down.
     */
    /** The content registry factory from the version adapter, kept for [reloadContentPacks]. */
    @Volatile
    private var contentRegistryFactory: ContentRegistryFactory? = null

    /** The directory packs were loaded from, kept for [reloadContentPacks]. */
    @Volatile
    private var contentPackDir: File? = null

    private fun declareContent(adapter: IAdapter, modsDir: File) {
        val contentRegistry = adapter.createContentRegistry() ?: return
        contentRegistryFactory = contentRegistry

        for (mod in loadedMods) {
            val instance = modInstance(mod)
            if (instance is OMLContentProvider) {
                instance.declareContent(contentRegistry.forNamespace(mod.id.lowercase(), reloadable = false))
            }
        }

        loadContentPacks(modsDir, contentRegistry)
    }

    /**
     * Loads the content packs in [modsDir] into [contentRegistry]. Internal for tests: this is the
     * whole no-code content track, and its failure modes (bad archive, duplicate namespace) must
     * stay unit-testable without an adapter.
     */
    internal fun loadContentPacks(modsDir: File, contentRegistry: ContentRegistryFactory) {
        contentRegistryFactory = contentRegistry
        contentPackDir = modsDir
        for (pack in packFiles(modsDir)) {
            try {
                val namespace = TomlContentLoader.namespaceOf(pack)
                val sources = contentSources(pack)
                if (sources.isEmpty()) {
                    throw IllegalStateException("the archive carries no content/ declarations")
                }
                contentPackFiles += pack
                val registry = contentRegistry.forNamespace(namespace, reloadable = true)
                for (source in sources) {
                    TomlContentLoader.loadFile(
                        namespace, "${pack.name}/${source.path}", source.type, source.id,
                        source.text, registry,
                    )
                }
                flatContentNamespaces += namespace
            } catch (t: Throwable) {
                OmlLog.error("OMLCore", "Failed to load content pack ${pack.name}", t)
            }
        }
    }

    private fun packFiles(modsDir: File): List<File> {
        // Content packs ship as `mods/*.oml` archives: the declarations under `content/` plus the
        // pack's own assets and data (`assets/<namespace>/...`, `data/<namespace>/...`), so a
        // creator ships textures and lang files without a jar. The file name (minus extension) is
        // the resource namespace. A loose `.toml` is not a pack form: it cannot carry assets, so
        // its blocks render with missing textures — one found in the mods directory is reported
        // and refused instead of loaded.
        for (stray in modsDir.listFiles { f -> f.isFile && f.name.endsWith(".toml", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()) {
            OmlLog.warn(
                "OMLCore",
                "${stray.name}: loose .toml content packs are no longer supported — " +
                    "pack it as an .oml archive (content/ + assets/ at the root)",
            )
        }
        return modsDir.listFiles { f -> f.isFile && f.name.endsWith(".oml", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /** One per-entity declaration file inside a pack archive. */
    private class ContentSource(val path: String, val type: String, val id: String, val text: String)

    /**
     * The declaration files of a pack archive: every zip entry under `content/` as
     * `content/<type>/<id>.toml`, sorted for deterministic load order. The single-file
     * `content.toml` layout is refused with the migration path named, and two files claiming the
     * same `type/id` abort the pack — with files, a duplicate is always fixable, so first-wins
     * (which hides one of the two) would be the wrong call.
     */
    private fun contentSources(pack: File): List<ContentSource> =
        ZipFile(pack).use { zip ->
            if (zip.getEntry(CONTENT_TOML) != null) {
                throw IllegalStateException(
                    "$CONTENT_TOML is no longer a supported layout — declare each entity as " +
                        "content/<type>/<id>.toml (e.g. content/block/ruby_ore.toml)",
                )
            }
            val seen = LinkedHashMap<String, String>()
            val sources = ArrayList<ContentSource>()
            for (entry in zip.entries().asSequence().sortedBy { it.name }) {
                val name = entry.name
                if (!name.startsWith("content/") || entry.isDirectory) continue
                if (!name.endsWith(".toml")) {
                    OmlLog.warn("OMLCore", "${pack.name}/$name: only .toml files are declarations, skipped")
                    continue
                }
                val relative = name.removePrefix("content/").removeSuffix(".toml")
                val type = relative.substringBefore('/', missingDelimiterValue = "")
                val id = relative.substringAfter('/', missingDelimiterValue = "")
                if (type !in TomlContentLoader.CONTENT_TYPES) {
                    OmlLog.warn(
                        "OMLCore",
                        "${pack.name}/$name: '$type' is not a content type " +
                            "(expected one of ${TomlContentLoader.CONTENT_TYPES.sorted()}), skipped",
                    )
                    continue
                }
                val previous = seen.put("$type/$id", name)
                if (previous != null) {
                    throw IllegalStateException(
                        "duplicate declaration $type/$id: $previous and $name both declare it",
                    )
                }
                sources += ContentSource(name, type, id, zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
            }
            sources
        }

    /**
     * Re-reads the content packs **as they exist on disk now** and refreshes the data
     * declarations (crafting recipes) they carry, so a vanilla `/reload` picks up edits without a
     * restart. Blocks and items are deliberately not re-declared — their registries are frozen and
     * the declarations were consumed at the freeze point. Called by the version adapters on
     * vanilla pack reloads; a no-op when no adapter with content registration is loaded.
     */
    @JvmStatic
    fun reloadContentPacks() {
        val contentRegistry = contentRegistryFactory ?: return
        val dataRegistry = contentRegistry as? AbstractContentRegistry ?: return
        val modsDir = contentPackDir ?: return
        dataRegistry.clearDataDeclarations()
        for (pack in packFiles(modsDir)) {
            try {
                val namespace = TomlContentLoader.namespaceOf(pack)
                contentPackFiles += pack
                val registry = contentRegistry.forNamespace(namespace, reloadable = true)
                for (source in contentSources(pack)) {
                    // block and item files are re-read as written but skipped: their registries are
                    // frozen and reloading must not grow the declaration queues.
                    if (source.type in TomlContentLoader.RELOADABLE_TYPES) {
                        TomlContentLoader.loadFile(
                            namespace, "${pack.name}/${source.path}", source.type, source.id,
                            source.text, registry,
                        )
                    }
                }
            } catch (t: Throwable) {
                OmlLog.error("OMLCore", "Failed to reload content pack ${pack.name}", t)
            }
        }
    }

    /**
     * Hands main-thread control to the game (or the dedicated server) through the OML main loader.
     *
     * Failures here are rethrown, not logged-and-returned: a startup that never reaches the game must
     * be observable to the launcher as a non-zero exit (the exception propagates out of
     * [org.ohmyloader.launcher.OMLBootstrap.main]). It must NOT die via `exitProcess` from here —
     * the diagnostics report is delivered by a shutdown hook, and throwing keeps the JVM on the
     * normal-termination path where that hook runs with the full stack trace already printed.
     */
    private fun launch(adapter: IAdapter, args: Array<String>) {
        val entryClass = if (serverSide) adapter.serverMainClass else adapter.mainClass
        if (entryClass == null) {
            val message = "server startup is not yet supported for ${adapter.versionId} " +
                "(IAdapter.serverMainClass is null)."
            OmlLog.error("OMLCore", "Fatal: $message")
            throw IllegalStateException(message)
        }

        try {
            val mcMainClass = Class.forName(entryClass, true, primaryLoader)
            val mainMethod = mcMainClass.getMethod("main", Array<String>::class.java)
            OmlLog.info(
                "OMLCore",
                "${loadedMods.size} mod(s) loaded, handing over to the ${if (serverSide) "dedicated server" else "client"}...",
            )
            mainMethod.invoke(null, args)
        } catch (e: ClassNotFoundException) {
            val message = "entry class $entryClass not found!\n" +
                "Please check that the game jar exists in oml-adapter-*/libs/ and matches the adapter layer version."
            OmlLog.error("OMLCore", "Fatal: $message", e)
            throw IllegalStateException(message, e)
        } catch (e: InvocationTargetException) {
            // The game's own crash — rethrow the original so the crash report path / launcher sees the
            // real exception, not a reflective wrapper
            OmlLog.error("OMLCore", "Failed to start Minecraft!", e.targetException)
            throw e.targetException
        } catch (t: Throwable) {
            OmlLog.error("OMLCore", "Failed to start Minecraft!", t)
            throw t
        }
    }

    /**
     * [Hook injection point] The call injected by MinecraftHookTransformer at the end of
     * Minecraft.init(). Reaching here means the whole chain — class-loading pipeline + ASM
     * injection — is live.
     */
    @JvmStatic
    fun onMinecraftReady() = enterModInitStage("Minecraft")

    /**
     * [Hook injection point] The call injected by ServerHookTransformer at the end of the
     * MinecraftServer constructor.
     *
     * The dedicated server has no `Minecraft` class, so the [onMinecraftReady] path never fires
     * there — the two sides' "environment ready" moments inherently differ, hence a separate
     * entry for each rather than one side pretending to be the other.
     */
    @Volatile
    @JvmField
    var serverInstance: Any? = null

    @JvmStatic
    fun onServerReady(server: Any?) {
        serverInstance = server
        enterModInitStage("MinecraftServer")
    }

    /** The mod-init stage is entered exactly once: `MinecraftServer` may have multiple constructors, and both sides may have multiple instances. */
    private val modInitEntered = AtomicBoolean(false)

    private fun enterModInitStage(where: String) {
        if (!modInitEntered.compareAndSet(false, true)) return
        OmlLog.info("OMLCore", "$where initialized, entering mod initialization stage (${loadedMods.size} mod(s))")
        // Version finalization hooks (content materialization / resource-pack injection) may only matter on one side; a failure must not block mod init
        runCatching { adapter?.onGameReady() }.onFailure {
            OmlLog.error("OMLCore", "Version finalization hook failed (mod init continues)", it)
        }
        initMods()
    }

    /** [Hook injection point] The call injected into the entry of net.minecraft.client.main.Main#main(). */
    @JvmStatic
    fun onMainIntercepted() {
    }

    /** Whether running on the dedicated server; the version adapter uses it to decide which finalization work is meaningful (e.g. resource packs matter only for the client). */
    @JvmStatic
    fun isServerSide(): Boolean = serverSide

    @Volatile
    private var serverSide = false

    /** Game class loader: the version adapter must reflect game classes through it (game classes are not on the parent loader). */
    @JvmStatic
    fun gameClassLoader(): ClassLoader = primaryLoader

    /** Ids of loaded mods (mod asset injection uses the id as a candidate resource domain). */
    @JvmStatic
    fun loadedModIds(): List<String> = loadedMods.map { it.id.lowercase() }

    /**
     * Resource namespaces contributed by content packs ("*.toml" / "*.oml" files in the mods directory, no jar).
     *
     * Thread contract: written once during startup content loading, read-only afterwards.
     */
    private val flatContentNamespaces = linkedSetOf<String>()

    /**
     * The `.oml` content pack archives: unlike loose `.toml` packs they carry their own assets and
     * data, so the asset index must scan them exactly like mod jars.
     *
     * Thread contract: [loadContentPacks] writes it at startup and [reloadContentPacks] (a vanilla
     * `/reload`) may write it again while [contentPackFiles] is being read for asset indexing —
     * hence the copy-on-write set, keeping both sides lock-free.
     */
    private val contentPackFiles = java.util.concurrent.CopyOnWriteArraySet<File>()

    /** The content pack archives (`.oml`), for the asset index. */
    @JvmStatic
    fun contentPackFiles(): List<File> = contentPackFiles.toList()

    /**
     * The asset index over the loader's own mod set — the form the version adapters consume. Built
     * lazily by the caller (first asset query), after the mod scan has populated this class. The
     * class itself lives in `oml-content`; this bridge is what keeps it decoupled from [OMLCore]'s
     * mod state.
     */
    @JvmStatic
    fun assetIndex(): org.ohmyloader.content.ModAssetIndex =
        org.ohmyloader.content.ModAssetIndex(loadedModFiles() + contentPackFiles(), assetDomainIds())

    /**
     * Resource domains the asset injector synthesizes content assets for: loaded mod ids plus the
     * TOML content pack namespaces. Packs register real blocks/items but ship no jar, so without
     * being listed here their synthesized model / item-definition JSON would never be served.
     */
    @JvmStatic
    fun assetDomainIds(): List<String> = loadedModIds() + flatContentNamespaces


    /**
     * The mod jars themselves. A resource pack has to answer two different questions — "give me file X"
     * and "what is in directory D" — and only the first can go through the class loader, so asset
     * injection needs the jars to enumerate what a mod ships.
     */
    @JvmStatic
    fun loadedModFiles(): List<File> = loadedMods.map { it.file }

    /** [Hook injection point] Called by Main before it converts a fatal exception into a crash report: records the original exception as-is. */
    @JvmStatic
    fun debugOnCrash(t: Throwable, message: String) {
        OmlLog.error("OMLCore", "Original startup exception [$message]", t)
    }

    /** Runs [log] exactly once; hook handlers fire repeatedly (every frame / every title update). */
    private fun once(flag: AtomicBoolean, log: () -> Unit) {
        if (flag.compareAndSet(false, true)) log()
    }

    /**
     * [Hook injection point] Handler for window-title `ModifyArg` (in-place rewrite): replaces the
     * game-computed title with [WINDOW_TITLE].
     *
     * The title is a **product decision** — the window reads "OhMyLoader" no matter which vanilla
     * path (startup, world join, focus change) recomputes it. The hook chain delivers the original
     * title here first, so it can still be logged once before being swapped out.
     */
    @JvmStatic
    fun onWindowTitle(title: String): String {
        once(titleLogged) { OmlLog.info("OMLCore", "Window title: \"$title\" -> \"$WINDOW_TITLE\"") }
        return WINDOW_TITLE
    }

    /**
     * [Hook injection point] The "how many game ticks advanced this frame" **method-body local variable**
     * (local read) in `Minecraft.runTick(boolean)`.
     *
     * This one only reports, never decides: it proves the engine can, **without being told the slot**,
     * locate that int via data-flow analysis and pick the correct load instruction for its type — a
     * positional slip shows up here as a different number (or fails class verification outright).
     * Prints only once: `runTick` is called every frame, so repeated printing would drown out other logs.
     */
    @JvmStatic
    fun onRunTickTicks(ticks: Int) {
        once(ticksLogged) {
            OmlLog.info(
                "OMLCore",
                "Local read (resolved by type, slot inferred by data flow): ticks advanced this frame = $ticks",
            )
        }
    }

    /**
     * [Hook injection point] The same int local variable (local write), but the value **after the
     * write** is modified. The engine must synthesize the whole "read out → modify → write back to the
     * same slot" sequence at the injection site — get the slot or type even slightly wrong and class
     * verification fails outright (`VerifyError`) rather than silently reading a different number.
     *
     * The handler **returns the value unchanged**: this rule exists to prove the chain works, not to
     * change game behavior ("the modified value really reaches its destination" is proven by unit tests,
     * where the bytecode is genuinely defined and executed).
     */
    @JvmStatic
    fun onTicksStored(ticks: Int): Int {
        once(ticksStoreLogged) {
            OmlLog.info(
                "OMLCore",
                "Local write (read-modify-write after STORE): ticks advanced this frame = $ticks (written back unchanged)",
            )
        }
        return ticks
    }

    private fun commandRegistryFor(modId: String) = object : OMLCommandRegistry {
        override fun register(name: String, configure: OMLCommandDeclaration.() -> Unit) {
            CommandDeclarations.entries += CommandDeclarations.Entry(
                modId, OMLCommandDeclaration(name, null).apply(configure),
            )
        }
    }

    private fun networkRegistryFor(modId: String) = object : OMLNetworkRegistry {
        override fun <T> clientToServer(type: OMLPayloadType<T>, handler: (T, OMLNetworkContext) -> Unit) {
            PayloadDeclarations.add(modId, type, PayloadDeclarations.Direction.CLIENT_TO_SERVER, handler)
        }

        override fun <T> serverToClient(type: OMLPayloadType<T>, handler: (T, OMLNetworkContext) -> Unit) {
            PayloadDeclarations.add(modId, type, PayloadDeclarations.Direction.SERVER_TO_CLIENT, handler)
        }
    }

    private fun keyBindingRegistryFor(modId: String) = object : OMLKeyBindingRegistry {
        override fun register(id: String, defaultKey: String, onPress: () -> Unit) {
            KeyBindingDeclarations.entries += KeyBindingDeclarations.Entry(modId, id, defaultKey, onPress)
        }
    }

    private fun modInstance(mod: ModContainer): Any = modInstances.getOrPut(mod.id) {
        Class.forName(mod.entryClass, true, primaryLoader).getDeclaredConstructor().newInstance()
    }

    private fun initMods() {
        // The order was computed (and the graph validated) in start(), before the main loader was built
        val ordered = loadedMods
        initOrder.addAll(ordered)
        registerBuiltinCommands()
        for (mod in ordered) {
            val (id, name, version, _) = mod
            try {
                val instance = modInstance(mod)
                if (instance is OMLModInitializer) {
                    val config = org.ohmyloader.core.config.OMLConfigImpl(id, File(gameDir, "config"))
                    instance.onInitialize(ModContext(id, name, version, config))
                    config.generateIfMissing()
                } else {
                    OmlLog.warn("OMLCore", "Mod [$id] does not implement OMLModInitializer, skipping")
                }
                if (instance is OMLCommandProvider) {
                    instance.declareCommands(commandRegistryFor(id))
                }
                if (instance is OMLNetworkProvider) {
                    instance.declareNetwork(networkRegistryFor(id))
                }
                if (instance is OMLKeyBindingProvider) {
                    instance.declareKeyBindings(keyBindingRegistryFor(id))
                }
            } catch (t: Throwable) {
                initFailures[id] = t
                OmlLog.error("OMLCore", "Mod [$id] initialization failed", t)
            }
        }
    }

    /**
     * Mods whose [OMLModInitializer.onInitialize] threw, by id — surfaced by the `/oml mods` command.
     *
     * Thread contract: written only during mod initialization, read-only afterwards (the `/oml`
     * executor reads it). No reader may mutate it.
     */
    val initFailures = LinkedHashMap<String, Throwable>()

    /** The initialization order after dependency sorting — what `/oml mods` reports. Same thread contract as [initFailures]. */
    val initOrder = mutableListOf<ModContainer>()

    /**
     * The loader's own `/oml` command, registered like a mod's but under the `oml` id: `mods`
     * lists every loaded mod with its version, failed inits in red via
     * [OMLCommandSource.replyError]; `version` prints the loader version.
     */
    private fun registerBuiltinCommands() {
        fun node(name: String) = OMLCommandDeclaration(name, null)
        val mods = node("mods").apply {
            executes { source ->
                source.reply("${initOrder.size} mod(s) loaded:")
                for ((id, _, version) in initOrder) {
                    val failure = initFailures[id]
                    if (failure == null) {
                        source.reply("  $id $version")
                    } else {
                        source.replyError("  $id $version — FAILED: ${failure.message}")
                    }
                }
                for (id in initFailures.keys - initOrder.map { it.id }.toSet()) {
                    source.replyError("  $id — FAILED: ${initFailures[id]?.message}")
                }
            }
        }
        val version = node("version").apply {
            executes { source -> source.reply("Oh My Loader $VERSION") }
        }
        val root = node("oml").apply {
            children += mods
            children += version
        }
        CommandDeclarations.entries.add(CommandDeclarations.Entry("oml", root))
    }

    /**
     * Injection-rule self-check.
     *
     * The most dangerous failure mode of a rule is "modified wrong but didn't crash, just behaves
     * incorrectly" — no exception, just silently one thing less. So every rule is re-checked **before
     * handing over the main thread**: handler existence, static-ness, signature consistency and require
     * sanity. Modes (`-Doml.injection.verify=`): `fail` (default) **aborts startup** and prints each
     * problem — the earlier a bad rule explodes, the cheaper it is; `warn` prints only; `off` no checking.
     */
    private fun verifyInjectionRules(transformers: List<IClassTransformer>) {
        val mode = System.getProperty("oml.injection.verify", "fail").lowercase()
        if (mode == "off") return
        val problems = transformers.filterIsInstance<IVerifiableTransformer>()
            .flatMap { it.verify(primaryLoader) }
        if (problems.isEmpty()) return
        problems.forEach { OmlLog.error("OMLCore", "injection rule: $it") }
        if (mode == "fail") {
            error(
                "[OMLCore] Injection-rule self-check failed (${problems.size} issue(s), see above). " +
                    "Fix the rules and retry; to start with problems regardless add -Doml.injection.verify=warn",
            )
        }
        OmlLog.warn(
            "OMLCore",
            "Injection-rule self-check has ${problems.size} issue(s) (warn mode, continuing startup)",
        )
    }

    /**
     * Game directory = the `--gameDir` game argument (the launcher expands it from the version JSON's
     * `${game_directory}`); otherwise the current working directory — the dedicated server exactly does
     * this, since its eula / server.properties / world are written in the launch directory.
     *
     * A path pointing to a nonexistent directory produces a notice: if the mods directory lands elsewhere,
     * the symptom is "no mod loads" while everything else looks fine, so this must not stay silent.
     */
    private fun resolveGameDir(args: Array<String>): File {
        val i = args.indexOf("--gameDir")
        if (i < 0 || i + 1 >= args.size) return File(System.getProperty("user.dir") ?: ".").absoluteFile
        val dir = File(args[i + 1])
        if (!dir.isDirectory) OmlLog.warn(
            "OMLCore",
            "the directory pointed to by --gameDir does not exist (${dir.path})",
        )
        return dir.absoluteFile
    }

    /** Builds the runtime URL list from the JVM classpath and the version library directory (`oml.library.dir`). */
    private fun buildRuntimeUrls(): List<URL> = buildList {
        System.getProperty("java.class.path")?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.forEach { add(Paths.get(it).toUri().toURL()) }
        // Version runtime library directory: recursively include the libraries downloaded by fetchLibraries
        // (gradle's fileTree snapshot may predate the download completing, so this complements it at runtime)
        System.getProperty("oml.library.dir")?.let { dirProp ->
            File(dirProp).walkTopDown().filter { it.isFile && it.extension == "jar" }
                .forEach { add(it.toURI().toURL()) }
        }
    }

    private fun printBanner() {
        OmlLog.info("OMLCore", "Oh My Loader (OML) v$VERSION starting...")
    }
}
