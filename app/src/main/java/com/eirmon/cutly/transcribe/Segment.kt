package com.eirmon.cutly.transcribe

import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.export.TimeMap

/**
 * One line of the transcript, with the stretch of audio it was said in.
 *
 * The camera and the transcriber both only ever show these as text, but the timings are what makes
 * captions possible, which is why the model is asked for structured segments rather than the
 * `[MM:SS]`-prefixed prose it used to return.
 */
data class Segment(val startMs: Long, val endMs: Long, val text: String) {

    companion object {

        /** The old `[MM:SS] line` rendering, kept because that is what the transcript sheet shows. */
        fun render(segments: List<Segment>): String =
            if (segments.isEmpty()) NO_SPEECH
            else segments.joinToString("\n") { "[${stamp(it.startMs)}] ${it.text}" }

        /**
         * Shifts caption timings onto the cut timeline.
         *
         * Every removed gap pulls everything after it earlier, so a segment's position in the
         * export is its position in the source minus all the silence removed before it. Without
         * this, captions drift further out of sync with every gap the cut takes out.
         *
         * A segment is dropped when the part of it surviving the cut is shorter than
         * [MIN_VISIBLE_MS] — a caption flashed for two frames is noise, not a caption.
         */
        fun remap(segments: List<Segment>, keep: List<Span>): List<Segment> {
            val map = TimeMap(keep)
            return segments.mapNotNull { segment ->
                // A segment straddling a cut keeps its first and last surviving instants, so the
                // caption spans the join instead of vanishing at it.
                val (start, end) = map.toOutputRange(segment.startMs, segment.endMs)
                    ?: return@mapNotNull null
                segment.copy(startMs = start, endMs = end).takeIf { end - start >= MIN_VISIBLE_MS }
            }
        }

        /** The caption showing at a given moment, or null. Segments must be sorted and disjoint. */
        fun at(segments: List<Segment>, timeMs: Long): Segment? {
            var low = 0
            var high = segments.lastIndex
            while (low <= high) {
                val mid = (low + high) / 2
                val segment = segments[mid]
                when {
                    timeMs < segment.startMs -> high = mid - 1
                    timeMs >= segment.endMs -> low = mid + 1
                    else -> return segment
                }
            }
            return null
        }

        private fun stamp(ms: Long): String {
            val total = ms / 1000
            return "%02d:%02d".format(total / 60, total % 60)
        }

        const val NO_SPEECH = "(no speech)"

        /** Roughly four frames at 30fps — below this a caption reads as a flicker. */
        private const val MIN_VISIBLE_MS = 120L
    }
}
