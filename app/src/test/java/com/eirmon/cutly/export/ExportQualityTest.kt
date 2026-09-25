package com.eirmon.cutly.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun fitFrameScalesByShortSideAndKeepsDimensionsEven() {
        assertEquals(1080 to 1920, ExportQuality.fitFrame(720, 1280, 1080))
        assertEquals(1920 to 1080, ExportQuality.fitFrame(1280, 720, 1080))
        assertEquals(1080 to 1440, ExportQuality.fitFrame(1080, 1440, 1080))
        assertEquals(720 to 1558, ExportQuality.fitFrame(1080, 2337, 720))
    }

    @Test
    fun sameShapeSeparatesOrientationsButForgivesRounding() {
        assertTrue(ExportQuality.sameShape(1080, 1920, 720, 1280))
        assertTrue(ExportQuality.sameShape(1080, 1920, 1088, 1920))
        assertFalse(ExportQuality.sameShape(1920, 1080, 1080, 1920))
        assertFalse(ExportQuality.sameShape(1080, 1080, 1080, 1920))
    }
}
