package org.ohmyloader.installer

import org.ohmyloader.devtools.AssetDownloader
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/**
 * Contract tests for the things that were wrong before and must not silently regress.
 *
 * Everything here is offline and fast on purpose: the failures this suite guards against (a launcher
 * version JSON without a Java requirement, an install path that kills the process, a shared `mods/`
 * directory accepted without a word, a Maven path that lets two instances overwrite each other) are all
 * decided by data and arguments, not by the network.
 */
class InstallerContractTest {

    private fun tempDir(): File = Files.createTempDirectory("oml-test").toFile()

    // ------------------------------------------------------------------------------------------
    // M1.2 — the version JSON must pin the Java requirement and the inherited game jar
    // ------------------------------------------------------------------------------------------

    @Test
    fun `version json declares java 27 and the inherited jar`() {
        val target = File(tempDir(), "26.3-OML.json")
        writeVersionJson(
            target = target,
            id = "26.3-OML",
            inherits = "26.3",
            mainClass = "org.ohmyloader.launcher.OMLBootstrap",
            javaMajor = 27,
            libraries = emptyList(),
            jvmArgs = listOf("-Doml.game.jar=/tmp/game.jar"),
        )
        val text = target.readText()

        // without javaVersion the launcher inherits vanilla's Java requirement, which can be older
        // than OML's bytecode needs — an instant UnsupportedClassVersionError
        assertContains(text, "\"javaVersion\"")
        assertContains(text, "\"majorVersion\": 27")
        // `jar` removes the ambiguity between our manifest-only stub and the real game jar
        assertContains(text, "\"jar\": \"26.3\"")
        assertContains(text, "\"inheritsFrom\": \"26.3\"")
        assertContains(text, "\"mainClass\": \"org.ohmyloader.launcher.OMLBootstrap\"")
        // timestamps must be UTC ISO-8601, not local offset
        assertTrue(Regex("\"time\": \"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"").containsMatchIn(text))
    }

    @Test
    fun `every bundled version declares the same java major`() {
        val versions = VersionCatalog.versions()
        assertTrue(versions.isNotEmpty(), "installer must bundle at least one version")
        versions.forEach { v ->
            assertEquals(
                VersionCatalog.requiredJavaMajor(), v.javaMajor,
                "${v.version} must require the same Java as the rest of OML",
            )
        }
    }

    @Test
    fun `maven path is keyed by install id so two instances cannot overwrite each other`() {
        val a = LayerLayout.mavenPath("oml-core", "26.3-OML")
        val b = LayerLayout.mavenPath("oml-core", "26.3-OML-test")
        assertNotEquals(a, b, "two instances of the same game version must not share a jar path")
        assertContains(a, "/26.3-OML/")
        assertContains(b, "/26.3-OML-test/")
        assertEquals("org.ohmyloader:oml-core:26.3-OML", LayerLayout.coordinate("oml-core", "26.3-OML"))
    }

    // ------------------------------------------------------------------------------------------
    // The embedded oml-native library: writer and reader must spell the platform the same way
    // (`natives/<os>-<arch>/oml-native.<ext>` vs the runtime's `windows-x86_64` token). The two halves
    // are pinned separately because they live in different worlds — a Gradle eachFile rename and a
    // runtime resource lookup — and when they disagree nothing fails: the second distribution line
    // (natives jars + JSON `natives` block) still works, so the loss stays invisible.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the embedded native library is found under the hyphenated platform token`() {
        val token = ArtifactSource.currentPlatformToken()
            ?: fail("currentPlatformToken() must recognize this machine's OS and arch")
        val ext = when (token.substringBefore('-')) {
            "windows" -> "dll"
            "osx" -> "dylib"
            else -> "so"
        }
        val library = ByteArray(64) { (it * 3).toByte() }
        val jar = File(tempDir(), "installer-with-native.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("natives/$token/oml-native.$ext"))
            zip.write(library)
            zip.closeEntry()
        }

        FatJarArtifactSource("26.3", "oml-adapter-26_3", jar).use { source ->
            val native = source.nativeLibrary()
            assertNotNull(native, "the installer must find natives/<os>-<arch>/oml-native.<ext>")
            assertEquals("oml-native.$ext", native.fileName)
            assertContentEquals(library, native.open().use { it.readBytes() }, "the library bytes must travel")
        }
    }

    @Test
    fun `the build embeds the native library under the hyphenated platform token`() {
        // The writer half, and the half the bug actually lived in. A build-time rename has no runtime
        // surface to assert against, so the guard reads the script — the same approach the atomic-move
        // test below takes with AssetDownloader, and for the same reason: the property is invisible in
        // any outcome the test could otherwise observe.
        val script = File("build.gradle.kts")
        assertTrue(script.isFile, "this test must run from the oml-installer project directory")
        assertTrue(
            script.readText().contains($$"RelativePath(true, \"$os-$arch\""),
            "embedOmlNative must lay the library out as natives/<os>-<arch>/...; a bare `os, arch` " +
                "pair writes natives/windows/x86_64/..., which nativeLibrary() never looks for",
        )
    }

    // ------------------------------------------------------------------------------------------
    // oml-native: one jar per OS under libraries/ (flat for Prism, Maven tree for a version JSON),
    // plus a bare library under natives/. All three paths must exist and agree with the JSON / patch
    // declaration, or a CLI / Gradle install — the shape a dedicated server is installed with — ends
    // up with an empty natives/ and a runtime that loads nothing.
    // ------------------------------------------------------------------------------------------

    /** A packaged natives jar the way `packageOmlNativeJars` builds one: the library at the root. */
    private fun packagedNativesJar(dir: File, version: String, os: String, libraryName: String): File {
        val jar = File(dir, "oml-native-$version-natives-$os.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\r\n\r\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(libraryName))
            zip.write(ByteArray(32) { (it * 5).toByte() })
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `oml-native lands in the maven tree with the standard classifiers block`() {
        val root = tempDir()
        val libraries = File(root, "libraries").also { it.mkdirs() }
        // the source jars live wherever the build put them, never at the install root
        val jar = packagedNativesJar(
            File(root, "download").also { it.mkdirs() },
            "0.1.0-SNAPSHOT",
            "windows",
            "oml-native.dll"
        )
        val source = DirectoryArtifactSource("26.3", emptyList(), null, nativeJarFiles = listOf(jar))
        // stale artifacts an install of an older layout may have left: a flat jar at the libraries
        // root, and a flat jar + a Maven tree at the target root
        val staleFlat = File(libraries, jar.name).apply { writeBytes(ByteArray(8)) }
        val staleRootJar = File(root, jar.name).apply { writeBytes(ByteArray(8)) }
        val staleTree = File(root, "org/ohmyloader/oml-native/0.1.0-SNAPSHOT/oml-native.jar").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(8))
        }

        val installed = installOmlNativePackages(libraries, source, {}, mavenTree = true)
        assertNotNull(installed, "a bundled natives jar must produce a library entry")

        // A version-JSON launcher resolves a classified artifact through the Maven tree, so that is
        // where the jar has to be — a flat name is not a Maven location and stays unconsumed.
        val mavenPath = "org/ohmyloader/oml-native/0.1.0-SNAPSHOT/oml-native-0.1.0-SNAPSHOT-natives-windows.jar"
        assertTrue(File(libraries, mavenPath).isFile, "the jar must land at its Maven location under libraries/")
        assertFalse(staleFlat.exists(), "the pre-fix flat jar under libraries/ must be swept")
        assertFalse(staleRootJar.exists(), "the pre-fix copy at the target root must be swept")
        assertFalse(staleTree.exists(), "the pre-fix Maven tree at the target root must be swept")
        val json = installed.toJson()
        assertContains(json, "\"classifiers\"", message = "a launcher reads downloads.classifiers only")
        assertContains(
            json,
            "\"path\": \"$mavenPath\"",
            message = "the path is the Maven location, relative to libraries/"
        )
        assertContains(json, "\"natives\": {\n        \"windows\": \"natives-windows\"")
    }

    @Test
    fun `oml-native stays flat for prism's local resolver`() {
        val root = tempDir()
        val libraries = File(root, "libraries").also { it.mkdirs() }
        val jar = packagedNativesJar(
            File(root, "download").also { it.mkdirs() },
            "0.1.0-SNAPSHOT",
            "windows",
            "oml-native.dll"
        )
        val source = DirectoryArtifactSource("26.3", emptyList(), null, nativeJarFiles = listOf(jar))

        val installed = installOmlNativePackages(libraries, source, {}, mavenTree = false)
        assertNotNull(installed, "a bundled natives jar must produce a library entry")

        // Prism resolves an `MMC-hint: local` library by exactly this flat file name under libraries/;
        // it never walks the Maven tree for a local entry, so flat mode must not write the tree either.
        assertTrue(File(libraries, jar.name).isFile, "Prism resolves a local library by its flat file name")
        assertFalse(File(libraries, "org").exists(), "flat mode must not also write the Maven tree")

        // Prism gets the same file, declared the way Prism declares every other entry it was handed:
        // a local library. A `downloads` block there advertises a fetch (with an empty url) that
        // Prism's local-library path would never make — and the file is already on disk.
        val prism = installed.toPrismJson()
        assertContains(prism, "\"MMC-hint\": \"local\"", message = "the patch entries are all local")
        assertFalse(prism.contains("\"downloads\""), "a local entry has nothing to download")
        assertContains(prism, "\"natives\": { \"windows\": \"natives-windows\" }")
    }

    @Test
    fun `every bundled platform is written, in both layouts`() {
        // Narrowing the write to the installing machine's OS was tried for Prism and removed. Neither
        // target directory is guaranteed to stay on that machine — a `.minecraft` gets copied and
        // restored from backups, a Prism instance is exported as a zip and imported elsewhere — while
        // the `natives` block is what tells the launcher which classifier an OS needs. A platform that
        // is missing there extracts nothing and degrades zstd without a word, so the count has to
        // follow the bundle, not the machine.
        val platforms = listOf(
            "windows" to "oml-native.dll",
            "linux" to "liboml-native.so",
            "osx" to "liboml-native.dylib",
            "windows-arm64" to "oml-native.dll",
            "linux-arm64" to "liboml-native.so",
            "osx-arm64" to "liboml-native.dylib",
        )
        listOf(true, false).forEach { mavenTree ->
            val root = tempDir()
            val libraries = File(root, "libraries").also { it.mkdirs() }
            val download = File(root, "download").also { it.mkdirs() }
            val jars = platforms.map { [os, name] -> packagedNativesJar(download, "0.1.0-SNAPSHOT", os, name) }
            val source = DirectoryArtifactSource("26.3", emptyList(), null, nativeJarFiles = jars)

            val installed = installOmlNativePackages(libraries, source, {}, mavenTree = mavenTree)
            assertNotNull(installed, "mavenTree=$mavenTree: a bundled set must produce a library entry")
            assertEquals(
                platforms.size,
                installed.natives.size,
                "mavenTree=$mavenTree: every bundled platform must be written"
            )

            platforms.forEach { [os, _] ->
                val expected = if (mavenTree) {
                    "org/ohmyloader/oml-native/0.1.0-SNAPSHOT/oml-native-0.1.0-SNAPSHOT-natives-$os.jar"
                } else {
                    "oml-native-0.1.0-SNAPSHOT-natives-$os.jar"
                }
                assertTrue(File(libraries, expected).isFile, "mavenTree=$mavenTree: $expected must exist")
            }

            // The declaration must match the writes, or a launcher is told to extract an OS whose file
            // is absent — the exact silent-degradation shape this test exists to prevent. The arm64
            // keys are Mojang's own 1.19+ spelling: the natives map is keyed `<os>` / `<os>-arm64`.
            val json = if (mavenTree) installed.toJson() else installed.toPrismJson()
            val declared = Regex("\"((?:windows|linux|osx)(?:-arm64)?)\": \"natives-\\1\"").findAll(json)
                .map { it.groupValues[1] }.toList()
            assertEquals(
                platforms.map { it.first },
                declared,
                "the `natives` block must declare every written platform, arm64 slots included"
            )
        }
    }

    @Test
    fun `the bare library is taken from the packaged natives jar when there is no plain copy`() {
        // A CLI / Gradle install only ever receives `--native-jar`, so nativeLibrary() is null and
        // the bare copy — the file the runtime loads out of the natives directory — has to come from
        // the jar. Without it the server's natives/ stays empty and zstd silently falls back. The
        // classifier must match the running JVM's architecture: an arm64 JVM handed the plain
        // (x86_64) jar would deploy a library that cannot load, so the selection is arch-exact.
        val token = ArtifactSource.currentPlatformToken()
            ?: fail("currentPlatformToken() must recognize this machine's OS and arch")
        val [os, arch] = token.split('-', limit = 2)
        val classifier = os + if (arch == "arm64") "-arm64" else ""
        val libraryName = "oml-native." + when (os) {
            "windows" -> "dll"
            "osx" -> "dylib"
            else -> "so"
        }
        val dir = tempDir()
        // Bundle BOTH slots: the plain jar must not win on an arm64 machine (it cannot load there),
        // and the arch-matched jar must not be missed on an x86_64 one.
        val jars = listOf(classifier, "linux").map { slot ->
            packagedNativesJar(dir, "0.1.0-SNAPSHOT", slot, libraryName)
        }.distinctBy { it.name }
        val source = DirectoryArtifactSource("26.3", emptyList(), null, nativeJarFiles = jars)
        assertNull(source.nativeLibrary(), "this source shape carries no plain copy by construction")

        val natives = File(dir, "natives")
        installOmlNative(natives, source) {}

        val written = File(natives, libraryName)
        assertTrue(written.isFile, "natives/ must receive the library out of the packaged jar")
        assertContentEquals(ByteArray(32) { (it * 5).toByte() }, written.readBytes())
    }

    @Test
    fun `the arm64 classifier is parsed and preferred over the plain jar on an arm64 token`() {
        // The parse half of arch-exact selection: the -arm64 suffix must yield its own classifier
        // (the value the version JSON `natives` map is keyed by), and the machine-token mapping
        // must land on it — not on the plain jar, whose library an arm64 JVM cannot load.
        assertEquals(
            ArtifactSource.parseOmlNativePackage("oml-native-0.1.0-SNAPSHOT-natives-linux-arm64.jar"),
            "0.1.0-SNAPSHOT" to "linux-arm64",
        )
        assertEquals(
            ArtifactSource.parseOmlNativePackage("oml-native-0.1.0-SNAPSHOT-natives-windows.jar"),
            "0.1.0-SNAPSHOT" to "windows",
        )
        val token = ArtifactSource.currentPlatformToken()
            ?: fail("currentPlatformToken() must recognize this machine's OS and arch")
        val [os, arch] = token.split('-', limit = 2)
        val expectedClassifier = os + if (arch == "arm64") "-arm64" else ""
        val jars = listOf(expectedClassifier, "linux").map { slot ->
            val ext = if (slot.endsWith("-arm64")) {
                "oml-native-arm64.so"
            } else {
                "oml-native.so"
            }
            packagedNativesJar(tempDir(), "0.1.0-SNAPSHOT", slot, ext)
        }
        val source = DirectoryArtifactSource("26.3", emptyList(), null, nativeJarFiles = jars)
        val native = nativeLibraryFromPackages(source)
        assertNotNull(native, "the arch-matched jar must be selected")
        assertEquals(
            expectedClassifier,
            if (native.fileName == "oml-native-arm64.so") "$os-arm64" else os,
            "the selected jar must be the one matching the running JVM's architecture"
        )
    }

    // ------------------------------------------------------------------------------------------
    // M1.3 — failures are exceptions, and they stay exceptions (no process death on the way out)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `unknown arguments are rejected as a controlled exception`() {
        assertFailsWith<InstallationException> {
            Installer.Options.parse(arrayOf("--version", "26.3", "--dir", ".", "--nope"))
        }
    }

