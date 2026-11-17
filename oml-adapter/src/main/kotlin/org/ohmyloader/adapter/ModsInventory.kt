package org.ohmyloader.adapter

import org.ohmyloader.api.OmlLog
import org.ohmyloader.core.OMLCore
import java.awt.Desktop
import java.io.File

/**
 * What the Mods screen lists and what "did anything change?" means, kept away from widgets so the
 * facts can be read (and logged) without a screen.
 *
 * <p>The rows are exactly the two things the loader loads: the code mods the registry knows
 * ([OMLCore.initOrder]) and the `.oml` declaration packs found under the mods directory. Their
 * names, ids and versions come from the same objects the runtime used, not from re-reading the
 * jars — a row can never claim something the session did not load.
 *
 * <p>A missing-vs-present difference is read off a baseline stamp of the directory taken at game
 * ready ([captureBaseline]): path, size and mtime per mod file. The comparison is deliberately
 * cheap — a stat per file, no hashing and no jar opens — because it runs on every screen open.
 */
internal object ModsInventory {

    enum class Kind(val badge: String) {
        CODE("jar"),
        PACK("oml"),
    }

    data class Entry(
        val kind: Kind,
        val name: String,
        val id: String,
        val version: String,
        /** Where it lives: the jar's file name, or the pack's path relative to `mods/`. */
        val origin: String,
        val file: File?,
        /** `config/<id>.toml`, whether or not it exists; the button checks. */
        val config: File?,
    )

    private data class Stamp(val length: Long, val modified: Long)

    /** The directory as the previous session started, or as this one did. */
    @Volatile
    private var baseline: Map<String, Stamp> = emptyMap()

    fun modsDirectory(): File? = OMLCore.modsDirectory()

    fun captureBaseline() {
        baseline = stamps(modsDirectory())
    }

    /** Every loaded code mod plus every declaration pack on disk, in a stable order. */
    fun entries(): List<Entry> {
        val dir = modsDirectory()
        val configDir = runCatching { OMLCore.configDirectory() }.getOrNull()
        val code = OMLCore.initOrder.distinctBy { it.id }.map { mod ->
            Entry(
                kind = Kind.CODE,
                name = mod.name,
                id = mod.id,
                version = mod.version,
                origin = dir?.let { runCatching { mod.file.relativeTo(it).path }.getOrNull() } ?: mod.file.name,
                file = mod.file,
                config = configDir?.let { File(it, "${mod.id.lowercase()}.toml") },
            )
        }
        val packs = modFiles(dir, ".oml").map { pack ->
            // A pack's namespace is its file name (OMLCore's rule), so that is its id.
            val namespace = pack.name.substringBeforeLast('.').lowercase()
            Entry(
                kind = Kind.PACK,
                name = pack.name,
                id = namespace,
                version = "—",
                origin = dir?.let { runCatching { pack.relativeTo(it).path }.getOrNull() } ?: pack.name,
                file = pack,
                config = configDir?.let { File(it, "$namespace.toml") },
            )
        }
        return code + packs
    }

    /**
     * The files that differ from the baseline, as `path (added|removed|modified)`, sorted. Empty
     * means "nothing a reload would pick up has changed".
     */
    fun changes(): List<String> {
        val known = baseline
        val current = stamps(modsDirectory())
        if (known.isEmpty() && current.isEmpty()) return emptyList()
        val touched = sortedSetOf<String>()
        for ((path, stamp) in current) {
            val before = known[path]
            if (before == null) {
                touched += "$path (added)"
            } else if (before != stamp) {
                touched += "$path (modified)"
            }
        }
        for (path in known.keys) {
            if (path !in current) touched += "$path (removed)"
        }
        return touched.toList()
    }

    /**
     * Opens a file, or its folder when the desktop cannot launch the file type.
     *
     * <p>The platform's own launcher is used first (`explorer` / `open` / `xdg-open`): AWT's
     * {@code Desktop} reports "not supported" in this game's environment (no AWT toolkit is ever
     * initialized here), which is exactly the case the Mods screen must not fail on. AWT stays as
     * the fallback for desktops whose launcher command is missing.
     */
    fun open(file: File): Boolean {
        // A directory is opened itself; a file with its default app; a path that does not exist
        // opens the folder that should contain it. (The old "file or its parent" rule turned
        // "open mods/" into "open the game directory", which is the bug this spelling fixes.)
        val target = when {
            file.isDirectory -> file
            file.isFile -> file
            else -> file.parentFile ?: file
        }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("explorer.exe", target.path)
            os.contains("mac") -> listOf("open", target.path)
            else -> listOf("xdg-open", target.path)
        }
        val launched = runCatching { ProcessBuilder(command).start() }
            .onFailure { OmlLog.warn("Mods", "launching ${command.first()} failed: $it") }
            .isSuccess
        if (launched) return true
        return runCatching {
            if (!Desktop.isDesktopSupported()) {
                OmlLog.warn("Mods", "no desktop integration is available; cannot open ${file.path}")
                return@runCatching false
            }
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                OmlLog.warn("Mods", "opening files is not supported on this desktop; cannot open ${file.path}")
                return@runCatching false
            }
            desktop.open(target)
            true
        }.onFailure { OmlLog.warn("Mods", "opening ${file.path} failed: $it") }.getOrDefault(false)
    }

    private fun stamps(root: File?): Map<String, Stamp> {
        val dir = root ?: return emptyMap()
        return modFiles(dir, ".jar", ".oml").associate { file ->
            val relative = runCatching { file.relativeTo(dir).path.replace('\\', '/') }.getOrNull() ?: file.name
            relative to Stamp(file.length(), file.lastModified())
        }
    }

    /**
     * The mod files under [root], recursively, with the loader's own rules: dot-directories are an
     * off switch and are not entered, and the result is sorted so two runs compare the same list.
     */
    private fun modFiles(root: File?, vararg suffixes: String): List<File> {
        if (root == null || !root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { file -> file.isFile && suffixes.any { file.name.endsWith(it, ignoreCase = true) } }
            .sortedBy { it.path.lowercase() }
            .toList()
    }
}
