package com.eirmon.cutly.export

import com.eirmon.cutly.audio.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class CutTimelineTest {

    /** Regression for the second clip crashing CompositionPlayer during setComposition. */
    @Test
    fun `declares the source timeline rather than one clipped span`() {
        val keep = listOf(Span(0, 1080), Span(1920, 3000))

        assertEquals(3_000_000L, CutTimeline.sourceDurationUs(keep, null))
    }

    @Test
    fun `retains measured duration after trailing silence is trimmed`() {
        val keep = listOf(Span(0, 1080))

        assertEquals(2_000_000L, CutTimeline.sourceDurationUs(keep, 2_000_000L))
    }

    @Test
    fun `clip endpoint wins when audio runs past reported video duration`() {
        val keep = listOf(Span(0, 3000))

        assertEquals(3_000_000L, CutTimeline.sourceDurationUs(keep, 2_999_500L))
    }

    @Test
    fun `clip trim cannot overlap its neighbours`() {
        val keep = listOf(Span(0, 1_000), Span(2_000, 3_000), Span(4_000, 5_000))

        assertEquals(
            listOf(Span(0, 1_000), Span(1_000, 4_000), Span(4_000, 5_000)),
            CutTimeline.trim(keep, 1, startMs = 500, endMs = 4_500, totalMs = 5_000)
        )
    }

    @Test
    fun `output playhead maps through removed source gaps`() {
        val keep = listOf(Span(0, 1_000), Span(2_000, 3_500), Span(8_000, 9_000))

        assertEquals(1_000L, CutTimeline.outputStartMs(keep, 1))
        assertEquals(1, CutTimeline.clipAtOutputPosition(keep, 2_200))
        assertEquals(2, CutTimeline.clipAtOutputPosition(keep, 3_500))
    }
}
