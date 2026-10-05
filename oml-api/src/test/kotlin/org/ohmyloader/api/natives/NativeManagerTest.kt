package org.ohmyloader.api.natives

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.net.URL
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.*

class NativeManagerTest {

    // -----------------------------------------------------------------------------------------
    // Platform token / naming
    // -----------------------------------------------------------------------------------------

    @Test
    fun platformTokensFollowTheRepositoryWideOsArchSpelling() {
        assertEquals("windows-x86_64", NativeManager.platformToken("Windows 11", "amd64"))
        assertEquals("windows-arm64", NativeManager.platformToken("Windows 11", "aarch64"))
        assertEquals("linux-x86_64", NativeManager.platformToken("Linux", "x86_64"))
        // macOS is `osx` — and the darwin name contains neither "win" nor trips the windows branch
        assertEquals("osx-arm64", NativeManager.platformToken("Mac OS X", "arm64"))
        assertEquals("osx-x86_64", NativeManager.platformToken("Mac OS X", "amd64"))
        assertEquals("linux-arm64", NativeManager.platformToken("Linux", "aarch64"))
    }

    @Test
    fun bundledPathUsesTheConventionLayout() {
        assertEquals(
            "natives/windows-x86_64/oml-native.dll",
            NativeManager.bundledPath("oml-native.dll", "Windows 10", "amd64"),
        )
    }

    @Test
    fun extensionIsAppendedOnlyWhenMissing() {
        assertEquals("libfoo.dll", NativeManager.withPlatformExtension("libfoo", "Windows 10"))
        assertEquals("libfoo.dylib", NativeManager.withPlatformExtension("libfoo", "Mac OS X"))
        assertEquals("libfoo.so", NativeManager.withPlatformExtension("libfoo", "Linux"))
        // an explicit extension is the caller's contract — never second-guessed
        assertEquals("libfoo.so", NativeManager.withPlatformExtension("libfoo.so", "Windows 10"))
    }

    // -----------------------------------------------------------------------------------------
    // natives/ directory resolution
    // -----------------------------------------------------------------------------------------

    @Test
    fun explicitPropertyWinsOverEveryLibraryPathEntry(@TempDir tempDir: File) {
        val named = newDir(tempDir, "natives")
        val other = newDir(tempDir, "somewhere")
        val resolved = NativeManager.nativesDirFrom(
            dirOverride = tempDir.resolve("explicit").absolutePath,
            libraryPath = "$other${File.pathSeparator}$named",
        )
        assertEquals(tempDir.resolve("explicit").absoluteFile, resolved)
    }

    @Test
    fun anEntryNamedNativesBeatsEarlierExistingEntries(@TempDir tempDir: File) {
        val other = newDir(tempDir, "somewhere")
        val named = newDir(tempDir, "natives")
        val resolved = NativeManager.nativesDirFrom(
            dirOverride = null,
            libraryPath = "$other${File.pathSeparator}$named",
        )
        assertEquals(named.absoluteFile, resolved)
    }

    @Test
    fun lastResortIsTheFirstEntryEvenWhenItDoesNotExistYet(@TempDir tempDir: File) {
        val missing = File(tempDir, "created-later/natives")
        assertFalse(missing.exists())
        val resolved = NativeManager.nativesDirFrom(dirOverride = null, libraryPath = missing.absolutePath)
        assertEquals(missing.absoluteFile, resolved)
    }

    // -----------------------------------------------------------------------------------------
    // Resolution + SHA-256-gated extraction
    //
    // The lookup always uses the *running* platform's token (that is the contract), so the tests
    // serve resources under NativeManager.bundledPath(...) with default parameters instead of a
    // hardcoded os-arch — the file name carries its extension, making the platform irrelevant to
    // everything except the directory token.
    // -----------------------------------------------------------------------------------------

    private val fakeBundlePath = NativeManager.bundledPath("libfake.so")

    @Test
    fun aBundledLibraryIsExtractedIntoTheNativesDirectory(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        val payload = "fake-library-v1".toByteArray()
        val loader = loaderServing("oml-test", fakeBundlePath, payload)
        val caller = classWith(loader)

        runWithNativesDir(natives) {
            val resolved = NativeManager.resolveLibrary(caller, "libfake.so")
            assertEquals(natives.resolve("libfake.so").absoluteFile, resolved)
            assertTrue(resolved.isFile)
            assertContains(resolved.readText(), "fake-library-v1")
        }
    }

    @Test
    fun anIdenticalCopyIsNotTouchedButADifferentOneIsReplaced(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        val caller = classWith(
            loaderServing("oml-test", fakeBundlePath, "fake-library-v2".toByteArray()),
        )

        runWithNativesDir(natives) {
            val first = NativeManager.resolveLibrary(caller, "libfake.so")
            val firstStamp = first.lastModified()

            // Same bytes: the extraction is skipped — an identical copy must be left alone
            // (File identity differs per call; the path, the bytes and the mtime must not change)
            val untouched = NativeManager.resolveLibrary(caller, "libfake.so")
            assertEquals(first.absolutePath, untouched.absolutePath)
            assertEquals("fake-library-v2", first.readText())
            assertEquals(firstStamp, first.lastModified())

            // Different bytes under the same name: the directory converges to the new build
            val changed = classWith(
                loaderServing("oml-test", fakeBundlePath, "fake-library-v3".toByteArray()),
            )
            NativeManager.resolveLibrary(changed, "libfake.so")
            assertEquals("fake-library-v3", first.readText())
        }
    }

    @Test
    fun aMissingBundleAndMissingCopyIsAnUnsatisfiedLinkError(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        val caller = classWith(loaderServing("oml-test", "nothing", ByteArray(0)))

        runWithNativesDir(natives) {
            val error = assertFailsWith<UnsatisfiedLinkError> {
                NativeManager.resolveLibrary(caller, "libabsent.so")
            }
            assertContains(error.message!!, "libabsent.so")
            assertContains(error.message!!, "natives/${NativeManager.platformToken()}")
        }
    }

    // -----------------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------------

    private fun newDir(parent: File, name: String): File =
        Files.createDirectories(parent.toPath().resolve(name)).toFile()

    /** A classloader that serves exactly one fabricated resource, backed by a temp file URL. */
    private fun loaderServing(id: String, path: String, bytes: ByteArray): ClassLoader {
        val backing = Files.createTempFile("oml-nm-test-$id", ".bin")
        backing.writeBytes(bytes)
        val url: URL = backing.toUri().toURL()
        return object : ClassLoader() {
            override fun getResource(name: String): URL? = if (name == path) url else null
        }
    }

    /** A caller class pinned to [loader] — NativeManager resolves bundles through its loader. */
    private fun classWith(loader: ClassLoader): Class<*> {
        // A dynamic proxy is DEFINED by the supplied loader (unlike, say, java.lang.Object, whose
        // defining loader is the bootstrap) — so callerClass.classLoader is exactly the loader the
        // resource lookup goes through.
        return Proxy.newProxyInstance(loader, arrayOf(Runnable::class.java)) { _, _, _ -> }
            .javaClass
    }

    /** Pins `oml.natives.dir` for the duration of [block] — the documented explicit override. */
    private fun runWithNativesDir(dir: File, block: () -> Unit) {
        val previous = System.getProperty("oml.natives.dir")
        System.setProperty("oml.natives.dir", dir.absolutePath)
        NativeManager.clearCache()
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty("oml.natives.dir") else System.setProperty(
                "oml.natives.dir",
                previous,
            )
            NativeManager.clearCache()
        }
    }
}
