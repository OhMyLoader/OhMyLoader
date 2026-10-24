package org.ohmyloader.devtools

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transfer loop's own contract, against a loopback server: a transient failure is retried and
 * lands, a truncated or wrong-digest body never reaches the target (no half file, no `.tmp` left
 * behind), and a success moves atomically. These are the failure shapes the installer's contract
 * tests only cover indirectly through a completed install.
 */
class DownloadToFileTest {

    private val payload = ByteArray(64 * 1024) { (it * 31).toByte() }
    private val payloadSha1 = AtomicFiles.sha1(payload)

    /** A stub whose response is decided per request by [respond]; answers with [payload] on 200. */
    private fun withStub(respond: (attempt: Int) -> Pair<Int, ByteArray>, block: (String, HttpClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val hits = AtomicInteger()
        server.createContext("/file") { exchange ->
            val attempt = hits.incrementAndGet()
            val (status, body) = respond(attempt)
            if (status == 200) {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(status, -1)
            }
        }
        server.start()
        val client = AssetDownloader.newClient()
        try {
            block("$base/file", client)
        } finally {
            AssetDownloader.closeClient(client)
            server.stop(0)
        }
    }

    private fun download(
        client: HttpClient,
        url: String,
        target: File,
        expectedSize: Long? = null,
        sha1: String? = null,
    ): Long = AssetDownloader.downloadToFile(
        client, Semaphore(4), url, target, expectedSize, "test artifact", null, sha1,
    )

    @Test
    fun `a transient failure is retried and the third attempt lands`() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "oml-dl").toFile()
        val target = File(dir, "artifact.jar")
        withStub({ attempt ->
            if (attempt < 3) 500 to ByteArray(0) else 200 to payload
        }) { url, client ->
            val written = download(client, url, target, sha1 = payloadSha1)
            assertEquals(payload.size.toLong(), written)
            assertTrue(target.isFile, "the landed file must exist")
            assertEquals(payloadSha1, AtomicFiles.sha1(target))
            assertFalse(File(dir, "artifact.jar.tmp").exists(), "no .tmp may survive a success")
        }
    }

    @Test
    fun `a truncated body never reaches the target and leaves no half file`() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "oml-dl").toFile()
        val target = File(dir, "truncated.jar")
        val truncated = payload.copyOf(payload.size / 2)
        withStub({ 200 to truncated }) { url, client ->
            val e = assertFailsWith<IllegalStateException> {
                download(client, url, target, expectedSize = payload.size.toLong(), sha1 = payloadSha1)
            }
            assertContains(e.message!!, "大小不符")
            assertFalse(target.exists(), "a failed transfer must not land the file")
            assertFalse(File(dir, "truncated.jar.tmp").exists(), "the failed attempt must clean its .tmp")
        }
    }

    @Test
    fun `a wrong digest is a rejection even when the size matches`() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "oml-dl").toFile()
        val target = File(dir, "substituted.jar")
        withStub({ 200 to payload }) { url, client ->
            val wrongSha1 = AtomicFiles.sha1(payload.reversedArray())
            val e = assertFailsWith<IllegalStateException> {
                download(client, url, target, sha1 = wrongSha1)
            }
            assertContains(e.message!!, "SHA-1 不符")
            assertFalse(target.exists())
        }
    }

    @Test
    fun `an empty body is a controlled failure`() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "oml-dl").toFile()
        val target = File(dir, "empty.jar")
        withStub({ 200 to ByteArray(0) }) { url, client ->
            val e = assertFailsWith<IllegalStateException> {
                download(client, url, target, sha1 = AtomicFiles.sha1(ByteArray(0)))
            }
            assertContains(e.message!!, "0 字节")
            assertFalse(target.exists())
        }
    }

    @Test
    fun `a digestless source still lands under the explicit opt-in`() {
        // A source publishing no digest is refused unless the opt-in is set: this test drives that
        // opt-in, the same flag a mirror-only setup would run with.
        System.setProperty("oml.allow.missing.digest", "true")
        try {
            val dir = kotlin.io.path.createTempDirectory(prefix = "oml-dl").toFile()
            val target = File(dir, "plain.bin")
            withStub({ 200 to payload }) { url, client ->
                download(client, url, target)
                assertEquals(payloadSha1, AtomicFiles.sha1(target))
            }
        } finally {
            System.clearProperty("oml.allow.missing.digest")
        }
    }
}
