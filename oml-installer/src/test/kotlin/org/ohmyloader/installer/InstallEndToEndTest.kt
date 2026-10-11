package org.ohmyloader.installer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/**
 * Offline end-to-end installs of the two launcher-shaped targets: a full [InstallationTarget.install]
 * against a [DirectoryArtifactSource] and a temporary directory, followed by an uninstall. The
 * assertion that matters is consistency — the version JSON / component patch must declare exactly the
 * files the install wrote (Maven path, flat name, sha1, size), the stub jar and natives must be where
 * the declarations point, and the uninstall must remove exactly that set and nothing else. The server
 * target needs the network and is exercised by the end-to-end boot test in the OhMyLoaderTestMod
 * repository instead.
 */
class InstallEndToEndTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun tempDir(): File = Files.createTempDirectory("oml-e2e").toFile()

    /** A non-empty jar-shaped file (writeAtomic rejects zero bytes; the layer is jar files). */
    private fun fakeJar(dir: File, name: String, fill: Byte = 3): File {
        dir.mkdirs()
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\r\n\r\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(name))
            zip.write(ByteArray(128) { (it * fill.toInt()).toByte() })
            zip.closeEntry()
        }
        return file
    }

    /** Every platform slot: neither target directory is guaranteed to stay on the installing machine. */
    private fun nativePackages(dir: File): List<File> = listOf(
        "windows" to "oml-native.dll",
        "linux" to "liboml-native.so",
        "osx" to "liboml-native.dylib",
        "windows-arm64" to "oml-native.dll",
        "linux-arm64" to "liboml-native.so",
        "osx-arm64" to "liboml-native.dylib",
    ).map { [os, name] ->
        val jar = File(dir, "oml-native-0.1.0-SNAPSHOT-natives-$os.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(ByteArray(64) { (it * 5).toByte() })
            zip.closeEntry()
        }
        jar
    }

    /** Asserts every artifact path the JSON declares (artifact and classifiers) exists and matches. */
    private fun assertJsonLibrariesMatchDisk(jsonText: String, librariesRoot: File) {
        val libs = json.parseToJsonElement(jsonText).jsonObject["libraries"]?.jsonArray
            ?: fail("the JSON must declare its libraries")
        assertTrue(libs.isNotEmpty(), "the installed layer must be declared")
        for (entry in libs) {
            val obj = entry.jsonObject
            assertEquals("org.ohmyloader", obj["name"]!!.jsonPrimitive.content.substringBefore(':'))
            val downloads = obj["downloads"]?.jsonObject ?: continue
            val artifacts = buildList {
                downloads["artifact"]?.jsonObject?.let { add(it) }
                downloads["classifiers"]?.jsonObject?.values?.forEach { add(it.jsonObject) }
            }
            assertTrue(artifacts.isNotEmpty(), "each of our entries declares at least one artifact")
            for (artifact in artifacts) {
                val path = artifact["path"]!!.jsonPrimitive.content
                val file = File(librariesRoot, path)
                assertTrue(file.isFile, "the declared file must exist on disk: $path")
                assertEquals(artifact["size"]!!.jsonPrimitive.content.toLong(), file.length(), path)
                assertEquals(artifact["sha1"]!!.jsonPrimitive.content, sha1Of(file), path)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Standard launcher: versions/<id>/<id>.json + the Maven tree + the bare natives copy
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a standard install lays out a self-consistent version and uninstalls cleanly`() {
        val gameDir = tempDir()
        val sources = tempDir()
        val layer = listOf("oml-core", "oml-launcher", "oml-adapter-26_3").map { fakeJar(sources, "$it.jar") }
        val natives = nativePackages(sources)
        val ctx = InstallContext(
            target = SupportedVersion("26.3", 27, "oml-adapter-26_3"),
            targetDir = gameDir,
            installId = "26.3-OML",
            isolation = true,
            acceptEula = true,
            allowSharedMods = false,
            artifacts = DirectoryArtifactSource("26.3", layer, null, nativeJarFiles = natives),
            side = "client",
            log = {},
        )

        val hint = Installer.performInstall(ctx, StandardLauncherTarget, ProgressSink.NOP)
        assertContains(hint, "26.3-OML")

        val versionDir = File(gameDir, "versions/26.3-OML")
        val jsonFile = File(versionDir, "26.3-OML.json")
        assertTrue(jsonFile.isFile, "the version JSON must exist")
        val root = json.parseToJsonElement(jsonFile.readText()).jsonObject
        assertEquals("26.3", root["inheritsFrom"]!!.jsonPrimitive.content)
        assertEquals("26.3", root["jar"]!!.jsonPrimitive.content)
        assertEquals("org.ohmyloader.launcher.OMLBootstrap", root["mainClass"]!!.jsonPrimitive.content)
        assertEquals(27, root["javaVersion"]!!.jsonObject["majorVersion"]!!.jsonPrimitive.content.toInt())

        // the pinned mods dir: inside the version dir (isolation) and existing
        val modsDirArg = root["arguments"]!!.jsonObject["jvm"]!!.jsonArray
            .map { it.jsonPrimitive.content }
            .first { it.startsWith("-Doml.mods.dir=") }
            .removePrefix("-Doml.mods.dir=")
        assertEquals(File(versionDir, "mods").absoluteFile.invariantSeparatorsPath, File(modsDirArg).absoluteFile.invariantSeparatorsPath)
        assertTrue(File(versionDir, "mods").isDirectory)

        // every declared library is on disk and matches the declaration (sha1 / size)
        assertJsonLibrariesMatchDisk(jsonFile.readText(), File(gameDir, "libraries"))
        for (artifact in layer) {
            val mavenPath = LayerLayout.mavenPath(artifact.name.removeSuffix(".jar"), "26.3-OML")
            val written = File(gameDir, "libraries/$mavenPath")
            assertTrue(written.isFile, "$mavenPath must be laid out in the Maven tree")
            assertEquals(sha1Of(artifact), sha1Of(written), "$mavenPath must carry the source bytes")
        }

        // the stub jar the launcher expects next to the JSON, and a real zip with a manifest
        val stub = File(versionDir, "26.3-OML.jar")
        assertTrue(stub.isFile)
        val stubEntries = java.util.zip.ZipFile(stub).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertEquals(listOf("META-INF/MANIFEST.MF"), stubEntries)

        // the bare natives copy: the arch-matched library out of the packaged jars
        val nativesDir = File(versionDir, "natives")
        val bareName = when (ArtifactSource.currentPlatformToken()?.substringBefore('-')) {
            "windows" -> "oml-native.dll"
            "osx" -> "oml-native.dylib"
            else -> "oml-native.so"
        }
        assertTrue(File(nativesDir, bareName).isFile, "natives/ must carry the bare library")

        // uninstall removes exactly the declared set; user content in the game dir survives.
        // With isolation the mods dir inside the version dir is user data: the JSON and jar go,
        // the dir itself stays because of it (the uninstall's File.delete() boundary).
        File(gameDir, "options.txt").writeText("soundCategory_music:0.5\n")
        val userMod = File(versionDir, "mods/my-mod.jar").apply { parentFile.mkdirs(); writeBytes(ByteArray(16)) }
        Uninstaller.perform(UninstallContext(StandardLauncherTarget, gameDir, "26.3-OML") {})

        assertFalse(jsonFile.exists(), "the version JSON must be gone")
        assertFalse(stub.exists(), "the version stub jar must be gone")
        assertFalse(File(nativesDir, bareName).exists(), "the bare native copy must be gone")
        assertFalse(File(gameDir, "libraries/org/ohmyloader").exists(), "the OML Maven branch must be pruned")
        assertTrue(userMod.isFile, "the isolated mods dir is user data and must survive")
        assertTrue(File(gameDir, "options.txt").isFile, "user content in the game dir must survive")
    }

    // ------------------------------------------------------------------------------------------
    // Prism: patches/<uid>.json + flat local libraries + mmc-pack registration
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a prism install lays out the component model and uninstalls cleanly`() {
        val instance = tempDir()
        File(instance, "instance.cfg").writeText("instanceType=OneSix\n")
        File(instance, "mmc-pack.json").writeText("""{ "components": [ { "uid": "net.minecraft", "version": "26.3" } ] }""")
        File(instance, "minecraft/saves/world").mkdirs()
        val levelDat = File(instance, "minecraft/saves/world/level.dat").apply { writeBytes(ByteArray(8)) }

        val sources = tempDir()
        val layer = listOf("oml-core", "oml-launcher", "oml-adapter-26_3").map { fakeJar(sources, "$it.jar", fill = 7) }
        val natives = nativePackages(sources)
        val ctx = InstallContext(
            target = SupportedVersion("26.3", 27, "oml-adapter-26_3"),
            targetDir = instance,
            installId = "26.3-OML",
            isolation = true,
            acceptEula = true,
            allowSharedMods = false,
            artifacts = DirectoryArtifactSource("26.3", layer, null, nativeJarFiles = natives),
            side = "client",
            addPrismComponent = true,
            log = {},
        )

        Installer.performInstall(ctx, PrismComponentTarget, ProgressSink.NOP)

        // the flat local library shape Prism resolves (not the Maven tree)
        assertFalse(File(instance, "libraries/org").exists(), "Prism must not get a Maven tree")
        val patchFile = File(instance, "patches/${PrismComponentTarget.COMPONENT_UID}.json")
        assertTrue(patchFile.isFile, "the component patch must exist")
        val patch = json.parseToJsonElement(patchFile.readText()).jsonObject
        val declaredFlatNames = patch["libraries"]!!.jsonArray.map { it.jsonObject }
        for (artifact in layer) {
            val base = artifact.name.removeSuffix(".jar")
            val flat = "$base-26.3-OML.jar"
            val written = File(instance, "libraries/$flat")
            assertTrue(written.isFile, "$flat must land flat under libraries/")
            assertEquals(sha1Of(artifact), sha1Of(written), "$flat must carry the source bytes")
            val entry = declaredFlatNames.first { it["name"]!!.jsonPrimitive.content == "org.ohmyloader:$base:26.3-OML" }
            assertEquals("local", entry["MMC-hint"]!!.jsonPrimitive.content)
        }

        // the natives entry declares every platform and points at the flat jars
        val nativesEntry = declaredFlatNames.first { it["natives"] != null }
        val declaredPlatforms = nativesEntry["natives"]!!.jsonObject.keys
        assertEquals(listOf("windows", "linux", "osx", "windows-arm64", "linux-arm64", "osx-arm64").sorted(), declaredPlatforms.sorted())
        for (os in declaredPlatforms) {
            val classifier = nativesEntry["natives"]!!.jsonObject[os]!!.jsonPrimitive.content
            assertTrue(
                File(instance, "libraries/oml-native-0.1.0-SNAPSHOT-$classifier.jar").isFile,
                "the classified natives jar must exist flat: $classifier",
            )
        }
        val jvmArgs = patch["+jvmArgs"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(jvmArgs, "-Doml.side=client")
        assertTrue(jvmArgs.any { it.startsWith("-Doml.mods.dir=") })
        assertEquals(27, patch["compatibleJavaMajors"]!!.jsonArray[0].jsonPrimitive.content.toInt())

        // the component is registered in Prism's own pack, next to the existing entries
        val pack = json.parseToJsonElement(File(instance, "mmc-pack.json").readText()).jsonObject
        val uids = pack["components"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content }
        assertTrue(PrismComponentTarget.COMPONENT_UID in uids)
        assertTrue("net.minecraft" in uids, "Prism's own components stay")

        // the game dir and its user data survive the uninstall; the OML artifacts go
        Uninstaller.perform(UninstallContext(PrismComponentTarget, instance, "26.3-OML") {})

        assertFalse(patchFile.exists(), "the component patch must be gone")
        for (artifact in layer) {
            assertFalse(File(instance, "libraries/${artifact.name.removeSuffix(".jar")}-26.3-OML.jar").exists())
        }
        assertFalse(File(instance, "libraries/oml-native-0.1.0-SNAPSHOT-natives-windows.jar").exists())
        assertFalse(File(instance, "mmc-pack.json.bak").exists(), "the install-time backup is ours and goes")
        val packAfter = json.parseToJsonElement(File(instance, "mmc-pack.json").readText()).jsonObject
        val uidsAfter = packAfter["components"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content }
        assertFalse(PrismComponentTarget.COMPONENT_UID in uidsAfter)
        assertTrue("net.minecraft" in uidsAfter)
        assertTrue(levelDat.isFile, "the instance's user data must survive")
    }
}
