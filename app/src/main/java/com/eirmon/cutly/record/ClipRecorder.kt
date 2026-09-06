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
        durationLimitMs: Long,
        onProgress: (elapsedMs: Long) -> Unit,
        onFinished: (durationMs: Long, failed: Boolean) -> Unit
    ) {
        if (recording != null) return

        val options = FileOutputOptions.Builder(file)
            // Hard stop so the running clip can never push the take past the global cap.
            .setDurationLimitMillis(durationLimitMs.coerceAtLeast(MIN_LIMIT_MS))
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
                    // Hitting the duration cap is a normal stop, not a failure.
                    val failed = event.hasError() &&
                        event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED &&
                        event.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    if (failed) file.delete()
                    onFinished(durationMs, failed)
                }
            }
        }
    }

    /** Asks CameraX to finalize. The clip is only usable once Finalize arrives. */
    fun stop() {
        recording?.stop()
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MIN_LIMIT_MS = 500L
    }
}
