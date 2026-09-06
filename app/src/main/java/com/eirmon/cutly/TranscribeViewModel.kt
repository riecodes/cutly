package com.eirmon.cutly

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.transcribe.OnDeviceTranscriber
import com.eirmon.cutly.transcribe.Segment
import com.eirmon.cutly.transcribe.TranscriptionLanguage
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
        val transcript: String? = null,
        /**
         * Which model the on-device recogniser loads.
         *
         * It takes one language per run, so this is a choice the user has to make rather than
         * something to detect: a Taglish take is transcribed by whichever half it is mostly in.
         */
        val language: TranscriptionLanguage = TranscriptionLanguage.DEFAULT
    )

    private val exporter = ClipExporter(application)
    private val onDevice = OnDeviceTranscriber(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setLanguage(language: TranscriptionLanguage) {
        _state.update { if (it.isBusy) it else it.copy(language = language) }
    }

    fun transcribe(video: Uri) {
        if (_state.value.isBusy) return
        val language = _state.value.language

        _state.update { it.copy(isBusy = true, status = "Extracting audio…", error = null) }
        viewModelScope.launch {
            // Transformer needs the main looper, so this stays on it; the decode moves itself off.
            val result = runCatching {
                val audio = exporter.extractAudio(video)
                _state.update { it.copy(status = "Transcribing on device…") }
                try {
                    Segment.render(onDevice.transcribe(audio, language))
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
