package com.eirmon.cutly

import android.app.Application
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.camera.FormatCatalog
import com.eirmon.cutly.data.ClipStore
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.export.MediaSaver
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.model.VideoFormat
import com.eirmon.cutly.record.ClipRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CameraViewModel(
    application: Application,
    private val handle: SavedStateHandle
) : AndroidViewModel(application) {

    data class UiState(
        val clips: List<Clip> = emptyList(),
        val isRecording: Boolean = false,
        val currentClipMs: Long = 0L,
        val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        val isExporting: Boolean = false,
        val status: String? = null,
        /** A merged take waiting to be handed to the editor; the screen consumes it. */
        val takeForEditor: File? = null,
        /** What the user asked for. Survives lens flips even when the lens cannot deliver it. */
        val preferredFormat: VideoFormat? = null,
        /** What the currently bound lens actually gave us. */
        val activeFormat: VideoFormat? = null,
        val availableFormats: List<VideoFormat> = emptyList(),
        val hasFlash: Boolean = false,
        val flashOn: Boolean = false,
        val timerSeconds: Int = 3,
        val countdownRemaining: Int = 0,
        val speed: Float = 1f,
        /** Optional per-clip cap set from the countdown sheet. Null means run until stopped. */
        val clipLimitMs: Long? = null,
        /** Frame shape, applied as a CameraX ViewPort crop. Locked once the take has a clip. */
        val aspect: Aspect = Aspect.PORTRAIT
    ) {
        val recordedMs: Long
            get() = clips.sumOf { it.outputDurationMs } + (currentClipMs / speed).toLong()

        val hasClips: Boolean get() = clips.isNotEmpty()
        val isCountingDown: Boolean get() = countdownRemaining > 0

        /** The format chip is only meaningful once a lens has reported its capabilities. */
        val formatLabel: String get() = activeFormat?.label ?: "…"
    }

    private val store = ClipStore(application)
    private val recorder = ClipRecorder(application)
    private val exporter = ClipExporter(application)
    // The few camera choices worth keeping across process death; the format is re-resolved
    // against the lens on rebind anyway.
    private val _state = MutableStateFlow(
        UiState(
            lensFacing = handle[KEY_LENS] ?: CameraSelector.LENS_FACING_BACK,
            speed = handle[KEY_SPEED] ?: 1f,
            timerSeconds = handle[KEY_TIMER] ?: 3,
            flashOn = handle[KEY_FLASH] ?: false,
            aspect = handle.get<String>(KEY_ASPECT)?.let { Aspect.valueOf(it) } ?: Aspect.PORTRAIT
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var countdownJob: Job? = null

    /** Per-lens record of frame rates the hardware requested-but-ignored, learned at runtime. */
    private val refusedFrameRates = mutableMapOf<Int, Set<Int>>()

    /** Set when a lens switch interrupted an active clip, so recording auto-resumes after rebind. */
    private var resumeAfterRebind = false

    /** A flip asked for mid-clip, held until that clip has finalized on the old lens. */
    private var flipAfterStop = false

    /** Clip-elapsed time of the last free-space check, so the running clip polls it sparingly. */
    private var lastSpaceCheckMs = 0L

    init {
        val restored = store.load()
        store.pruneOrphans(restored)
        _state.update {
            it.copy(
                clips = restored,
                status = if (restored.isNotEmpty()) "Restored ${restored.size} clip(s)" else null
            )
        }
    }

    /**
     * Called by the UI every time CameraX finishes binding — on first launch, after every lens
     * switch, and after every format change, since a bound VideoCapture cannot survive a rebind.
     */
    fun onCameraReady(
        capture: VideoCapture<Recorder>,
        availableFormats: List<VideoFormat>,
        activeFormat: VideoFormat?,
        hasFlash: Boolean
    ) {
        videoCapture = capture
        val lens = _state.value.lensFacing
        val refused = refusedFrameRates[lens].orEmpty()

        _state.update {
            it.copy(
                // Rates this lens has already been caught ignoring never come back.
                availableFormats = availableFormats.filterNot { format -> format.fps in refused },
                activeFormat = activeFormat,
                hasFlash = hasFlash,
                // A lens without a torch cannot stay lit through a flip.
                flashOn = it.flashOn && hasFlash
            )
        }
        if (resumeAfterRebind) {
            resumeAfterRebind = false
            startClip()
        }
    }

    fun onCameraReleased() {
        videoCapture = null
    }

    /**
     * The frame rate the camera actually settled on, read from a real capture result.
     *
     * Some devices accept an unadvertised rate and then ignore it. When that happens the rate is
     * struck off for this lens and the camera falls back, so the format chip can never advertise
     * something the hardware will not deliver.
     */
    fun onAchievedFrameRate(requested: Int, achieved: Range<Int>?) {
        if (achieved == null || achieved.upper >= requested) return

        val lens = _state.value.lensFacing
        refusedFrameRates[lens] = refusedFrameRates[lens].orEmpty() + requested

        _state.update { current ->
            val pruned = current.availableFormats.filterNot { it.fps == requested }
            val fallback = FormatCatalog.resolve(
                preferred = current.preferredFormat?.let { preferred ->
                    pruned.firstOrNull { it.quality == preferred.quality }
                },
                available = pruned
            )
            current.copy(
                availableFormats = pruned,
                preferredFormat = fallback,
                status = "This camera does not support $requested fps"
            )
        }
    }

    // region recording

    /** The record button. A running countdown is cancelled rather than ignored. */
    fun onRecordPressed() {
        val current = _state.value
        when {
            current.isCountingDown -> cancelCountdown()
            current.isRecording -> stopClip()
            else -> startClip()
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.update { it.copy(countdownRemaining = remaining) }
                delay(1000)
            }
            _state.update { it.copy(countdownRemaining = 0) }
            startClip()
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(countdownRemaining = 0) }
    }

    fun startClip() {
        val capture = videoCapture ?: return
        val current = _state.value
        if (current.isRecording || current.isExporting) return

        // The take has no length cap any more, so the volume is the only thing that ends it.
        // Refusing here — rather than letting CameraX fail mid-clip — keeps the message honest.
        if (store.usableSpaceBytes() < LOW_SPACE_BYTES) {
            _state.update { it.copy(status = "Not enough storage to record") }
            return
        }

        val file = store.newClipFile()
        lastSpaceCheckMs = 0L
        val speed = current.speed
        val lens = current.lensFacing
        val height = current.activeFormat?.heightPx ?: 1080
        _state.update {
            it.copy(
                isRecording = true,
                currentClipMs = 0L,
                // A denied microphone still records; the user should not learn that after the take.
                status = if (recorder.hasAudioPermission()) null else "Recording without sound"
            )
        }

        recorder.start(
            videoCapture = capture,
            file = file,
            // Null unless the countdown sheet set a per-clip cap. The cap is in output
            // milliseconds, so a 2x clip is allowed to run twice as long on the wire.
            durationLimitMs = current.clipLimitMs?.let { (it * speed).toLong() },
            onProgress = { elapsed ->
                _state.update { if (it.isRecording) it.copy(currentClipMs = elapsed) else it }
                stopIfSpaceRunsOut(elapsed)
            },
            onFinished = { durationMs, failed, outOfSpace ->
                if (failed || durationMs < MIN_CLIP_MS) {
                    // Sub-threshold taps produce unusable files; drop them silently.
                    file.delete()
                    _state.update {
                        it.copy(
                            isRecording = false,
                            currentClipMs = 0L,
                            status = when {
                                outOfSpace -> OUT_OF_SPACE_MESSAGE
                                failed -> "Clip failed"
                                else -> null
                            }
                        )
                    }
                } else {
                    // Trust the file over the request: the height actually muxed is what export
                    // has to normalise against. Metadata only: this runs on the main executor.
                    val probed = ClipProbe.probeMetadata(file)
                    val clips = _state.value.clips +
                        Clip(file, durationMs, lens, speed, probed?.height ?: height)
                    store.save(clips)
                    _state.update {
                        it.copy(
                            clips = clips,
                            isRecording = false,
                            currentClipMs = 0L,
                            status = if (outOfSpace) OUT_OF_SPACE_MESSAGE else it.status
                        )
                    }
                }
                if (flipAfterStop) {
                    flipAfterStop = false
                    flipLens()
                }
            }
        )
    }

    /** Pause. Finalizes the running clip; the next start() begins a new one. */
    fun stopClip() {
        if (_state.value.isRecording) recorder.stop()
    }

    /**
     * Ends the clip before the volume fills up. CameraX has its own storage floor, but it
     * finalizes with an error at that point; stopping a little earlier keeps the clip ordinary.
     */
    private fun stopIfSpaceRunsOut(elapsedMs: Long) {
        if (elapsedMs - lastSpaceCheckMs < SPACE_CHECK_INTERVAL_MS) return
        lastSpaceCheckMs = elapsedMs
        if (store.usableSpaceBytes() >= LOW_SPACE_BYTES) return

        _state.update { it.copy(status = OUT_OF_SPACE_MESSAGE) }
        recorder.stop()
    }

    fun discardLast() = discardClip(_state.value.clips.lastIndex)

    /** Drops one clip from anywhere in the take; every clip is its own file, so nothing re-encodes. */
    fun discardClip(index: Int) {
        if (_state.value.isRecording) return
        val clips = _state.value.clips
        val clip = clips.getOrNull(index) ?: return

        val remaining = clips.filterIndexed { i, _ -> i != index }
        clip.file.delete()
        store.save(remaining)
        _state.update { it.copy(clips = remaining, status = "Clip discarded") }
    }

    /** Reorders the take; the merge follows list order, so this is the whole edit. */
    fun moveClip(from: Int, to: Int) {
        if (_state.value.isRecording || from == to) return
        val clips = _state.value.clips
        if (from !in clips.indices || to !in clips.indices) return

        val reordered = clips.toMutableList().apply { add(to, removeAt(from)) }
        store.save(reordered)
        _state.update { it.copy(clips = reordered) }
    }

    fun discardAll() {
        if (_state.value.isRecording) return
        cancelCountdown()
        store.clear()
        _state.update { it.copy(clips = emptyList(), status = "Take cleared") }
    }

    // endregion

    // region camera controls

    /**
     * Double tap. If a clip is running it is finalized first — CameraX cannot swap the bound
     * CameraSelector underneath an active recording — then recording resumes on the new lens.
     */
    fun switchLens() {
        if (_state.value.isExporting) return
        if (_state.value.isRecording) {
            // Rebinding before Finalize would unbind the capture under the running clip, and the
            // resume would find isRecording still set and silently not start.
            if (!flipAfterStop) {
                flipAfterStop = true
                resumeAfterRebind = true
                recorder.stop()
            }
            return
        }
        flipLens()
    }

    private fun flipLens() {
        _state.update {
            it.copy(
                lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            )
        }
        handle[KEY_LENS] = _state.value.lensFacing
    }

    /**
     * Picks a resolution, keeping the current frame rate when that lens can still deliver it and
     * dropping to its best available rate when it cannot.
     */
    fun selectQuality(quality: Quality) {
        val current = _state.value
        if (current.isRecording || current.isExporting) return

        val forQuality = current.availableFormats.filter { it.quality == quality }
        if (forQuality.isEmpty()) return

        val target = forQuality.firstOrNull { it.fps == current.activeFormat?.fps }
            ?: forQuality.first()
        _state.update { it.copy(preferredFormat = target) }
    }

    /** Picks a frame rate at the current resolution. */
    fun selectFps(fps: Int) {
        val current = _state.value
        if (current.isRecording || current.isExporting) return

        val target = current.availableFormats
            .firstOrNull { it.quality == current.activeFormat?.quality && it.fps == fps }
            ?: return
        _state.update { it.copy(preferredFormat = target) }
    }

    fun toggleFlash() {
        val current = _state.value
        if (!current.hasFlash) {
            _state.update { it.copy(status = "This lens has no flash") }
            return
        }
        _state.update { it.copy(flashOn = !it.flashOn) }
        handle[KEY_FLASH] = _state.value.flashOn
    }

    fun setTimerSeconds(seconds: Int) {
        _state.update { it.copy(timerSeconds = seconds) }
        handle[KEY_TIMER] = seconds
    }

    /** How long a clip is allowed to run. Null is the default: until stopped, or out of space. */
    fun setClipLimit(limitMs: Long?) {
        _state.update {
            it.copy(clipLimitMs = limitMs?.coerceIn(1_000L, MAX_CLIP_LIMIT_MS))
        }
    }

    /** The countdown sheet's start button. */
    fun startCountdownNow() {
        val current = _state.value
        if (current.isRecording || current.isExporting) return
        startCountdown(current.timerSeconds)
    }

    /**
     * Aspect applies at capture, so it cannot change once the take has a clip: the merge would
     * otherwise have to reconcile two frame shapes.
     */
    fun setAspect(aspect: Aspect) {
        val current = _state.value
        if (current.isRecording || current.isExporting || current.hasClips) return
        _state.update { it.copy(aspect = aspect) }
        handle[KEY_ASPECT] = aspect.name
    }

    /** Speed applies at export, so it can change between clips and each clip keeps its own. */
    fun setSpeed(speed: Float) {
        if (_state.value.isRecording) return
        _state.update { it.copy(speed = speed) }
        handle[KEY_SPEED] = speed
    }

    // endregion

    // region export

    /** Exports every clip as its own video file in Movies/Cutly. */
    fun exportSeparateClips() = export { clips ->
        clips.forEachIndexed { index, clip ->
            _state.update { it.copy(status = "Exporting part ${index + 1}/${clips.size}") }
            val normalized = exporter.normalize(clip)
            withContext(Dispatchers.IO) {
                MediaSaver.saveVideo(
                    getApplication(),
                    normalized,
                    "cutly_${System.currentTimeMillis()}_part${index + 1}.mp4"
                )
                normalized.delete()
            }
        }
        "Saved ${clips.size} clip(s) to Movies/Cutly"
    }

    /** Merges the take and hands the file to the editor, where it becomes a project. */
    fun openInEditor() = export { clips ->
        _state.update { it.copy(status = "Merging ${clips.size} clip(s)") }
        val merged = exporter.merge(clips)
        _state.update { it.copy(takeForEditor = merged) }
        "Opening in editor…"
    }

    /** The screen has handed the merged file on; the take itself stays until cleared. */
    fun takeConsumed() {
        _state.update { it.copy(takeForEditor = null, status = null) }
    }

    /** Exports the whole take concatenated into a single video. */
    fun exportMerged() = export { clips ->
        _state.update { it.copy(status = "Merging ${clips.size} clip(s)") }
        val merged = exporter.merge(clips)
        withContext(Dispatchers.IO) {
            MediaSaver.saveVideo(
                getApplication(),
                merged,
                "cutly_${System.currentTimeMillis()}.mp4"
            )
            merged.delete()
        }
        "Saved merged video to Movies/Cutly"
    }

    private fun export(block: suspend (List<Clip>) -> String) {
        val clips = _state.value.clips
        if (clips.isEmpty() || _state.value.isExporting || _state.value.isRecording) return

        _state.update { it.copy(isExporting = true, status = "Exporting…") }
        viewModelScope.launch {
            // Transformer needs the main looper; only the MediaStore copy moves off it.
            val message = runCatching { block(clips) }
                .getOrElse { throwable -> "Export failed: ${throwable.message}" }
            _state.update { it.copy(isExporting = false, status = message) }
        }
    }

    // endregion

    fun clearStatus() {
        _state.update { it.copy(status = null) }
    }

    /** Exposed so the UI can resolve a preference against a lens before binding. */
    fun resolveFormat(available: List<VideoFormat>): VideoFormat? =
        FormatCatalog.resolve(_state.value.preferredFormat, available)

    /** The frame shapes on offer, as the reference lists them. Width:height in portrait. */
    enum class Aspect(val label: String, val width: Int, val height: Int) {
        PORTRAIT("9:16", 9, 16),
        FOUR_FIVE("4:5", 4, 5),
        SQUARE("1:1", 1, 1),
        LANDSCAPE("16:9", 16, 9);

        val ratio: Float get() = width.toFloat() / height
    }

    companion object {
        private const val KEY_LENS = "lens"
        private const val KEY_ASPECT = "aspect"
        private const val KEY_SPEED = "speed"
        private const val KEY_TIMER = "timer"
        private const val KEY_FLASH = "flash"

        const val MIN_CLIP_MS = 300L

        /** The longest per-clip cap the countdown sheet can set; past it the slider reads off. */
        const val MAX_CLIP_LIMIT_MS = 600_000L

        /**
         * Recording stops with this much of the volume left. FHD 60 runs at roughly 20 MB a
         * minute, so the floor is a few minutes of headroom for the muxer and for the export
         * that follows the take.
         */
        private const val LOW_SPACE_BYTES = 150L * 1024 * 1024
        private const val SPACE_CHECK_INTERVAL_MS = 2_000L
        private const val OUT_OF_SPACE_MESSAGE = "Storage full — take stopped"

        val TIMER_OPTIONS = listOf(3, 10)

        /** Fastest first, matching the top-to-bottom order of the reference's speed column. */
        val SPEED_OPTIONS = listOf(3f, 2f, 1f, 0.5f, 0.3f)
    }
}
