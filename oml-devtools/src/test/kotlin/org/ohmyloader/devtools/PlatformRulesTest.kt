package org.ohmyloader.devtools

import kotlin.test.*

/**
 * Pins the two pieces of version-JSON interpretation that produced silent defects.
 *
 * Neither of the bugs these tests cover raised an error. One downloaded every platform's libraries and
 * the other selected natives for the wrong CPU architecture, and both looked exactly like success in the
 * log. They are the reason the platform is a parameter here instead of a system property read.
 */
class PlatformRulesTest {

    private val x64 = "x86_64"

    // -------------------------------------------------------------------------------------------
    // os.arch
    // -------------------------------------------------------------------------------------------

    @Test
    fun `jvm arch spellings map to the launcher's tokens`() {
        assertEquals("x86_64", PlatformRules.archToken("amd64"))
        assertEquals("x86_64", PlatformRules.archToken("AMD64"))
        assertEquals("x86_64", PlatformRules.archToken("x86_64"))
        assertEquals("x86", PlatformRules.archToken("x86"))
        assertEquals("x86", PlatformRules.archToken("i386"))
        assertEquals("arm64", PlatformRules.archToken("aarch64"))
        assertEquals("arm64", PlatformRules.archToken("arm64"))
        // An unknown spelling is passed through rather than mapped to something plausible: a wrong answer
        // would silently select the wrong natives, whereas an unmapped token simply matches nothing.
        assertEquals("riscv64", PlatformRules.archToken("riscv64"))
    }

