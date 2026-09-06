package com.eirmon.cutly.model

import androidx.camera.video.Quality

/**
 * A capture format the user can pick: a CameraX [Quality] plus a frame rate.
 *
 * Lenses do not offer the same set — a Galaxy A56, for example, does UHD 30 on both lenses but
 * only reaches FHD 60 on the back. So a format is always resolved against the lens that is
 * actually bound, while the user's *preference* is kept separately and reapplied when the
 * capable lens comes back.
 */
data class VideoFormat(
    val quality: Quality,
    val fps: Int,
    val heightPx: Int
) {
    val label: String get() = "${qualityLabel(quality)} · $fps"

    companion object {
        fun qualityLabel(quality: Quality): String = when (quality) {
            Quality.UHD -> "UHD"
            Quality.FHD -> "FHD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            else -> "AUTO"
        }

        /** Highest first, so cycling the chip walks down from the best format. */
        fun rank(quality: Quality): Int = when (quality) {
            Quality.UHD -> 4
            Quality.FHD -> 3
            Quality.HD -> 2
            Quality.SD -> 1
            else -> 0
        }
    }
}
