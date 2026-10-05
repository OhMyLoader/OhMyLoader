package org.ohmyloader.installer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile

/**
 * Pins our own stdout/stderr to UTF-8, but only when it is not an interactive console.
 *
 * On a zh-CN Windows the JVM picks GBK for a *redirected* stdout, so Chinese installer output ends up in
 * a log file as GBK bytes and reads as mojibake in everything that assumes UTF-8 — including this
 * installer's own log area, which is fed by tee-ing stdout through a UTF-8 decoder. With a real console
 * the JVM's own choice is the correct one (GBK is what that console renders), so it is left alone.
 */
fun pinStdoutToUtf8() {
    if (System.console() != null) return
    runCatching {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))
    }
}

// ---------------------------------------------------------------------------------------------------
// Progress reporting: a real sink lets the GUI show "正在下载 (37/41)" and a percentage — an
// indeterminate bar over a 29 MB jar plus 41 libraries is indistinguishable from a hang.
// ---------------------------------------------------------------------------------------------------
interface ProgressSink {
    /** Entering a new phase. [total] > 0 means the phase can report a percentage. */
    fun stage(label: String, total: Long = 0L) {}

    /** Work done inside the current phase. */
    fun progress(done: Long, total: Long, label: String = "") {}

    /** A phase whose size is unknown (copying a layer of many small files). */
    fun indeterminate(label: String) {}

    /**
     * One already-formatted line of running commentary from a component the installer drives. This is
     * where the downloader's own narration goes — routed here rather than to stdout, so the GUI's log
     * pane (the only thing a player actually looks at) sees it and the progress dialog is not
     * overwritten by stray technical lines.
     */
    fun log(line: String) {}

    companion object {
        val NOP: ProgressSink = object : ProgressSink {}
    }
}

/** Prints the same events to stdout, so the CLI and the Gradle task show progress too. */
class ConsoleProgressSink : ProgressSink {

    // Full lines rather than a carriage-return spinner: the installer writes its own log lines straight
    // to stdout, and \r-based progress gets spliced into whichever line comes next. The callers report
    // coarsely (every 64 items / every 512 KB), so the line count stays reasonable.
    private val prefix: String get() = Messages.t("progress.consolePrefix")

    override fun stage(label: String, total: Long) {
        println("$prefix $label")
    }

    override fun progress(done: Long, total: Long, label: String) {
        if (total <= 0) {
            if (done > 0) println("$prefix ${Messages.t("progress.bytes", label, done / 1024)}")
            return
        }
        val pct = (done * 100 / total).coerceIn(0, 100)
        println("$prefix ${Messages.t("progress.console", label, done, total, pct)}")
    }

    override fun indeterminate(label: String) {
        println("$prefix $label")
    }

    override fun log(line: String) {
        // The CLI *is* a terminal: forwarding verbatim keeps the downloader's detail available there,
        // where it is useful for diagnosis, without inventing a second formatting layer.
        println(line)
    }
}

// ---------------------------------------------------------------------------------------------------
// Where the installer's own bytes come from: an ArtifactSource is just "give me streams".
// FatJarArtifactSource reads the entries straight out of the running installer jar,
// DirectoryArtifactSource wraps explicit files (the CLI / Gradle path). Both are consumed in a single
// streaming pass that writes each destination exactly once — no staging directory exists to leak.
// ---------------------------------------------------------------------------------------------------

/** One jar of the OML layer. [coordinateBase] is the artifact name used in the Maven path. */
class LayerArtifact(val fileName: String, val coordinateBase: String, val open: () -> InputStream)

/**
 * One native library (e.g. `oml-native.dll`) the installer lays out into the target's `natives/`
 * directory — the same directory `java.library.path` points at, where the vanilla natives already
 * live. [relativePath] carries the `<os>-<arch>` token (`windows-x86_64`, `osx-arm64`, …) so the
 * targets can preserve the platform layout or flatten it, per target convention.
 */
class NativeArtifact(val fileName: String, val relativePath: String, val open: () -> InputStream)

/**
 * One packaged oml-native jar in the standard Maven `natives-<classifier>` shape: `classifier` is
 * null for the minimal main jar (`oml-native-<version>.jar`, exists so launchers resolving the plain
 * coordinate on the classpath find a file) and an OS token with optional architecture slot
 * (`windows` / `linux` / `osx` / `windows-arm64` / `linux-arm64` / `osx-arm64`) for a natives jar
 * (`oml-native-<version>-natives-<os>[-arm64].jar`, library at the jar root — the form PCL2 /
 * HMCL / Prism extract into their `${natives_directory}`). The plain spelling carries the x86_64
 * library; `-arm64` is what an arm64 JVM needs. The classifier doubles as the version JSON
 * `natives` map key — Mojang's own 1.19+ spelling for per-architecture natives.
 */
