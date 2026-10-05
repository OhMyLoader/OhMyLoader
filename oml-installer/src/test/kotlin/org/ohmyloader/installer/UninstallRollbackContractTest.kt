package org.ohmyloader.installer

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * T-1.6 contract: an install that fails leaves no half-finished tree, and an uninstall removes
 * exactly what the install wrote — never user data. All offline, like the rest of this module's
 * contract tests.
 */
class UninstallRollbackContractTest {

    private fun tempDir(): File = Files.createTempDirectory("oml-test").toFile()

    private fun writeJar(file: File, size: Int = 32) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size) { (it * 3).toByte() })
    }

    // ------------------------------------------------------------------------------------------
    // Rollback — the journal's three records behave as documented
    // ------------------------------------------------------------------------------------------

    @Test
    fun `rollback removes created files and restores overwritten ones`() {
        val dir = tempDir()
        val preExisting = File(dir, "cfg.properties").apply { writeText("original") }
        val created = File(dir, "lib/oml-core.jar")
        val journal = InstallJournal()

        writeAtomic(created, journal) { ByteArrayInputStream(ByteArray(16)) }
        writeAtomicText(preExisting, "changed", journal)

        assertTrue(created.isFile)
        assertEquals("changed", preExisting.readText())

        val lines = journal.rollback()
        assertFalse(created.exists(), "a file the install created must be gone after rollback")
        assertEquals("original", preExisting.readText(), "an overwritten file must be restored byte-for-byte")
        assertTrue(lines.isNotEmpty(), "the rollback must report what it did")
    }

    @Test
    fun `rollback prunes empty created directories and removes an exclusive download tree`() {
        val dir = tempDir()
        val journal = InstallJournal()

        val nested = journal.ensureDir(File(dir, "versions/my-OML/natives"))
        val downloads = journal.ensureDir(File(dir, "minecraft/26.3"))
        journal.ownTree(downloads)
        writeJar(File(downloads, "server.jar"))

        assertTrue(nested.isDirectory)
        journal.rollback()

        assertFalse(File(dir, "versions").exists(), "empty created directories are pruned bottom-up")
        assertFalse(downloads.exists(), "a tree the install owns is removed with its downloaded content")
    }

    @Test
    fun `ownTree on a pre-existing directory is a no-op — rollback never wipes what it did not create`() {
        val dir = tempDir()
        val preExisting = File(dir, "libraries").also { it.mkdirs() }
        writeJar(File(preExisting, "user-jar.jar"))
        val journal = InstallJournal()

        journal.ownTree(preExisting)
        journal.rollback()

        assertTrue(File(preExisting, "user-jar.jar").isFile, "user content must survive a rollback")
    }

    @Test
    fun `rollback restores a config file that two failed installs overwrote in one run`() {
        val dir = tempDir()
        val pack = File(dir, "mmc-pack.json").apply { writeText("""{"components":[]}""") }
        val journal = InstallJournal()
        // two writes to the same path in one install: only the pre-install content is remembered
        writeAtomicText(pack, """{"components":[{"uid":"x"}]}""", journal)
        writeAtomicText(pack, """{"components":[{"uid":"x"},{"uid":"y"}]}""", journal)

        journal.rollback()
        assertEquals("""{"components":[]}""", pack.readText())
    }

    // ------------------------------------------------------------------------------------------
    // Uninstall — standard launcher: the version JSON is the removal manifest
    // ------------------------------------------------------------------------------------------

    /** A minimal installed tree the way StandardLauncherTarget lays it out. */
    private fun standardGameDir(): File {
        val gameDir = tempDir()
        val versionDir = File(gameDir, "versions/my-OML")
        val modsDir = File(versionDir, "mods").also { it.mkdirs() }
        writeJar(File(modsDir, "my-mod.jar"))
        writeJar(File(versionDir, "my-OML.jar"))
        writeJar(File(versionDir, "natives/oml-native.so"))
        writeJar(File(gameDir, "libraries/org/ohmyloader/oml-core/my-OML/oml-core-my-OML.jar"))
        writeJar(
            File(
                gameDir,
                "libraries/org/ohmyloader/oml-native/0.1.0-SNAPSHOT/oml-native-0.1.0-SNAPSHOT-natives-windows.jar",
            ),
        )
        // vanilla content in the same tree: never ours
        writeJar(File(gameDir, "libraries/com/mojang/brigadier/brigadier-1.0.jar"))
        File(versionDir, "my-OML.json").writeText(
            """
            {
              "id": "my-OML",
              "inheritsFrom": "26.3",
              "mainClass": "org.ohmyloader.launcher.OMLBootstrap",
              "arguments": { "jvm": [
                "-Doml.side=client",
                "-Doml.mods.dir=${modsDir.absolutePath.replace('\\', '/')}"
              ] },
              "libraries": [
                {
                  "name": "org.ohmyloader:oml-core:my-OML",
                  "downloads": { "artifact": {
                    "path": "org/ohmyloader/oml-core/my-OML/oml-core-my-OML.jar",
                    "url": "", "sha1": "", "size": 1
                  } }
                },
                {
                  "name": "org.ohmyloader:oml-native:0.1.0-SNAPSHOT",
                  "downloads": { "classifiers": {
                    "windows": { "path": "org/ohmyloader/oml-native/0.1.0-SNAPSHOT/oml-native-0.1.0-SNAPSHOT-natives-windows.jar",
                                  "url": "", "sha1": "", "size": 1 }
                  } },
                  "natives": { "windows": "natives-windows" }
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return gameDir
    }

    @Test
    fun `uninstall removes the layer, the version dir and the declared libraries, keeps mods and vanilla`() {
        val gameDir = standardGameDir()
        val versionDir = File(gameDir, "versions/my-OML")
        val removed = mutableListOf<String>()

        val hint = Uninstaller.perform(UninstallContext(StandardLauncherTarget, gameDir, "my-OML") { removed += it })

        assertContains(hint, "my-OML")
        assertFalse(
            File(gameDir, "libraries/org/ohmyloader/oml-core").exists(),
            "the layer jar is gone, its empty Maven branch pruned",
        )
        assertFalse(File(gameDir, "libraries/org/ohmyloader/oml-native").exists(), "the classified native jar is gone")
        assertTrue(File(gameDir, "libraries/com/mojang/brigadier/brigadier-1.0.jar").isFile, "vanilla libraries stay")
        assertTrue(File(versionDir, "mods/my-mod.jar").isFile, "the isolated mods dir is user data and survives")
        assertFalse(versionDir.exists() && File(versionDir, "my-OML.json").exists(), "the version JSON is gone")
        assertTrue(removed.any { it.startsWith("removed libraries/org/ohmyloader") })
    }

    @Test
    fun `uninstalling a directory OML never touched is a controlled error`() {
        val empty = tempDir()
        val e = assertFailsWith<InstallationException> {
            Uninstaller.perform(UninstallContext(StandardLauncherTarget, empty, "my-OML"))
        }
        assertContains(e.message!!, "my-OML.json")
    }

    // ------------------------------------------------------------------------------------------
    // Uninstall — Prism: the component patch is the removal manifest
    // ------------------------------------------------------------------------------------------

    private fun prismInstance(): File {
        val instance = tempDir()
        File(instance, "minecraft/saves/world").mkdirs()
        File(instance, "minecraft/saves/world/level.dat").writeBytes(ByteArray(8))
        writeJar(File(instance, "libraries/oml-core-my-OML.jar"))
        writeJar(File(instance, "libraries/oml-native-0.1.0-SNAPSHOT-natives-windows.jar"))
        File(instance, "patches").mkdirs()
        File(instance, "mmc-pack.json").writeText(
            """
            { "components": [
                { "uid": "org.ohmyloader", "version": "my-OML" },
                { "uid": "net.minecraft", "version": "26.3" }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(instance, "mmc-pack.json.bak").writeText("""{ "components": [] }""", Charsets.UTF_8)
        File(instance, "patches/org.ohmyloader.json").writeText(
            """
            {
              "formatVersion": 1,
              "name": "OhMyLoader",
              "uid": "org.ohmyloader",
              "version": "my-OML",
              "mainClass": "org.ohmyloader.launcher.OMLBootstrap",
              "libraries": [
                { "name": "org.ohmyloader:oml-core:my-OML", "MMC-hint": "local" },
                { "name": "org.ohmyloader:oml-native:0.1.0-SNAPSHOT", "MMC-hint": "local",
                  "natives": { "windows": "natives-windows" } }
              ],
              "+jvmArgs": [ "-Doml.side=client" ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return instance
    }

    @Test
    fun `uninstall removes the patch, the local jars and our component entry, keeps the instance`() {
        val instance = prismInstance()

        Uninstaller.perform(UninstallContext(PrismComponentTarget, instance, "my-OML"))

        assertFalse(File(instance, "patches/org.ohmyloader.json").exists(), "the component patch is gone")
        assertFalse(File(instance, "libraries/oml-core-my-OML.jar").exists(), "local layer jars are gone")
        assertFalse(
            File(instance, "libraries/oml-native-0.1.0-SNAPSHOT-natives-windows.jar").exists(),
            "the classified native jar is gone",
        )
        assertFalse(
            File(instance, "mmc-pack.json.bak").exists(),
            "the install-time backup is ours and no longer needed",
        )
        assertTrue(File(instance, "minecraft/saves/world/level.dat").isFile, "the instance's user data stays")

        val pack = File(instance, "mmc-pack.json").readText()
        assertFalse(pack.contains("org.ohmyloader"), "our component entry is removed from mmc-pack.json")
        assertTrue(pack.contains("net.minecraft"), "Prism's own components stay")
    }

    // ------------------------------------------------------------------------------------------
    // Uninstall — dedicated server: the fixed layout is the removal manifest
    // ------------------------------------------------------------------------------------------

    @Test
    fun `uninstall removes the shell, lib and scripts, keeps worlds and everything the server generated`() {
        val base = tempDir()
        File(base, "lib").mkdirs()
        File(base, "lib/oml-core.jar").writeBytes(ByteArray(8))
        File(base, "natives").mkdirs()
        File(base, "natives/oml-native.so").writeBytes(ByteArray(8))
        File(base, "natives/lwjgl.so").writeBytes(ByteArray(8)) // vanilla-extracted: stays
        File(base, "oml-launcher.jar").writeBytes(ByteArray(8))
        File(base, "launch.properties").writeText("side=server\n", Charsets.UTF_8)
        File(base, "run.sh").writeText("#!/bin/sh\n", Charsets.UTF_8)
        File(base, "run.bat").writeText("@echo off\r\n", Charsets.UTF_8)
        File(base, "eula.txt").writeText("eula=true\n", Charsets.UTF_8)
        File(base, "world/region").mkdirs()
        File(base, "world/region/r.0.0.mca").writeBytes(ByteArray(8))

        Uninstaller.perform(UninstallContext(DedicatedServerTarget, base, "server"))

        assertFalse(File(base, "lib").exists(), "lib/ is ours alone")
        assertFalse(File(base, "oml-launcher.jar").exists())
        assertFalse(File(base, "launch.properties").exists())
        assertFalse(File(base, "run.sh").exists())
        assertFalse(File(base, "run.bat").exists())
        assertFalse(File(base, "natives/oml-native.so").exists(), "only our bare library is removed")
        assertTrue(File(base, "natives/lwjgl.so").isFile, "vanilla natives stay")
        assertTrue(File(base, "eula.txt").isFile, "the EULA the owner accepted stays")
        assertTrue(File(base, "world/region/r.0.0.mca").isFile, "world data stays")
    }

    @Test
    fun `a server uninstall without the launch marker is a controlled error`() {
        val empty = tempDir()
        val e = assertFailsWith<InstallationException> {
            Uninstaller.perform(UninstallContext(DedicatedServerTarget, empty, "server"))
        }
        assertContains(e.message!!, "launch.properties")
    }

    // ------------------------------------------------------------------------------------------
    // Class-init regression: the first touch of a single target object used to leave a null in
    // InstallationTarget.ALL (the interface <clinit> runs while that object's own <clinit> is
    // still in progress), and the first byId() call then NPE'd — `java -jar ... --target server`
    // reproduced it. These uninstall tests are exactly the "touch one target directly" shape.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `first touching a single target object does not leave a null in the target registry`() {
        DedicatedServerTarget.id // the partial-order first touch that used to poison ALL
        listOf("standard", "prism", "server").forEach { id ->
            assertNotNull(InstallationTarget.byId(id), "byId($id) must resolve after a partial-order first touch")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Class-init regression guard ends
    // ------------------------------------------------------------------------------------------

    // ------------------------------------------------------------------------------------------
    // CLI surface: --uninstall parses without --version when the id is explicit
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the uninstall flag parses with target, dir and id and without a version`() {
        val options = Installer.Options.parse(
            arrayOf("--uninstall", "--target", "standard", "--dir", ".", "--id", "my-OML"),
        )
        assertTrue(options.uninstall)
        assertEquals("", options.version, "a real install would fail without --version; uninstall does not")
    }

    @Test
    fun `an uninstall without a derivable install id is refused`() {
        assertFailsWith<InstallationException> {
            Installer.Options.parse(arrayOf("--uninstall", "--target", "standard", "--dir", "."))
        }
    }
}
