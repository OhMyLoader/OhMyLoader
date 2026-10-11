package org.ohmyloader.devtools

import java.util.Collections
import kotlin.test.*

/**
 * The download fan-out's failure contract: a task that fails (a download whose retries ran out) must
 * reach the caller, and the fetch's closing accounting must reject a run that neither downloaded nor
 * verified every object. Both behaviors exist because the opposite used to end a "successful" install
 * with files silently missing.
 */
class AssetDownloaderFanOutTest {

    @Test
    fun `a failing task propagates out of the fan-out after all tasks settle`() {
        val ran = Collections.synchronizedList(mutableListOf<Int>())

        val e = assertFailsWith<RuntimeException> {
            AssetDownloader.fanOut(8) { index ->
                ran += index
                if (index == 3) throw IllegalStateException("download failed after retries")
            }
        }
        // the executor's wrapper must not swallow the download's own failure or its message
        assertEquals("download failed after retries", e.message)
        assertEquals((0 until 8).toList(), ran.sorted(), "every task must still run to completion")
    }

    @Test
    fun `a checked-style failure is unwrapped to its cause`() {
        val e = assertFailsWith<RuntimeException> {
            AssetDownloader.fanOut(1) { throw java.io.IOException("connection reset") }
        }
        assertEquals("connection reset", e.message, "the caller must see the download's message")
    }

    @Test
    fun `a count of zero is a no-op and a full run reports success`() {
        AssetDownloader.fanOut(0) { fail("no task may run for an empty fan-out") }
        AssetDownloader.fanOut(3) { /* nothing to do */ }

        AssetDownloader.requireAllAssetsAccounted(total = 10, downloaded = 3, skipped = 7)
    }

    @Test
    fun `the accounting rejects a run with unaccounted objects`() {
        val e = assertFailsWith<IllegalStateException> {
            AssetDownloader.requireAllAssetsAccounted(total = 10, downloaded = 3, skipped = 6)
        }
        assertContains(e.message!!, "neither downloaded nor verified")
        assertContains(e.message!!, "10")
    }
}
