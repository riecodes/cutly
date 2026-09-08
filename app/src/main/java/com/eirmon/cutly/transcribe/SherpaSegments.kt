package com.eirmon.cutly.transcribe

import kotlin.math.roundToLong

/** Converts sherpa Whisper's byte-pair tokens and second timestamps into caption-sized segments. */
internal object SherpaSegments {

    fun fromTokens(
        tokens: Array<String>,
        timestamps: FloatArray,
        durationMs: Long,
        maxWords: Int = 8
    ): List<Segment> {
        if (tokens.size != timestamps.size || tokens.isEmpty() || durationMs <= 0) return emptyList()

        val timed = tokens.indices.mapNotNull { index ->
            val token = tokens[index]
            val seconds = timestamps[index]
            if (token.isBlank() || token.startsWith("<|") || !seconds.isFinite() || seconds < 0f) {
                null
            } else {
                TimedToken(token, (seconds * 1000).roundToLong())
            }
        }.filter { it.startMs < durationMs }
        // An all-zero array is the binding's "timestamps unavailable" shape, not real timing.
        if (timed.isEmpty() || timed.drop(1).none { it.startMs > 0 }) return emptyList()

        val segments = mutableListOf<Segment>()
        var line = mutableListOf<TimedToken>()
        var words = 0

        fun flush(endMs: Long) {
            if (line.isEmpty()) return
            val text = line.joinToString("") { it.text }.trim()
            val startMs = line.first().startMs.coerceAtLeast(0)
            val safeEnd = minOf(maxOf(endMs, startMs + MIN_LINE_MS), durationMs)
            if (text.isNotEmpty() && safeEnd > startMs) {
                segments += Segment(startMs, safeEnd, text)
            }
            line = mutableListOf()
            words = 0
        }

        timed.forEachIndexed { index, token ->
            val previous = line.lastOrNull()
            val beginsWord = token.text.firstOrNull()?.isWhitespace() == true
            if (previous != null &&
                (token.startMs - previous.startMs > BREAK_GAP_MS ||
                    (beginsWord && words >= maxWords))
            ) {
                flush(token.startMs)
            }
            line += token
            if (beginsWord || words == 0) words++

            val next = timed.getOrNull(index + 1)
            if (token.text.trimEnd().lastOrNull() in SENTENCE_ENDS || next == null) {
                flush(next?.startMs ?: minOf(durationMs, token.startMs + LAST_TOKEN_MS))
            }
        }

        // Duplicate or out-of-order model times would break Segment.at's binary search.
        val ordered = segments.sortedBy { it.startMs }
        return ordered
            .mapIndexed { index, segment ->
                val next = ordered.getOrNull(index + 1)
                    ?: return@mapIndexed segment
                if (segment.endMs > next.startMs) segment.copy(endMs = next.startMs) else segment
            }
            .filter { it.endMs > it.startMs }
    }

    private data class TimedToken(val text: String, val startMs: Long)

    private val SENTENCE_ENDS = setOf('.', '?', '!')
    private const val BREAK_GAP_MS = 700L
    private const val LAST_TOKEN_MS = 400L
    private const val MIN_LINE_MS = 300L
}
