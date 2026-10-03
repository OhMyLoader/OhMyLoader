package org.ohmyloader.api.natives

import org.ohmyloader.api.OmlLog
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
 * One-physical-place rule: every OML-managed dynamic library lives in exactly one directory, `<gameDir>/natives/` — where `-Djava.library.path` points and where the vanilla (LWJGL) natives were extracted. Nothing is released into `%TEMP%` or a private subdirectory: temp extraction defeats unified cleanup, and a private path is a second location every crash report must explain.
 * [loadLibrary] resolves the library from the *caller's* jar under `/natives/<os>-<arch>/<libname>.<ext>`, verifies the `natives/` copy against the resource's SHA-256 (identical = untouched, so N mods shipping one library extract once; differing = same-directory temp file + atomic move, so a crash mid-extract never leaves a half library), then opens it with `SymbolLookup.libraryLookup` — never `System.loadLibrary`, so the result does not depend on how the JVM captured its search path at startup.
 * The `<os>-<arch>` token (`windows-x86_64`, `osx-arm64`) is the repository-wide spelling used by the installer and the Gradle deploy.
 */
object NativeManager {

    /** Optional explicit override of the natives directory (`-Doml.natives.dir=...`). */
    private const val DIR_PROPERTY = "oml.natives.dir"

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
     * Resolves [libName] to a physical file in the `natives/` directory, extracting the caller's
     * bundled copy when needed. Split from [loadLibrary] so the path/extraction logic is testable
     * without actually dlopen-ing anything.
     */
    fun resolveLibrary(callerClass: Class<*>, libName: String): File {
        val dir = nativesDir()
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
                    StandardCopyOption.ATOMIC_MOVE
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
