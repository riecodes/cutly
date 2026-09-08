package com.eirmon.cutly.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportQualityTest {

    @Test
    fun singleSourceKeepsHeightAndBitrate() {
        val settings = ExportQuality.single(ExportQuality.Source(2160, 42_000_000))

        assertEquals(2160, settings.height)
        assertEquals(42_000_000, settings.bitrate)
    }

    @Test
    fun bitrateIsCappedAtEncoderSafeCeiling() {
        val settings = ExportQuality.single(ExportQuality.Source(2160, 120_000_000))

        assertEquals(ExportQuality.MAX_BITRATE, settings.bitrate)
    }

    @Test
    fun missingMetadataLeavesMedia3FallbackAvailable() {
        val settings = ExportQuality.single(ExportQuality.Source(null, null))

        assertNull(settings.height)
        assertNull(settings.bitrate)
    }

    @Test
    fun mergeUsesTallestClipAndHighestValidBitrate() {
        val settings = ExportQuality.merge(
            listOf(
                ExportQuality.Source(720, 8_000_000),
                ExportQuality.Source(2160, 45_000_000),
                ExportQuality.Source(1080, 20_000_000)
            )
        )

        assertEquals(2160, settings.height)
        assertEquals(45_000_000, settings.bitrate)
    }
}
