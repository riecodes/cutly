package com.eirmon.cutly

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.audio.PcmDecoder
import com.eirmon.cutly.audio.SilenceDetector
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.export.MediaSaver
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.annotation.OptIn
import com.eirmon.cutly.transcribe.GeminiTranscriber
import com.eirmon.cutly.transcribe.Segment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        /** Non-null while the review sheet is open. */
        val review: Review? = null
    )

    /** What the current settings would produce, without having encoded anything yet. */
    data class Review(
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
    }

    /**
     * The decoded loudness of the picked video, held so the sliders stay instant.
     *
     * A ten-minute take is about 120 KB of readings, which is why keeping it costs nothing and
     * re-decoding on every slider move would cost seconds.
     */
    private class Analysis(val source: Uri, val levels: PcmDecoder.Levels)

    private val exporter = ClipExporter(application)
    private val transcriber = GeminiTranscriber(BuildConfig.GEMINI_API_KEY)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var analysis: Analysis? = null

    /** Measures the video and opens the review sheet. Nothing is encoded or written here. */
    fun analyze(video: Uri) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Reading the audio…", error = null) }

        viewModelScope.launch {
            // Transformer needs the main looper, so extraction stays on it; the decode moves off.
            val result = runCatching {
                val audio = exporter.extractAudio(video)
                try {
                    _state.update { it.copy(status = "Measuring silence…") }
                    PcmDecoder.levels(audio)
                } finally {
                    audio.delete()
                }
            }

            result.fold(
                onSuccess = { levels ->
                    analysis = Analysis(video, levels)
                    val settings = SilenceSettings()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            status = null,
                            review = Review(settings, detect(levels, settings), levels.durationMs)
                        )
                    }
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
            source,
            review.keep,
            Segment.remap(review.captions.orEmpty(), review.keep)
        )
    }

    /** Encodes the cut and publishes it to Movies/Cutly. */
    fun save() {
        val current = _state.value
        val review = current.review ?: return
        val source = analysis?.source ?: return
        if (current.isBusy || !review.hasSomethingToCut || review.savedName != null) return

        _state.update { it.copy(isBusy = true, status = "Cutting…", error = null) }

        viewModelScope.launch {
            val name = "cutly_cut_${System.currentTimeMillis()}.mp4"
            // Remapped here, against the spans actually being exported, because every removed gap
            // pulls the captions after it earlier — skip this and the drift grows with each cut.
            val captions = Segment.remap(review.captions.orEmpty(), review.keep)
            val result = runCatching {
                val cut = exporter.exportCut(source, review.keep, captions)
                try {
                    MediaSaver.saveVideo(getApplication(), cut, name)
                } finally {
                    cut.delete()
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
        val current = _state.value
        val review = current.review ?: return
        val source = analysis?.source ?: return
        if (current.isBusy || review.captions != null || review.savedName != null) return

        if (BuildConfig.GEMINI_API_KEY.isEmpty()) {
            _state.update {
                it.copy(error = "No Gemini API key set. Add gemini.api.key to local.properties.")
            }
            return
        }

        _state.update { it.copy(isBusy = true, status = "Transcribing…", error = null) }

        viewModelScope.launch {
            // Transformer needs the main looper, so extraction stays on it; the upload moves off.
            val result = runCatching {
                val audio = exporter.extractAudio(source)
                try {
                    transcriber.transcribe(audio)
                } finally {
                    audio.delete()
                }
            }

            result.fold(
                onSuccess = { segments ->
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
                },
                onFailure = { failure -> fail("Could not transcribe", failure) }
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun closeReview() {
        analysis = null
        _state.update { it.copy(review = null) }
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
}
