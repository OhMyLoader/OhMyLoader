package org.ohmyloader.installer

import kotlinx.serialization.json.*
import org.ohmyloader.devtools.AssetDownloader
import org.ohmyloader.devtools.GameEnvironment
import java.io.File

/**
 * Validation outcome. Errors block the install; warnings are shown to the user, who then decides.
 *
 * Splitting the two matters: "this directory does not exist" must stop everything, while "this does
 * not look like a Minecraft directory" is a judgment call the user is entitled to make (a portable
 * install, a staging folder, a symlinked root).
 */
class Validation(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * An installation shape. Everything that differs between "install into a launcher's .minecraft",
 * "install into a Prism/MultiMC instance" and "build a self-bootstrapping server directory" lives
 * behind this interface — the CLI, the GUI and the Gradle task all just pick one.
 */
interface InstallationTarget {

    /** Stable id used by `--target`. */
    val id: String

    /** Translation key for the name shown in the GUI (see messages.properties). */
    val displayNameKey: String

    /** The localized name, resolved on every access. */
    val displayName: String get() = Messages.t(displayNameKey)

    /** Checked before anything is written. Never writes. */
    fun validate(ctx: InstallContext): Validation

    /** Performs the install. Throws [InstallationException] with a user-facing message on failure. */
    fun install(ctx: InstallContext, sink: ProgressSink)

    /** The single sentence the user has to read after success. */
    fun successHint(ctx: InstallContext): String

    companion object {
        // `lazy` is load-bearing, not decoration. The first touch of any one target object runs this
        // interface's <clinit> (it declares default methods) while that object's own <clinit> is
        // still in progress, and an eagerly initialized ALL would capture the unfinished object as
        // null — the first byId() call (java -jar ... --target server) would then NPE. Lazy defers
        // the list build until all three singletons are fully initialized.
        val ALL: List<InstallationTarget> by lazy {
            listOf(StandardLauncherTarget, PrismComponentTarget, DedicatedServerTarget)
        }

        fun byId(id: String): InstallationTarget? = ALL.firstOrNull { it.id == id }
    }
}

// ---------------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------------

internal fun validateDirectory(dir: File, what: String): List<String> {
    val problems = mutableListOf<String>()
    if (!dir.exists()) {
        problems += Messages.t("validate.dirMissing", what, dir.absolutePath)
    } else if (!dir.isDirectory) {
        problems += Messages.t("validate.dirNotDir", what, dir.absolutePath)
    } else if (!dir.canWrite()) {
        problems += Messages.t("validate.dirNotWritable", what, dir.absolutePath)
    }
    return problems
}

/** A loose sanity check — used to warn, never to block. */
internal fun looksLikeMinecraftDir(dir: File): Boolean =
    File(dir, "versions").isDirectory || File(dir, "launcher_profiles.json").isFile ||
        File(dir, "options.txt").isFile

/** Install id becomes a directory name and a Maven path segment; anything path-like is rejected. */
internal fun validateInstallId(id: String): List<String> {
    if (id.isBlank()) return listOf(Messages.t("installId.empty"))
    val bad =
        id.any { it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' }
    // 双引号也在拦截集合里，只是不列进提示文案（免得提示本身还要转义）
    if (bad) return listOf(Messages.t("installId.chars", id))
    if (id == "." || id == "..") return listOf(Messages.t("installId.dots"))
    return emptyList()
}

/**
 * The mods directory name is one path segment under the game (or version) directory. Unchecked it
 * reaches `File(base, name)` — a name carrying separators or `..` would place mods outside the
 * installation, which is the one directory the whole (possibly version-isolated) layout is built on.
 */
internal fun validateModsDirName(name: String): List<String> {
    if (name.isBlank()) return listOf(Messages.t("modsDir.empty"))
    val bad =
        name.any { it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' }
    if (bad) return listOf(Messages.t("modsDir.chars", name))
    if (name == "." || name == "..") return listOf(Messages.t("modsDir.dots"))
    return emptyList()
}

internal fun downloadProgressFor(sink: ProgressSink) =
    AssetDownloader.DownloadProgress { done, total, label -> sink.progress(done, total, label) }

/**
 * Lays the bundled `oml-native` library out into [nativesDir] — the single directory
 * `java.library.path` points at, where the vanilla natives already live. Every installation shape
 * calls this so the runtime's NativeManager finds our library exactly where the game finds LWJGL.
 *
 * The source is whichever this install has: the plain copy keyed by the `<os>-<arch>` token, or — an
 * install made through the CLI / Gradle, which is handed nothing but the packaged jars — the library
 * inside the platform's `natives-<os>` jar.
 *
 * A `null` artifact (this build bundles no library for the platform it runs on) is a logged skip,
 * never a failure: the runtime's zstd users degrade to the vanilla paths they also support.
 */
internal fun installOmlNative(
    nativesDir: File,
    artifacts: ArtifactSource,
    journal: InstallJournal? = null,
    log: (String) -> Unit,
) {
    val native = artifacts.nativeLibrary() ?: nativeLibraryFromPackages(artifacts)
    if (native == null) {
        log("natives/: no oml-native library bundled for this platform, skipping")
        return
    }
    val written = writeAtomic(File(nativesDir, native.fileName), journal) { native.open() }
    log("natives/${native.fileName} (${written.size / 1024} KB)")
}

/**
 * Runs [body] against the downloader and translates its failures into [InstallationException]: the
 * downloader reports failures as plain exceptions with a technical message ("Connection refused:
 * getsockopt"), which left as-is reach the user as an unhandled stack trace. The translation belongs
 * here, at the boundary, where we know who is looking at the screen.
 *
 * The downloader's running commentary is routed into [sink] rather than to stdout: this install runs
 * inside a Swing window whose log pane believes it is the only writer.
 */
internal fun <T> withDownloader(sink: ProgressSink, body: (AssetDownloader.DownloadProgress) -> T): T {
    val previousLog = AssetDownloader.logSink
    val previousError = AssetDownloader.errorSink
    AssetDownloader.logSink = { line -> sink.log(line) }
    AssetDownloader.errorSink = { line -> sink.log(line) }
    try {
        return body(downloadProgressFor(sink))
    } catch (e: Exception) {
        throw InstallationException(Messages.t("err.download", e.message ?: e.toString()), e)
    } finally {
        AssetDownloader.logSink = previousLog
        AssetDownloader.errorSink = previousError
    }
}

/** The vanilla game jar the runtime loads. Missing now is fine: the client downloads it on first launch. */
internal fun vanillaGameJar(gameDir: File, version: String): File =
    File(gameDir, "versions/$version/$version.jar")

// ===================================================================================================
// Target 1 — standard launcher (HMCL / PCL2 / official)
// ===================================================================================================
object StandardLauncherTarget : InstallationTarget {

    override val id = "standard"
    override val displayNameKey = "target.standard"

    private const val MAIN_CLASS = "org.ohmyloader.launcher.OMLBootstrap"

    /**
     * The OS default game directory — a real, recognized path, never "wherever the jar was launched
     * from" (usually Downloads): an install into a download folder succeeds but the version never
     * shows up in the player's launcher.
     */
    fun defaultGameDir(): File {
        val home = System.getProperty("user.home") ?: "."
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", ".minecraft")
            os.contains("mac") -> File(home, "Library/Application Support/minecraft")
            else -> File(home, ".minecraft")
        }
    }

    override fun validate(ctx: InstallContext): Validation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        errors += validateDirectory(ctx.targetDir, Messages.t("validate.gameDir"))
        errors += validateInstallId(ctx.installId)
        errors += validateModsDirName(ctx.modsDirName)

        if (ctx.targetDir.isDirectory && !looksLikeMinecraftDir(ctx.targetDir)) {
            warnings += Messages.t("validate.mcLooksOdd")
        }

        // A shared mods/ folder is not merely untidy: nothing downstream filters jars by game version,
        // so a mod built for another game version sitting next to one for this version will be loaded
        // by both. That is a crash whose cause is invisible from the install, so it must be an explicit
        // decision, never a default.
        if (!ctx.isolation && !ctx.allowSharedMods) {
            errors += Messages.t("validate.sharedMods")
        }

        return Validation(errors, warnings)
    }

    override fun install(ctx: InstallContext, sink: ProgressSink) {
        val gameDir = ctx.targetDir
        val versionDir = File(gameDir, "versions/${ctx.installId}")
        // mods directory: pinned into the version JSON so a later change of the launcher's isolation
        // setting cannot silently move it
        val modsDir = (if (ctx.isolation) File(versionDir, ctx.modsDirName) else File(gameDir, ctx.modsDirName))
            .let { ctx.journal.ensureDir(it) }

        sink.stage(Messages.t("stage.installLayer"))
        val layer = ctx.artifacts.layerJars()
        val libraries = mutableListOf<InstalledLibrary>()
        layer.forEachIndexed { index, artifact ->
            sink.progress(index.toLong(), layer.size.toLong(), Messages.t("label.layerFiles"))
            libraries += installLibrary(gameDir, artifact, ctx.installId, ctx.log, ctx.journal)
        }
        sink.progress(layer.size.toLong(), layer.size.toLong(), Messages.t("label.layerFiles"))

        // oml-native travels with the version through the standard natives mechanism: the packaged
        // `natives-<os>` jars land in the Maven tree under libraries/ and the JSON below declares them
        // under `downloads.classifiers` with a `natives` block, so PCL2 / HMCL / the official launcher
        // resolve and extract the DLL into *their* `${natives_directory}` themselves — the only
        // directory `-Djava.library.path` points at. A Maven resolver looks an artifact up by
        // `group/artifact/version/artifact-version-classifier.jar`, not by a bare file name, so the
        // tree (not a flat write) is what makes the jar findable. The bare copy into
        // versions/<id>/natives below is the fallback the runtime loads when nothing extracted the jar.
        val nativeLibrary =
            installOmlNativePackages(
                File(gameDir, "libraries"),
                ctx.artifacts,
                ctx.log,
                mavenTree = true,
                journal = ctx.journal,
            )
        installOmlNative(File(versionDir, "natives"), ctx.artifacts, ctx.journal, ctx.log)

        // game jar: the client jar is the game jar for both sides — the modern server artifact is a
        // bundler wrapper, and every class it unpacks is byte-for-byte present in the client jar
        val gameJar = vanillaGameJar(gameDir, ctx.target.version)

        val props = RuntimeProperties(
            gameJar = gameJar,
            modsDir = modsDir,
            side = ctx.side,
        )

        sink.stage(Messages.t("stage.writeVersionJson"))
        writeVersionStubJar(File(versionDir, "${ctx.installId}.jar"), ctx.journal)
        writeVersionJson(
            target = File(versionDir, "${ctx.installId}.json"),
            id = ctx.installId,
            inherits = ctx.target.version,
            mainClass = MAIN_CLASS,
            javaMajor = ctx.target.javaMajor,
            libraries = libraries,
            jvmArgs = launcherJvmArgs(props),
            nativeLibrary = nativeLibrary,
            journal = ctx.journal,
        )
        ctx.log("versions/${ctx.installId}/${ctx.installId}.json")
        ctx.log(Messages.t("log.librariesInstalled", ctx.installId, libraries.size))

        if (ctx.side == "server") {
            if (ctx.acceptEula) {
                writeAtomicText(File(gameDir, "eula.txt"), "eula=true\n", ctx.journal)
                ctx.log(Messages.t("log.eulaWritten"))
            } else {
                ctx.log(Messages.t("log.eulaSkipped", EULA_URL))
            }
        }
    }

    override fun successHint(ctx: InstallContext): String {
        val base = Messages.t("hint.standard", ctx.installId)
        return if (ctx.side == "server" && !ctx.acceptEula) {
            Messages.t("hint.standard.eula", base)
        } else {
            base
        }
    }
}

internal const val EULA_URL = "https://aka.ms/MinecraftEULA"

// ===================================================================================================
// Target 2 — Prism Launcher / MultiMC
//
// Prism/MultiMC do not read `versions/<id>/<id>.json` + inheritsFrom at all: they use a component
// model (instance.cfg + mmc-pack.json + patches/*.json). Producing only the standard layout therefore
// means "cannot be imported at all", which is exactly the state a pack author would run into.
// ===================================================================================================
object PrismComponentTarget : InstallationTarget {

    override val id = "prism"
    override val displayNameKey = "target.prism"

    internal const val COMPONENT_UID = "org.ohmyloader"
    private const val MAIN_CLASS = "org.ohmyloader.launcher.OMLBootstrap"

    /** The bare library's name per platform — the file [installOmlNative] writes, and nothing else. */
    private val BARE_NATIVE_NAMES = listOf("oml-native.dll", "oml-native.so", "oml-native.dylib")

    override fun validate(ctx: InstallContext): Validation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        errors += validateDirectory(ctx.targetDir, Messages.t("validate.instanceDir"))
        errors += validateInstallId(ctx.installId)
        errors += validateModsDirName(ctx.modsDirName)

        if (ctx.targetDir.isDirectory) {
            val instance = instanceRootOf(ctx.targetDir)
            val hasMarker = File(instance, "instance.cfg").isFile || File(instance, "mmc-pack.json").isFile
            if (!hasMarker) {
                errors += Messages.t("validate.prismNotInstance", instance.absolutePath)
            }
        }
        if (ctx.targetDir.isDirectory && gameDirOf(instanceRootOf(ctx.targetDir)) == null) {
            warnings += Messages.t("validate.prismNoGameDir")
        }
        return Validation(errors, warnings)
    }

    override fun install(ctx: InstallContext, sink: ProgressSink) {
        val instance = instanceRootOf(ctx.targetDir)
        val gameDir = gameDirOf(instance) ?: ctx.journal.ensureDir(File(instance, "minecraft"))
        val librariesDir = ctx.journal.ensureDir(File(instance, "libraries"))

        sink.stage(Messages.t("stage.installLayer"))
        val layer = ctx.artifacts.layerJars()
        val libraryNames = mutableListOf<String>()
        layer.forEachIndexed { index, artifact ->
            sink.progress(index.toLong(), layer.size.toLong(), Messages.t("label.layerFiles"))
            // Prism resolves `MMC-hint: local` libraries as <instance>/libraries/<artifact>-<version>.jar
            // — a FLAT file name derived from the maven coordinate, not the Maven tree it uses for
            // remote downloads. Writing the Maven layout here made Prism report every jar as a
            // missing local file on first launch. (The source file name lacks the install id suffix,
            // so derive the target name from the coordinate instead of artifact.fileName.)
            val flatName = "${artifact.coordinateBase}-${ctx.installId}.jar"
            val written = writeAtomic(File(librariesDir, flatName), ctx.journal) { artifact.open() }
            libraryNames += LayerLayout.coordinate(artifact.coordinateBase, ctx.installId)
            ctx.log("libraries/$flatName (${written.size / 1024} KB)")
        }
        sink.progress(layer.size.toLong(), layer.size.toLong(), Messages.t("label.layerFiles"))

        // a previous layout wrote a Maven tree under libraries/org; it is unreferenced by the patch
        // and can only confuse future readers, so remove it (best effort, ours alone)
        val staleMavenTree = File(librariesDir, "org/ohmyloader")
        if (staleMavenTree.exists()) {
            val removed = staleMavenTree.deleteRecursively()
            ctx.log(if (removed) "removed stale libraries/org/ohmyloader" else "could not remove stale libraries/org/ohmyloader")
        }

        // Zombie purge: zstd-ffm jars (zstd + per-platform zstd-native-*) that an older layer shipped
        // flat. Nothing in any patch references them, they are unambiguously ours (a mod's zstd
        // binding would live inside its own jar), and leaving them means every launch scans dead
        // classpath entries. Only the flat pattern is swept — never the whole directory.
        librariesDir.listFiles { f -> f.isFile && f.name.startsWith("zstd") && f.name.endsWith(".jar") }
            ?.forEach { zombie ->
                if (zombie.delete()) ctx.log("removed stale ${zombie.name}")
            }

        val gameJar = vanillaGameJar(gameDir, ctx.target.version)
        if (!gameJar.isFile) {
            ctx.log(Messages.t("log.gameJarMissing", gameJar))
        }

        // oml-native through Prism's own channel: the packaged jars land flat in libraries/ (the one
        // place Prism resolves an `MMC-hint: local` entry), and the patch's `natives` block makes Prism
        // extract the matching one into its own natives directory at launch — so there is no bare copy
        // into <instance>/natives. Every platform's jar is written, not only this machine's: a Prism
        // instance is exported as a zip and imported on another OS, and a missing platform degrades
        // zstd in silence rather than failing, so narrowing here buys nothing but a latent bug.
        val nativeLibrary = installOmlNativePackages(librariesDir, ctx.artifacts, ctx.log, journal = ctx.journal)
        // a previous layout wrote the bare library there; nothing references it
        BARE_NATIVE_NAMES.forEach { name ->
            val stale = File(instance, "natives/$name")
            if (stale.isFile && stale.delete()) ctx.log("removed stale natives/$name")
        }

        val props = RuntimeProperties(
            gameJar = gameJar,
            modsDir = ctx.journal.ensureDir(File(gameDir, ctx.modsDirName)),
            side = "client",
        )

        sink.stage(Messages.t("stage.prismPatch"))
        writeAtomicText(
            File(instance, "patches/$COMPONENT_UID.json"),
            prismPatchJson(ctx, props, libraryNames, nativeLibrary),
            ctx.journal,
        )
        ctx.log("patches/$COMPONENT_UID.json")

        if (ctx.addPrismComponent) {
            registerComponentInPack(instance, ctx)
        }
    }

    private fun prismPatchJson(
        ctx: InstallContext,
        props: RuntimeProperties,
        libraries: List<String>,
        nativeLibrary: InstalledNativeLibrary?,
    ): String {
        // `+jvmArgs` is where the JVM contract goes. Prism applies it on top of the component's own
        // args, so the -D properties and Java requirement travel with the component instead of relying
        // on the user setting them by hand.
        // Deliberately *not* launcherJvmArgs(): `-cp` / `-Djava.library.path` are for launchers that
        // read a Minecraft version JSON (HMCL / PCL2 / official). Prism builds the classpath and the
        // natives directory itself out of the component's libraries, so those two entries are its
        // business — handing it a `${classpath}` it may not define would risk a literal argument.
        val jvmArgs = props.asJvmArgs().joinToString(",\n") { "    \"${it.replace("\\", "\\\\")}\"" }
        val libEntries = libraries.map {
            """    {
      "name": "$it",
      "MMC-hint": "local"
    }"""
        }
        // The natives entry is a local library like the ones above, plus the `natives` block that
        // makes Prism treat it as extractable — it unpacks the jar into the instance's natives
        // directory at launch, the same way it handles the vanilla lwjgl natives.
        val nativeEntry = nativeLibrary?.let { listOf(it.toPrismJson()) } ?: emptyList()
        val libs = (libEntries + nativeEntry).joinToString(",\n")
        return """{
  "formatVersion": 1,
  "name": "OhMyLoader",
  "uid": "$COMPONENT_UID",
  "version": "${ctx.installId}",
  "compatibleJavaMajors": [ ${ctx.target.javaMajor} ],
  "mainClass": "$MAIN_CLASS",
  "libraries": [
$libs
  ],
  "+jvmArgs": [
$jvmArgs
  ]
}
"""
    }

    /**
     * Opt-in: adds the component to `mmc-pack.json` so the user does not have to click it in.
     *
     * Off by default on purpose — this edits a file Prism owns, and getting it wrong can leave the
     * instance unlaunchable. The previous copy is kept as `mmc-pack.json.bak`.
     */
    private fun registerComponentInPack(instance: File, ctx: InstallContext) {
        val pack = File(instance, "mmc-pack.json")
        if (!pack.isFile) {
            ctx.log(Messages.t("log.prismNoPack"))
            return
        }
        val root = Json.parseToJsonElement(pack.readText(Charsets.UTF_8)).jsonObject
        val components = root["components"]?.jsonArray ?: run {
            ctx.log(Messages.t("log.prismNoComponents"))
            return
        }
        val exists = components.any {
            (it as? JsonObject)?.get("uid")?.jsonPrimitive?.contentOrNull == COMPONENT_UID
        }
        if (exists) {
            ctx.log(Messages.t("log.prismAlreadyRegistered", COMPONENT_UID))
            return
        }
        writeAtomicText(File(instance, "mmc-pack.json.bak"), pack.readText(Charsets.UTF_8), ctx.journal)
        val entry = buildJsonObject {
            put("uid", COMPONENT_UID)
            put("version", ctx.installId)
            put("cachedName", "OhMyLoader")
            put("cachedVersion", ctx.installId)
            put("cachedVolatile", true)
            put("dependencyOnly", false)
        }
        // A new root rather than an in-place edit: kotlinx JsonElements are immutable, which here is
        // the honest model anyway - the file on disk is replaced atomically only after the whole
        // document has been built.
        val updated =
            JsonObject(root.entries.associate { it.toPair() } + ("components" to JsonArray(components + entry)))
        val pretty = Json { prettyPrint = true }
        writeAtomicText(pack, pretty.encodeToString(JsonElement.serializer(), updated) + "\n", ctx.journal)
        ctx.log(Messages.t("log.prismRegistered", COMPONENT_UID))
    }

    /** Accepts either the instance root or its minecraft dir; walks up one level when needed. */
    internal fun instanceRootOf(dir: File): File {
        if (File(dir, "instance.cfg").isFile || File(dir, "mmc-pack.json").isFile) return dir
        val parent = dir.parentFile
        if (parent != null && (File(parent, "instance.cfg").isFile || File(parent, "mmc-pack.json").isFile)) {
            return parent
        }
        return dir
    }

    private fun gameDirOf(instance: File): File? =
        listOf("minecraft", ".minecraft").map { File(instance, it) }.firstOrNull { it.isDirectory }

    override fun successHint(ctx: InstallContext): String =
        if (ctx.addPrismComponent) {
            Messages.t("hint.prism.registered")
        } else {
            Messages.t("hint.prism")
        }
}

