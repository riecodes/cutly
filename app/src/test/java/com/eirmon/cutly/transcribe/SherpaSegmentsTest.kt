package com.eirmon.cutly.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSegmentsTest {

    @Test
    fun groupsBytePairTokensAtSentenceBoundaries() {
        val segments = SherpaSegments.fromTokens(
            tokens = arrayOf(" Kum", "usta", " ka", "?", " I", " am", " fine", "."),
            timestamps = floatArrayOf(0.2f, 0.3f, 0.7f, 0.9f, 1.5f, 1.7f, 2.0f, 2.3f),
            durationMs = 3_000
        )

        assertEquals(listOf("Kumusta ka?", "I am fine."), segments.map { it.text })
        assertEquals(200, segments.first().startMs)
        assertEquals(1_500, segments.first().endMs)
    }

    @Test
    fun emptyTimestampShapeRequestsFallback() {
        val segments = SherpaSegments.fromTokens(
            tokens = arrayOf(" Hello", " world"),
            timestamps = floatArrayOf(0f, 0f),
            durationMs = 2_000
        )

        assertTrue(segments.isEmpty())
    }

    /**
     * The shape SherpaTranscriber hands over once a take is longer than Whisper's 30 second
     * context: each window's own timestamps, already shifted onto the take's clock.
     */
    @Test
    fun keepsSecondWindowTokensOnTheTakeClock() {
        val segments = SherpaSegments.fromTokens(
            tokens = arrayOf(" First", " window", ".", " Second", " window", "."),
            timestamps = floatArrayOf(1.0f, 1.4f, 1.8f, 28.5f, 29.1f, 29.6f),
            durationMs = 56_000
        )

        assertEquals(listOf("First window.", "Second window."), segments.map { it.text })
        assertEquals(28_500, segments.last().startMs)
        assertTrue(segments.last().endMs > 28_500)
        assertTrue(segments.first().endMs <= segments.last().startMs)
    }

    @Test
    fun mismatchedTokenAndTimestampArraysRequestFallback() {
        val segments = SherpaSegments.fromTokens(
            tokens = arrayOf(" Hello", " world"),
            timestamps = floatArrayOf(0.2f),
            durationMs = 2_000
        )

        assertTrue(segments.isEmpty())
    }
}
