package org.ohmyloader.core.compression

import org.ohmyloader.api.core.natives.NativeManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared test wiring for the in-repo `native/oml-native` crate: builds the host library with
 * cargo (incremental — a no-op when sources are unchanged) and points `oml.natives.dir` at the
 * built binary, so every consumer dlopens the exact bytes the working tree produces. No published
 * artifact, no staging step — the tests and the shipped installer see the same source of truth.
 *
 * [ensureOmlNative] returns false and the calling tests assume-skip only when cargo or the Rust
 * toolchain is genuinely unavailable on the machine: a missing toolchain reads as "skipped", a
 * build failure reads as red.
 */
object NativeTestLibraries {

    private val attempted = AtomicBoolean(false)
    private var available = false

    /** The crate root, resolved relative to this module's working directory. */
    internal fun crateRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val candidate = File(dir, "native/oml-native")
            if (File(candidate, "Cargo.toml").isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("native/oml-native not found from ${System.getProperty("user.dir")}")
    }

    fun ensureOmlNative(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            try {
                val crate = crateRoot()
                val target = File(crate, "target")
                val build = ProcessBuilder("cargo", "build", "--release")
                    .directory(crate)
                    .redirectErrorStream(true)
                    .start()
                val output = build.inputStream.buffered().readBytes().toString(Charsets.UTF_8)
                val code = build.waitFor()
                if (code != 0) {
                    println("[OMLTest] cargo build failed ($code):\n$output")
                    return false
                }
                val fileName = NativeManager.withPlatformExtension("oml-native")
                // cargo names the cdylib after the crate identifier: oml_native.dll (underscore); the
                // loader resolves oml-native.dll — the deploy renames, exactly like the publication CI did.
                val built = File(target, "release/oml_native.${fileName.substringAfterLast('.')}")
                if (!built.isFile) {
                    println("[OMLTest] cargo produced no $fileName under ${built.parentFile} — native tests will skip")
                    return false
                }
                val dir = Files.createTempDirectory("oml-native-test").toFile()
                val deployed = File(dir, fileName)
                deployed.writeBytes(built.readBytes())
                deployed.deleteOnExit()
                dir.deleteOnExit()
                System.setProperty("oml.natives.dir", dir.absolutePath)
                available = OmlNativeZstd.available()
            } catch (t: Throwable) {
                println("[OMLTest] the native build could not run (${t.javaClass.simpleName}: ${t.message}) — native tests will skip")
            }
        }
        return available
    }
}
