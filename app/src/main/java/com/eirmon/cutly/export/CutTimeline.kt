package com.eirmon.cutly.export

import com.eirmon.cutly.audio.Span

/** Pure timeline checks shared by preview and export before Media3 sees a clipped item. */
internal object CutTimeline {

    /** Start of one source clip after all removed gaps have been collapsed. */
    fun outputStartMs(keep: List<Span>, index: Int): Long {
        require(index in keep.indices) { "Unknown clip" }
        return keep.take(index).sumOf { it.durationMs }
    }

    /** Clip under a position on the collapsed output timeline. */
    fun clipAtOutputPosition(keep: List<Span>, positionMs: Long): Int {
        require(keep.isNotEmpty()) { "Nothing on the timeline" }
        var end = 0L
        keep.forEachIndexed { index, span ->
            end += span.durationMs
            if (positionMs < end) return index
        }
        return keep.lastIndex
    }

    /** Applies one clip's trim handles while keeping the source timeline valid. */
    fun trim(
        keep: List<Span>,
        index: Int,
        startMs: Long,
        endMs: Long,
        totalMs: Long,
        minimumMs: Long = 100L
    ): List<Span> {
        require(index in keep.indices) { "Unknown clip" }
        val before = keep.getOrNull(index - 1)?.endMs ?: 0L
        val after = keep.getOrNull(index + 1)?.startMs ?: totalMs
        val minimum = minOf(minimumMs, after - before).coerceAtLeast(1L)
        val start = startMs.coerceIn(before, after - minimum)
        val end = endMs.coerceIn(start + minimum, after)
        return keep.toMutableList().apply { this[index] = Span(start, end) }
    }

    /**
     * Media3 asks for the original media duration on every clipped `EditedMediaItem`, not the
     * duration of that clip. The furthest requested endpoint is also treated as a lower bound:
     * audio-derived cuts can finish a fraction later than the video track metadata reports.
     */
    fun sourceDurationUs(keep: List<Span>, measuredDurationUs: Long?): Long {
        require(keep.isNotEmpty()) { "Nothing left to keep" }
        require(keep.all { it.startMs >= 0 && it.endMs > it.startMs }) {
            "Cut spans must be forward ranges on the source timeline"
        }
        require(keep.zipWithNext().all { (first, second) -> first.endMs <= second.startMs }) {
            "Cut spans must be ordered and non-overlapping"
        }

        val requestedEndUs = Math.multiplyExact(keep.last().endMs, MICROS_PER_MILLISECOND)
        return maxOf(measuredDurationUs?.takeIf { it > 0 } ?: 0L, requestedEndUs)
    }

    private const val MICROS_PER_MILLISECOND = 1_000L
}