    // -------------------------------------------------------------------------------------------
    // rules
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a library with no rules is allowed everywhere`() {
        assertTrue(PlatformRules.allows(null, PlatformRules.WINDOWS, x64))
        assertTrue(PlatformRules.allows(emptyList<Any>(), PlatformRules.WINDOWS, x64))
    }

    @Test
    fun `rules are present - the default flips to not allowed`() {
        // The predecessor started from `allow` here, which is how every platform's library got accepted
        // and downloaded.
        val osxOnly = listOf(mapOf("action" to "allow", "os" to mapOf("name" to "osx")))
        assertFalse(PlatformRules.allows(osxOnly, PlatformRules.WINDOWS, x64))
        assertTrue(PlatformRules.allows(osxOnly, "osx", "arm64"))
    }

    @Test
    fun `a bare allow rule matches everywhere, which is how 1_7_10 writes them`() {
        val shape = listOf(
            mapOf("action" to "allow"),
            mapOf("action" to "disallow", "os" to mapOf("name" to "osx")),
        )
        assertTrue(PlatformRules.allows(shape, PlatformRules.WINDOWS, x64))
        assertFalse(PlatformRules.allows(shape, "osx", "arm64"))
    }

    @Test
    fun `arch is part of the match, so the arm64 and x86 natives are excluded`() {
        val windowsAny = listOf(mapOf("action" to "allow", "os" to mapOf("name" to "windows")))
        val windowsArm =
            listOf(mapOf("action" to "allow", "os" to mapOf("name" to "windows", "arch" to "arm64")))
        val windowsX86 =
            listOf(mapOf("action" to "allow", "os" to mapOf("name" to "windows", "arch" to "x86")))

        assertTrue(PlatformRules.allows(windowsAny, PlatformRules.WINDOWS, x64))
        // Both of these returned true before os.arch was considered at all.
        assertFalse(PlatformRules.allows(windowsArm, PlatformRules.WINDOWS, x64))
        assertFalse(PlatformRules.allows(windowsX86, PlatformRules.WINDOWS, x64))
        assertTrue(PlatformRules.allows(windowsArm, PlatformRules.WINDOWS, "arm64"))
        assertTrue(PlatformRules.allows(windowsX86, PlatformRules.WINDOWS, "x86"))
    }

    @Test
    fun `the last matching rule decides`() {
        val disallowLast = listOf(
            mapOf("action" to "allow", "os" to mapOf("name" to "windows")),
            mapOf("action" to "disallow", "os" to mapOf("name" to "windows")),
        )
        assertFalse(PlatformRules.allows(disallowLast, PlatformRules.WINDOWS, x64))
        assertTrue(PlatformRules.allows(disallowLast.reversed(), PlatformRules.WINDOWS, x64))
    }

    @Test
    fun `an os version condition is treated as satisfied rather than excluding the library`() {
        val versioned = listOf(
            mapOf("action" to "allow", "os" to mapOf("name" to "windows", "version" to "^10\\.")),
        )
        assertTrue(PlatformRules.allows(versioned, PlatformRules.WINDOWS, x64))
    }

    @Test
    fun `a malformed rule is skipped rather than derailing the fetch`() {
        val junk = listOf("not-a-map", mapOf("no" to "action"), mapOf("action" to "allow"))
        assertTrue(PlatformRules.allows(junk, PlatformRules.WINDOWS, x64))

        // A rule that matches but carries no usable action counts as a denial, not as "ignore this one".
        val actionless = listOf(mapOf("os" to mapOf("name" to "windows")))
        assertFalse(PlatformRules.allows(actionless, PlatformRules.WINDOWS, x64))
    }

    // -------------------------------------------------------------------------------------------
    // os.name mapping
    // -------------------------------------------------------------------------------------------

    @Test
    fun `jvm os name spellings map to the launcher's tokens`() {
        assertEquals(PlatformRules.WINDOWS, PlatformRules.osNameToken("Windows 11"))
        assertEquals(PlatformRules.WINDOWS, PlatformRules.osNameToken("Windows Server 2022"))
        assertEquals(PlatformRules.MACOS, PlatformRules.osNameToken("Mac OS X"))
        assertEquals(PlatformRules.MACOS, PlatformRules.osNameToken("macOS"))
        assertEquals(PlatformRules.MACOS, PlatformRules.osNameToken("Darwin"))
        assertEquals(PlatformRules.LINUX, PlatformRules.osNameToken("Linux"))
        // An unknown value falls back to Linux (the least surprising POSIX guess) rather than failing.
        assertEquals(PlatformRules.LINUX, PlatformRules.osNameToken("FreeBSD"))
        assertEquals(PlatformRules.LINUX, PlatformRules.osNameToken(""))
    }

    // -------------------------------------------------------------------------------------------
    // natives classifier per platform / arch
    // -------------------------------------------------------------------------------------------

    @Test
    fun `natives classifiers follow the platform and the arch`() {
        // Windows keeps the plain spelling as the x86_64 default; the variants are arch-suffixed.
        assertEquals("natives-windows", PlatformRules.nativesClassifier(PlatformRules.WINDOWS, x64))
        assertEquals("natives-windows-arm64", PlatformRules.nativesClassifier(PlatformRules.WINDOWS, "arm64"))
        assertEquals("natives-windows-x86", PlatformRules.nativesClassifier(PlatformRules.WINDOWS, "x86"))
        assertEquals("natives-linux", PlatformRules.nativesClassifier(PlatformRules.LINUX, x64))
        assertEquals("natives-linux-arm64", PlatformRules.nativesClassifier(PlatformRules.LINUX, "arm64"))
        assertEquals("natives-macos", PlatformRules.nativesClassifier(PlatformRules.MACOS, x64))
        assertEquals("natives-macos-arm64", PlatformRules.nativesClassifier(PlatformRules.MACOS, "arm64"))
    }

    @Test
    fun `the osx and linux classifiers find their own natives jars in the modern layout`() {
        // Pinned together with nativesJarPath: the classifier that nativesClassifier returns must be
        // the exact suffix the version JSON's artifact path carries — an off-by-one here selects the
        // x86_64 set on Apple Silicon and dies in LWJGL far away from the cause.
        listOf(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar" to
                PlatformRules.nativesClassifier(PlatformRules.LINUX, x64),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux-arm64.jar" to
                PlatformRules.nativesClassifier(PlatformRules.LINUX, "arm64"),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos.jar" to
                PlatformRules.nativesClassifier(PlatformRules.MACOS, x64),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos-arm64.jar" to
                PlatformRules.nativesClassifier(PlatformRules.MACOS, "arm64"),
        ).forEach { [path, classifier] ->
            assertEquals(
                path,
                PlatformRules.nativesJarPath(artifact(path), classifier),
                "$classifier must select its own artifact path",
            )
        }
        // ...and must not grab a neighboring platform's or arch's jar.
        assertNull(
            PlatformRules.nativesJarPath(
                artifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos.jar"),
                PlatformRules.nativesClassifier(PlatformRules.MACOS, "arm64"),
            ),
        )
        assertNull(
            PlatformRules.nativesJarPath(
                artifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux-arm64.jar"),
                PlatformRules.nativesClassifier(PlatformRules.LINUX, x64),
            ),
        )
    }

    // -------------------------------------------------------------------------------------------
    // natives: the two layouts
    // -------------------------------------------------------------------------------------------

    private fun lib(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(*pairs)

    private fun artifact(path: String): Map<String, Any?> =
        lib("downloads" to mapOf("artifact" to mapOf("path" to path)))

    @Test
    fun `legacy layout - the natives come from a classifier entry`() {
        val legacy = lib(
            "downloads" to mapOf(
                "artifact" to mapOf("path" to "org/lwjgl/lwjgl/lwjgl/2.9.1/lwjgl-2.9.1.jar"),
                "classifiers" to mapOf(
                    "natives-windows" to mapOf(
                        "path" to "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1-natives-windows.jar",
                    ),
                ),
            ),
        )
        assertEquals(
            "org/lwjgl/lwjgl/lwjgl-platform/2.9.1/lwjgl-platform-2.9.1-natives-windows.jar",
            PlatformRules.nativesJarPath(legacy, PlatformRules.WINDOWS_NATIVES),
        )
    }

    @Test
    fun `modern layout - the artifact itself is the natives jar`() {
        assertEquals(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar",
            PlatformRules.nativesJarPath(
                artifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"),
                PlatformRules.WINDOWS_NATIVES,
            ),
        )
    }

    @Test
    fun `modern layout - the arch variants and other platforms are not windows x64 natives`() {
        // The suffix test is what rejects these. A prefix match accepted them and unpacked arm64 or
        // 32-bit dlls over the x64 ones — in the same directory, with the later write winning.
        listOf(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows-arm64.jar",
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows-x86.jar",
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos.jar",
        ).forEach { path ->
            assertNull(
                PlatformRules.nativesJarPath(artifact(path), PlatformRules.WINDOWS_NATIVES),
                "must not treat $path as a windows x64 natives jar",
            )
        }
    }

    @Test
    fun `a plain library jar contributes no natives`() {
        assertNull(PlatformRules.nativesJarPath(lib(), PlatformRules.WINDOWS_NATIVES))
        assertNull(
            PlatformRules.nativesJarPath(
                lib("downloads" to mapOf("artifact" to mapOf("url" to "https://example/"))),
                PlatformRules.WINDOWS_NATIVES,
            ),
        )
        assertNull(
            PlatformRules.nativesJarPath(
                artifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"),
                PlatformRules.WINDOWS_NATIVES,
            ),
        )
    }
}