    @Test
    fun `a standard install without a declared isolation is refused`() {
        val e = assertFailsWith<InstallationException> {
            Installer.Options.parse(
                arrayOf("--version", "26.3", "--dir", ".", "--layer-jar", "x.jar"),
            )
        }
        assertContains(e.message!!, "--isolation")
    }

    @Test
    fun `a bad proxy is reported instead of killing the process`() {
        assertNull(AssetDownloader.parseProxy("not-a-proxy"))
        assertNull(AssetDownloader.parseProxy(""))
        // a well-formed one parses, and must not throw
        assertTrue(AssetDownloader.parseProxy("127.0.0.1:7890") != null)
    }

    // ------------------------------------------------------------------------------------------
    // M2.3 — writes land atomically and are hashed
    // ------------------------------------------------------------------------------------------

    @Test
    fun `atomic write leaves no tmp file and reports the sha1 of the bytes`() {
        val dir = tempDir()
        val target = File(dir, "sub/thing.bin")
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val written = writeAtomic(target) { ByteArrayInputStream(payload) }

        assertTrue(target.isFile)
        assertEquals(payload.size.toLong(), written.size)
        assertEquals(sha1Of(target), written.sha1)
        assertFalse(File(dir, "sub/thing.bin.tmp").exists(), "the .tmp file must not survive a successful write")
    }

