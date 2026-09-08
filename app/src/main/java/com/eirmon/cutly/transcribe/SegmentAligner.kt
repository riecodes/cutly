package com.eirmon.cutly.transcribe

import android.util.Log
import com.eirmon.cutly.audio.PcmDecoder
import com.eirmon.cutly.audio.SilenceDetector
import com.eirmon.cutly.audio.Span

/**
 * Places recognised text on an audio timeline when a backend returns no usable timestamps.
 *
 * Android's recogniser and sherpa's Whisper Kotlin binding can both produce this shape, so the
 * fallback lives here rather than letting two implementations drift into different guesses.
 */
internal object SegmentAligner {

    fun align(lines: List<String>, levels: PcmDecoder.Levels): List<Segment> {
        val text = lines.filter { it.isNotBlank() }
        if (text.isEmpty()) return emptyList()

        val speech: List<Span> =
            SilenceDetector.keepSpans(levels.db, levels.frameMs, levels.durationMs)
        Log.i(TAG, "align lines=${text.size} speech=${speech.size} ms=${levels.durationMs}")

        if (speech.size == text.size) {
            return text.mapIndexed { index, line ->
                Segment(speech[index].startMs, speech[index].endMs, line)
            }
        }

        val slice = (levels.durationMs / text.size).coerceAtLeast(1)
        return text.mapIndexed { index, line ->
            Segment(index * slice, minOf((index + 1) * slice, levels.durationMs), line)
        }.filter { it.endMs > it.startMs }
    }

    /** Breaks one model paragraph into readable lines before it is paired with speech runs. */
    fun captionLines(text: String, maxWords: Int = 8): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        var line = mutableListOf<String>()
        for (word in words) {
            line += word
            if (line.size >= maxWords || word.endsWithSentence()) {
                lines += line.joinToString(" ")
                line = mutableListOf()
            }
        }
        if (line.isNotEmpty()) lines += line.joinToString(" ")
        return lines
    }

    private fun String.endsWithSentence(): Boolean =
        endsWith('.') || endsWith('?') || endsWith('!')

    private const val TAG = "Cutly"
}
