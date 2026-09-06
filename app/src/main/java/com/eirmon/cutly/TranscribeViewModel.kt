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
         * What this phone's recogniser will accept, read from the device on first composition.
         *
         * Empty means on-device transcription is unavailable here, which is a real state worth
         * showing rather than an error to hide: some phones have no recogniser at all.
         */
        val languages: List<TranscriptionLanguage> = emptyList(),
        /**
         * Which model the recogniser loads. It takes one language per run, so this is a choice
         * the user makes rather than something to detect: a Taglish take is transcribed by
         * whichever half it is mostly in.
         */
        val language: TranscriptionLanguage? = null
    )

    private val exporter = ClipExporter(application)
    private val onDevice = OnDeviceTranscriber(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Asking the recogniser costs a service binding, so it happens once here rather than on
        // every recomposition of the picker.
        viewModelScope.launch {
            val languages = runCatching { onDevice.languages() }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    languages = languages,
                    // Default to something that works now, not merely something that exists.
                    language = languages.firstOrNull { language -> language.installed }
                        ?: languages.firstOrNull()
                )
            }
        }
    }

    fun setLanguage(language: TranscriptionLanguage) {
        _state.update { if (it.isBusy) it else it.copy(language = language) }
    }

    fun transcribe(video: Uri) {
        if (_state.value.isBusy) return
        val language = _state.value.language
        if (language == null) {
            _state.update {
                it.copy(error = "This phone has no on-device speech recogniser.")
            }
            return
        }

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
