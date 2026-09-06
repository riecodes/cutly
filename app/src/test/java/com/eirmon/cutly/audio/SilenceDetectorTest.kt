package com.eirmon.cutly.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cut boundaries are the whole feature, and every one of these cases is an artefact a viewer
 * would hear: a shaved first syllable, a swallowed breath, a sliver of dead air left at the end.
 */
class SilenceDetectorTest {

    @Test
    fun `keeps both sides of a long pause and pads the boundaries`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(LOUD to 1000, QUIET to 1000, LOUD to 1000),
            frameMs = FRAME_MS,
            durationMs = 3000
        )

        // The 1000 ms gap gives 80 ms back to each neighbour, so 840 ms is removed.
        assertEquals(listOf(Span(0, 1080), Span(1920, 3000)), keeps)
    }

    /** A breath between sentences is not a cut. Removing it is what makes edits sound robotic. */
    @Test
    fun `leaves a pause shorter than the minimum alone`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(LOUD to 1000, QUIET to 400, LOUD to 1000),
            frameMs = FRAME_MS,
            durationMs = 2400
        )

        assertEquals(listOf(Span(0, 2400)), keeps)
    }

    /**
     * Padding is protection for adjacent speech, and there is none past the end of the file, so
     * a trailing run is cut all the way out. Padding it would strand an 80 ms sliver of silence
     * that is then too short to survive [SilenceSettings.minKeepMs] and drags the whole gap back.
     */
    @Test
    fun `trims trailing silence completely`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(LOUD to 1000, QUIET to 1000),
            frameMs = FRAME_MS,
            durationMs = 2000
        )

        assertEquals(listOf(Span(0, 1080)), keeps)
    }

    @Test
    fun `trims leading silence completely`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(QUIET to 1000, LOUD to 1000),
            frameMs = FRAME_MS,
            durationMs = 2000
        )

        assertEquals(listOf(Span(920, 2000)), keeps)
    }

    @Test
    fun `returns nothing for a silent source`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(QUIET to 3000),
            frameMs = FRAME_MS,
            durationMs = 3000
        )

        assertTrue(keeps.toString(), keeps.isEmpty())
    }

    @Test
    fun `returns nothing for an empty reading`() {
        assertTrue(SilenceDetector.keepSpans(FloatArray(0), FRAME_MS, 3000).isEmpty())
    }

    /**
     * A 100 ms fragment between two cuts is a click, not a word. Merging swallows the pause next
     * to it as well, which is the intended trade — one pause left in beats a stutter.
     */
    @Test
    fun `merges a kept fragment too short to stand on its own`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(LOUD to 1000, QUIET to 1000, LOUD to 100, QUIET to 1000, LOUD to 1000),
            frameMs = FRAME_MS,
            durationMs = 4100,
            settings = SilenceSettings(padMs = 0)
        )

        assertEquals(listOf(Span(0, 2100), Span(3100, 4100)), keeps)
    }

    /** Every kept span becomes its own EditedMediaItem, so the count has to be bounded. */
    @Test
    fun `keeps only the longest gaps once the span cap is reached`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(
                LOUD to 1000, QUIET to 600,   // shortest gap, dropped
                LOUD to 1000, QUIET to 2000,
                LOUD to 1000, QUIET to 3000,
                LOUD to 1000, QUIET to 700,   // second shortest, dropped
                LOUD to 1000
            ),
            frameMs = FRAME_MS,
            durationMs = 11300,
            settings = SilenceSettings(maxSpans = 3)
        )

        assertEquals(3, keeps.size)
        // The two surviving cuts are the 2000 ms and 3000 ms gaps, minus 160 ms of padding each.
        assertEquals(listOf(Span(0, 2680), Span(4520, 5680), Span(8520, 11300)), keeps)
    }

    @Test
    fun `never emits overlapping or backwards spans`() {
        val keeps = SilenceDetector.keepSpans(
            levelsDb = levels(
                QUIET to 500, LOUD to 300, QUIET to 900, LOUD to 40, QUIET to 1200, LOUD to 800
            ),
            frameMs = FRAME_MS,
            durationMs = 3740
        )

        keeps.forEach { assertTrue(it.toString(), it.durationMs > 0) }
        keeps.zipWithNext { a, b -> assertTrue("$a then $b", a.endMs <= b.startMs) }
    }

    private companion object {
        const val FRAME_MS = 20L
        const val LOUD = -12f
        const val QUIET = -70f

        /** Builds a per-frame reading from `(level, durationMs)` runs. */
        fun levels(vararg runs: Pair<Float, Long>): FloatArray {
            val out = mutableListOf<Float>()
            runs.forEach { (db, ms) -> repeat((ms / FRAME_MS).toInt()) { out += db } }
            return out.toFloatArray()
        }
    }
}
