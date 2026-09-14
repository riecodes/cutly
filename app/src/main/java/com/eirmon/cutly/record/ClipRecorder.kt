package com.eirmon.cutly.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Thin wrapper over a single CameraX [Recording].
 *
 * Deliberately one recording per clip: Recording.pause()/resume() would produce one continuous
 * file, which makes "discard last clip" impossible without re-encoding.
 */
class ClipRecorder(private val context: Context) {

    private var recording: Recording? = null

    val isRecording: Boolean get() = recording != null

    fun start(
        videoCapture: VideoCapture<Recorder>,
        file: File,
        durationLimitMs: Long?,
        onProgress: (elapsedMs: Long) -> Unit,
        onFinished: (durationMs: Long, failed: Boolean, outOfSpace: Boolean) -> Unit
    ) {
        if (recording != null) return

        val options = FileOutputOptions.Builder(file)
            .apply {
                // Only when the user asked for a per-clip cap. Without one the clip runs until
                // it is stopped or the volume fills up.
                durationLimitMs?.let { setDurationLimitMillis(it.coerceAtLeast(MIN_LIMIT_MS)) }
            }
            .build()

        val pending = videoCapture.output.prepareRecording(context, options)
        if (hasAudioPermission()) {
            pending.withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    onProgress(event.recordingStats.recordedDurationNanos / 1_000_000)
                }

                is VideoRecordEvent.Finalize -> {
                    recording = null
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    val outOfSpace = event.hasError() &&
                        event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
                    // Hitting a cap — length, file size, or the volume itself — is a normal stop
                    // rather than a failure: CameraX still finalizes the footage recorded so far.
                    val failed = event.hasError() && event.error !in GRACEFUL_STOPS
                    if (failed) file.delete()
                    onFinished(durationMs, failed, outOfSpace)
                }
            }
        }
    }

    /** Asks CameraX to finalize. The clip is only usable once Finalize arrives. */
    fun stop() {
        recording?.stop()
    }

    fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MIN_LIMIT_MS = 500L

        /** Finalize errors that still leave a usable clip on disk. */
        val GRACEFUL_STOPS = setOf(
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
        )
    }
}