    @Test
    fun `an empty source is a controlled failure, and no half file is left behind`() {
        val dir = tempDir()
        val target = File(dir, "empty.bin")
        assertFailsWith<InstallationException> { writeAtomic(target) { ByteArrayInputStream(ByteArray(0)) } }
        assertFalse(target.exists())
        assertFalse(File(dir, "empty.bin.tmp").exists())
    }

    @Test
    fun `a digest mismatch is a rejection, not a warning`() {
        val dir = tempDir()
        val payload = ByteArray(2048) { it.toByte() }
        val file = File(dir, "payload.bin").apply { writeBytes(payload) }
        val real = AssetDownloader.sha1Hex(file)

        assertTrue(AssetDownloader.digestMatches(file, real), "the true digest must be accepted")
        assertTrue(AssetDownloader.digestMatches(file, real.uppercase()), "hex case must not matter")
        // The whole point of the check: bytes of the *right length* that are not the expected bytes.
        // Size-only verification accepts this; a digest must not.
        assertFalse(
            AssetDownloader.digestMatches(
                file,
                sha1Of(File(dir, "other.bin").apply { writeBytes(payload.reversedArray()) })
            )
        )
        assertFalse(AssetDownloader.digestMatches(file, "0".repeat(40)))
    }

    @Test
    fun `a source without a published digest degrades to size-only instead of failing every time`() {
        val dir = tempDir()
        val file = File(dir, "no-digest.bin").apply { writeBytes(ByteArray(16)) }
        // Piston publishes SHA-1 for game jars and libraries; the MCP / Forge maven artifacts do not.
        // A null/blank expectation therefore means "unknown", never "must not exist".
        assertTrue(AssetDownloader.digestMatches(file, null))
        assertTrue(AssetDownloader.digestMatches(file, ""))
        assertTrue(AssetDownloader.digestMatches(file, "   "))
    }

