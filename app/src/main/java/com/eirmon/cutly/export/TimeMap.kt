package com.eirmon.cutly.export

import com.eirmon.cutly.audio.Span

/**
 * The one place the source clock and the output clock meet.
 *
 * Every kept span sits on the source timeline; the export concatenates them, so a position in the
 * output is a position in the source minus every removed gap before it. Prefix sums make each
 * direction a binary search instead of a walk, and having a single implementation is what stops
 * the caret, the captions and the split point from disagreeing about where a cut is.
 *
 * @param keep non-overlapping spans on the source clock, listed in output order. The order need
 * not follow the source: a clip inserted or dragged into place sits wherever the user put it.
 */
class TimeMap(val keep: List<Span>) {

    /** Output time at which each kept span starts; one extra entry holding the total. */
    private val outStarts = LongArray(keep.size + 1).also { starts ->
        keep.forEachIndexed { index, span -> starts[index + 1] = starts[index] + span.durationMs }
    }

    val totalOutputMs: Long get() = outStarts[keep.size]

    fun outputStart(index: Int): Long {
        require(index in keep.indices) { "Unknown clip" }
        return outStarts[index]
    }

    /** Index of the span under an output position; the last span for anything past the end. */
    fun clipAt(outputMs: Long): Int {
        require(keep.isNotEmpty()) { "Nothing on the timeline" }
        if (outputMs <= 0) return 0
        if (outputMs >= totalOutputMs) return keep.lastIndex
        var low = 0
        var high = keep.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (outStarts[mid] <= outputMs) low = mid else high = mid - 1
        }
        return low
    }

    /** The source instant playing at an output position. Clamped to the timeline's ends. */
    fun toSource(outputMs: Long): Long {
        if (keep.isEmpty()) return 0L
        val clamped = outputMs.coerceIn(0L, totalOutputMs)
        val index = clipAt(clamped)
        val span = keep[index]
        return (span.startMs + (clamped - outStarts[index])).coerceAtMost(span.endMs)
    }

    /** Where a source instant lands in the output, or null when it was cut. */
    fun toOutput(sourceMs: Long): Long? {
        // A linear walk: the list is not in source order, and a take has tens of clips at most.
        keep.forEachIndexed { index, span ->
            if (sourceMs >= span.startMs && sourceMs < span.endMs) {
                return outStarts[index] + (sourceMs - span.startMs)
            }
        }
        return null
    }

    /**
     * The first and last surviving instants of a source range, on the output clock.
     *
     * A range straddling a cut keeps both ends and spans the join. Null when nothing survives.
     */
    fun toOutputRange(startMs: Long, endMs: Long): Pair<Long, Long>? {
        var first = -1L
        var last = -1L
        for ((index, span) in keep.withIndex()) {
            val overlapStart = maxOf(startMs, span.startMs)
            val overlapEnd = minOf(endMs, span.endMs)
            if (overlapEnd > overlapStart) {
                if (first < 0) first = outStarts[index] + (overlapStart - span.startMs)
                last = outStarts[index] + (overlapEnd - span.startMs)
            }
        }
        return if (first < 0) null else first to last
    }
}
