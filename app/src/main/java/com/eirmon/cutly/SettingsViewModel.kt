package com.eirmon.cutly

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.data.AppSettings
import com.eirmon.cutly.transcribe.OnDeviceTranscriber
import com.eirmon.cutly.transcribe.SherpaModelManager
import com.eirmon.cutly.transcribe.SherpaModelState
import com.eirmon.cutly.transcribe.TranscriptionEngine
import com.eirmon.cutly.transcribe.TranscriptionLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The transcription choices: which engine, which language for the phone's recogniser, and the
 * optional Whisper model's download state. Keys are edited straight into [AppSettings] by the
 * screen; this owns only what needs a coroutine or a service binding.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val engine: TranscriptionEngine = TranscriptionEngine.SYSTEM,
        /**
         * What this phone's recogniser will accept, read from the device once.
         *
         * Empty means on-device transcription is unavailable here, which is a real state worth
         * showing rather than an error to hide: some phones have no recogniser at all.
         */
        val languages: List<TranscriptionLanguage> = emptyList(),
        val language: TranscriptionLanguage? = null,
        val sherpaModel: SherpaModelState = SherpaModelState.Missing,
        val cloudProvider: String? = null
    )

    private val settings = AppSettings(application)
    private val sherpa = SherpaModelManager(application)
    private var modelPoll: Job? = null

    private val _state = MutableStateFlow(
        UiState(engine = settings.engine, cloudProvider = settings.cloudProvider)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Builds before the engine setting existed kept the Whisper choice in the model manager.
        if (sherpa.isSelected() && settings.engine == TranscriptionEngine.SYSTEM) {
            settings.engine = TranscriptionEngine.SHERPA
            _state.update { it.copy(engine = TranscriptionEngine.SHERPA) }
        }

        // Asking the recogniser costs a service binding, so it happens once here.
        viewModelScope.launch {
            val languages = runCatching {
                OnDeviceTranscriber.languages(application)
            }.getOrDefault(emptyList())
            val chosen = languages.firstOrNull { it.tag == settings.languageTag }
                // Default to something that works now, not merely something that exists.
                ?: languages.firstOrNull { it.installed }
                ?: languages.firstOrNull()
            if (chosen != null && settings.languageTag == null) settings.languageTag = chosen.tag
            _state.update { it.copy(languages = languages, language = chosen) }
        }

        pollModel()
    }

    /** Re-reads the keys after the screen has edited them. */
    fun refreshCloud() {
        _state.update { current ->
            val provider = settings.cloudProvider
            if (provider == null && current.engine == TranscriptionEngine.CLOUD) {
                setEngine(TranscriptionEngine.SYSTEM)
            }
            current.copy(cloudProvider = provider)
        }
    }

    /** Returns false when the choice needs something first: a key, or the downloaded model. */
    fun setEngine(engine: TranscriptionEngine): Boolean {
        when (engine) {
            TranscriptionEngine.CLOUD -> if (!settings.hasCloudKey) return false
            TranscriptionEngine.SHERPA -> if (_state.value.sherpaModel !is SherpaModelState.Ready) return false
            TranscriptionEngine.SYSTEM -> Unit
        }
        settings.engine = engine
        sherpa.select(engine == TranscriptionEngine.SHERPA)
        _state.update { it.copy(engine = engine) }
        return true
    }

    fun setLanguage(language: TranscriptionLanguage) {
        settings.languageTag = language.tag
        _state.update { it.copy(language = language) }
    }

    /**
     * DownloadManager survives this process. Polling its persisted ids reconnects the UI to an
     * in-flight transfer after recreation, then performs the integrity-checked install. The loop
     * ends as soon as the model is settled, so a phone that never downloads it pays nothing.
     */
    private fun pollModel() {
        if (modelPoll?.isActive == true) return
        modelPoll = viewModelScope.launch {
            do {
                val modelState = runCatching { sherpa.state() }
                    .getOrElse { SherpaModelState.Failed(it.message ?: "Could not read model") }
                _state.update { current -> current.copy(sherpaModel = modelState) }

                if (modelState is SherpaModelState.Verifying) {
                    runCatching { sherpa.install() }.fold(
                        onSuccess = {
                            _state.update { it.copy(sherpaModel = SherpaModelState.Ready) }
                            setEngine(TranscriptionEngine.SHERPA)
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

                val inFlight = modelState is SherpaModelState.Downloading ||
                    modelState is SherpaModelState.Verifying
                if (inFlight) delay(if (modelState is SherpaModelState.Downloading) 500 else 2_000)
            } while (inFlight)
        }
    }

    fun downloadSherpaModel() {
        if (_state.value.sherpaModel is SherpaModelState.Downloading) return
        _state.update {
            it.copy(sherpaModel = SherpaModelState.Downloading(0, SherpaModelManager.TOTAL_BYTES))
        }
        viewModelScope.launch {
            runCatching { sherpa.download() }
                .onSuccess { pollModel() }
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
        viewModelScope.launch {
            sherpa.delete()
            _state.update { it.copy(sherpaModel = SherpaModelState.Missing) }
            if (_state.value.engine == TranscriptionEngine.SHERPA) setEngine(TranscriptionEngine.SYSTEM)
        }
    }
}
