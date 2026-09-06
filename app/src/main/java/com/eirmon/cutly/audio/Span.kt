package com.eirmon.cutly.audio

/** A half-open range of a media timeline, in milliseconds from the start of the source. */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}
