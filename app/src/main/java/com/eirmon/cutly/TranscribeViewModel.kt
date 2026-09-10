package com.eirmon.cutly

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.transcribe.CloudTranscriberFactory
import com.eirmon.cutly.transcribe.OnDeviceTranscriber
import com.eirmon.cutly.transcribe.Segment
import com.eirmon.cutly.transcribe.SherpaModelManager
import com.eirmon.cutly.transcribe.SherpaModelState
import com.eirmon.cutly.transcribe.SherpaTranscriber
import com.eirmon.cutly.transcribe.Transcriber
import com.eirmon.cutly.transcribe.TranscriptionLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TranscriptionEngine { SYSTEM, SHERPA, GEMINI }

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
        val language: TranscriptionLanguage? = null,
        /** Which recogniser handles the next picked video. */
        val engine: TranscriptionEngine = TranscriptionEngine.SYSTEM,
        /** Download/install state for the optional multilingual Whisper model. */
        val sherpaModel: SherpaModelState = SherpaModelState.Missing,
        /** Whether this build has the local Gemini credential needed for the cloud option. */
        val geminiConfigured: Boolean = false
    )

    private val exporter = ClipExporter(application)
    private val sherpa = SherpaModelManager(application)
    private val gemini = CloudTranscriberFactory.createGemini(BuildConfig.GEMINI_API_KEY)
    private val _state = MutableStateFlow(
        UiState(
            engine = if (sherpa.isSelected()) TranscriptionEngine.SHERPA
            else TranscriptionEngine.SYSTEM,
            geminiConfigured = gemini != null
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Asking the recogniser costs a service binding, so it happens once here rather than on
        // every recomposition of the picker.
        viewModelScope.launch {
            val languages = runCatching {
                OnDeviceTranscriber.languages(application)
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    languages = languages,
                    // Default to something that works now, not merely something that exists.
                    language = languages.firstOrNull { language -> language.installed }
                        ?: languages.firstOrNull()
                )
            }
        }

        // DownloadManager survives this process. Polling its persisted ids reconnects the UI to
        // an in-flight transfer after recreation, then performs the integrity-checked install.
        viewModelScope.launch {
            while (true) {
                val modelState = runCatching { sherpa.state() }
                    .getOrElse { SherpaModelState.Failed(it.message ?: "Could not read model") }
                _state.update { current -> current.copy(sherpaModel = modelState) }

                if (modelState is SherpaModelState.Verifying) {
                    runCatching { sherpa.install() }.fold(
                        onSuccess = {
                            sherpa.select(true)
                            _state.update {
                                it.copy(
                                    sherpaModel = SherpaModelState.Ready,
                                    engine = TranscriptionEngine.SHERPA
                                )
                            }
                        },
                        onFailure = { failure ->
                            _state.update {
                                it.copy(
                                    sherpaModel = SherpaModelState.Failed(
                                        failure.message ?: "Model verification failed"
                                    )
                                )
                            }
                        }
                    )
                }

                delay(if (modelState is SherpaModelState.Downloading) 500 else 2_000)
            }
        }
    }

    fun setLanguage(language: TranscriptionLanguage) {
        _state.update { if (it.isBusy) it else it.copy(language = language) }
    }

    fun downloadSherpaModel() {
        if (_state.value.isBusy || _state.value.sherpaModel is SherpaModelState.Downloading) return
        _state.update {
            it.copy(
                sherpaModel = SherpaModelState.Downloading(0, SherpaModelManager.TOTAL_BYTES)
            )
        }
        viewModelScope.launch {
            runCatching { sherpa.download() }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            sherpaModel = SherpaModelState.Failed(
                                failure.message ?: "Could not start model download"
                            )
                        )
                    }
                }
        }
    }

    fun deleteSherpaModel() {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            sherpa.delete()
            _state.update {
                it.copy(
                    sherpaModel = SherpaModelState.Missing,
                    engine = TranscriptionEngine.SYSTEM
                )
            }
        }
    }

    fun useSherpaModel() {
        if (_state.value.isBusy || _state.value.sherpaModel !is SherpaModelState.Ready) return
        sherpa.select(true)
        _state.update { it.copy(engine = TranscriptionEngine.SHERPA, error = null) }
    }

    fun useSystemRecognizer() {
        if (_state.value.isBusy) return
        sherpa.select(false)
        _state.update { it.copy(engine = TranscriptionEngine.SYSTEM, error = null) }
    }

    fun useGemini() {
        if (_state.value.isBusy) return
        if (gemini == null) {
            _state.update {
                it.copy(error = "No Gemini API key set. Add gemini.api.key to local.properties.")
            }
            return
        }
        sherpa.select(false)
        _state.update { it.copy(engine = TranscriptionEngine.GEMINI, error = null) }
    }

    fun transcribe(video: Uri) {
        if (_state.value.isBusy) return
        val current = _state.value
        val language = current.language
        if (current.engine == TranscriptionEngine.SYSTEM && language == null) {
            _state.update {
                it.copy(error = "This phone has no on-device speech recogniser.")
            }
            return
        }

        _state.update { it.copy(isBusy = true, status = "Extracting audio…", error = null) }
        viewModelScope.launch {
            // Transformer needs the main looper, so this stays on it; the decode moves itself off.
            val result = runCatching {
                val transcriber: Transcriber = when (current.engine) {
                    TranscriptionEngine.SYSTEM ->
                        OnDeviceTranscriber(getApplication(), requireNotNull(language))
                    TranscriptionEngine.SHERPA -> {
                        val files = sherpa.files()
                            ?: error("The offline Whisper model is not installed")
                        SherpaTranscriber(getApplication(), files)
                    }
                    TranscriptionEngine.GEMINI ->
                        gemini ?: error(
                            "No Gemini API key set. Add gemini.api.key to local.properties."
                        )
                }
                val audio = exporter.extractAudio(video)
                _state.update {
                    it.copy(
                        status = when (current.engine) {
                            TranscriptionEngine.SYSTEM -> "Transcribing on device…"
                            TranscriptionEngine.SHERPA -> "Transcribing with offline Whisper…"
                            TranscriptionEngine.GEMINI -> "Transcribing with Gemini…"
                        }
                    )
                }
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
