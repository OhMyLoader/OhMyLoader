package org.ohmyloader.installer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/** Everything [Uninstaller] needs: which shape, which directory, which install id. */
class UninstallContext(
    /** Which install shape to remove — the removal logic differs exactly where the install logic does. */
    val target: InstallationTarget,
    /** Game directory (standard), Prism instance directory, or server directory. */
    val dir: File,
    /** The install id the OML layer was installed under. */
    val installId: String,
    val log: (String) -> Unit = {},
)

/**
 * Removes what [Installer] wrote (T-1.6), leaving everything else — worlds, mods, settings, the
 * game itself — untouched.
 *
 * The removal lists are **read back from the install's own artifacts** wherever possible (the
 * launcher version JSON's `libraries` array, the Prism component patch's `libraries` array) rather
 * than re-derived from the current layer contents: the JSON on disk is what THIS install actually
 * wrote, including for an installer version older or newer than the one running the uninstall.
 * Where no artifact records a write (the dedicated server's fixed layout), the removal list is the
 * deterministic file set the install produces — `lib/` is ours alone, while `libraries/`, `cache/`,
 * `minecraft/` and everything the server generated at runtime stays.
 *
 * Nothing here removes user data: directories are only ever wiped when the install created and
 * filled them outright (`lib/`), and `File.delete()` on a non-empty directory is the boundary that
 * keeps anything else from going.
 */
object Uninstaller {

    private val json = Json { ignoreUnknownKeys = true }

    fun perform(ctx: UninstallContext): String = when (ctx.target) {
        StandardLauncherTarget -> uninstallStandard(ctx)
        PrismComponentTarget -> uninstallPrism(ctx)
        DedicatedServerTarget -> uninstallServer(ctx)
        else -> throw InstallationException("no uninstall procedure for ${ctx.target.id}")
    }

    // ---------------------------------------------------------------------------------------------------
    // Standard launcher: versions/<id>/<id>.json is the manifest of what went in
    // ---------------------------------------------------------------------------------------------------

    private fun uninstallStandard(ctx: UninstallContext): String {
        val gameDir = ctx.dir
        val versionDir = File(gameDir, "versions/${ctx.installId}")
        val versionJson = File(versionDir, "${ctx.installId}.json")
        if (!versionJson.isFile) {
            throw InstallationException(Messages.t("uninstall.notInstalled", versionJson.absolutePath))
        }
        val root = try {
            json.parseToJsonElement(versionJson.readText(Charsets.UTF_8)).jsonObject
        } catch (t: Throwable) {
            throw InstallationException(
                Messages.t("uninstall.badJson", versionJson.absolutePath, versionDir.absolutePath), t
            )
        }

        // The mods directory travels in the JSON's -Doml.mods.dir (pinned there by the install):
        // when it sits inside the version dir (isolation), it is user data and must survive.
        val modsDir = root["arguments"]?.jsonObject?.get("jvm")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.firstOrNull { it.startsWith("-Doml.mods.dir=") }
            ?.removePrefix("-Doml.mods.dir=")
            ?.let(::File)

        // Every library entry in this JSON was written by the install (the Maven tree under
        // org/ohmyloader keyed by the install id): declared paths are the removal list, including
        // the classified oml-native jars under downloads.classifiers.
        val declaredPaths = mutableListOf<String>()
        root["libraries"]?.jsonArray?.forEach { entry ->
            val downloads = (entry as? JsonObject)?.get("downloads")?.jsonObject ?: return@forEach
            downloads["artifact"]?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull?.let(declaredPaths::add)
            downloads["classifiers"]?.jsonObject?.values?.forEach { classifier ->
                classifier.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.let(declaredPaths::add)
            }
        }
        val librariesRoot = File(gameDir, "libraries")
        declaredPaths.forEach { relative ->
            val file = File(librariesRoot, relative)
            if (file.isFile && file.delete()) ctx.log("removed libraries/$relative")
            pruneEmptyParents(file.parentFile, stop = librariesRoot, log = ctx.log)
        }

        // The version directory is ours except for the isolated mods dir. The kept-dir check is a
        // path-prefix check against the recorded mods dir, so it covers the mods dir itself and
        // any (theoretical) ancestor level of it inside the version dir.
        versionDir.listFiles()?.forEach { child ->
            if (modsDir != null && modsDir.absoluteFile.path.startsWith(child.absoluteFile.path)) {
                ctx.log(Messages.t("uninstall.modsKept", child.absolutePath))
                return@forEach
            }
            if (child.deleteRecursively()) ctx.log("removed versions/${ctx.installId}/${child.name}")
        }
        // succeeds only when nothing (including kept user data) is left behind
        versionDir.delete()
        return Messages.t("hint.uninstall", ctx.installId)
    }

    // ---------------------------------------------------------------------------------------------------
    // Prism: patches/<uid>.json is the manifest of what went in
    // ---------------------------------------------------------------------------------------------------

