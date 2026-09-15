package com.eirmon.cutly.export

import com.eirmon.cutly.audio.Span

/** Pure timeline checks shared by preview and export before Media3 sees a clipped item. */
internal object CutTimeline {

    /** Start of one source clip after all removed gaps have been collapsed. */
    fun outputStartMs(keep: List<Span>, index: Int): Long = TimeMap(keep).outputStart(index)

    /** Clip under a position on the collapsed output timeline. */
    fun clipAtOutputPosition(keep: List<Span>, positionMs: Long): Int = TimeMap(keep).clipAt(positionMs)

    /**
     * Cuts the clip containing a source instant in two at that instant.
     *
     * Returns the list unchanged when the instant is in a gap or too close to either edge for
     * both halves to be at least [minimumMs] long; a sliver is not a clip anyone wanted.
     */
    fun split(keep: List<Span>, sourceMs: Long, minimumMs: Long = 100L): List<Span> {
        val index = keep.indexOfFirst { sourceMs > it.startMs && sourceMs < it.endMs }
        if (index < 0) return keep
        val span = keep[index]
        if (sourceMs - span.startMs < minimumMs || span.endMs - sourceMs < minimumMs) return keep
        return keep.toMutableList().apply {
            this[index] = Span(span.startMs, sourceMs)
            add(index + 1, Span(sourceMs, span.endMs))
        }
    }

    /**
     * How far one clip's handles may travel: up to the nearest other clip on each side *on the
     * source clock*. List neighbours are no guide once clips have been dragged out of source
     * order.
     */
    fun sourceBounds(keep: List<Span>, index: Int, totalMs: Long): Pair<Long, Long> {
        val span = keep[index]
        val before = keep.filterIndexed { i, other -> i != index && other.endMs <= span.startMs }
            .maxOfOrNull { it.endMs } ?: 0L
        val after = keep.filterIndexed { i, other -> i != index && other.startMs >= span.endMs }
            .minOfOrNull { it.startMs } ?: totalMs
        return before to after
    }

    /** Moves one clip to another slot in the output order. */
    fun move(keep: List<Span>, from: Int, to: Int): List<Span> {
        if (from == to || from !in keep.indices || to !in keep.indices) return keep
        return keep.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Takes hand-deleted source regions back out of a freshly detected list, so a re-detection
     * never resurrects footage the user removed on purpose.
     */
    fun subtract(spans: List<Span>, removed: List<Span>, minimumMs: Long = 100L): List<Span> {
        if (removed.isEmpty()) return spans
        return spans.flatMap { span ->
            var pieces = listOf(span)
            for (cut in removed) {
                pieces = pieces.flatMap { piece ->
                    when {
                        cut.endMs <= piece.startMs || cut.startMs >= piece.endMs -> listOf(piece)
                        else -> listOfNotNull(
                            Span(piece.startMs, cut.startMs).takeIf { it.durationMs > 0 },
                            Span(cut.endMs, piece.endMs).takeIf { it.durationMs > 0 }
                        )
                    }
                }
            }
            pieces
        }.filter { it.durationMs >= minimumMs }
    }

    /**
     * Puts re-detected spans into the order the previous cut had: a new span goes where the old
     * clip it overlaps used to be, and spans in untouched footage fall in by source position.
     */
    fun orderLike(previous: List<Span>, spans: List<Span>): List<Span> {
        if (previous.isEmpty()) return spans.sortedBy { it.startMs }
        fun slot(span: Span): Double {
            val overlapping = previous.indexOfFirst { it.startMs < span.endMs && span.startMs < it.endMs }
            if (overlapping >= 0) return overlapping.toDouble()
            // Between old clips on the source clock: after the last one that ends before it.
            val precedingIndex = previous.withIndex()
                .filter { (_, old) -> old.endMs <= span.startMs }
                .maxByOrNull { (_, old) -> old.endMs }?.index
            return (precedingIndex ?: -1) + 0.5
        }
        return spans.sortedWith(compareBy({ slot(it) }, { it.startMs }))
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
        val (before, after) = sourceBounds(keep, index, totalMs)
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
        val bySource = keep.sortedBy { it.startMs }
        require(bySource.zipWithNext().all { (first, second) -> first.endMs <= second.startMs }) {
            "Cut spans must not overlap"
        }

        val requestedEndUs = Math.multiplyExact(bySource.last().endMs, MICROS_PER_MILLISECOND)
        return maxOf(measuredDurationUs?.takeIf { it > 0 } ?: 0L, requestedEndUs)
    }

    private const val MICROS_PER_MILLISECOND = 1_000L
}
