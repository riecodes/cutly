package com.eirmon.cutly

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eirmon.cutly.data.AppSettings
import com.eirmon.cutly.data.TranscriptEntry
import com.eirmon.cutly.data.TranscriptStore
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.transcribe.SherpaModelManager
import com.eirmon.cutly.transcribe.TranscriberFactory
import com.eirmon.cutly.transcribe.TranscriptionEngine
import com.eirmon.cutly.transcribe.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The quick transcribe tool: a video picked off the phone in, a saved transcript out.
 *
 * Separate from [EditorViewModel] on purpose. Nothing here becomes a project, and a transcript
 * running in the background must not block the project grid or the editor.
 */
class TranscriptsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val entries: List<TranscriptEntry> = emptyList(),
        /** Display name of the video being transcribed; null when idle. One job at a time. */
        val running: String? = null,
        val status: String? = null,
        val error: String? = null,
        val engineLabel: String = "",
        /** Who a cloud transcript would upload to; null when no key is set. */
        val cloudProvider: String? = null,
        val needsKey: Boolean = false,
        /** Only cloud transcripts leave the phone, so only those go through the consent gate. */
        val uploads: Boolean = false,
        /** Id of the entry that just finished, so the app can open it once. */
        val finished: String? = null
    )

    private val settings = AppSettings(application)
    private val sherpa = SherpaModelManager(application)
    private val exporter = ClipExporter(application)
    private val store = TranscriptStore(File(application.filesDir, "transcripts.json"))

    private var job: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Rereads the Settings choices and the history. Called whenever a transcript screen shows. */
    fun refresh() {
        _state.update {
            it.copy(
                engineLabel = TranscriberFactory.label(settings),
                cloudProvider = settings.cloudProvider,
                needsKey = settings.engine == TranscriptionEngine.CLOUD && settings.cloudProvider == null,
                uploads = settings.engine == TranscriptionEngine.CLOUD
            )
        }
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(entries = entries) }
        }
    }

    fun transcribe(video: Uri) {
        if (_state.value.running != null) return
        val transcriber = runCatching {
            TranscriberFactory.create(getApplication(), settings, sherpa)
        }.getOrElse { failure ->
            _state.update { it.copy(error = failure.message ?: "Transcription is not set up.") }
            return
        }
        val label = TranscriberFactory.label(settings)
        _state.update { it.copy(running = "Video", status = "Transcribing $label…", error = null) }

        job = viewModelScope.launch {
            var audio: File? = null
            try {
                val (name, durationMs) = withContext(Dispatchers.IO) { describe(video) }
                _state.update { it.copy(running = name) }
                // Transformer needs the main looper, so extraction stays on it.
                val extracted = exporter.extractAudio(video).also { audio = it }
                val segments = retry(attempts = TRANSCRIBE_ATTEMPTS, baseDelayMs = 1_500L) { attempt ->
                    if (attempt > 0) _state.update { it.copy(status = "Transcription failed. Retrying…") }
                    transcriber.transcribe(extracted)
                }
                // An empty transcript is still saved: "(no speech)" is a real answer.
                val entry = TranscriptEntry(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    engine = label,
                    segments = segments
                )
                withContext(Dispatchers.IO) { store.add(entry) }
                _state.update {
                    it.copy(entries = listOf(entry) + it.entries, running = null, status = null, finished = entry.id)
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(running = null, status = null) }
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        running = null,
                        status = null,
                        error = "Could not transcribe: ${failure.message ?: failure::class.simpleName}"
                    )
                }
            } finally {
                audio?.delete()
                if (job === coroutineContext[Job]) job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(id: String) {
        _state.update { state -> state.copy(entries = state.entries.filter { it.id != id }) }
        viewModelScope.launch(Dispatchers.IO) { store.delete(id) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun consumeFinished() {
        _state.update { it.copy(finished = null) }
    }

    /** The picked file's name without its extension, and its length; both are best effort. */
    private fun describe(video: Uri): Pair<String, Long> {
        val context = getApplication<Application>()
        val name = runCatching {
            context.contentResolver.query(video, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Video"
        val durationMs = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, video)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull() ?: 0L
        return name to durationMs
    }

    private companion object {
        const val TRANSCRIBE_ATTEMPTS = 3
    }
}
