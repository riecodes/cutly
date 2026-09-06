package com.eirmon.cutly

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.transcribe.GeminiTranscriber
import com.eirmon.cutly.transcribe.Segment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The upload service: one video off the device, one transcript back.
 *
 * Separate from [CameraViewModel] because it owns none of the take — no clip list, no store, no
 * process-death index. A picked video is read once and forgotten.
 */
class TranscribeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isBusy: Boolean = false,
        val status: String? = null,
        /** Set on failure; shown as embedded text until the next attempt or [dismissError]. */
        val error: String? = null,
        /** Non-null while the transcript sheet is open. */
        val transcript: String? = null
    )

    private val exporter = ClipExporter(application)
    private val transcriber = GeminiTranscriber(BuildConfig.GEMINI_API_KEY)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun transcribe(video: Uri) {
        if (_state.value.isBusy) return
        if (BuildConfig.GEMINI_API_KEY.isEmpty()) {
            _state.update {
                it.copy(error = "No Gemini API key set. Add gemini.api.key to local.properties.")
            }
            return
        }

        _state.update { it.copy(isBusy = true, status = "Extracting audio…", error = null) }
        viewModelScope.launch {
            // Transformer needs the main looper, so this stays on it; the upload moves itself off.
            val result = runCatching {
                val audio = exporter.extractAudio(video)
                _state.update { it.copy(status = "Transcribing…") }
                try {
                    Segment.render(transcriber.transcribe(audio))
                } finally {
                    audio.delete()
                }
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { current.copy(isBusy = false, status = null, transcript = it) },
                    onFailure = {
                        current.copy(
                            isBusy = false,
                            status = null,
                            error = "Transcription failed: ${it.message ?: it::class.simpleName}"
                        )
                    }
                )
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Editable, because Tagalog proper nouns come back wrong often enough to matter. */
    fun editTranscript(text: String) {
        _state.update { if (it.transcript == null) it else it.copy(transcript = text) }
    }

    fun closeTranscript() {
        _state.update { it.copy(transcript = null) }
    }
}
