package org.ohmyloader.devtools

/**
 * Evaluation of a Mojang version JSON's `rules` block, and of the two layouts a natives jar is
 * declared in — with the target platform as a **parameter** rather than read from the running JVM,
 * so tests can pin every platform/arch combination. The rule semantics are Mojang's: the presence of
 * rules flips the default to "not allowed", and both directions of getting it wrong are silent.
 */
internal object PlatformRules {

    /** `os.name` token the version JSONs use for Windows. */
    const val WINDOWS: String = "windows"

    /** `os.name` token for Linux. */
    const val LINUX: String = "linux"

    /** `os.name` token for macOS — Mojang's JSONs say `osx`, not `macos`. */
    const val MACOS: String = "osx"

    /** The classifier a Windows x86_64 natives artifact carries, in both layouts. */
    const val WINDOWS_NATIVES: String = "natives-windows"

    /**
     * `os.arch` in a version JSON uses the launcher's spellings, which are not the JVM's.
     *
     * `amd64` and `x86_64` both mean x86_64; the 32-bit JVM reports `x86`/`i386`; ARM is `aarch64` on
     * some JVMs and `arm64` on others. An unrecognized value is passed through lowercased rather than
     * mapped to something plausible: a wrong answer here silently selects the wrong natives, whereas
     * an unmapped token simply matches nothing and leaves the entry excluded.
     */
    fun archToken(jvmArch: String): String = when (val arch = jvmArch.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        "aarch64", "arm64" -> "arm64"
        else -> arch
    }

    /** The arch token of the JVM this code is running on. */
    fun currentArch(): String = archToken(System.getProperty("os.arch", ""))

    /**
     * Maps a JVM `os.name` to the launcher's token: `windows`, `osx`, `linux`.
     *
     * The JVM reports "Windows 11", "Mac OS X", "Linux" …; the version JSON knows `windows`, `osx`,
     * `linux`. An unrecognized value falls back to Linux rather than to an error: on any unknown
     * Unix-like host the `.so` set is the best available guess, and a wrong answer here would at
     * worst fail at `UnsatisfiedLinkError` just as loudly as refusing to launch would.
     */
    fun osNameToken(jvmOsName: String): String {
        val name = jvmOsName.lowercase()
        return when {
            // darwin is checked before windows because the string literally contains "win":
            // darWIN would otherwise classify as Windows.
            name.contains("darwin") || name.contains("mac") -> MACOS
            name.contains("win") -> WINDOWS
            else -> LINUX
        }
    }

    /** The `os.name` token of the JVM this code is running on. */
    fun currentOsName(): String = osNameToken(System.getProperty("os.name", ""))

    /**
     * The natives classifier the version JSON declares for [osName] / [arch].
     *
     * Both the legacy (`classifiers.<classifier>`) and the modern (artifact file name) layouts key off
     * this exact string. The arch variants matter: an Apple-silicon Mac must unpack
     * `natives-macos-arm64`, and Windows-on-ARM has its own artifact — falling back to the x86_64
     * classifier would be exactly the "wrong natives over the right ones" defect the suffix test in
     * [nativesJarPath] exists to prevent.
     */
    fun nativesClassifier(osName: String, arch: String): String = when (osName) {
        MACOS -> if (arch == "arm64") "natives-macos-arm64" else "natives-macos"
        LINUX -> if (arch == "arm64") "natives-linux-arm64" else "natives-linux"
        else -> when (arch) {
            "arm64" -> "natives-windows-arm64"
            "x86" -> "natives-windows-x86"
            else -> WINDOWS_NATIVES
        }
    }

    /**
     * Whether a library's `rules` allow it on [osName] / [arch].
     *
     * **Presence of rules flips the default to "not allowed"**, and each rule that matches then sets
     * the verdict — last match wins. That is Mojang's semantics, and both directions of getting it
     * wrong are silent, so the tests pin them explicitly.
     *
     * `os.version` is a regex over the OS version string; OML does not differentiate between the
     * versions of any one OS, so such a condition is treated as satisfied.
     */
    fun allows(rules: List<*>?, osName: String, arch: String): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        for (ruleRaw in rules) {
            val rule = ruleRaw as? Map<*, *> ?: continue
            if (!matches(rule, osName, arch)) continue
            allowed = (rule["action"] as? String) == "allow"
        }
        return allowed
    }

    /** A rule with no `os` block matches everywhere; one with an `os` block must match name and arch. */
    private fun matches(rule: Map<*, *>, osName: String, arch: String): Boolean {
        val os = rule["os"] as? Map<*, *> ?: return true
        (os["name"] as? String)?.let { if (it != osName) return false }
        (os["arch"] as? String)?.let { if (it != arch) return false }
        return true
    }

    /**
     * The path of the natives jar [lib] contributes for [classifier], or null when it contributes none.
     * Two layouts, and missing the second ends in an empty natives directory and an
     * `UnsatisfiedLinkError` that names no directory:
     *
     *  * **legacy**: a `classifiers.<classifier>` entry inside `downloads`;
     *  * **modern** (1.19+): the natives *are* the artifact, with the classifier in the file name.
     *
     * The modern branch's suffix test excludes the neighbouring arch variants
     * (`…-natives-windows-arm64.jar`), which a prefix match would unpack over the x64 files.
     */
    fun nativesJarPath(lib: Map<*, *>, classifier: String): String? {
        val downloads = lib["downloads"] as? Map<*, *> ?: return null
        val legacyEntry = (downloads["classifiers"] as? Map<*, *>)?.get(classifier) as? Map<*, *>
        val legacyPath = legacyEntry?.get("path") as? String
        if (legacyPath != null) return legacyPath

        val artifact = downloads["artifact"] as? Map<*, *> ?: return null
        val artifactPath = artifact["path"] as? String ?: return null
        return artifactPath.takeIf { it.endsWith("-$classifier.jar") }
    }
}