class OmlNativePackage(
    val fileName: String,
    val coordinateVersion: String,
    val classifier: String?,
    val open: () -> InputStream,
) {
    /** `org.ohmyloader:oml-native:<version>` — or the same with `:classifier` appended. */
    val coordinate: String
        get() = "${LayerLayout.GROUP}:oml-native:$coordinateVersion" +
            (classifier?.let { ":natives-$it" } ?: "")
}

interface ArtifactSource : Closeable {
    val version: String

    /** Every jar that makes up the loader layer (OML modules + Kotlin + ASM + annotations). */
    fun layerJars(): List<LayerArtifact>

    /** The `java -jar` shell (`oml-launcher.jar`) — the bootstrap server's entry point. */
    fun shellJar(): LayerArtifact

    /**
     * The OML native library for the platform the **installer runs on**, or `null` when this build
     * does not bundle one (a fat jar built without the build output, or a directory source given no
     * natives). Null is a logged skip at the target, never an error: the library is an enhancement
     * over vanilla zlib behavior, and every consumer of it degrades gracefully.
     */
    fun nativeLibrary(): NativeArtifact?

    /**
     * Every packaged oml-native jar (main + `natives-<classifier>`) this build bundles, for the
     * standard Maven natives mechanism the launchers apply themselves. Empty means this build ships
     * without packaged natives — the same logged-skip semantics as [nativeLibrary].
     */
    fun omlNativePackages(): List<OmlNativePackage> = emptyList()

    override fun close() {}

    companion object {
        /** `oml-core.jar` -> `oml-core`; `asm-9.10.1.jar` -> `asm-9.10.1`. */
        fun coordinateBaseOf(fileName: String): String = fileName.removeSuffix(".jar")

        /**
         * Parses an oml-native package file name into its coordinate version and (nullable)
         * classifier: `oml-native-0.1.0-SNAPSHOT.jar` -> (0.1.0-SNAPSHOT, null);
         * `oml-native-0.1.0-SNAPSHOT-natives-windows.jar` -> (0.1.0-SNAPSHOT, windows);
         * `oml-native-0.1.0-SNAPSHOT-natives-osx-arm64.jar` -> (0.1.0-SNAPSHOT, osx-arm64).
         * Anything else (foreign files dropped into the same directory) yields null rather than a
         * guess.
         */
        fun parseOmlNativePackage(fileName: String): Pair<String, String?>? {
            val natives = Regex("""^oml-native-(.+)-natives-((?:windows|linux|osx)(?:-arm64)?)\.jar$""").find(fileName)
            if (natives != null) return natives.destructured.let { [v, os] -> v to os }
            val main = Regex("""^oml-native-(.+)\.jar$""").find(fileName)
            return main?.destructured?.let { [v] -> v to null }
        }

        /**
         * The `<os>-<arch>` token of the running JVM — the same spelling the runtime's
         * NativeManager uses for jar-embedded natives and the installer uses for resource paths.
         * Unrecognized OS values return null rather than guessing Linux: a wrong guess silently
         * ships a library that cannot load.
         */
        fun currentPlatformToken(): String? {
            val os = when {
                System.getProperty("os.name", "").lowercase().contains("win") -> "windows"
                System.getProperty("os.name", "").lowercase().contains("mac") -> "osx"
                System.getProperty("os.name", "").lowercase().contains("nux") ||
                    System.getProperty("os.name", "").lowercase().contains("nix") -> "linux"

                else -> return null
            }
            val arch = when (System.getProperty("os.arch", "").lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "arm64"
                else -> return null
            }
            return "$os-$arch"
        }
    }
}

/**
 * Reads the installer's own embedded resources out of the running fat jar.
 *
 * Entries are held open for the lifetime of the source: the layer is a few MB, and streaming straight
 * from the jar avoids ever materializing the whole thing on disk twice.
 */
