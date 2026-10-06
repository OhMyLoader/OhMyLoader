package org.ohmyloader.adapter.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two Anvil-format constants [ZstdRegionChunkFormat] owns:
 *
 * - The registered region format id must be the modding-standard Zstd extension id 127. This is
 *   the contract the .mca verification step asserts on disk: the chunk header's 5th byte is
 *   stamped from the registered version's id.
 * - The registered codec's level must equal the level the oml-core codec round-trip test
 *   exercises, so the tested path is the registered path.
 */
class ZstdRegionChunkFormatTest {

    @Test
    fun `the region format id this adapter registers is 127`() {
        assertEquals(127, ZstdRegionChunkFormat.VERSION_ID)
    }

    @Test
    fun `the registered level matches the level the core codec tests exercise`() {
        assertEquals(3, ZstdRegionChunkFormat.LEVEL)
    }
}
