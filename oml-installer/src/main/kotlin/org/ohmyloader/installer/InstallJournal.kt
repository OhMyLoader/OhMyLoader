package org.ohmyloader.installer

import java.io.File

/**
 * Records every path an install touches so a failed install can undo itself: overwritten files are
 * restored from an in-memory snapshot taken on their first write, created files are deleted, created
 * directories pruned bottom-up.
 *
 * In-memory snapshots only suit small config files; [recordDownload] covers the 40 MB game jar.
 * `File.delete()` refusing non-empty directories is the wanted safety property — a directory that
 * gained user content between creation and rollback survives, and [ownTree] is the deliberate
 * exception. Recording is idempotent per path; rollback reports failures, never throws.
 */
class InstallJournal {

    private val backups = LinkedHashMap<File, ByteArray>()
    private val created = LinkedHashSet<File>()
    private val createdDirs = LinkedHashSet<File>()
    private val exclusiveTrees = LinkedHashSet<File>()

    /** Snapshots [target]'s current content before its first write this install. */
    fun recordWrite(target: File) {
        val abs = target.absoluteFile
        if (abs in created || backups.containsKey(abs)) return
        if (abs.isFile) backups[abs] = abs.readBytes() else created += abs
    }

    /**
     * Marks a downloaded file. A file that did not exist before is removed on rollback; a file that
     * did exist keeps the new (complete, atomically moved) download — restoring a 40 MB game jar
     * byte-for-byte would mean snapshotting it in memory, and the old download is not worth that.
     */
    fun recordDownload(target: File) {
        val abs = target.absoluteFile
        if (!abs.isFile) created += abs
    }

    /** `mkdirs` with bookkeeping: every level that did not exist before is recorded. */
    fun ensureDir(dir: File): File {
        val missing = ArrayList<File>()
        var cursor: File? = dir.absoluteFile
        while (cursor != null && !cursor.exists()) {
            missing += cursor
            cursor = cursor.parentFile
        }
        dir.mkdirs()
        missing.asReversed().forEach { if (it.isDirectory) createdDirs += it }
        return dir
    }

    /**
     * Marks [dir] as filled exclusively by this install (bulk game downloads), so rollback may
     * remove it with its content instead of pruning it empty-first. A no-op for a directory this
     * install did not create: a pre-existing `libraries/` tree is not ours to wipe.
     */
    fun ownTree(dir: File) {
        val abs = dir.absoluteFile
        if (abs in createdDirs) exclusiveTrees += abs
    }

    /** Undoes everything recorded; returns localized report lines for the caller's log. */
    fun rollback(): List<String> {
        val lines = mutableListOf<String>()
        var removed = 0
        var restored = 0
        var failed = 0

        for (tree in exclusiveTrees.sortedByDescending { it.absolutePath.length }) {
            if (tree.isDirectory && tree.deleteRecursively()) removed++ else failed++
        }
        for (path in created) {
            if (!path.exists()) continue
            if (path.isFile && path.delete()) removed++ else failed++
        }
        for ([path, previous] in backups) {
            try {
                path.writeBytes(previous)
                restored++
            } catch (t: Throwable) {
                failed++
                lines += Messages.t("log.rollbackRestoreFailed", path.absolutePath, t.message ?: t.toString())
            }
        }
        // bottom-up; File.delete() refuses non-empty directories, so user content survives
        for (dir in createdDirs.sortedByDescending { it.absolutePath.length }) {
            if (dir.exists() && !dir.delete()) {
                // non-empty (or locked): the empty children were still pruned, the rest stays
            }
        }

        if (removed > 0) lines += Messages.t("log.rollbackRemoved", removed)
        if (restored > 0) lines += Messages.t("log.rollbackRestored", restored)
        if (failed > 0) lines += Messages.t("log.rollbackFailed", failed)
        return lines
    }
}