    private fun uninstallPrism(ctx: UninstallContext): String {
        val instance = PrismComponentTarget.instanceRootOf(ctx.dir)
        val patchFile = File(instance, "patches/${PrismComponentTarget.COMPONENT_UID}.json")
        if (!patchFile.isFile) {
            throw InstallationException(Messages.t("uninstall.notInstalled", patchFile.absolutePath))
        }
        val root = try {
            json.parseToJsonElement(patchFile.readText(Charsets.UTF_8)).jsonObject
        } catch (t: Throwable) {
            throw InstallationException(
                Messages.t("uninstall.badJson", patchFile.absolutePath, instance.absolutePath), t
            )
        }

        // `MMC-hint: local` entries resolve by flat file name under <instance>/libraries — the
        // same derivation the install used when it wrote them, here run backwards. An entry with a
        // `natives` block additionally wrote one jar per declared classifier.
        val librariesDir = File(instance, "libraries")
        root["libraries"]?.jsonArray?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val parts = name.split(":")
            if (parts.size < 3) return@forEach
            val artifact = parts[1]
            val version = parts[2]
            val flats = buildList {
                add("$artifact-$version.jar")
                obj["natives"]?.jsonObject?.values?.forEach { classifier ->
                    classifier.jsonPrimitive.contentOrNull?.let { add("$artifact-$version-$it.jar") }
                }
            }
            flats.forEach { flat ->
                val file = File(librariesDir, flat)
                if (file.isFile && file.delete()) ctx.log("removed libraries/$flat")
            }
        }

        if (patchFile.delete()) ctx.log("removed patches/${patchFile.name}")

        removeComponentFromPack(instance, ctx)
        // the backup the install made when it first edited mmc-pack.json — ours, no longer needed
        val bak = File(instance, "mmc-pack.json.bak")
        if (bak.isFile && bak.delete()) ctx.log("removed mmc-pack.json.bak")

        ctx.log(Messages.t("uninstall.userKept", File(instance, "minecraft").absolutePath))
        return Messages.t("hint.uninstall", ctx.installId)
    }

    /** Removes the OhMyLoader component from mmc-pack.json when present; the file is Prism's, only our entry goes. */
    private fun removeComponentFromPack(instance: File, ctx: UninstallContext) {
        val pack = File(instance, "mmc-pack.json")
        if (!pack.isFile) return
        val root = runCatching { json.parseToJsonElement(pack.readText(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return
        val components = root["components"]?.jsonArray ?: return
        val uid = PrismComponentTarget.COMPONENT_UID
        val kept = components.filter {
            (it as? JsonObject)?.get("uid")?.jsonPrimitive?.contentOrNull != uid
        }
        if (kept.size == components.size) {
            ctx.log(Messages.t("uninstall.componentNotInPack", uid))
            return
        }
        val updated = JsonObject(root.entries.associate { it.toPair() } + ("components" to JsonArray(kept)))
        val pretty = Json { prettyPrint = true }
        runCatching {
            Files.writeString(
                pack.toPath(),
                pretty.encodeToString(JsonElement.serializer(), updated) + "\n",
            )
        }.onSuccess { ctx.log(Messages.t("uninstall.componentRemoved", uid)) }
    }

    // ---------------------------------------------------------------------------------------------------
    // Dedicated server: the install owns lib/, the shell, the config and the scripts; everything the
    // server generated at runtime (worlds, cache, vanilla libraries) stays
    // ---------------------------------------------------------------------------------------------------

    private fun uninstallServer(ctx: UninstallContext): String {
        val base = ctx.dir
        val launchProperties = File(base, "launch.properties")
        if (!launchProperties.isFile) {
            throw InstallationException(Messages.t("uninstall.notInstalled", launchProperties.absolutePath))
        }

        // lib/ is ours alone — every jar in it was put there by an OML install (see the install path)
        val lib = File(base, "lib")
        if (lib.isDirectory) {
            lib.deleteRecursively()
            ctx.log("removed lib/")
        }
        // the bare oml-native library lives among the vanilla-extracted natives: take only ours
        listOf("oml-native.dll", "oml-native.so", "oml-native.dylib").forEach { name ->
            val file = File(base, "natives/$name")
            if (file.isFile && file.delete()) ctx.log("removed natives/$name")
        }
        listOf("oml-launcher.jar", "launch.properties", "run.sh", "run.bat").forEach { name ->
            val file = File(base, name)
            if (file.isFile && file.delete()) ctx.log("removed $name")
        }

        // server-generated or user-owned: worlds, mods, server.properties, the downloaded game,
        // the vanilla library tree, the runtime cache, the EULA the owner accepted
        ctx.log(Messages.t("uninstall.userKept", "mods/, world/, libraries/, cache/, minecraft/, eula.txt"))
        return Messages.t("hint.uninstall", ctx.installId)
    }

    /** Removes [dir] and then its parents, stopping at [stop] — a library jar's Maven tree branches die with it. */
    private fun pruneEmptyParents(dir: File?, stop: File, log: (String) -> Unit) {
        var cursor = dir ?: return
        val stopAbs = stop.absoluteFile
        while (cursor.absoluteFile != stopAbs && cursor.isDirectory && cursor.listFiles()?.isEmpty() == true) {
            val parent = cursor.parentFile ?: return
            if (cursor.delete()) log("removed empty ${cursor.name}/")
            cursor = parent
        }
    }
}
