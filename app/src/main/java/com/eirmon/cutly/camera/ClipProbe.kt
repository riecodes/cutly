package com.eirmon.cutly.camera

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

/**
 * Reads back what a finished clip really contains.
 *
 * The camera is asked for a resolution and frame rate, but a HAL is free to ignore an
 * unadvertised request — Samsung advertises no 60 fps AE range yet its sensor can drive one. The
 * only trustworthy source for what was captured is the file itself, so the clip's stored height
 * comes from here rather than from the format that was requested.
 */
object ClipProbe {

    private const val TAG = "CutlyProbe"

    data class Info(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val durationUs: Long
    ) {
        /** Measured, not declared: frames actually muxed over the actual duration. */
        val measuredFps: Float
            get() = if (durationUs <= 0) 0f else frameCount * 1_000_000f / durationUs
    }

    fun probe(file: File): Info? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            var frames = 0
            var lastSampleUs = 0L
            while (extractor.sampleTime >= 0) {
                lastSampleUs = extractor.sampleTime
                frames++
                if (!extractor.advance()) break
            }

            val durationUs = format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?: lastSampleUs

            Info(
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                frameCount = frames,
                durationUs = durationUs
            ).also {
                Log.i(
                    TAG,
                    "clip=${file.name} ${it.width}x${it.height} " +
                        "frames=${it.frameCount} durationMs=${it.durationUs / 1000} " +
                        "measuredFps=${"%.2f".format(it.measuredFps)}"
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "probe failed for ${file.name}: $error")
            null
        } finally {
            extractor.release()
        }
    }
}
