package com.eirmon.cutly.model

import java.io.File

/**
 * One recorded segment. Every clip is a standalone, playable MP4 on disk — that is what makes
 * "discard last" and per-clip export cheap, and it is why we never use Recording.pause().
 *
 * [speed] is recorded but not applied yet: capture always runs at real time, and the speed
 * change is a Media3 effect at export. [durationMs] is therefore the wall-clock length of the
 * file, while [outputDurationMs] is what the viewer will see — the value the take budget counts.
 */
data class Clip(
    val file: File,
    val durationMs: Long,
    val lensFacing: Int,
    val speed: Float = 1f,
    val heightPx: Int = 1080
) {
    val outputDurationMs: Long get() = (durationMs / speed).toLong()
}