// ===================================================================================================
// Target 3 — self-bootstrapping dedicated server
// ===================================================================================================
object DedicatedServerTarget : InstallationTarget {

    override val id = "server"
    override val displayNameKey = "target.server"

    override fun validate(ctx: InstallContext): Validation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val dir = ctx.targetDir
        if (dir.exists()) {
            errors += validateDirectory(dir, Messages.t("validate.serverDir"))
        } else {
            // creating the server directory is the normal case for a first install
            val parent = dir.parentFile
            if (parent != null && parent.exists() && !parent.canWrite()) {
                errors += Messages.t("validate.serverParentNotWritable", dir.absolutePath)
            }
        }

        if (!ctx.acceptEula) {
            errors += Messages.t("validate.serverEula", EULA_URL)
        }
        errors += validateModsDirName(ctx.modsDirName)
        return Validation(errors, warnings)
    }

    override fun install(ctx: InstallContext, sink: ProgressSink) {
        val base = ctx.targetDir
        val version = ctx.target.version
        // Every directory here except the mods dir is created and filled exclusively by this
        // install (game downloads), so a failed install removes them with their content; mods/ may
        // already hold user mods and is only ever created-empty, never wiped.
        listOf("lib", "libraries", "cache", "minecraft/$version")
            .forEach { ctx.journal.ownTree(ctx.journal.ensureDir(File(base, it))) }
        ctx.journal.ensureDir(File(base, ctx.modsDirName))
        val nativesDir = ctx.journal.ensureDir(File(base, "natives"))
        ctx.journal.ownTree(nativesDir)
        sink.stage(Messages.t("stage.prepareServer"))

        // 1. Everything that needs the network happens first, and every local write waits until the
        //    downloads have succeeded: a network failure must leave an empty directory, not one that
        //    looks nearly installed but has no game in it.

        // the game itself: the modern (bundler) server artifact cannot be started directly, so the
        // client jar is used, exactly like the Gradle runServer task does
        val serverJar = File(base, "minecraft/$version/server.jar")
        sink.stage(Messages.t("stage.downloadGame"))
        withDownloader(sink) { _ ->
            GameEnvironment.open(version).use { env ->
                ctx.log(Messages.t("log.bundlerClientJar", version))
                env.downloadClientJar(serverJar)
                ctx.journal.recordDownload(serverJar)
            }
        }

        // libraries: the game's runtime libraries, natives included (selected for the platform the
        // installer runs on, and the natives directory is what java.library.path in the start script
        // points at)
        sink.stage(Messages.t("stage.downloadLibraries"))
        val librariesDir = File(base, "libraries")
        withDownloader(sink) { progress ->
            GameEnvironment.open(version).use { env ->
                env.downloadLibraries(librariesDir, progress)
                env.extractNatives(librariesDir, nativesDir, progress)
            }
        }
        // our own library joins the vanilla ones in the same directory the start scripts'
        // bootstrap points java.library.path at
        installOmlNative(nativesDir, ctx.artifacts, ctx.journal, ctx.log)

        // 2. local writes: the shell and the layer
        // the shell's manifest carries Add-Opens / Enable-Native-Access — that is what makes a bare
        // `java -jar oml-launcher.jar` a complete command
        sink.stage(Messages.t("stage.installLayer"))
        val shell = ctx.artifacts.shellJar()
        val shellWritten = writeAtomic(File(base, "oml-launcher.jar"), ctx.journal) { shell.open() }
        ctx.log(Messages.t("log.shellJar", shellWritten.size / 1024))

        val layer = ctx.artifacts.layerJars()
        layer.forEachIndexed { index, artifact ->
            sink.progress(index.toLong(), layer.size.toLong(), Messages.t("label.layerFiles"))
            val written = writeAtomic(File(base, "lib/${artifact.fileName}"), ctx.journal) { artifact.open() }
            ctx.log(Messages.t("log.layerJar", artifact.fileName, written.size / 1024))
        }
        sink.progress(layer.size.toLong(), layer.size.toLong(), Messages.t("label.layerFiles"))

        // lib/ is ours alone — every jar in it was put there by an OML install. Anything the current
        // layer does not carry is a leftover of an older installer; re-installation over a broken tree
        // must sweep it, or it keeps riding the bootstrap's classpath forever.
        val layerNames = layer.map { it.fileName }.toSet()
        File(base, "lib").listFiles { f -> f.isFile && f.name.endsWith(".jar") && f.name !in layerNames }
            ?.forEach { zombie ->
                if (zombie.delete()) ctx.log("removed stale lib/${zombie.name}")
            }

        // 3. launch.properties
        sink.stage(Messages.t("stage.writeConfig"))
        val props = java.util.Properties().apply {
            setProperty("side", "server")
            setProperty("version", version)
            setProperty("mods.dir", File(base, ctx.modsDirName).absolutePath)
        }
        // Properties.store, not a hand-built "$k=$v": java.util.Properties.load treats a backslash as an
        // escape character, so writing a Windows path verbatim makes the reader silently drop every
        // backslash — "D:\\Workspaces\\…\\mods" comes back as "D:Workspaces…mods". The runtime then
        // finds no mods at all, and nothing reports it. (Caught by the end-to-end server boot test.)
        val propsBytes = java.io.ByteArrayOutputStream()
        props.store(propsBytes, "OhMyLoader server launch configuration")
        writeAtomicText(File(base, "launch.properties"), propsBytes.toString("ISO-8859-1"), ctx.journal)
        ctx.log(Messages.t("log.launchProperties"))

        // 4. start scripts (with the Java version check the owner needs)
        sink.stage(Messages.t("stage.writeScripts"))
        writeServerScripts(base, ctx.target.javaMajor, ctx.log, ctx.journal)

        // 5. EULA — only because the user said so explicitly
        writeAtomicText(File(base, "eula.txt"), "eula=true\n", ctx.journal)
        ctx.log(Messages.t("log.eulaWritten"))
    }

    override fun successHint(ctx: InstallContext): String =
        Messages.t("hint.server", ctx.targetDir.name, ctx.target.javaMajor)
}
