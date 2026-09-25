package com.eirmon.cutly.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.eirmon.cutly.model.VideoFormat

/**
 * Asks a specific lens what it can actually record, instead of assuming a fixed menu.
 *
 * Frame rate needs two questions, not one:
 *
 *  1. `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` — what the device advertises. CameraX validates
 *     against this, and on Samsung hardware it stops at 30 even for sizes the sensor can drive
 *     faster, which is why the stock camera offers FHD 60 and a naive CameraX app does not.
 *  2. `StreamConfigurationMap.getOutputMinFrameDuration` — the sensor's real floor for a given
 *     recording size. A 16.6 ms minimum means 60 fps is achievable for that size.
 *
 * When (2) allows a rate that (1) does not advertise, the rate is requested through Camera2
 * interop instead of the CameraX session, which is the only route the platform leaves open.
 */
object FormatCatalog {

    /** Only these two matter for a TikTok-style camera; 24 and 120 are out of scope. */
    private val CANDIDATE_FPS = listOf(60, 30)

    private const val TAG = "CutlyFormats"

    fun forCamera(cameraInfo: CameraInfo): List<VideoFormat> {
        val capabilities = Recorder.getVideoCapabilities(cameraInfo)
        val qualities = capabilities.getSupportedQualities(DynamicRange.SDR)

        return qualities
            .sortedByDescending { VideoFormat.rank(it) }
            .flatMap { quality ->
                val size = runCatching {
                    capabilities.getResolution(quality, DynamicRange.SDR)
                }.getOrNull()

                frameRatesFor(cameraInfo, size).map { fps ->
                    VideoFormat(quality, fps, size?.height ?: 1080)
                }
            }
            .also { formats ->
                Log.i(TAG, "offered=" + formats.joinToString { "${it.label}" })
            }
    }

    private fun frameRatesFor(cameraInfo: CameraInfo, size: Size?): List<Int> {
        val advertised = runCatching { cameraInfo.supportedFrameRateRanges }.getOrDefault(emptySet())
        val sensorCeiling = sensorMaxFps(cameraInfo, size)

        return CANDIDATE_FPS
            .filter { fps ->
                advertised.any { it.lower <= fps && fps <= it.upper } || fps <= sensorCeiling
            }
            .ifEmpty { listOf(30) }
    }

    /** Highest frame rate the sensor can sustain for [size], from its minimum frame duration. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun sensorMaxFps(cameraInfo: CameraInfo, size: Size?): Int {
        if (size == null) return 0
        return runCatching {
            val map = Camera2CameraInfo.from(cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val minDurationNs = map?.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                ?: return 0
            if (minDurationNs <= 0) 0 else (1_000_000_000.0 / minDurationNs).toInt()
        }.getOrDefault(0)
    }

    /** True when the rate must be forced through camera2 because CameraX will reject it. */
    fun needsCamera2Override(cameraInfo: CameraInfo?, fps: Int): Boolean {
        if (cameraInfo == null) return false
        val advertised = runCatching { cameraInfo.supportedFrameRateRanges }.getOrDefault(emptySet())
        return advertised.none { it.lower <= fps && fps <= it.upper }
    }

    /**
     * One recorder configuration, shared by the capability probe and the real bind.
     *
     * [forceFrameRate] pins CONTROL_AE_TARGET_FPS_RANGE directly on the capture request. The HAL
     * is free to ignore an unadvertised range, so the caller should treat the result as a request
     * rather than a guarantee.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun videoCaptureFor(quality: Quality, forceFrameRate: Int? = null): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    quality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()

        // The front preview is a mirror, so the file must be one too; CameraX defaults the
        // recording to unmirrored, which saves selfie takes flipped against what was framed.
        val builder = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        if (forceFrameRate != null) {
            Camera2Interop.Extender(builder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(forceFrameRate, forceFrameRate)
            )
        }
        return builder.build()
    }

    /**
     * Preview builder, optionally pinned to [forceFrameRate].
     *
     * [onAchievedRange] reports the range the HAL actually settled on, read from the first
     * completed capture result. This matters because a device can accept an unadvertised request
     * and quietly ignore it — a Galaxy A56 takes CONTROL_AE_TARGET_FPS_RANGE=[60,60] for 1080p
     * and still delivers 30 — so the request alone is not evidence of anything.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun previewFor(
        forceFrameRate: Int?,
        onAchievedRange: ((Range<Int>?) -> Unit)? = null
    ): Preview.Builder {
        val builder = Preview.Builder()
        val extender = Camera2Interop.Extender(builder)

        if (forceFrameRate != null) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(forceFrameRate, forceFrameRate)
            )
        }

        if (onAchievedRange != null) {
            extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                private var reported = false

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (reported) return
                    reported = true
                    onAchievedRange(result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE))
                }
            })
        }

        return builder
    }

    /**
     * Maps the user's preference onto what this lens supports.
     *
     * Resolution is held ahead of frame rate: flipping to a lens that cannot do FHD 60 drops to
     * FHD 30 rather than jumping to a different resolution. The preference itself is untouched,
     * so flipping back restores FHD 60.
     */
    fun resolve(preferred: VideoFormat?, available: List<VideoFormat>): VideoFormat? {
        if (available.isEmpty()) return null
        if (preferred == null) return available.defaultChoice()

        available.firstOrNull { it.quality == preferred.quality && it.fps == preferred.fps }
            ?.let { return it }
        available.firstOrNull { it.quality == preferred.quality }?.let { return it }
        available.firstOrNull { it.fps == preferred.fps }?.let { return it }
        return available.defaultChoice()
    }

    /** FHD 30 is the safe starting point: universally supported and cheap to encode. */
    private fun List<VideoFormat>.defaultChoice(): VideoFormat =
        firstOrNull { it.quality == Quality.FHD && it.fps == 30 }
            ?: firstOrNull { it.quality == Quality.FHD }
            ?: last()

    /** Logged once per bind so a device that silently ignores the override is visible. */
    fun logResolvedFormat(format: VideoFormat?, overridden: Boolean, lensFacing: Int) {
        val lens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
        Log.i(TAG, "bound lens=$lens format=${format?.label} camera2Override=$overridden")
    }
}
