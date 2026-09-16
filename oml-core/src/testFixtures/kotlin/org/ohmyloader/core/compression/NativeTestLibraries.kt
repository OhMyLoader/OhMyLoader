package org.ohmyloader.core.compression

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared test wiring between [org.ohmyloader.api.natives.NativeManager] and the zig build of
 * `oml-native` that lives next to this repository, published as oml-core's **test fixtures** so
 * the adapter's codec tests exercise the same instance instead of a drifting copy. Pointing
 * `oml.natives.dir` at the build output means every consumer tests the REAL library — same file
 * the runtime deploys — with no copy, no staging, no temp directory.
 *
 * If the C library has not been built (fresh clone, build output not present), [ensureOmlNative] returns
 * false and the calling tests assume-skip: a missing optional build must read as "skipped", not as
 * a red codec test.
 */
object NativeTestLibraries {

    private val attempted = AtomicBoolean(false)
    private var available = false

    fun ensureOmlNative(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            val buildDir = File("..", "oml-native/build")
            val lib = candidates(buildDir).firstOrNull { it.isFile }
            if (lib == null) {
                println("[OMLTest] oml-native build output not found under ${buildDir.absolutePath} — native codec tests will skip")
            } else {
                System.setProperty("oml.natives.dir", lib.parentFile.absolutePath)
                available = OmlNativeZstd.available()
            }
        }
        return available
    }

    /** build output candidates for the running platform (plat/arch spellings vary by OS). */
    private fun candidates(buildDir: File): List<File> {
        val os = System.getProperty("os.name", "").lowercase()
        val arch = System.getProperty("os.arch", "").lowercase()
        val plat = when {
            os.contains("mac") || os.contains("darwin") -> "macosx"
            os.contains("win") -> "windows"
            else -> "linux"
        }
        val archs = when {
            arch.contains("aarch64") || arch.contains("arm64") -> listOf("arm64", "aarch64")
            else -> listOf("x64", "x86_64", "amd64")
        }
        val ext = when (plat) {
            "windows" -> ".dll"
            "macosx" -> ".dylib"
            else -> ".so"
        }
        return archs.map { File(buildDir, "$plat/$it/release/oml-native$ext") }
    }
}
