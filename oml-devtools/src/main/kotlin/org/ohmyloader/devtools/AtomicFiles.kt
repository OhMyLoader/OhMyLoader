package org.ohmyloader.devtools

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The one landing zone for every artifact write in devtools and the installer: bytes go through a
 * `<name>.tmp` file and move into place atomically, so a killed process never leaves a truncated
 * file that the next run accepts as present.
 */
object AtomicFiles {

    /** Where the cross-filesystem downgrade (see [moveIntoPlace]) is reported. */
    @Volatile
    var report: (String) -> Unit = System.err::println

    /** Lowercase hex of a completed digest. */
    fun hex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

    /**
     * SHA-1 of a file, lowercase hex. Streamed rather than read into memory: the callers include a
     * ~26 MB game jar, and a checksum must be cheap enough that nobody is tempted to skip it.
     */
    fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        DigestInputStream(file.inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (input.read(buffer) >= 0) {
                // DigestInputStream does the work on read
            }
        }
        return hex(digest)
    }

    /** SHA-1 of bytes already in memory, same hex form as [sha1] — the two must never disagree. */
    fun sha1(bytes: ByteArray): String = hex(
        MessageDigest.getInstance("SHA-1").also { it.update(bytes) },
    )

    /** Writes [bytes] through a `.tmp` file, so an interrupted write cannot leave a half file behind. */
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        moveIntoPlace(tmp, target)
    }

    /**
     * Moves a fully written `.tmp` file onto [target].
     *
     * `ATOMIC_MOVE` is the guarantee the caller wants: a reader sees the old file or the new one, never
     * a partial one. Across filesystems (a `.tmp` on a different mount, a network share, a container
     * bind mount) the JDK cannot rename — this is the one place the guarantee has to be **downgraded
     * rather than assumed**. Only the move itself is retried without it; the bytes were already
     * verified by the caller, so a non-atomic move still lands a complete file.
     */
    fun moveIntoPlace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // cross-device: REPORT the downgrade (never silently), then finish the move
            report("原子替换不可用（跨文件系统），退化为普通移动：${target.absolutePath}")
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
