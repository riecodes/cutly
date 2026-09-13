package com.eirmon.cutly

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import com.eirmon.cutly.audio.PcmDecoder
import com.eirmon.cutly.audio.SilenceDetector
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.data.AppSettings
import com.eirmon.cutly.data.Project
import com.eirmon.cutly.data.ProjectStore
import com.eirmon.cutly.export.ClipExporter
import com.eirmon.cutly.export.CutTimeline
import com.eirmon.cutly.export.MediaSaver
import com.eirmon.cutly.export.TimeMap
import com.eirmon.cutly.transcribe.Segment
import com.eirmon.cutly.transcribe.SherpaModelManager
import com.eirmon.cutly.transcribe.TranscriberFactory
import com.eirmon.cutly.transcribe.TranscriptionEngine
import com.eirmon.cutly.transcribe.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The project list and the one project open in the editor.
 *
 * Split into an analyse pass and an export pass on purpose. Decoding the audio is the slow part
 * and it only has to happen once per project; moving a threshold slider afterwards is arithmetic
 * over the readings already in memory, so the preview updates as the slider moves and nothing is
 * re-encoded until the user is happy with the numbers.
 */
@OptIn(UnstableApi::class)
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isBusy: Boolean = false,
        val status: String? = null,
        /** Set on failure; shown inline until the next attempt or [dismissError]. */
        val error: String? = null,
        val projects: List<Project> = emptyList(),
        /** Which recogniser a transcript would use, so the editor can label the action. */
        val engine: TranscriptionEngine = TranscriptionEngine.SYSTEM,
        val engineLabel: String = "",
        /** Who a cloud transcript would upload to. Null with [engine] CLOUD means no key is set. */
        val cloudProvider: String? = null,
        /** Non-null while a project is open in the editor. */
        val review: Review? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false
    ) {
        /** The transcript action cannot run until a key is pasted in Settings. */
        val needsKey: Boolean get() = engine == TranscriptionEngine.CLOUD && cloudProvider == null

        /** Only cloud transcripts leave the phone, so only those go through the consent gate. */
        val uploads: Boolean get() = engine == TranscriptionEngine.CLOUD
    }

    /** What the current settings would produce, without having encoded anything yet. */
    data class Review(
        val projectId: String,
        val name: String,
        val source: Uri,
        val settings: SilenceSettings,
        val keep: List<Span>,
        val originalMs: Long,
        /** True once a clip was trimmed or deleted by hand; the sliders then leave [keep] alone. */
        val manualEdits: Boolean = false,
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

        /** Cuts, not kept pieces: a trimmed head or tail is a cut with no piece before it. */
        val cutCount: Int
            get() = if (keep.isEmpty()) 0 else
                keep.size - 1 +
                    (if (keep.first().startMs > 0) 1 else 0) +
                    (if (keep.last().endMs < originalMs) 1 else 0)

        val hasSomethingToCut: Boolean get() = keep.isNotEmpty() && cutCount > 0
        val canSave: Boolean get() = keep.isNotEmpty() && (hasSomethingToCut || captionsEnabled)
    }

    /**
     * The decoded loudness of the open project, held so the sliders stay instant.
     *
     * A ten-minute take is about 120 KB of readings, which is why keeping it costs nothing and
     * re-decoding on every slider move would cost seconds.
     */
    private class Analysis(
        val project: Project,
        val levels: PcmDecoder.Levels,
        val videoInfo: ClipProbe.Info?
    )

    private val exporter = ClipExporter(application)
    private val settings = AppSettings(application)
    private val sherpa = SherpaModelManager(application)
    val store = ProjectStore(File(application.filesDir, "projects"))

    private var transcriptJob: Job? = null
    private var analysis: Analysis? = null

    /** Snapshots of [Review] before each edit. Immutable data, so a snapshot is a reference. */
    private val undoStack = ArrayDeque<Review>()
    private val redoStack = ArrayDeque<Review>()

    /** One lane, so two quick edits cannot land on disk in the wrong order. */
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshSettings()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { store.migrateLegacy(application) } }
            refreshProjects()
        }
    }

    // region projects

    fun refreshProjects() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(projects = projects) }
        }
    }

    /** Settings is a separate screen; this picks up choices made there on the way back. */
    fun refreshSettings() {
        _state.update {
            it.copy(
                engine = settings.engine,
                engineLabel = TranscriberFactory.label(settings),
                cloudProvider = settings.cloudProvider
            )
        }
    }

    /** Copies a picked video into a new project and opens it. */
    fun import(video: Uri) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Saving project…", error = null) }
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            runCatching {
                withContext(Dispatchers.IO) {
                    store.create(defaultName()) { resolver.openInputStream(video) }
                }
            }.fold(
                onSuccess = ::open,
                onFailure = { failure -> fail("Could not read the video", failure) }
            )
        }
    }

    /** Takes a merged camera take (already a file in the cache) as a new project and opens it. */
    fun adoptTake(file: File) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Saving project…", error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.adopt(file, defaultName()) } }
                .fold(
                    onSuccess = ::open,
                    onFailure = { failure -> fail("Could not save the take", failure) }
                )
        }
    }

    fun open(id: String) {
        if (_state.value.isBusy) return
        if (_state.value.review?.projectId == id) return
        _state.update { it.copy(isBusy = true, status = "Opening project…", error = null) }
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) { store.load(id) }
            if (project == null) {
                fail("Could not open the project", IllegalStateException("It is no longer on disk"))
            } else {
                open(project)
            }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            if (_state.value.review?.projectId == id) closeReview()
            refreshProjects()
        }
    }

    fun renameProject(id: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(id, name) }
            _state.update { current ->
                val review = current.review
                if (review?.projectId == id) current.copy(review = review.copy(name = name)) else current
            }
            refreshProjects()
        }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.duplicate(id) }
            refreshProjects()
        }
    }

    /** Measures the audio if it has not been measured yet, then opens the editor. */
    private fun open(project: Project) {
        _state.update { it.copy(isBusy = true, status = "Opening project…", error = null) }
        viewModelScope.launch {
            val source = Uri.fromFile(store.source(project.id))
            val result = runCatching {
                val videoInfo = withContext(Dispatchers.IO) {
                    ClipProbe.probe(getApplication(), source)
                }
                val levels = withContext(Dispatchers.IO) { store.loadLevels(project.id) } ?: run {
                    _state.update { it.copy(status = "Reading the audio…") }
                    // Transformer needs the main looper, so extraction stays on it.
                    val audio = exporter.extractAudio(source)
                    try {
                        _state.update { it.copy(status = "Measuring silence…") }
                        PcmDecoder.levels(audio).also { measured ->
                            withContext(Dispatchers.IO) { store.saveLevels(project.id, measured) }
                        }
                    } finally {
                        audio.delete()
                    }
                }
                Analysis(project, levels, videoInfo)
            }

            result.fold(
                onSuccess = { measured ->
                    analysis = measured
                    val durationMs = measured.levels.durationMs
                    val stored = measured.project.keep.filter { it.endMs <= durationMs }
                    val review = Review(
                        projectId = project.id,
                        name = project.name,
                        source = source,
                        settings = project.settings,
                        keep = stored.ifEmpty { detect(measured.levels, project.settings) },
                        originalMs = durationMs,
                        manualEdits = project.manualEdits && stored.isNotEmpty(),
                        captions = project.captions,
                        captionsEnabled = project.captionsEnabled && project.captions != null,
                        savedName = project.savedName
                    )
                    undoStack.clear()
                    redoStack.clear()
                    _state.update {
                        it.copy(isBusy = false, status = null, review = review, canUndo = false, canRedo = false)
                    }
                    persist(review)
                },
                onFailure = { failure -> fail("Could not read the video", failure) }
            )
        }
    }

    private fun defaultName(): String =
        "Take " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())

    // endregion

    // region editing

    /**
     * Re-runs the detection with a moved slider.
     *
     * Synchronous because it is arithmetic over readings already in memory: a ten-minute take is
     * 30,000 comparisons, which is not worth a coroutine or a spinner. Once a clip has been edited
     * by hand the detector keeps off; [redetect] is the explicit way back.
     */
    fun updateSettings(settings: SilenceSettings) {
        val levels = analysis?.levels ?: return
        edit { review ->
            if (review.manualEdits) review.copy(settings = settings)
            else review.copy(settings = settings, keep = detect(levels, settings))
        }
    }

    /** Throws away the hand edits and lets the sliders decide again. */
    fun redetect() {
        val levels = analysis?.levels ?: return
        edit { review -> review.copy(manualEdits = false, keep = detect(levels, review.settings)) }
    }

    /** Trims one generated clip without forcing the detector to rebuild the whole timeline. */
    fun updateClip(index: Int, startMs: Long, endMs: Long) {
        edit { review ->
            if (index !in review.keep.indices) review
            else review.copy(
                manualEdits = true,
                keep = CutTimeline.trim(review.keep, index, startMs, endMs, review.originalMs)
            )
        }
    }

    fun removeClip(index: Int) {
        edit { review ->
            if (index !in review.keep.indices) review
            else review.copy(manualEdits = true, keep = review.keep.filterIndexed { i, _ -> i != index })
        }
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        edit { review ->
            if (enabled && review.captions == null) review
            else review.copy(captionsEnabled = enabled)
        }
    }

    /** Cuts the clip under the playhead in two. [outputMs] is on the preview's clock. */
    fun splitAtPlayhead(outputMs: Long) {
        edit { review ->
            val sourceMs = TimeMap(review.keep).toSource(outputMs)
            val split = CutTimeline.split(review.keep, sourceMs)
            if (split.size == review.keep.size) review else review.copy(manualEdits = true, keep = split)
        }
    }

    fun undo() = restore(from = undoStack, to = redoStack)

    fun redo() = restore(from = redoStack, to = undoStack)

    private fun restore(from: ArrayDeque<Review>, to: ArrayDeque<Review>) {
        var restored: Review? = null
        _state.update { current ->
            val review = current.review ?: return@update current
            if (review.savedName != null) return@update current
            val previous = from.removeLastOrNull() ?: return@update current
            to.addLast(review)
            restored = previous
            current.copy(review = previous, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        restored?.let(::persist)
    }

    /**
     * Applies one change to the open review and writes it out. A saved review is a finished
     * result, not a draft: letting the sliders move on it would show numbers that no longer
     * describe the file already in the gallery.
     */
    private fun edit(change: (Review) -> Review) {
        var changed: Review? = null
        _state.update { current ->
            val review = current.review ?: return@update current
            if (review.savedName != null) return@update current
            val next = change(review)
            if (next === review) return@update current
            undoStack.addLast(review)
            while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
            redoStack.clear()
            changed = next
            current.copy(review = next, canUndo = true, canRedo = false)
        }
        changed?.let(::persist)
    }

    private fun persist(review: Review) {
        val project = analysis?.project?.takeIf { it.id == review.projectId } ?: return
        val updated = project.copy(
            name = review.name,
            updatedAt = System.currentTimeMillis(),
            settings = review.settings,
            keep = review.keep,
            manualEdits = review.manualEdits,
            captions = review.captions,
            captionsEnabled = review.captionsEnabled,
            savedName = review.savedName
        )
        analysis = analysis?.let { Analysis(updated, it.levels, it.videoInfo) }
        viewModelScope.launch(persistDispatcher) { runCatching { store.save(updated) } }
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
        val measured = analysis ?: return null
        if (review.keep.isEmpty()) return null

        return exporter.cutComposition(
            source = review.source,
            keep = review.keep,
            captions = if (review.captionsEnabled) {
                Segment.remap(review.captions.orEmpty(), review.keep)
            } else {
                emptyList()
            },
            sourceHeight = measured.videoInfo?.height,
            sourceDurationUs = measured.videoInfo?.durationUs
        )
    }

    // endregion

    // region export

    /** Encodes the cut and publishes it to Movies/Cutly. */
    fun save() {
        val current = _state.value
        val review = current.review ?: return
        val measured = analysis ?: return
        if (current.isBusy || !review.canSave || review.savedName != null) return

        _state.update { it.copy(isBusy = true, status = "Cutting…", error = null) }

        viewModelScope.launch {
            val name = "cutly_cut_${System.currentTimeMillis()}.mp4"
            // Remapped here, against the spans actually being exported, because every removed gap
            // pulls the captions after it earlier; skip this and the drift grows with each cut.
            val captions = if (review.captionsEnabled) {
                Segment.remap(review.captions.orEmpty(), review.keep)
            } else {
                emptyList()
            }
            val result = runCatching {
                val cut = exporter.exportCut(review.source, review.keep, captions, measured.videoInfo)
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
                    var saved: Review? = null
                    _state.update { state ->
                        val open = state.review?.copy(savedName = name).also { saved = it }
                        state.copy(isBusy = false, status = null, review = open)
                    }
                    saved?.let(::persist)
                },
                onFailure = { failure -> fail("Could not save the cut", failure) }
            )
        }
    }

    // endregion

    // region transcription

    /**
     * Fetches the transcript so the cut can be captioned.
     *
     * Opt-in rather than automatic, because with a cloud engine it is the one part of the editor
     * that goes online. The audio is re-extracted rather than held from [open]: a second
     * Transformer pass on an explicit tap is cheaper to reason about than a temp file whose
     * lifetime spans the whole editing session.
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

    private fun requestTranscript(enableCaptions: Boolean) {
        val current = _state.value
        val review = current.review ?: return
        if (current.isBusy || review.captions != null || review.savedName != null) return

        val transcriber = runCatching {
            TranscriberFactory.create(getApplication(), settings, sherpa)
        }.getOrElse { failure ->
            _state.update { it.copy(error = failure.message ?: "Transcription is not set up.") }
            return
        }
        val label = TranscriberFactory.label(settings)

        _state.update { it.copy(isBusy = true, status = "Transcribing $label…", error = null) }

        transcriptJob = viewModelScope.launch {
            // Transformer needs the main looper, so extraction stays on it; the work moves off.
            val audio = try {
                exporter.extractAudio(review.source)
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
                var done: Review? = null
                _state.update {
                    val open = it.review?.copy(
                        captions = segments,
                        captionsEnabled = it.review.captionsEnabled || (enableCaptions && segments.isNotEmpty())
                    ).also { updated -> done = updated }
                    it.copy(
                        isBusy = false,
                        status = null,
                        review = open,
                        // An empty transcript is a real answer, and silently leaving the
                        // button unchanged would read as the request having failed.
                        error = if (segments.isEmpty()) "No speech found to caption." else null
                    )
                }
                done?.let(::persist)
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

    // endregion

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun closeReview() {
        // A transcript that lands after the editor is gone has nowhere to go.
        transcriptJob?.cancel()
        analysis = null
        _state.update { it.copy(review = null) }
        refreshProjects()
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

    private companion object {
        const val TRANSCRIBE_ATTEMPTS = 3
        const val UNDO_DEPTH = 30
    }
}