class FatJarArtifactSource(
    override val version: String,
    /** The adapter artifact id for [version] (e.g. `oml-adapter-26_3`), from the version catalog. */
    private val adapterArtifact: String,
    private val installerJar: File = runningJar(),
) : ArtifactSource {

    private val jar: JarFile = JarFile(installerJar, false)

    // The shared layer sits flat under lib/; the only per-version artifact is the adapter jar,
    // recognized by its artifact-id prefix — that prefix is the whole "which version is embedded"
    // discriminator.
    private val sharedEntries: List<String> = jar.entries().asSequence()
        .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".jar") }
        .map { it.name }
        .filter { !it.substringAfterLast('/').startsWith("oml-adapter-") }
        .toList()

    private val adapterEntry: String? = jar.entries().asSequence()
        .filter { !it.isDirectory && it.name.startsWith("lib/$adapterArtifact-") && it.name.endsWith(".jar") }
        .map { it.name }
        .firstOrNull()

    /** Embedded native libraries, laid out by the build as `natives/<os>-<arch>/oml-native.<ext>`. */
    private val nativeEntries: List<String> = jar.entries().asSequence()
        .filter { !it.isDirectory && it.name.startsWith("natives/") }
        .map { it.name }
        .toList()

    /** Packaged natives jars, laid out by the build as `natives-jars/oml-native-<v>[-natives-<os>].jar`. */
    private val nativeJarEntries: List<String> = jar.entries().asSequence()
        .filter { !it.isDirectory && it.name.startsWith("natives-jars/") && it.name.endsWith(".jar") }
        .map { it.name }
        .toList()

    override fun layerJars(): List<LayerArtifact> {
        // The adapter is the version-identifying artifact: without it this installer does not
        // bundle [version] at all, and an install from a version-less layer would produce a
        // launcher version JSON with a runtime that cannot start.
        val adapter = adapterEntry
            ?: throw InstallationException(Messages.t("err.fatjar.noLayer", version, adapterArtifact))
        return (sharedEntries + adapter).map { entry ->
            val name = entry.substringAfterLast('/')
            LayerArtifact(name, ArtifactSource.coordinateBaseOf(name)) {
                jar.getInputStream(jar.getEntry(entry))
            }
        }
    }

    override fun shellJar(): LayerArtifact {
        val entry = sharedEntries.firstOrNull { it.contains("launcher", ignoreCase = true) }
            ?: throw InstallationException(Messages.t("err.fatjar.noShell"))
        val name = entry.substringAfterLast('/')
        return LayerArtifact(name, ArtifactSource.coordinateBaseOf(name)) {
            jar.getInputStream(jar.getEntry(entry))
        }
    }

    override fun nativeLibrary(): NativeArtifact? {
        val token = ArtifactSource.currentPlatformToken() ?: return null
        val entry = nativeEntries.firstOrNull { "/$token/" in it }
            ?: return null
        val name = entry.substringAfterLast('/')
        val relative = entry.removePrefix("natives/").substringBeforeLast('/')
        return NativeArtifact(name, relative) { jar.getInputStream(jar.getEntry(entry)) }
    }

    override fun omlNativePackages(): List<OmlNativePackage> =
        nativeJarEntries.mapNotNull { entry ->
            val name = entry.substringAfterLast('/')
            val [coordinateVersion, classifier] = ArtifactSource.parseOmlNativePackage(name) ?: return@mapNotNull null
            OmlNativePackage(name, coordinateVersion, classifier) {
                jar.getInputStream(jar.getEntry(entry))
            }
        }

    override fun close() {
        runCatching { jar.close() }
    }

    companion object {
        /** The fat jar this process is running from, located through `java.class.path`. */
        fun runningJar(): File =
            System.getProperty("java.class.path")?.split(File.pathSeparator)
                ?.firstOrNull { it.isNotBlank() && it.endsWith(".jar") }
                ?.let(::File)?.takeIf { it.isFile }
                ?: throw InstallationException(Messages.t("err.fatjar.noJar"))
    }
}

