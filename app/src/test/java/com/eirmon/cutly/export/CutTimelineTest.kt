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
    fun `split cuts the clip under a source instant in two`() {
        val keep = listOf(Span(0, 1_000), Span(2_000, 3_000))

        assertEquals(
            listOf(Span(0, 1_000), Span(2_000, 2_400), Span(2_400, 3_000)),
            CutTimeline.split(keep, 2_400)
        )
    }

    @Test
    fun `split refuses a gap, an edge, and a sliver`() {
        val keep = listOf(Span(0, 1_000), Span(2_000, 3_000))

        assertEquals(keep, CutTimeline.split(keep, 1_500))
        assertEquals(keep, CutTimeline.split(keep, 2_000))
        assertEquals(keep, CutTimeline.split(keep, 2_950))
    }

    @Test
    fun `output playhead maps through removed source gaps`() {
        val keep = listOf(Span(0, 1_000), Span(2_000, 3_500), Span(8_000, 9_000))

        assertEquals(1_000L, CutTimeline.outputStartMs(keep, 1))
        assertEquals(1, CutTimeline.clipAtOutputPosition(keep, 2_200))
        assertEquals(2, CutTimeline.clipAtOutputPosition(keep, 3_500))
    }

    @Test
    fun `trim bounds follow source neighbours, not list neighbours`() {
        // Output order 0-1s, 4-5s, 2-3s: the middle tile's source neighbours are the other two.
        val keep = listOf(Span(0, 1_000), Span(4_000, 5_000), Span(2_000, 3_000))
        assertEquals(1_000L to 4_000L, CutTimeline.sourceBounds(keep, 2, 10_000))
        assertEquals(3_000L to 10_000L, CutTimeline.sourceBounds(keep, 1, 10_000))
    }

    @Test
    fun `subtract keeps hand-deleted footage out of a re-detection`() {
        val detected = listOf(Span(0, 5_000), Span(6_000, 9_000))
        val removed = listOf(Span(1_000, 2_000), Span(6_000, 9_000))
        assertEquals(
            listOf(Span(0, 1_000), Span(2_000, 5_000)),
            CutTimeline.subtract(detected, removed)
        )
    }

    @Test
    fun `orderLike keeps an inserted clip in its slot after re-detection`() {
        // The appended footage (10-12s) was slotted between two original clips.
        val previous = listOf(Span(0, 1_000), Span(10_000, 12_000), Span(2_000, 3_000))
        val redetected = listOf(Span(0, 1_200), Span(1_900, 3_000), Span(10_000, 12_000))
        assertEquals(
            listOf(Span(0, 1_200), Span(10_000, 12_000), Span(1_900, 3_000)),
            CutTimeline.orderLike(previous, redetected)
        )
    }

    @Test
    fun `move slides a clip to a new slot`() {
        val keep = listOf(Span(0, 1), Span(2, 3), Span(4, 5))
        assertEquals(listOf(Span(2, 3), Span(4, 5), Span(0, 1)), CutTimeline.move(keep, 0, 2))
        assertEquals(keep, CutTimeline.move(keep, 1, 1))
    }
}
