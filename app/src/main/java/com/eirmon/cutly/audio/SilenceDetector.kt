package com.eirmon.cutly.audio

/**
 * Decides which parts of a timeline to keep, from per-frame loudness alone.
 *
 * Deliberately not a speech model. Silence is an amplitude question, and an amplitude answer is
 * exact, offline, free, and works on music and room tone where a speech model returns nothing.
 * The transcript is used for captions, never for cut boundaries: LLM audio timestamps drift, and
 * a drifted boundary shaves the first syllable off every sentence, which is the one artefact a
 * viewer notices instantly.
 *
 * Pure arithmetic on purpose — no Android imports — so it runs in a JVM unit test.
 */
object SilenceDetector {

    /**
     * @param levelsDb one loudness reading per [frameMs] window, in dBFS, from [PcmDecoder].
     * @param frameMs the window each reading covers.
     * @param durationMs the true source duration, which is longer than
     *        `levelsDb.size * frameMs` whenever the last window is partial.
     * @return the spans worth keeping, in order, never overlapping. Empty when the whole source
     *         is below the threshold.
     */
    fun keepSpans(
        levelsDb: FloatArray,
        frameMs: Long,
        durationMs: Long,
        settings: SilenceSettings = SilenceSettings()
    ): List<Span> {
        if (levelsDb.isEmpty() || durationMs <= 0) return emptyList()

        val gaps = qualifyingGaps(levelsDb, frameMs, durationMs, settings)
            .let { capped(it, settings.maxSpans) }

        return mergeShortKeeps(complement(gaps, durationMs), settings.minKeepMs)
    }

    /**
     * Runs of quiet frames long enough to be worth removing, already shrunk by the padding.
     *
     * The length test is applied to the padded span, not the raw one, so an accepted gap always
     * removes [SilenceSettings.minSilenceMs] or more. A run touching the start or the end of the
     * source is not padded on that side: padding exists to protect adjacent speech, and at the
     * boundaries there is none to protect. Without that exception the padding would leave an
     * unwanted sliver of leading or trailing silence behind.
     */
    private fun qualifyingGaps(
        levelsDb: FloatArray,
        frameMs: Long,
        durationMs: Long,
        settings: SilenceSettings
    ): List<Span> {
        val gaps = mutableListOf<Span>()
        var runStart = -1

        // One extra step past the end so a trailing quiet run is closed rather than dropped.
        for (index in 0..levelsDb.size) {
            val quiet = index < levelsDb.size && levelsDb[index] < settings.thresholdDb
            if (quiet) {
                if (runStart < 0) runStart = index
                continue
            }
            if (runStart < 0) continue

            val rawStart = runStart * frameMs
            // The final frame covers whatever is left of the source, not just one window.
            val rawEnd = if (index == levelsDb.size) durationMs else index * frameMs

            val start = if (rawStart == 0L) 0L else rawStart + settings.padMs
            val end = if (rawEnd >= durationMs) durationMs else rawEnd - settings.padMs

            if (end - start >= settings.minSilenceMs) gaps += Span(start, end)
            runStart = -1
        }
        return gaps
    }

    /** Keeps only the longest gaps, so the cut count stays inside the Transformer budget. */
    private fun capped(gaps: List<Span>, maxSpans: Int): List<Span> {
        val maxGaps = (maxSpans - 1).coerceAtLeast(0)
        if (gaps.size <= maxGaps) return gaps
        return gaps.sortedByDescending { it.durationMs }
            .take(maxGaps)
            .sortedBy { it.startMs }
    }

    /** Everything in `[0, durationMs)` that the gaps do not cover. */
    private fun complement(gaps: List<Span>, durationMs: Long): List<Span> {
        val keeps = mutableListOf<Span>()
        var cursor = 0L
        for (gap in gaps) {
            if (gap.startMs > cursor) keeps += Span(cursor, gap.startMs)
            cursor = gap.endMs
        }
        if (cursor < durationMs) keeps += Span(cursor, durationMs)
        return keeps
    }

    /**
     * Folds away kept pieces too short to survive as their own clip.
     *
     * A 40 ms fragment between two cuts is a click, not a word, and it costs a whole
     * EditedMediaItem. Merging swallows the gap between the two neighbours as well, which is the
     * intended trade: better to leave one pause in than to emit a stutter.
     */
    private fun mergeShortKeeps(keeps: List<Span>, minKeepMs: Long): List<Span> {
        val merged = mutableListOf<Span>()
        for (keep in keeps) {
            val previous = merged.lastOrNull()
            // Checking the previous span too lets a short leading piece absorb forwards, which a
            // backwards-only merge cannot do — there is nothing before the first span.
            if (previous != null &&
                (keep.durationMs < minKeepMs || previous.durationMs < minKeepMs)
            ) {
                merged[merged.lastIndex] = Span(previous.startMs, keep.endMs)
            } else {
                merged += keep
            }
        }
        // A single span covering everything means nothing was actually cut.
        return merged
    }
}
