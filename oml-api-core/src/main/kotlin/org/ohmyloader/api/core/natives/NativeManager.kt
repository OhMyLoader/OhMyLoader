package org.ohmyloader.api.core.natives

import org.ohmyloader.api.core.OmlLog
import java.io.File
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The single entry point a mod uses to load a native library.
 *
 * Residency is two-tier, by structural rule rather than by validation: the shared `natives/` root
 * is **first-party property** — only the loader's own deployment (installer / Gradle deploy) writes
 * it, and only callers from the `org.ohmyloader.*` namespace read-and-resolve there. Every other
 * caller is a mod, and a mod's bundled libraries extract into a **mod-scoped directory**
 * (`natives/mod/<caller package path>/`), never the shared root — so a third-party jar physically
 * cannot replace a first-party binary, and two mods shipping the same library cannot overwrite
 * each other. Within its own directory the old rules hold: SHA-256-gated extraction (identical =
 * untouched), same-directory temp file + atomic move so a crash mid-extract never leaves a half
 * library. The loader's own namespace is additionally protected upstream: the mod scanner refuses
 * any jar that packs classes under `org/ohmyloader/`, so a third-party jar cannot even claim the
 * first-party caller prefix.
 *
 * [loadLibrary] resolves the library from the *caller's* jar under `/natives/<os>-<arch>/<libname>.<ext>`, then opens it with `SymbolLookup.libraryLookup` — never `System.loadLibrary`, so the result does not depend on how the JVM captured its search path at startup.
 * The `<os>-<arch>` token (`windows-x86_64`, `osx-arm64`) is the repository-wide spelling used by the installer and the Gradle deploy.
 */
object NativeManager {

    /** Optional explicit override of the natives directory (`-Doml.natives.dir=...`). */
    private const val DIR_PROPERTY = "oml.natives.dir"

    /** The caller-package prefix that counts as first-party (the loader's own code). */
    private const val FIRST_PARTY_PACKAGE = "org.ohmyloader."

    /** The subdirectory of [nativesDir] where mod-scoped libraries extract. */
    private const val MOD_DIR = "mod"

    private val lookups = ConcurrentHashMap<String, SymbolLookup>()

    /**
     * The directory every native library physically lives in. See [nativesDirFrom] for the
     * resolution order; the result is created if it does not exist yet, because the first mod to
     * ship a native must not crash on a fresh install.
     */
    fun nativesDir(): File =
        nativesDirFrom(System.getProperty(DIR_PROPERTY), System.getProperty("java.library.path", ""))
            .also { it.mkdirs() }

    /**
     * Pure form of [nativesDir] for tests: an explicit override wins; otherwise the
     * `java.library.path` entries are searched for one **named** `natives` (dev launches and both
     * launcher forms point it there), then the first existing directory; the first entry is the
     * last resort even when it does not exist yet.
     */
    internal fun nativesDirFrom(dirOverride: String?, libraryPath: String?): File {
        dirOverride?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
        val entries = libraryPath?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.map(::File)
            .orEmpty()
        entries.firstOrNull { it.isDirectory && it.name == "natives" }?.let { return it.absoluteFile }
        entries.firstOrNull { it.isDirectory }?.let { return it.absoluteFile }
        return (entries.firstOrNull() ?: File("natives")).absoluteFile
    }

    /**
     * Loads [libName] for the caller and returns a [SymbolLookup] over its exported symbols.
     *
     * [callerClass] is the mod class making the call: its classloader is where the bundled
     * `/natives/<os>-<arch>/…` resource is looked up. [libName] may carry its platform extension
     * (`libfoo.so`) or not (`libfoo`); without one, the running platform's extension is appended.
     *
     * @throws java.lang.UnsatisfiedLinkError when neither the caller's jar nor the `natives/`
     *   directory provides the library.
     */
    fun loadLibrary(callerClass: Class<*>, libName: String): SymbolLookup {
        val file = resolveLibrary(callerClass, libName)
        // Keyed by path *and content*, not path+length: two different builds of the same library
        // that happen to be byte-length-equal must not share one cached lookup, while a re-deploy
        // that is byte-identical legitimately reuses it. Hashing a library once per load is cheap.
        val key = "${file.canonicalFile.absolutePath}|${sha256(file.inputStream())}"
        return lookups.computeIfAbsent(key) {
            val lookup = SymbolLookup.libraryLookup(file.toPath(), Arena.global())
            // Once per file per JVM: the evidence line the run-verification looks for — WHERE the
            // library physically came from (the unified natives/ directory, never a temp path).
            OmlLog.info("OMLNative", "loaded ${file.name} from ${file.parentFile.absolutePath}")
            lookup
        }
    }

