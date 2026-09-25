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
import androidx.camera.core.CameraSelector
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.model.Clip
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
import java.io.IOException
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
        /** Source regions deleted by hand; a re-detection leaves them out. */
        val removed: List<Span> = emptyList(),
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
        /**
         * Set once the cut has been written to Movies/Cutly, and cleared by the next edit that
         * would change that file, so the header offers EXPORT again.
         */
        val savedName: String? = null
    ) {
        val keptMs: Long get() = keep.sumOf { it.durationMs }

        /** Whether this review would export differently from [before]. A transcript alone does not. */
        fun changesExport(before: Review): Boolean =
            keep != before.keep ||
                captionsEnabled != before.captionsEnabled ||
                (captionsEnabled && captions != before.captions)
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

    /** Joins a gallery video into the open project, as the clip at [at] in the output order. */
    fun appendImport(video: Uri, at: Int) = append(at, "Copying the video…") {
        val app = getApplication<Application>()
        val temp = File(app.cacheDir, "cutly_append_${System.currentTimeMillis()}.mp4")
        val input = app.contentResolver.openInputStream(video)
            ?: throw IOException("The selected video cannot be opened")
        input.use { stream -> temp.outputStream().use(stream::copyTo) }
        if (temp.length() == 0L) throw IOException("The selected video is empty")
        temp
    }

    /** Joins a merged camera take (already a cache file) into the open project at [at]. */
    fun appendTake(file: File, at: Int) = append(at, "Adding the take…") { file }

    /**
     * The footage itself is joined onto the end of the source file, but the clip for it is
     * slotted into the output order at [at], so it plays wherever the user was looking. The
     * existing cut is left as it is, for the user to trim, split or re-detect. Captions stay on
     * their old timestamps, which the join does not move; the new part is simply uncaptioned
     * until regenerated.
     */
    private fun append(at: Int, status: String, produce: suspend () -> File) {
        val review = _state.value.review ?: return
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = status, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val added = withContext(Dispatchers.IO) { produce() }
                val merged = try {
                    _state.update { it.copy(status = "Joining the clips…") }
                    exporter.merge(listOf(clipOf(store.source(review.projectId)), clipOf(added)))
                } finally {
                    added.delete()
                }
                val project = withContext(Dispatchers.IO) {
                    store.replaceSource(review.projectId, merged)
                } ?: throw IllegalStateException("The project is no longer on disk")
                val slot = at.coerceIn(0, review.keep.size)
                project.copy(
                    keep = review.keep.toMutableList().apply {
                        add(slot, Span(review.originalMs, project.durationMs))
                    },
                    removed = review.removed,
                    manualEdits = true,
                    savedName = null
                ).also { withContext(Dispatchers.IO) { store.save(it) } }
            }
            result.fold(
                onSuccess = { project ->
                    _state.update { it.copy(review = null) }
                    open(project)
                },
                onFailure = { failure -> fail("Could not add the video", failure) }
            )
        }
    }

    private suspend fun clipOf(file: File): Clip {
        val info = withContext(Dispatchers.IO) { ClipProbe.probeMetadata(file) }
        return Clip(
            file = file,
            durationMs = (info?.durationUs ?: 0L) / 1000,
            lensFacing = CameraSelector.LENS_FACING_BACK,
            heightPx = info?.height ?: 1080
        )
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
                    val stored = measured.project.keep
                        .filter { it.startMs < durationMs }
                        .map { if (it.endMs > durationMs) it.copy(endMs = durationMs) else it }
                    val review = Review(
                        projectId = project.id,
                        name = project.name,
                        source = source,
                        settings = project.settings,
                        keep = stored.ifEmpty {
                            CutTimeline.subtract(detect(measured.levels, project.settings), project.removed)
                        },
                        originalMs = durationMs,
                        manualEdits = project.manualEdits && stored.isNotEmpty(),
                        removed = project.removed,
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
            else review.copy(settings = settings, keep = redetected(review, levels, settings))
        }
    }

    /**
     * Lets the sliders decide the cuts again. Trims and splits are forgotten; footage deleted by
     * hand stays deleted, and clips keep the order they were dragged or inserted into.
     */
    fun redetect() {
        val levels = analysis?.levels ?: return
        edit { review ->
            review.copy(manualEdits = false, keep = redetected(review, levels, review.settings))
        }
    }

    private fun redetected(review: Review, levels: PcmDecoder.Levels, settings: SilenceSettings): List<Span> =
        CutTimeline.orderLike(review.keep, CutTimeline.subtract(detect(levels, settings), review.removed))

    /** Drags one clip to another slot in the output order. */
    fun moveClip(from: Int, to: Int) {
        edit { review ->
            val moved = CutTimeline.move(review.keep, from, to)
            if (moved === review.keep) review else review.copy(manualEdits = true, keep = moved)
        }
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

    /** Deletes a clip for good: the region is remembered so a re-detection cannot bring it back. */
    fun removeClip(index: Int) {
        edit { review ->
            val clip = review.keep.getOrNull(index) ?: return@edit review
            review.copy(
                manualEdits = true,
                keep = review.keep.filterIndexed { i, _ -> i != index },
                removed = review.removed + clip
            )
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
            val previous = from.removeLastOrNull() ?: return@update current
            to.addLast(review)
            restored = previous
            current.copy(review = previous, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        restored?.let(::persist)
    }

    /**
     * Applies one change to the open review and writes it out. A saved project stays editable;
     * a change that would alter the exported file drops [Review.savedName], so the header stops
     * claiming the gallery copy matches and offers EXPORT again.
     */
    private fun edit(change: (Review) -> Review) {
        var changed: Review? = null
        _state.update { current ->
            val review = current.review ?: return@update current
            val edited = change(review)
            if (edited === review) return@update current
            val next = if (edited.savedName != null && edited.changesExport(review)) {
                edited.copy(savedName = null)
            } else {
                edited
            }
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
            removed = review.removed,
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

    /** Drops the saved transcript. Goes through [edit], so undo brings it back. */
    fun deleteTranscript() = edit { it.copy(captions = null, captionsEnabled = false) }

    /**
     * Asks the engine again. The old transcript stays in the review until the new one lands, and
     * the swap is one undo step, so a worse result is one tap from the previous one.
     */
    fun regenerateTranscript() {
        if (_state.value.review?.captions == null) return
        requestTranscript(enableCaptions = false, replace = true)
    }

    private fun requestTranscript(enableCaptions: Boolean, replace: Boolean = false) {
        val current = _state.value
        val review = current.review ?: return
        if (current.isBusy || (review.captions != null && !replace)) return

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
                // Through [edit] so the arrival is an undo step, whether it is the first transcript
                // or a replacement for one the user liked better.
                edit { open ->
                    open.copy(
                        captions = segments,
                        captionsEnabled = (open.captionsEnabled || enableCaptions) && segments.isNotEmpty()
                    )
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        status = null,
                        // An empty transcript is a real answer, and silently leaving the
                        // button unchanged would read as the request having failed.
                        error = if (segments.isEmpty()) "No speech found to caption." else null
                    )
                }
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
