package org.ohmyloader.core.compression

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming codecs over [OmlNativeZstd]'s one-shot surface — the pair the region-file `RegionFileVersion.StreamWrapper` proxies hand to
 * vanilla (write side / read side of a chunk). Buffering is the honest implementation here: the C surface is deliberately five one-shot
 * functions, and a region chunk is bounded by the sector size (~2 MiB), so the write side buffers into a `ByteArrayOutputStream` and
 * emits one whole-payload frame on `close()` (one memcpy, invisible next to the compression itself), while the read side lifts the
 * exact-length slice and inflates once — no state machine, no partial-frame edge cases, no C-side context plumbing.
 * The read side also handles frames without a declared content size (written by the older *streaming* implementation): it consults
 * `oml_zstd_get_frame_content_size` and, when the answer is "unknown", delegates to [OmlNativeZstd.decompressGrowing]'s bounded growth
 * loop — old worlds stay readable.
 */
class OmlZstdOutputStream(
    private val target: OutputStream,
    private val level: Int,
) : OutputStream() {

    private val buffer = ByteArrayOutputStream()
    private var closed = false

    private fun checkOpen() {
        if (closed) throw IOException("OmlZstdOutputStream is closed")
    }

    override fun write(b: Int) {
        checkOpen()
        buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkOpen()
        buffer.write(b, off, len)
    }

    /** Nothing passes through until close — the whole payload becomes one frame. */
    override fun flush() {}

    override fun close() {
        if (closed) return
        closed = true
        target.use { target ->
            val plain = buffer.toByteArray()
            val dst = ByteArray(OmlNativeZstd.bound(plain.size.toLong()).toInt())
            val n = OmlNativeZstd.compress(dst, plain, level)
            target.write(dst, 0, n)
        }
    }
}

/**
 * The read-side counterpart: lifts the compressed frame from [source] on first demand, inflates
 * it once, and serves reads out of the inflated bytes. Closing closes the source, mirroring the
 * zstd-ffm streams it replaces.
 */
class OmlZstdInputStream(
    private val source: InputStream,
) : InputStream() {

    private var inflated: ByteArray? = null
    private var pos = 0
    private var end = 0
    private var lifted = false

    private fun ensureInflated() {
        if (lifted) return
        lifted = true
        try {
            val compressed = source.readBytes()
            inflated = if (compressed.isEmpty()) ByteArray(0) else OmlNativeZstd.decompressGrowing(compressed)
        } catch (t: Throwable) {
            throw IOException("failed to inflate zstd chunk payload", t)
        }
        end = inflated?.size ?: 0
    }

    override fun read(): Int {
        ensureInflated()
        val buf = inflated ?: return -1
        if (pos >= end) return -1
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureInflated()
        val buf = inflated ?: return -1
        // A zero-length request is answered with 0 *before* the EOF test: the InputStream
        // contract makes it unconditional, and a caller that probes with an empty buffer at
        // the end of the chunk would otherwise read the -1 as "premature end of data".
        if (len == 0) return 0
        if (pos >= end) return -1
        val n = minOf(len, end - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int {
        ensureInflated()
        return end - pos
    }

    override fun close() {
        source.close()
    }
}
