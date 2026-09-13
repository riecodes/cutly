package com.eirmon.cutly

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.audio.PcmDecoder
import com.eirmon.cutly.audio.SilenceDetector
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.data.AppSettings
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.export.CutTimeline
import com.eirmon.cutly.export.MediaSaver
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.annotation.OptIn
import com.eirmon.cutly.transcribe.CloudTranscriberFactory
import com.eirmon.cutly.transcribe.Segment
import com.eirmon.cutly.transcribe.retry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The cut service: one video off the device, the dead air taken out of it.
 *
 * Split into an analyse pass and an export pass on purpose. Decoding the audio is the slow part
 * and it only has to happen once; moving a threshold slider afterwards is pure arithmetic over
 * the readings already in memory, so the preview updates as the slider moves and nothing is
 * re-encoded until the user is happy with the numbers.
 *
 * Separate from [TranscribeViewModel] for the reason that one is separate from [CameraViewModel]:
 * a picked video never becomes a [com.eirmon.cutly.model.Clip], so it never touches the take or
 * the clip store.
 */
@OptIn(UnstableApi::class)
class CleanupViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isBusy: Boolean = false,
        val status: String? = null,
        /** Set on failure; shown inline until the next attempt or [dismissError]. */
        val error: String? = null,
        /** True when an imported source is available to resume after leaving the editor. */
        val hasProject: Boolean = false,
        /** Who a transcript upload would go to. Null means no key is set, so cloud actions ask for one. */
        val cloudProvider: String? = null,
        /** Non-null while the review sheet is open. */
        val review: Review? = null
    )

    /** What the current settings would produce, without having encoded anything yet. */
    data class Review(
        val source: Uri,
        val settings: SilenceSettings,
        val keep: List<Span>,
        val originalMs: Long,
        /**
         * Captions on the *source* timeline, or null until they are asked for.
         *
         * Stored unremapped because the sliders keep changing which spans survive, and remapping
         * against a stale cut list is exactly how captions end up on the wrong words. The remap
         * happens once, at save, against the spans actually being exported.
         */
        val captions: List<Segment>? = null,
        /** Transcript exists independently; this controls whether it is burned into the export. */
        val captionsEnabled: Boolean = false,
        /** Set once the cut has actually been written to Movies/Cutly. */
        val savedName: String? = null
    ) {
        val keptMs: Long get() = keep.sumOf { it.durationMs }
        val removedMs: Long get() = originalMs - keptMs

        /** Cuts, not kept pieces — a trimmed head or tail is a cut with no piece before it. */
        val cutCount: Int
            get() = if (keep.isEmpty()) 0 else
                keep.size - 1 +
                    (if (keep.first().startMs > 0) 1 else 0) +
                    (if (keep.last().endMs < originalMs) 1 else 0)

        val hasSomethingToCut: Boolean get() = keep.isNotEmpty() && cutCount > 0
        val canSave: Boolean get() = keep.isNotEmpty() && (hasSomethingToCut || captionsEnabled)
    }

    /**
     * The decoded loudness of the picked video, held so the sliders stay instant.
     *
     * A ten-minute take is about 120 KB of readings, which is why keeping it costs nothing and
     * re-decoding on every slider move would cost seconds.
     */
    private class Analysis(
        val source: Uri,
        val levels: PcmDecoder.Levels,
        val videoInfo: ClipProbe.Info?
    )

    private val exporter = ClipExporter(application)
    private val settings = AppSettings(application)

    private val projectDir = File(application.filesDir, "cut-project").apply { mkdirs() }
    private val projectSource = File(projectDir, "source.mp4")
    private val projectLevels = File(projectDir, "levels.bin")
    private val projectPrefs = application.getSharedPreferences("cut-project", 0)
    private var transcriptJob: Job? = null

    private val _state = MutableStateFlow(
        UiState(hasProject = projectSource.exists(), cloudProvider = settings.cloudProvider)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var analysis: Analysis? = null

    init {
        if (projectSource.exists()) analyze(Uri.fromFile(projectSource), persistSource = false)
    }

    /** Measures the video and opens the review sheet. Nothing is encoded or written here. */
    fun analyze(video: Uri) {
        analyze(video, persistSource = true)
    }

    private fun analyze(video: Uri, persistSource: Boolean) {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(
                isBusy = true,
                status = if (persistSource) "Saving project…" else "Opening project…",
                error = null
            )
        }

        viewModelScope.launch {
            // Transformer needs the main looper, so extraction stays on it; the decode moves off.
            val result = runCatching {
                val source = if (persistSource) {
                    withContext(Dispatchers.IO) { copyProjectSource(video) }
                } else {
                    video
                }
                _state.update { it.copy(status = "Reading the audio…") }
                val videoInfo = withContext(Dispatchers.IO) {
                    ClipProbe.probe(getApplication(), source)
                }
                val levels = if (persistSource) null else withContext(Dispatchers.IO) {
                    restoredLevels()
                }
                Triple(source, levels ?: run {
                    val audio = exporter.extractAudio(source)
                    try {
                        _state.update { it.copy(status = "Measuring silence…") }
                        PcmDecoder.levels(audio).also { measured ->
                            withContext(Dispatchers.IO) { persistLevels(measured) }
                        }
                    } finally {
                        audio.delete()
                    }
                }, videoInfo)
            }

            result.fold(
                onSuccess = { (source, levels, videoInfo) ->
                    analysis = Analysis(source, levels, videoInfo)
                    val settings = if (persistSource) SilenceSettings() else restoredSettings()
                    val detected = detect(levels, settings)
                    val review = Review(
                        source = source,
                        settings = settings,
                        keep = if (persistSource) detected else restoredSpans(levels.durationMs)
                            .takeIf { it.isNotEmpty() } ?: detected,
                        originalMs = levels.durationMs,
                        captions = if (persistSource) null else restoredCaptions(),
                        captionsEnabled = !persistSource &&
                            projectPrefs.getBoolean(KEY_CAPTIONS_ENABLED, false)
                    )
                    _state.update {
                        it.copy(
                            isBusy = false,
                            status = null,
                            hasProject = true,
                            review = review
                        )
                    }
                    persist(review)
                },
                onFailure = { failure -> fail("Could not read the video", failure) }
            )
        }
    }

    /**
     * Re-runs the detection with a moved slider.
     *
     * Synchronous because it is arithmetic over readings already in memory — a ten-minute take is
     * 30,000 comparisons, which is not worth a coroutine or a spinner.
     */
    fun updateSettings(settings: SilenceSettings) {
        val levels = analysis?.levels ?: return
        _state.update { current ->
            val review = current.review ?: return@update current
            // A saved review is a finished result, not a draft. Letting the sliders move on it
            // would show numbers that no longer describe the file already in the gallery.
            if (review.savedName != null) current
            else current.copy(
                review = review.copy(settings = settings, keep = detect(levels, settings))
            )
        }
        _state.value.review?.let(::persist)
    }

    /** Trims one generated clip without forcing the detector to rebuild the whole timeline. */
    fun updateClip(index: Int, startMs: Long, endMs: Long) {
        _state.update { current ->
            val review = current.review ?: return@update current
            if (review.savedName != null || index !in review.keep.indices) return@update current
            current.copy(
                review = review.copy(
                    keep = CutTimeline.trim(review.keep, index, startMs, endMs, review.originalMs)
                )
            )
        }
        _state.value.review?.let(::persist)
    }

    fun removeClip(index: Int) {
        _state.update { current ->
            val review = current.review ?: return@update current
            if (review.savedName != null || index !in review.keep.indices) return@update current
            current.copy(review = review.copy(keep = review.keep.filterIndexed { i, _ -> i != index }))
        }
        _state.value.review?.let(::persist)
    }

    /**
     * The current cut as something playable, for the preview above the sliders.
     *
     * Built from the same [ClipExporter.cutComposition] the export uses, so the preview cannot
     * drift away from the file: if what you watch is wrong, the export is wrong the same way.
     *
     * Captions are remapped here exactly as [save] does. Previewing the source-timeline captions
     * would show them sliding out of sync and send someone hunting a bug that is not there.
     */
    fun previewComposition(): Composition? {
        val review = _state.value.review ?: return null
        val source = analysis?.source ?: return null
        if (review.keep.isEmpty()) return null

        return exporter.cutComposition(
            source = source,
            keep = review.keep,
            captions = if (review.captionsEnabled) {
                Segment.remap(review.captions.orEmpty(), review.keep)
            } else {
                emptyList()
            },
            sourceHeight = analysis?.videoInfo?.height,
            sourceDurationUs = analysis?.videoInfo?.durationUs
        )
    }

    /** Encodes the cut and publishes it to Movies/Cutly. */
    fun save() {
        val current = _state.value
        val review = current.review ?: return
        val source = analysis?.source ?: return
        if (current.isBusy || !review.canSave || review.savedName != null) return

        _state.update { it.copy(isBusy = true, status = "Cutting…", error = null) }

        viewModelScope.launch {
            val name = "cutly_cut_${System.currentTimeMillis()}.mp4"
            // Remapped here, against the spans actually being exported, because every removed gap
            // pulls the captions after it earlier — skip this and the drift grows with each cut.
            val captions = if (review.captionsEnabled) {
                Segment.remap(review.captions.orEmpty(), review.keep)
            } else {
                emptyList()
            }
            val result = runCatching {
                val cut = exporter.exportCut(source, review.keep, captions, analysis?.videoInfo)
                // The MediaStore copy streams the whole file; on Main that is an ANR.
                withContext(Dispatchers.IO) {
                    try {
                        MediaSaver.saveVideo(getApplication(), cut, name)
                    } finally {
                        cut.delete()
                    }
                }
            }

            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            status = null,
                            review = it.review?.copy(savedName = name)
                        )
                    }
                },
                onFailure = { failure -> fail("Could not save the cut", failure) }
            )
        }
    }

    /**
     * Fetches the transcript so the cut can be captioned.
     *
     * Opt-in rather than automatic, because it is the one part of this service that goes online.
     * The audio is re-extracted rather than held from [analyze]: a second Transformer pass on an
     * explicit tap is cheaper to reason about than a temp file whose lifetime spans the sheet.
     */
    fun addCaptions() {
        val review = _state.value.review ?: return
        if (review.captions != null) {
            setCaptionsEnabled(true)
            return
        }
        requestTranscript(enableCaptions = true)
    }

    fun transcribe() {
        if (_state.value.review?.captions != null) return
        requestTranscript(enableCaptions = false)
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        _state.update { current ->
            val review = current.review ?: return@update current
            if (review.savedName != null || (enabled && review.captions == null)) current
            else current.copy(review = review.copy(captionsEnabled = enabled))
        }
        _state.value.review?.let(::persist)
    }

    private fun requestTranscript(enableCaptions: Boolean) {
        val current = _state.value
        val review = current.review ?: return
        val source = analysis?.source ?: return
        if (current.isBusy || review.captions != null || review.savedName != null) return

        val transcriber = CloudTranscriberFactory.create(settings)
        if (transcriber == null) {
            _state.update { it.copy(error = "Add an OpenAI or Gemini key in Settings.") }
            return
        }

        _state.update { it.copy(isBusy = true, status = "Transcribing…", error = null) }

        transcriptJob = viewModelScope.launch {
            // Transformer needs the main looper, so extraction stays on it; the upload moves off.
            val audio = try {
                exporter.extractAudio(source)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false, status = null) }
                throw cancelled
            } catch (failure: Throwable) {
                fail("Could not transcribe", failure)
                return@launch
            }
            try {
                val segments = retry(attempts = TRANSCRIBE_ATTEMPTS, baseDelayMs = 1_500L) { attempt ->
                    if (attempt > 0) {
                        _state.update {
                            it.copy(status = "Transcription failed. Retrying…", error = null)
                        }
                    }
                    transcriber.transcribe(audio)
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        status = null,
                        review = it.review?.copy(captions = segments),
                        // An empty transcript is a real answer, and silently leaving the
                        // button unchanged would read as the request having failed.
                        error = if (segments.isEmpty()) "No speech found to caption." else null
                    )
                }
                if (segments.isNotEmpty() && enableCaptions) setCaptionsEnabled(true)
                _state.value.review?.let(::persist)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false, status = null) }
            } catch (failure: Throwable) {
                fail("Could not transcribe", failure)
            } finally {
                audio.delete()
                // A newer request may already own the handle; only the job that set it clears it.
                if (transcriptJob === coroutineContext[Job]) transcriptJob = null
            }
        }
    }

    fun cancelTranscription() {
        transcriptJob?.cancel()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Settings is a separate screen; this picks up a key pasted there on the way back. */
    fun refreshSettings() {
        _state.update { it.copy(cloudProvider = settings.cloudProvider) }
    }

    fun closeReview() {
        // A transcript that lands after the sheet is gone has nowhere to go.
        transcriptJob?.cancel()
        analysis = null
        _state.update { it.copy(review = null) }
    }

    fun openProject() {
        if (!_state.value.isBusy && projectSource.exists()) {
            analyze(Uri.fromFile(projectSource), persistSource = false)
        }
    }

    private fun detect(levels: PcmDecoder.Levels, settings: SilenceSettings): List<Span> =
        SilenceDetector.keepSpans(levels.db, levels.frameMs, levels.durationMs, settings)

    private fun fail(prefix: String, cause: Throwable) {
        _state.update {
            it.copy(
                isBusy = false,
                status = null,
                error = "$prefix: ${cause.message ?: cause::class.simpleName}"
            )
        }
    }

    private fun copyProjectSource(source: Uri): Uri {
        val pending = File(projectDir, "source.pending")
        try {
            getApplication<Application>().contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "The selected video cannot be opened" }
                pending.outputStream().use(input::copyTo)
            }
            require(pending.length() > 0L) { "The selected video is empty" }
            Files.move(
                pending.toPath(),
                projectSource.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            projectPrefs.edit().clear().apply()
            projectLevels.delete()
            return Uri.fromFile(projectSource)
        } finally {
            pending.delete()
        }
    }

    private fun persist(review: Review) {
        val captions = JSONArray().apply {
            review.captions.orEmpty().forEach { segment ->
                put(JSONObject().apply {
                    put("start", segment.startMs)
                    put("end", segment.endMs)
                    put("text", segment.text)
                })
            }
        }
        projectPrefs.edit()
            .putFloat(KEY_THRESHOLD, review.settings.thresholdDb)
            .putLong(KEY_MIN_SILENCE, review.settings.minSilenceMs)
            .putLong(KEY_PAD, review.settings.padMs)
            .putString(KEY_SPANS, review.keep.joinToString(";") { "${it.startMs},${it.endMs}" })
            .putString(KEY_CAPTIONS, captions.toString())
            .putBoolean(KEY_HAS_TRANSCRIPT, review.captions != null)
            .putBoolean(KEY_CAPTIONS_ENABLED, review.captionsEnabled)
            .apply()
    }

    private fun persistLevels(levels: PcmDecoder.Levels) {
        DataOutputStream(projectLevels.outputStream().buffered()).use { output ->
            output.writeLong(levels.frameMs)
            output.writeLong(levels.durationMs)
            output.writeInt(levels.db.size)
            levels.db.forEach(output::writeFloat)
        }
    }

    private fun restoredLevels(): PcmDecoder.Levels? = runCatching {
        if (!projectLevels.exists()) return null
        DataInputStream(projectLevels.inputStream().buffered()).use { input ->
            val frameMs = input.readLong()
            val durationMs = input.readLong()
            val size = input.readInt()
            require(frameMs > 0 && durationMs > 0 && size in 1..MAX_LEVELS)
            PcmDecoder.Levels(FloatArray(size) { input.readFloat() }, frameMs, durationMs)
        }
    }.getOrNull()

    private fun restoredSettings() = SilenceSettings(
        thresholdDb = projectPrefs.getFloat(KEY_THRESHOLD, SilenceSettings().thresholdDb),
        minSilenceMs = projectPrefs.getLong(KEY_MIN_SILENCE, SilenceSettings().minSilenceMs),
        padMs = projectPrefs.getLong(KEY_PAD, SilenceSettings().padMs)
    )

    private fun restoredSpans(durationMs: Long): List<Span> =
        projectPrefs.getString(KEY_SPANS, null)
            ?.split(';')
            ?.mapNotNull { value ->
                val parts = value.split(',')
                val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                Span(start, end).takeIf { start >= 0 && end > start && end <= durationMs }
            }
            ?.takeIf { spans -> spans.zipWithNext().all { (a, b) -> a.endMs <= b.startMs } }
            .orEmpty()

    private fun restoredCaptions(): List<Segment>? {
        if (!projectPrefs.getBoolean(KEY_HAS_TRANSCRIPT, false)) return null
        return runCatching {
            val json = JSONArray(projectPrefs.getString(KEY_CAPTIONS, "[]"))
            List(json.length()) { index ->
                val item = json.getJSONObject(index)
                Segment(item.getLong("start"), item.getLong("end"), item.getString("text"))
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_THRESHOLD = "threshold"
        const val KEY_MIN_SILENCE = "min-silence"
        const val KEY_PAD = "pad"
        const val KEY_SPANS = "spans"
        const val KEY_CAPTIONS = "captions"
        const val KEY_HAS_TRANSCRIPT = "has-transcript"
        const val KEY_CAPTIONS_ENABLED = "captions-enabled"
        const val MAX_LEVELS = 3_600_000
        const val TRANSCRIBE_ATTEMPTS = 3
    }
}
