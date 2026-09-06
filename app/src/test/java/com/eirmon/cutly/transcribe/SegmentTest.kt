package com.eirmon.cutly.transcribe

import com.eirmon.cutly.audio.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Remapping is what keeps captions on the right words after the silence is gone.
 *
 * Skip it and every caption is late by the total silence removed before it, so the drift grows
 * with each cut — the last line of a heavily-cut video can land seconds after it was said.
 */
class SegmentTest {

    /** Two spans, one 1000 ms gap removed between them: everything after it moves back by 1000. */
    private val keep = listOf(Span(0, 2000), Span(3000, 6000))

    @Test
    fun `leaves a segment before the first cut alone`() {
        assertEquals(
            listOf(Segment(500, 1500, "before")),
            Segment.remap(listOf(Segment(500, 1500, "before")), keep)
        )
    }

    @Test
    fun `pulls a segment after a cut earlier by the silence removed before it`() {
        assertEquals(
            listOf(Segment(3000, 4000, "after")),
            Segment.remap(listOf(Segment(4000, 5000, "after")), keep)
        )
    }

    /** The caption should span the join rather than disappearing at it. */
    @Test
    fun `keeps a segment that straddles a cut`() {
        assertEquals(
            listOf(Segment(1500, 3500, "straddling")),
            Segment.remap(listOf(Segment(1500, 4500, "straddling")), keep)
        )
    }

    /**
     * A caption for words that were cut out has nothing left to sit over. It can happen: the
     * detector works on amplitude and the model works on language, so they disagree at the edges.
     */
    @Test
    fun `drops a segment that falls entirely inside a cut`() {
        assertEquals(emptyList<Segment>(), Segment.remap(listOf(Segment(2200, 2800, "gone")), keep))
    }

    /** Two surviving frames is a flicker, not a caption. */
    @Test
    fun `drops a segment with almost nothing left after the cut`() {
        assertEquals(
            emptyList<Segment>(),
            Segment.remap(listOf(Segment(1950, 2600, "sliver")), keep)
        )
    }

    @Test
    fun `remaps a whole transcript in order`() {
        val segments = listOf(
            Segment(0, 1000, "one"),
            Segment(1000, 2000, "two"),
            Segment(3000, 4000, "three"),
            Segment(5000, 6000, "four")
        )

        assertEquals(
            listOf(
                Segment(0, 1000, "one"),
                Segment(1000, 2000, "two"),
                Segment(2000, 3000, "three"),
                Segment(4000, 5000, "four")
            ),
            Segment.remap(segments, keep)
        )
    }

    @Test
    fun `finds the caption showing at a moment`() {
        val segments = listOf(
            Segment(0, 1000, "one"),
            Segment(1000, 2000, "two"),
            Segment(4000, 5000, "three")
        )

        assertEquals("one", Segment.at(segments, 0)?.text)
        assertEquals("one", Segment.at(segments, 999)?.text)
        assertEquals("two", Segment.at(segments, 1000)?.text)
        assertEquals("three", Segment.at(segments, 4500)?.text)
        // The gap between segments, and past the end, are both "no caption" rather than the
        // nearest one — a held-over caption reads as a sync bug.
        assertNull(Segment.at(segments, 3000))
        assertNull(Segment.at(segments, 9000))
        assertNull(Segment.at(emptyList(), 0))
    }
}
