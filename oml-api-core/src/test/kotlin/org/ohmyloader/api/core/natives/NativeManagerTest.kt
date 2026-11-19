package org.ohmyloader.api.core.natives

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.writeBytes
import kotlin.test.*

/**
 * The natives residency contract: the shared `natives/` root is first-party property, every other
 * caller (a mod) resolves into its own `natives/mod/<package path>/` scope, and within a scope the
 * extraction is SHA-256-gated (identical = untouched, differing = atomic replace).
 *
 * Callers are fabricated as dynamic proxies: a proxy class is DEFINED by the loader it is created
 * with, so `callerClass.classLoader` (where the resource lookup goes through) and the caller's
 * package (which decides the scope — JDK 27 names proxy packages per defining loader, `jdk.proxyN`)
 * both come from the same fabricated loader. Two proxies from one loader therefore share a scope;
 * two proxies from two loaders never do.
 */
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
    // Residency: first-party root vs mod-scoped directories
    //
    // The lookup always uses the *running* platform's token (that is the contract), so the tests
    // serve resources under NativeManager.bundledPath(...) with default parameters instead of a
    // hardcoded os-arch — the file name carries its extension, making the platform irrelevant to
    // everything except the directory token.
    // -----------------------------------------------------------------------------------------

    private val fakeBundlePath = NativeManager.bundledPath("libfake.so")

    @Test
    fun aModCallerExtractsIntoItsOwnScopeNeverTheFirstPartyRoot(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        val loader = loaderServing("oml-test", fakeBundlePath, "fake-library-v1".toByteArray())
        val caller = classWith(loader)

        runWithNativesDir(natives) {
            val resolved = NativeManager.resolveLibrary(caller, "libfake.so")
            val scoped = scopeDir(natives, caller).resolve("libfake.so").absoluteFile
            assertEquals(scoped, resolved)
            assertTrue(resolved.isFile)
            assertContains(resolved.readText(), "fake-library-v1")
            // The first-party root is untouched by a third-party caller
            assertFalse(File(natives, "libfake.so").isFile)
        }
    }

    // The first-party branch of the residency decision is covered by name in
    // [theResidencyDecisionSplitsFirstPartyFromModScoped] — fabricating a class genuinely named
    // org.ohmyloader.* would mean shipping bytecode in the test resources.

    @Test
    fun theResidencyDecisionSplitsFirstPartyFromModScoped(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        assertEquals(
            natives,
            NativeManager.directoryFor("org.ohmyloader.core.compression.OmlNativeZstd", natives),
            "the loader's own namespace shares the first-party root",
        )
        // A look-alike one character past the namespace is NOT first-party
        val scoped = NativeManager.directoryFor("org.ohmyloaderish.Evil", natives)
        assertEquals(natives.resolve("mod/org/ohmyloaderish"), scoped)
        assertTrue(scoped.isDirectory)
    }

    @Test
    fun twoModsShippingTheSameLibraryExtractIntoTheirOwnScopes(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        val modA = classWith(loaderServing("oml-test-a", fakeBundlePath, "mod-a-v1".toByteArray()))
        val modB = classWith(loaderServing("oml-test-b", fakeBundlePath, "mod-b-v1".toByteArray()))

        runWithNativesDir(natives) {
            val a = NativeManager.resolveLibrary(modA, "libfake.so")
            val b = NativeManager.resolveLibrary(modB, "libfake.so")
            assertTrue(
                a.parentFile != b.parentFile,
                "two mods shipping one library extract into their own scopes (each defining loader names its own proxy package)",
            )
            assertContains(a.readText(), "mod-a-v1")
            assertContains(b.readText(), "mod-b-v1")
            // and neither touched the first-party root
            assertFalse(File(natives, "libfake.so").isFile)
        }
    }

    @Test
    fun anIdenticalCopyIsNotTouchedButADifferentOneIsReplaced(@TempDir tempDir: File) {
        val natives = newDir(tempDir, "natives")
        // One backing file, rewritten in place: the same caller jar "updating" its library. The
        // loader and its proxy class stay identical, so the scope is identical too.
        val backing = Files.createTempFile("oml-nm-test", ".bin")
        backing.writeBytes("fake-library-v2".toByteArray())
        val loader = object : ClassLoader() {
            override fun getResource(name: String): URL? =
                if (name == fakeBundlePath) backing.toUri().toURL() else null
        }
        val caller = classWith(loader)

        runWithNativesDir(natives) {
            val first = NativeManager.resolveLibrary(caller, "libfake.so")
            val firstStamp = first.lastModified()
            assertEquals("fake-library-v2", first.readText())

            // Same bytes served again: the extraction is skipped — an identical copy must be left
            // alone (the path, the bytes and the mtime must not change)
            val untouched = NativeManager.resolveLibrary(caller, "libfake.so")
            assertEquals(first.absolutePath, untouched.absolutePath)
            assertEquals(firstStamp, first.lastModified())

            // Different bytes under the same name: the scope converges to the new build
            backing.writeBytes("fake-library-v3".toByteArray(), StandardOpenOption.WRITE)
            NativeManager.resolveLibrary(caller, "libfake.so")
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

    @Test
    fun theScopePathIsSanitizedAndDeterministic() {
        // The simple name after the last dot is dropped; only the package path scopes
        assertEquals(
            listOf("com", "sun", "proxy"),
            NativeManager.modScopeSegments("com.sun.proxy.\$Proxy12"),
        )
        assertEquals(listOf("unnamed"), NativeManager.modScopeSegments("NakedClass"))
        // "M-D Mod!\Weird" is the simple name (no dot before it), so it never reaches the scope
        assertEquals(listOf("org", "example"), NativeManager.modScopeSegments("Org.Example.M-D Mod!\\Weird"))
        assertTrue(NativeManager.isFirstPartyCaller("org.ohmyloader.core.compression.OmlNativeZstd"))
        assertTrue(NativeManager.isFirstPartyCaller("org.ohmyloader.api.core.natives.NativeManager"))
        // A look-alike one character past the namespace is NOT first-party
        assertFalse(NativeManager.isFirstPartyCaller("org.ohmyloaderish.Evil"))
        assertFalse(NativeManager.isFirstPartyCaller("com.example.mod.Foo"))
    }

    // -----------------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------------

    private fun newDir(parent: File, name: String): File =
        Files.createDirectories(parent.toPath().resolve(name)).toFile()

    /** A classloader that serves exactly one fabricated resource from a temp file. */
    private fun loaderServing(id: String, path: String, bytes: ByteArray): ClassLoader {
        val backing = Files.createTempFile("oml-nm-test-$id", ".bin")
        backing.writeBytes(bytes)
        val url: URL = backing.toUri().toURL()
        return object : ClassLoader() {
            override fun getResource(name: String): URL? = if (name == path) url else null
        }
    }

    /** A caller class pinned to [loader] — NativeManager resolves bundles through its loader. */
    private fun classWith(loader: ClassLoader): Class<*> =
        Proxy.newProxyInstance(loader, arrayOf(Runnable::class.java)) { _, _, _ -> }
            .javaClass

    /** The mod-scoped directory a caller's class resolves into. */
    private fun scopeDir(natives: File, caller: Class<*>): File =
        NativeManager.directoryFor(caller.name, natives)

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