/** Explicit files — the CLI and the Gradle `installLauncher` task. */
class DirectoryArtifactSource(
    override val version: String,
    private val layerFiles: List<File>,
    private val shell: File?,
    /** Pre-resolved oml-native library for the running platform, or null when not supplied. */
    private val nativeLib: File? = null,
    /** Packaged oml-native jars (main + natives-<classifier>), when supplied by the caller. */
    private val nativeJarFiles: List<File> = emptyList(),
) : ArtifactSource {

    override fun layerJars(): List<LayerArtifact> {
        if (layerFiles.isEmpty()) throw InstallationException(Messages.t("err.dir.noLayerJars"))
        layerFiles.forEach {
            if (!it.isFile) throw InstallationException(Messages.t("err.dir.layerMissing", it.absolutePath))
        }
        return layerFiles.map { file ->
            LayerArtifact(file.name, ArtifactSource.coordinateBaseOf(file.name)) { file.inputStream() }
        }
    }

    override fun shellJar(): LayerArtifact {
        val file = shell ?: throw InstallationException(Messages.t("err.dir.noShell"))
        if (!file.isFile) throw InstallationException(Messages.t("err.dir.shellMissing", file.absolutePath))
        return LayerArtifact(file.name, "oml-launcher") { file.inputStream() }
    }

    override fun nativeLibrary(): NativeArtifact? {
        val file = nativeLib?.takeIf { it.isFile } ?: return null
        val token = ArtifactSource.currentPlatformToken() ?: file.name.substringBeforeLast('.')
        return NativeArtifact(file.name, token) { file.inputStream() }
    }

    override fun omlNativePackages(): List<OmlNativePackage> =
        nativeJarFiles.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val [coordinateVersion, classifier] = ArtifactSource.parseOmlNativePackage(file.name)
                ?: return@mapNotNull null
            OmlNativePackage(file.name, coordinateVersion, classifier) { file.inputStream() }
        }
}

// ---------------------------------------------------------------------------------------------------
// Version metadata — read from oml-versions.json, which the build writes from the version list it also
// uses to decide which adapters to embed. No layer keeps a private copy of "which versions exist".
// ---------------------------------------------------------------------------------------------------
data class SupportedVersion(
    val version: String,
    val javaMajor: Int,
    /** The adapter artifact id for this version (e.g. `oml-adapter-26_3`) — the only per-version artifact. */
    val adapterArtifact: String,
)

object VersionCatalog {

    private const val RESOURCE = "/oml-versions.json"

    @Serializable
    private class FileModel(
        val format: Int = 0,
        val requiredJavaMajor: Int = 0,
        val versions: List<Entry> = emptyList(),
    )

    @Serializable
    private class Entry(
        val version: String = "",
        val javaMajor: Int = 0,
        val adapter: String = "",
    )

    // The catalogue is our own artifact, but ignoreUnknownKeys keeps a future format bump from
    // breaking installs made with an older launcher build.
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loaded: FileModel? = null

    private fun model(): FileModel {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val stream = VersionCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: throw InstallationException(Messages.t("err.catalog.missing", RESOURCE))
            val parsed = stream.use {
                json.decodeFromString<FileModel>(it.reader(Charsets.UTF_8).readText())
            }
            if (parsed.versions.isEmpty()) throw InstallationException(Messages.t("err.catalog.empty", RESOURCE))
            loaded = parsed
            return parsed
        }
    }

    fun versions(): List<SupportedVersion> = model().versions.map {
        SupportedVersion(it.version, it.javaMajor, it.adapter)
    }

    fun find(version: String): SupportedVersion? = versions().firstOrNull { it.version == version }

    /** Highest javaMajor across the bundled versions: what the installer must tell the user about. */
    fun requiredJavaMajor(): Int = model().requiredJavaMajor
}

// ---------------------------------------------------------------------------------------------------
// Install context — everything a target needs, resolved once by the front end (GUI or CLI)
// ---------------------------------------------------------------------------------------------------
class InstallContext(
    val target: SupportedVersion,
    /** Game directory (standard), Prism instance directory, or server directory, depending on the target. */
    val targetDir: File,
    /** Install id: the launcher version name, the isolation key, and the library coordinate version. */
    val installId: String,
    /** true = mods live inside the version directory; false = the game root's shared `mods/`. */
    val isolation: Boolean,
    /** Server only: the user explicitly accepted the Minecraft EULA. Never assumed on their behalf. */
    val acceptEula: Boolean,
    /** The user explicitly confirmed that a shared `mods/` directory is what they want. */
    val allowSharedMods: Boolean,
    val artifacts: ArtifactSource,
    val side: String,
    val modsDirName: String = "mods",
    /** Prism target: also register the component in mmc-pack.json (opt-in — it edits the user's instance). */
    val addPrismComponent: Boolean = false,
    /**
     * Every write the install makes is recorded here, so [Installer.performInstall] can undo the
     * whole install when any step throws (T-1.6). Created by each front end with the context.
     */
    val journal: InstallJournal = InstallJournal(),
    val log: (String) -> Unit = { println(it) },
)
