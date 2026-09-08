package com.eirmon.cutly.camera

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
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
        val frameRate: Float,
        val bitrate: Int?,
        val frameCount: Int,
        val durationUs: Long
    ) {
        /** Measured, not declared: frames actually muxed over the actual duration. */
        val measuredFps: Float
            get() = if (durationUs <= 0 || frameCount <= 0) frameRate
            else frameCount * 1_000_000f / durationUs
    }

    fun probe(file: File): Info? = probe(
        label = file.name,
        countFrames = true,
        setExtractorSource = { setDataSource(file.absolutePath) },
        setRetrieverSource = { setDataSource(file.absolutePath) }
    )

    /** Metadata-only variant for export, where counting every frame would add no information. */
    fun probeMetadata(file: File): Info? = probe(
        label = file.name,
        countFrames = false,
        setExtractorSource = { setDataSource(file.absolutePath) },
        setRetrieverSource = { setDataSource(file.absolutePath) }
    )

    /**
     * Reads only container metadata for a picked video.
     *
     * Unlike recorded clips, picked videos do not need their frames counted to verify a camera
     * request. Avoiding that scan makes this effectively constant-time even for a long source.
     */
    fun probe(context: Context, uri: Uri): Info? = probe(
        label = uri.toString(),
        countFrames = false,
        setExtractorSource = { setDataSource(context, uri, null) },
        setRetrieverSource = { setDataSource(context, uri) }
    )

    private fun probe(
        label: String,
        countFrames: Boolean,
        setExtractorSource: MediaExtractor.() -> Unit,
        setRetrieverSource: MediaMetadataRetriever.() -> Unit
    ): Info? {
        val extractor = MediaExtractor()
        return try {
            extractor.setExtractorSource()

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null

            val format = extractor.getTrackFormat(trackIndex)

            var frames = 0
            var lastSampleUs = 0L
            if (countFrames) {
                extractor.selectTrack(trackIndex)
                while (extractor.sampleTime >= 0) {
                    lastSampleUs = extractor.sampleTime
                    frames++
                    if (!extractor.advance()) break
                }
            }

            val durationUs = format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?: lastSampleUs
            val declaredFps = format.intOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f

            Info(
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                frameRate = if (frames > 0 && durationUs > 0) {
                    frames * 1_000_000f / durationUs
                } else {
                    declaredFps
                },
                bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }
                    ?: videoBitrateFromContainer(extractor, setRetrieverSource),
                frameCount = frames,
                durationUs = durationUs
            ).also {
                Log.i(
                    TAG,
                    "clip=$label ${it.width}x${it.height} " +
                        "frames=${it.frameCount} durationMs=${it.durationUs / 1000} " +
                        "fps=${"%.2f".format(it.frameRate)} bitrate=${it.bitrate ?: "unknown"}"
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "probe failed for $label: $error")
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * What the video track is really worth when the track itself declines to say.
     *
     * MP4s written by the camera carry no `btrt` box, so the video format has no bitrate key and
     * an export left to the encoder's own heuristic re-encodes 17 Mbps footage at about a quarter
     * of that. The container knows its total rate, so the video share is that minus the audio.
     */
    private fun videoBitrateFromContainer(
        extractor: MediaExtractor,
        setRetrieverSource: MediaMetadataRetriever.() -> Unit
    ): Int? {
        val audioBitrate = (0 until extractor.trackCount)
            .map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            // A track with no declared rate is still taking bytes, so assume a typical AAC one
            // rather than crediting the whole container to video.
            ?.let { it.intOrNull(MediaFormat.KEY_BIT_RATE) ?: ASSUMED_AUDIO_BITRATE }
            ?: 0

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setRetrieverSource()
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?.minus(audioBitrate)
                ?.takeIf { it > 0 }
        } catch (error: Exception) {
            Log.w(TAG, "container bitrate unavailable: $error")
            null
        } finally {
            retriever.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private const val ASSUMED_AUDIO_BITRATE = 128_000
}
