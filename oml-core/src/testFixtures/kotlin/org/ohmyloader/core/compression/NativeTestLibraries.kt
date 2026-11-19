package org.ohmyloader.core.compression

import org.ohmyloader.api.core.natives.NativeManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared test wiring between [NativeManager] and the published `org.ohmyloader:oml-native`
 * artifact, published as oml-core's **test fixtures** so the adapter's codec tests exercise the
 * same library instead of a drifting copy.
 *
 * The host `natives-<classifier>` jar is on the test runtime classpath (see oml-core's build
 * script), and its library sits at the jar root. This extracts that one file into a per-run temp
 * directory and points `oml.natives.dir` at it, so every consumer dlopens the exact bytes the
 * publication ships — no second build, no staging step.
 *
 * If the artifact is absent (a platform the publication does not cover), [ensureOmlNative] returns
 * false and the calling tests assume-skip: a missing optional artifact must read as "skipped", not
 * as a red codec test.
 */
object NativeTestLibraries {

    private val attempted = AtomicBoolean(false)
    private var available = false

    fun ensureOmlNative(): Boolean {
        if (attempted.compareAndSet(false, true)) {
            val fileName = NativeManager.withPlatformExtension("oml-native")
            val resource = NativeTestLibraries::class.java.classLoader?.getResource(fileName)
            if (resource == null) {
                println("[OMLTest] $fileName is not on the test runtime classpath — native tests will skip")
            } else {
                val dir = Files.createTempDirectory("oml-native-test").toFile()
                val target = File(dir, fileName)
                resource.openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.deleteOnExit()
                dir.deleteOnExit()
                System.setProperty("oml.natives.dir", dir.absolutePath)
                available = OmlNativeZstd.available()
            }
        }
        return available
    }
}
