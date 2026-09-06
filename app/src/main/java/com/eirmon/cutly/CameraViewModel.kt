package com.eirmon.cutly

import android.app.Application
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.camera.FormatCatalog
import com.eirmon.cutly.data.ClipStore
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.export.MediaSaver
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.model.VideoFormat
import com.eirmon.cutly.record.ClipRecorder
import com.eirmon.cutly.transcribe.GeminiTranscriber
import com.eirmon.cutly.transcribe.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val clips: List<Clip> = emptyList(),
        val isRecording: Boolean = false,
        val currentClipMs: Long = 0L,
        val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        val maxTakeMs: Long = DEFAULT_MAX_TAKE_MS,
        val isExporting: Boolean = false,
        val status: String? = null,
        /** The take's transcript, non-null while the transcript sheet is open. */
        val transcript: String? = null,
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
        /** Optional per-clip cap set from the countdown sheet; defaults to the whole take. */
        val clipLimitMs: Long = DEFAULT_MAX_TAKE_MS
    ) {
        val recordedMs: Long
            get() = clips.sumOf { it.outputDurationMs } + (currentClipMs / speed).toLong()

        val remainingMs: Long get() = (maxTakeMs - recordedMs).coerceAtLeast(0L)
        val isFull: Boolean get() = remainingMs <= 0L
        val hasClips: Boolean get() = clips.isNotEmpty()
        val isCountingDown: Boolean get() = countdownRemaining > 0

        /** The format chip is only meaningful once a lens has reported its capabilities. */
        val formatLabel: String get() = activeFormat?.label ?: "…"
    }

    private val store = ClipStore(application)
    private val recorder = ClipRecorder(application)
    private val exporter = ClipExporter(application)
    private val transcriber = GeminiTranscriber(BuildConfig.GEMINI_API_KEY)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var countdownJob: Job? = null

    /** Per-lens record of frame rates the hardware requested-but-ignored, learned at runtime. */
    private val refusedFrameRates = mutableMapOf<Int, Set<Int>>()

    /** Set when a lens switch interrupted an active clip, so recording auto-resumes after rebind. */
    private var resumeAfterRebind = false

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
        if (current.isRecording || current.isFull || current.isExporting) return

        val file = store.newClipFile()
        val speed = current.speed
        val height = current.activeFormat?.heightPx ?: 1080
        _state.update { it.copy(isRecording = true, currentClipMs = 0L, status = null) }

        recorder.start(
            videoCapture = capture,
            file = file,
            // The budget is in output milliseconds, so a 2x clip may run twice as long on the
            // wire. The countdown sheet's per-clip cap tightens it further when it is shorter.
            durationLimitMs = (minOf(current.remainingMs, current.clipLimitMs) * speed).toLong(),
            onProgress = { elapsed ->
                _state.update { if (it.isRecording) it.copy(currentClipMs = elapsed) else it }
            },
            onFinished = { durationMs, failed ->
                val lens = _state.value.lensFacing
                if (failed || durationMs < MIN_CLIP_MS) {
                    // Sub-threshold taps produce unusable files; drop them silently.
                    file.delete()
                    _state.update {
                        it.copy(
                            isRecording = false,
                            currentClipMs = 0L,
                            status = if (failed) "Clip failed" else null
                        )
                    }
                } else {
                    // Trust the file over the request: the height actually muxed is what export
                    // has to normalise against.
                    val probed = ClipProbe.probe(file)
                    val clips = _state.value.clips +
                        Clip(file, durationMs, lens, speed, probed?.height ?: height)
                    store.save(clips)
                    _state.update {
                        it.copy(clips = clips, isRecording = false, currentClipMs = 0L)
                    }
                }
            }
        )
    }

    /** Pause. Finalizes the running clip; the next start() begins a new one. */
    fun stopClip() {
        if (_state.value.isRecording) recorder.stop()
    }

    fun discardLast() {
        if (_state.value.isRecording) return
        val clips = _state.value.clips
        if (clips.isEmpty()) return

        val remaining = clips.dropLast(1)
        clips.last().file.delete()
        store.save(remaining)
        _state.update { it.copy(clips = remaining, status = "Clip discarded") }
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
            resumeAfterRebind = true
            recorder.stop()
        }
        _state.update {
            it.copy(
                lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            )
        }
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
    }

    fun setTimerSeconds(seconds: Int) {
        _state.update { it.copy(timerSeconds = seconds) }
    }

    /** How long the clip started by the countdown is allowed to run. */
    fun setClipLimit(limitMs: Long) {
        _state.update { it.copy(clipLimitMs = limitMs.coerceIn(1_000L, it.maxTakeMs)) }
    }

    /** The countdown sheet's start button. */
    fun startCountdownNow() {
        val current = _state.value
        if (current.isRecording || current.isExporting || current.isFull) return
        startCountdown(current.timerSeconds)
    }

    /** Speed applies at export, so it can change between clips and each clip keeps its own. */
    fun setSpeed(speed: Float) {
        if (_state.value.isRecording) return
        _state.update { it.copy(speed = speed) }
    }

    /**
     * Changes the take length, matching the reference's 15s / 60s selector. Locked once the take
     * has started, since shrinking the cap under already-recorded clips has no sane meaning.
     */
    fun setTakeLimit(limitMs: Long) {
        val current = _state.value
        if (current.hasClips || current.isRecording || current.isExporting) return
        // The per-clip cap can never exceed the take it lives inside.
        _state.update { it.copy(maxTakeMs = limitMs, clipLimitMs = limitMs) }
    }

    // endregion

    // region export

    /** Exports every clip as its own video file in Movies/Cutly. */
    fun exportSeparateClips() = export { clips ->
        val height = clips.maxOf { it.heightPx }
        clips.forEachIndexed { index, clip ->
            _state.update { it.copy(status = "Exporting part ${index + 1}/${clips.size}") }
            val normalized = exporter.normalize(clip, height)
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

    /** Exports the whole take concatenated into a single video. */
    fun exportMerged() = export { clips ->
        _state.update { it.copy(status = "Merging ${clips.size} clip(s)") }
        val merged = exporter.merge(clips, clips.maxOf { it.heightPx })
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

    // region transcription

    /**
     * Transcribes the whole take. Runs behind [UiState.isExporting] because it is the same kind of
     * busy: the clip list must not change underneath a job that is reading every file in it.
     */
    fun transcribe() {
        val clips = _state.value.clips
        if (clips.isEmpty() || _state.value.isExporting || _state.value.isRecording) return
        if (BuildConfig.GEMINI_API_KEY.isEmpty()) {
            _state.update { it.copy(status = "Set gemini.api.key in local.properties") }
            return
        }

        _state.update { it.copy(isExporting = true, status = "Extracting audio…") }
        viewModelScope.launch {
            // Transformer needs the main looper; the upload moves itself off it.
            val result = runCatching {
                val audio = exporter.extractAudio(clips)
                _state.update { it.copy(status = "Transcribing…") }
                try {
                    Segment.render(transcriber.transcribe(audio))
                } finally {
                    audio.delete()
                }
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { current.copy(isExporting = false, status = null, transcript = it) },
                    onFailure = {
                        current.copy(
                            isExporting = false,
                            status = "Transcribe failed: ${it.message}"
                        )
                    }
                )
            }
        }
    }

    /** The transcript is editable — the model gets Tagalog proper nouns wrong often enough. */
    fun editTranscript(text: String) {
        _state.update { if (it.transcript == null) it else it.copy(transcript = text) }
    }

    fun closeTranscript() {
        _state.update { it.copy(transcript = null) }
    }

    // endregion

    fun clearStatus() {
        _state.update { it.copy(status = null) }
    }

    /** Exposed so the UI can resolve a preference against a lens before binding. */
    fun resolveFormat(available: List<VideoFormat>): VideoFormat? =
        FormatCatalog.resolve(_state.value.preferredFormat, available)

    companion object {
        const val DEFAULT_MAX_TAKE_MS = 60_000L
        const val MIN_CLIP_MS = 300L

        /** Selectable take lengths, shortest first. */
        val TAKE_LIMITS = listOf(15_000L, 60_000L, 600_000L)
        val TIMER_OPTIONS = listOf(3, 10)

        /** Fastest first, matching the top-to-bottom order of the reference's speed column. */
        val SPEED_OPTIONS = listOf(3f, 2f, 1f, 0.5f, 0.3f)
    }
}
