package org.ohmyloader.core.mod

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Recursive listing for the mods directory. Dot-directories are not entered (a `.disabled/` folder is
 * an off switch); symlinked directories are not followed, while symlinked files are accepted. The
 * result is ordered by relative path, so discovery does not depend on filesystem iteration order.
 */
internal object ModsDirectory {

    fun files(root: File, suffix: String, ignoreCase: Boolean = false): List<File> {
        if (!root.isDirectory) return emptyList()
        val rootPath = runCatching { root.toPath().toRealPath() }.getOrElse { root.toPath() }
        val found = ArrayList<File>()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != rootPath && dir.fileName?.toString()?.startsWith(".") == true) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isRegularFile(file) && file.fileName.toString().endsWith(suffix, ignoreCase)) {
                    found += file.toFile()
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
        return found.sortedWith(
            compareBy<File> { relative(rootPath, it).lowercase() }
                .thenBy { relative(rootPath, it) },
        )
    }

    private fun relative(root: Path, file: File): String =
        root.relativize(file.toPath()).toString().replace('\\', '/')
}
