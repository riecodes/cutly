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

    private fun Int?.validHeight(): Int? = this?.takeIf { it > 0 }

    private fun Int?.validBitrate(): Int? = this
        ?.takeIf { it > 0 }
        ?.coerceAtMost(MAX_BITRATE)
}
