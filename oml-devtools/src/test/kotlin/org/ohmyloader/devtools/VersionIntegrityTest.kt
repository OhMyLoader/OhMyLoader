package org.ohmyloader.devtools

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Serves Mojang's two-step version lookup (manifest, then the version document it names) from a loopback
 * server, because the step being pinned sits between those two fetches: the version document carries every
 * digest the jar, the libraries and the asset index are later checked against, so a manifest whose recorded
 * SHA-1 does not match what arrived means that document was truncated, substituted, or rewritten in transit
 * — and every "verified" verdict downstream would then rest on the tampered copy.
 */
class VersionIntegrityTest {

    private val releaseDoc = """{"id":"26.3","downloads":{}}""".toByteArray(Charsets.UTF_8)
    private val snapshotDoc = """{"id":"26.4-snapshot-2","downloads":{}}""".toByteArray(Charsets.UTF_8)

    /** [routes] receives the stub's own base URL: a manifest naming a placeholder would fetch elsewhere. */
    private fun withStub(routes: (base: String) -> Map<String, ByteArray>, block: (String, HttpClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        for ((path, body) in routes(base)) {
            server.createContext(path) { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val client = AssetDownloader.newClient()
        try {
            block(base, client)
        } finally {
            AssetDownloader.closeClient(client)
            server.stop(0)
        }
    }

    /** A single-entry manifest; a null [sha1] models a source that publishes no digest for that version. */
    private fun manifest(id: String, url: String, sha1: String?, alias: String? = null): ByteArray {
        val latest = buildString {
            append("""{"release":"$id"""")
            if (alias != null) append(""","snapshot":"$alias"""")
            append('}')
        }
        val entry = buildString {
            append("""{"id":"$id","url":"$url"""")
            if (sha1 != null) append(""","sha1":"$sha1"""")
            append('}')
        }
        return """{"latest":$latest,"versions":[$entry]}""".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `a version document matching the manifest digest is accepted`() {
        withStub({ base ->
            mapOf(
                "/manifest.json" to manifest("26.3", "$base/26.3.json", AssetDownloader.sha1Hex(releaseDoc)),
                "/26.3.json" to releaseDoc,
            )
        }) { base, client ->
            val details = AssetDownloader.resolveVersionDetails(client, "26.3", "$base/manifest.json")
            assertEquals("26.3", details["id"])
        }
    }

    @Test
    fun `a version document the manifest digest does not cover is refused`() {
        // The bytes served are a real version document; only the digest the manifest declares is wrong, so
        // a size check or a JSON parse would pass this and the run would continue on tampered data.
        withStub({ base ->
            mapOf(
                "/manifest.json" to manifest("26.3", "$base/26.3.json", "0".repeat(40)),
                "/26.3.json" to releaseDoc,
            )
        }) { base, client ->
            val failure = assertFailsWith<IllegalStateException> {
                AssetDownloader.resolveVersionDetails(client, "26.3", "$base/manifest.json")
            }
            assertContains(failure.message!!, "SHA-1")
        }
    }

    @Test
    fun `a manifest entry with no digest is refused instead of trusted`() {
        withStub({ base ->
            mapOf(
                "/manifest.json" to manifest("26.3", "$base/26.3.json", null),
                "/26.3.json" to releaseDoc,
            )
        }) { base, client ->
            val failure = assertFailsWith<IllegalStateException> {
                AssetDownloader.resolveVersionDetails(client, "26.3", "$base/manifest.json")
            }
            assertContains(failure.message!!, "sha1")
        }
    }

    @Test
    fun `the snapshot alias is verified against its own entry digest`() {
        // Resolving `snapshot` to a newer id is exactly where a stale digest stops matching, and trusting
        // whatever arrives there is how a mis-resolved snapshot gets installed as "the latest".
        withStub({ base ->
            mapOf(
                "/manifest.json" to manifest(
                    "26.4-snapshot-2",
                    "$base/snap.json",
                    AssetDownloader.sha1Hex(snapshotDoc),
                    alias = "26.4-snapshot-2",
                ),
                "/snap.json" to snapshotDoc,
            )
        }) { base, client ->
            val details = AssetDownloader.resolveVersionDetails(client, "snapshot", "$base/manifest.json")
            assertEquals("26.4-snapshot-2", details["id"])
        }
    }
}