    @Test
    fun `the download path verifies with the same algorithm the installer reports`() {
        // Guards against a reimplementation drifting: the version JSON written for the launcher records
        // sha1Of(...) over the same bytes the downloader computes internally, so the two must agree.
        val dir = tempDir()
        val file = File(dir, "layer.jar").apply { writeBytes(ByteArray(8192) { (it * 7).toByte() }) }
        assertEquals(sha1Of(file), AssetDownloader.sha1Hex(file))
    }

    @Test
    fun `the atomic move is actually requested, not merely named`() {
        // A regression guard with teeth: `REPLACE_EXISTING` alone compiles, passes every other test in
        // this file, and silently gives up the "a reader sees the old file or the new one" guarantee on
        // a same-filesystem rename. Assert on the source, because the property is not observable from
        // the result of a successful move.
        val src =
            File("src/main/kotlin/org/ohmyloader/installer/../../../../oml-devtools/src/main/kotlin/org/ohmyloader/devtools/AssetDownloader.kt")
        val candidates = listOf(
            File("../oml-devtools/src/main/kotlin/org/ohmyloader/devtools/AssetDownloader.kt"),
            src.canonicalFile,
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: fail("cannot locate AssetDownloader.kt from ${File(".").absolutePath}")

        val text = source.readText()
        assertTrue(
            text.contains("StandardCopyOption.ATOMIC_MOVE"),
            "the download path must request an atomic move",
        )
        assertTrue(
            text.contains("AtomicMoveNotSupportedException"),
            "the cross-filesystem downgrade must be caught explicitly, never left to crash the install",
        )
        // Every Files.move in the file must go through the single helper, so a new call site cannot
        // quietly reintroduce a non-atomic move.
        val rawMoves = Regex("""Files\.move\(""").findAll(text).count()
        val helper = Regex("""private fun moveIntoPlace""").findAll(text).count()
        assertEquals(2, rawMoves, "only moveIntoPlace's atomic attempt and its downgrade may call Files.move")
        assertEquals(1, helper, "there must be exactly one move helper")
    }

    // ------------------------------------------------------------------------------------------
    // M4.1 — a shared mods directory is an explicit decision
    // ------------------------------------------------------------------------------------------

    private fun clientContext(dir: File, isolation: Boolean, allowShared: Boolean): InstallContext {
        val supported = VersionCatalog.versions().first()
        return InstallContext(
            target = supported,
            targetDir = dir,
            installId = "${supported.version}-OML",
            isolation = isolation,
            acceptEula = true,
            allowSharedMods = allowShared,
            artifacts = DirectoryArtifactSource(supported.version, emptyList(), null),
            side = "client",
        )
    }

    @Test
    fun `turning off isolation blocks the install until it is acknowledged`() {
        val gameDir = tempDir()
        val blocked = StandardLauncherTarget.validate(clientContext(gameDir, isolation = false, allowShared = false))
        assertFalse(blocked.ok)
        assertTrue(blocked.errors.any { it.contains("mods") })

        val acknowledged =
            StandardLauncherTarget.validate(clientContext(gameDir, isolation = false, allowShared = true))
        assertTrue(acknowledged.ok, "an explicit acknowledgement must be enough to proceed")
    }

    @Test
    fun `a missing directory is an error, an unusual one is only a warning`() {
        val missing = File(tempDir(), "does-not-exist")
        assertFalse(StandardLauncherTarget.validate(clientContext(missing, isolation = true, allowShared = false)).ok)

        val odd = tempDir()
        val validation = StandardLauncherTarget.validate(clientContext(odd, isolation = true, allowShared = false))
        assertTrue(validation.ok)
        assertTrue(validation.warnings.isNotEmpty(), "a directory that is not a game directory should warn")
    }

    @Test
    fun `a server install refuses to accept the EULA on the user's behalf`() {
        val dir = tempDir()
        val supported = VersionCatalog.versions().first()
        val ctx = InstallContext(
            target = supported,
            targetDir = if (dir.exists()) File(dir, "server") else dir,
            installId = "server",
            isolation = true,
            acceptEula = false,
            allowSharedMods = true,
            artifacts = DirectoryArtifactSource(supported.version, emptyList(), null),
            side = "server",
        )
        val validation = DedicatedServerTarget.validate(ctx)
        assertFalse(validation.ok)
        assertTrue(validation.errors.any { it.contains("EULA") })
    }

    // ------------------------------------------------------------------------------------------
    // A launcher-installed runtime learns its side only from the version JSON, and resolves our jars
    // only from their `name` — both are contract, not detail
    // ------------------------------------------------------------------------------------------

    @Test
    fun `runtime properties state the side explicitly on both sides`() {
        fun argsFor(side: String) = RuntimeProperties(
            gameJar = File("/game/26.3.jar"),
            modsDir = File("/game/mods"),
            side = side,
        ).asJvmArgs()

        // Without this property a launcher-installed runtime has nothing to read: OMLBootstrap then
        // falls back to its own default, and a default of "server" sends a client launch into
        // net.minecraft.server.Main, which rejects the client's arguments outright.
        assertContains(argsFor("client"), "-Doml.side=client")
        assertContains(argsFor("server"), "-Doml.side=server")
    }

    @Test
    fun `every layer coordinate resolves to the maven path we write`() {
        // Mirrors the embedded layer. The artifacts with a dot or a hyphen in their name are the
        // interesting ones: a launcher derives the file path from `name`, and we write the file
        // ourselves, so the two must land on exactly the same path — otherwise the launcher reports
        // "could not find or load main class" for a version that is installed perfectly well.
        val artifacts = listOf(
            "annotations-13.0", "asm-9.10.1", "asm-analysis-9.10.1", "asm-commons-9.10.1",
            "asm-tree-9.10.1", "asm-util-9.10.1", "kotlin-stdlib-2.4.20",
            "oml-adapter-26_3", "oml-api", "oml-core", "oml-launcher",
        )
        for (base in artifacts) {
            val coordinate = LayerLayout.coordinate(base, "26.3-OML")
            val mavenPath = LayerLayout.mavenPath(base, "26.3-OML")
            assertEquals(mavenPathFromCoordinate(coordinate), mavenPath, "name and path disagree for $base")
            // the artifact segment of `name` must be a plain artifact id, never a directory prefix
            assertEquals(base, coordinate.split(":")[1], "the artifact segment must not carry a prefix")
            assertEquals("$base-26.3-OML.jar", mavenPath.substringAfterLast('/'))
        }
    }

    @Test
    fun `prism local libraries land flat under instance libraries with the coordinate basename`() {
        // Prism checks `MMC-hint: local` files at <instance>/libraries/<artifact>-<version>.jar — a
        // flat name it derives from the maven coordinate, NOT the group/artifact/version tree it
        // uses for remote downloads. The installer once wrote the Maven layout there and Prism then
        // refused to launch, listing every OML jar as a missing local file.
        val coordinate = LayerLayout.coordinate("oml-core-0.1.0-SNAPSHOT", "26.3-OML")
        // the flat name Prism computes from the coordinate, re-implemented so the test cannot
        // mirror a bug in production code
        val prismFlatName = "${coordinate.split(":")[1]}-${coordinate.split(":")[2]}.jar"
        assertEquals("oml-core-0.1.0-SNAPSHOT-26.3-OML.jar", prismFlatName)
        // and it must be distinguishable from the source jar name, which lacks the install id
        assertNotEquals("oml-core-0.1.0-SNAPSHOT.jar", prismFlatName)
    }

    @Test
    fun `a launcher install carries the classpath entries a modern json needs`() {
        // A vanilla JSON that only has `minecraftArguments` gets `-cp` added by launchers such as PCL2
        // on that legacy path — but only while the version JSON declares no `arguments.jvm`. OML has to
        // declare one (that is where the -Doml.* properties and the Java requirement travel), so the JSON
        // must supply `-cp ${classpath}` and `-Djava.library.path=${natives_directory}` the way Mojang's own
        // modern JSONs do. Without them the JVM is started with no classpath at all and reports
        // "Could not find or load main class org.ohmyloader.launcher.OMLBootstrap".
        val args = launcherJvmArgs(
            RuntimeProperties(
                gameJar = File("/game/26.3.jar"),
                modsDir = File("/game/mods"),
                side = "client",
            ),
        )
        assertContains(args, "-cp")
        assertContains(args, $$"${classpath}")
        assertContains(args, $$"-Djava.library.path=${natives_directory}")
        assertEquals(
            args.indexOf($$"${classpath}"), args.indexOf("-cp") + 1,
            $$"-cp must be immediately followed by ${classpath}",
        )
        // the fix appends: our own properties must survive
        assertTrue(args.any { it.startsWith("-Doml.game.jar=") }, "the -D properties must still be there")
    }

    // ------------------------------------------------------------------------------------------
    // Path templates (the "double-click installs into Downloads" regression)
    // ------------------------------------------------------------------------------------------

    @Test
    fun `install ids that would escape the target directory are refused`() {
        assertTrue(validateInstallId("").isNotEmpty())
        assertTrue(validateInstallId("../evil").isNotEmpty())
        assertTrue(validateInstallId("a/b").isNotEmpty())
        assertTrue(validateInstallId("26.3-OML").isEmpty())
    }

    /** Launcher-side convention, re-implemented so the test cannot mirror a bug in the code it checks. */
    private fun mavenPathFromCoordinate(coordinate: String): String {
        val [group, artifact, version] = coordinate.split(":")
        return "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
    }
}
