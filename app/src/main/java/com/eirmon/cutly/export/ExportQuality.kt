package com.eirmon.cutly.export

/**
 * Chooses the video shape passed to Media3 without knowing anything about codecs or Android.
 *
 * Keeping this policy pure makes the quality regression testable: single-source work retains its
 * height, while a mixed take uses one common height. A 50 Mbps ceiling is high enough for phone
 * 4K capture without asking encoders for bitrates that many devices cannot sustain.
 */
internal object ExportQuality {
    const val MAX_BITRATE = 50_000_000

    data class Source(val height: Int?, val bitrate: Int?)
    data class Settings(val height: Int?, val bitrate: Int?)

    fun single(source: Source): Settings = Settings(
        height = source.height.validHeight(),
        bitrate = source.bitrate.validBitrate()
    )

    fun merge(sources: List<Source>): Settings = Settings(
        height = sources.mapNotNull { it.height.validHeight() }.maxOrNull(),
        bitrate = sources.mapNotNull { it.bitrate.validBitrate() }.maxOrNull()
    )

    /** [width] x [height] scaled so its short side is [shortSide], rounded to even for encoders. */
    fun fitFrame(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
        val scale = shortSide.toDouble() / minOf(width, height)
        fun even(value: Double) = (Math.round(value / 2) * 2).toInt().coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /** Same aspect within 1%, so rounding in a muxer's dimensions is not a different shape. */
    fun sameShape(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean {
        val a = width.toDouble() / height
        val b = otherWidth.toDouble() / otherHeight
        return kotlin.math.abs(a - b) <= 0.01 * b
    }

    private fun Int?.validHeight(): Int? = this?.takeIf { it > 0 }

    private fun Int?.validBitrate(): Int? = this
        ?.takeIf { it > 0 }
        ?.coerceAtMost(MAX_BITRATE)
}