    /**
     * Resolves [libName] to a physical file, first-party root or mod-scoped directory depending on
     * the caller's namespace, extracting the caller's bundled copy when needed. Split from
     * [loadLibrary] so the path/extraction logic is testable without actually dlopen-ing anything.
     */
    fun resolveLibrary(callerClass: Class<*>, libName: String): File {
        val dir = directoryFor(callerClass.name, nativesDir())
        val fileName = withPlatformExtension(libName)
        val target = File(dir, fileName)
        when (val resource = callerClass.classLoader?.getResource(bundledPath(fileName))) {
            null if target.isFile -> {
                // Not bundled by this caller, but already deployed (installer / Gradle / another
                // mod that ships the same library): use what is there.
            }

            null -> throw UnsatisfiedLinkError(
                "native library '$fileName' not found: no bundled resource '/${bundledPath(fileName)}' "
                    + "in ${callerClass.classLoader} and no copy in ${dir.absolutePath}",
            )

            else -> {
                val digest = sha256(resource.openStream())
                if (target.isFile && sha256(target.inputStream()) == digest) {
                    // Already extracted by an earlier launch (or another mod shipping the same
                    // build): byte-identical, touching it again would only churn the directory.
                } else {
                    extractAtomically(resource.openStream(), target)
                    OmlLog.info("OMLNative", "deployed $fileName -> ${target.absolutePath}")
                }
            }
        }
        return target
    }

    /**
     * The directory a caller's libraries resolve in: the first-party root for the loader's own
     * namespace (what the installer deployed), a mod-scoped `natives/mod/<package path>/`
     * directory for everyone else. Identity by **namespace**, not by classloader: the mod scanner
     * refuses any jar packing classes under `org/ohmyloader/`, so the prefix cannot be claimed by
     * a third-party jar. Takes the class name so the decision is testable without fabricating
     * classes.
     */
    internal fun directoryFor(className: String, natives: File): File =
        if (isFirstPartyCaller(className)) {
            natives
        } else {
            File(natives, "$MOD_DIR/${modScopeSegments(className).joinToString("/")}").apply { mkdirs() }
        }

    /** True when [className] names a first-party caller (`org.ohmyloader.*`). Visible for tests. */
    internal fun isFirstPartyCaller(className: String): Boolean =
        className == FIRST_PARTY_PACKAGE.trimEnd('.') || className.startsWith(FIRST_PARTY_PACKAGE)

    /**
     * The mod-scoped directory segments for [className]: the class's package path, each segment
     * sanitized to filesystem-safe characters and lowercased. A class in the default package (no
     * package) scopes to `unnamed` — a mod shipping natives from the default package lands in one
     * shared bucket, which is still its own, never the first-party root.
     */
    internal fun modScopeSegments(className: String): List<String> {
        val packagePath = className.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } ?: return listOf("unnamed")
        return packagePath.split('.').map { segment ->
            val safe = segment.filter { it.isLetterOrDigit() || it == '_' }.lowercase()
            safe.ifEmpty { "_" }
        }
    }

    /** `libfoo` on windows becomes `libfoo.dll`; names that already carry an extension pass through. */
    fun withPlatformExtension(libName: String, osName: String = System.getProperty("os.name", "")): String {
        if (libName.substringAfterLast('.', "").isNotEmpty()) return libName
        return "$libName${extensionFor(osName)}"
    }

    /** The convention path a mod bundles its native under: `natives/<os>-<arch>/<file>`. */
    fun bundledPath(
        fileName: String,
        osName: String = System.getProperty("os.name", ""),
        osArch: String = System.getProperty("os.arch", ""),
    ): String = "natives/${platformToken(osName, osArch)}/$fileName"

    /** The repository-wide `<os>-<arch>` token, e.g. `windows-x86_64`, `osx-arm64`. */
    fun platformToken(
        osName: String = System.getProperty("os.name", ""),
        osArch: String = System.getProperty("os.arch", ""),
    ): String {
        val os = osName.lowercase()
        val osToken = when {
            os.contains("mac") || os.contains("darwin") -> "osx"
            os.contains("win") -> "windows"
            os.contains("nux") || os.contains("nix") -> "linux"
            else -> "unknown"
        }
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            else -> osArch.lowercase().ifEmpty { "unknown" }
        }
        return "$osToken-$arch"
    }

    private fun extensionFor(osName: String): String {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> ".dylib"
            os.contains("win") -> ".dll"
            else -> ".so"
        }
    }

    private fun sha256(input: InputStream): String =
        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    /**
     * Streams [data] to a uniquely named temp file next to the destination and atomically moves it
     * into place. The temp file lives in the SAME directory — a cross-directory move is not atomic,
     * and the whole point is that `natives/` never holds a half library. The name is unique per
     * call on purpose: a fixed `<target>.oml-tmp` would let two threads (or two mods racing on the
     * same first launch) corrupt each other's stream through one shared temp path.
     */
    private fun extractAtomically(data: InputStream, target: File) {
        val dir = (target.parentFile ?: File(".")).apply { mkdirs() }
        val tmp = Files.createTempFile(dir.toPath(), "${target.name}.", ".oml-tmp").toFile()
        try {
            data.use { input -> tmp.outputStream().buffered().use { input.copyTo(it) } }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** Test hook: forget cached lookups. */
    internal fun clearCache() = lookups.clear()
}
