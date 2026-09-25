package com.eirmon.cutly.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.transformer.Composition
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.eirmon.cutly.EditorViewModel
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.export.CutTimeline
import com.eirmon.cutly.export.TimeMap
import com.eirmon.cutly.transcribe.Segment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.AccentPressed
import com.eirmon.cutly.ui.theme.EditorFaint
import com.eirmon.cutly.ui.theme.EditorLine
import com.eirmon.cutly.ui.theme.EditorMuted
import com.eirmon.cutly.ui.theme.EditorPanel
import com.eirmon.cutly.ui.theme.EditorSurface
import com.eirmon.cutly.ui.theme.EditorTrack
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val NO_CLIP_SELECTED = -1
private const val PREVIEW_DEBOUNCE_MS = 150L

private enum class EditorTool(val label: String, val mark: String) {
    Clips("Clips", "▤"), Cut("Cut", "✂"), Captions("Captions", "CC"), Transcript("Transcript", "T")
}

@Composable
internal fun ProjectOpening(status: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorSurface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = status ?: "Opening project…",
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "This project is saved locally as it opens.",
                color = EditorMuted,
                fontFamily = TikTokSans,
                fontSize = 11.sp
            )
        }
    }
}

/** Full-screen project editor. Preview, clips, tools and export share one timeline. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun EditorScreen(
    review: EditorViewModel.Review,
    status: String?,
    error: String?,
    buildPreview: () -> Composition?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSplit: (outputMs: Long) -> Unit,
    engineLabel: String,
    needsKey: Boolean,
    uploads: Boolean,
    cloudProvider: String?,
    onOpenSettings: () -> Unit,
    onSettingsChange: (SilenceSettings) -> Unit,
    onRedetect: () -> Unit,
    onClipChange: (Int, Long, Long) -> Unit,
    onRemoveClip: (Int) -> Unit,
    onMoveClip: (Int, Int) -> Unit,
    onAddFromCamera: (at: Int) -> Unit,
    onAddFromGallery: (Uri, at: Int) -> Unit,
    onAddCaptions: () -> Unit,
    onCaptionsEnabled: (Boolean) -> Unit,
    onTranscribe: () -> Unit,
    onRegenerateTranscript: () -> Unit,
    onDeleteTranscript: () -> Unit,
    onCancelTranscription: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val saved = review.savedName != null
    var tool by rememberSaveable { mutableStateOf(EditorTool.Clips) }
    var selectedClip by rememberSaveable { mutableIntStateOf(NO_CLIP_SELECTED) }
    var saveRequested by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Composition?>(null) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var seekId by remember { mutableIntStateOf(0) }
    var seek by remember { mutableStateOf<PreviewSeek?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var pendingCloud by remember { mutableStateOf<(() -> Unit)?>(null) }
    val busy = status != null || saveRequested

    LaunchedEffect(review.keep.size) {
        if (selectedClip !in review.keep.indices) selectedClip = NO_CLIP_SELECTED
    }
    LaunchedEffect(review.keep) {
        playheadMs = playheadMs.coerceIn(0L, review.keptMs.coerceAtLeast(0L))
        seek = PreviewSeek(playheadMs, ++seekId)
    }
    // Building the composition allocates every clipped item; a short debounce folds a burst of
    // edits into one rebuild while the player stays mounted and keeps its frame.
    LaunchedEffect(review.keep, review.captions, review.captionsEnabled) {
        delay(PREVIEW_DEBOUNCE_MS)
        preview = buildPreview()
    }
    LaunchedEffect(saveRequested) {
        if (saveRequested) {
            onSave()
            saveRequested = false
        }
    }

    BackHandler { if (!busy) onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorSurface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        EditorHeader(
            title = if (saved) "Saved to Movies/Cutly" else review.name,
            canSave = review.canSave && !busy,
            canUndo = canUndo && !busy,
            canRedo = canRedo && !busy,
            onUndo = onUndo,
            onRedo = onRedo,
            onBack = { if (!busy) onDismiss() },
            onSave = { if (review.canSave && !busy) saveRequested = true }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 60.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CutPreviewLoading(
                    message = status ?: if (saveRequested) "Preparing export…" else "Updating preview…",
                    modifier = Modifier.fillMaxHeight()
                )
            } else {
                CutPreview(
                    composition = preview,
                    seek = seek,
                    scrubbing = scrubbing,
                    onPositionChanged = { playheadMs = it },
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }

        TimelineHeader(review, playheadMs)
        TimelineScrubber(
            positionMs = playheadMs,
            durationMs = review.keptMs,
            enabled = !busy && review.keep.isNotEmpty(),
            onScrubbingChange = { scrubbing = it },
            onSeek = { position ->
                playheadMs = position
                seek = PreviewSeek(position, ++seekId)
            }
        )
        var showAddChooser by remember { mutableStateOf(false) }
        // Frozen when the chooser opens: the preview can report a new playhead while the gallery
        // covers the screen, and the slot must be the one the user was looking at when they tapped.
        var insertAt by rememberSaveable { mutableIntStateOf(0) }
        val galleryPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { picked -> picked?.let { onAddFromGallery(it, insertAt) } }
        if (showAddChooser) {
            ChoiceDialog(
                title = when (insertAt) {
                    0 -> if (review.keep.isEmpty()) "Add a clip" else "Add a clip at the start"
                    review.keep.size -> "Add a clip at the end"
                    else -> "Add a clip after clip $insertAt"
                },
                choices = listOf(
                    "Record with the camera" to {
                        showAddChooser = false
                        onAddFromCamera(insertAt)
                    },
                    "Pick from the gallery" to {
                        showAddChooser = false
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                ),
                dismissLabel = "Cancel",
                onDismiss = { showAddChooser = false }
            )
        }

        ClipTimeline(
            source = review.source,
            clips = review.keep,
            totalMs = review.originalMs,
            playheadMs = playheadMs,
            selected = selectedClip,
            enabled = !busy,
            onClipChange = onClipChange,
            onMove = onMoveClip,
            onAdd = {
                // After the selected clip; otherwise at the clip edge nearest the caret, which is
                // the only way footage can go in front of the first clip.
                insertAt = if (selectedClip in review.keep.indices) {
                    selectedClip + 1
                } else {
                    TimeMap(review.keep).boundaryNear(playheadMs)
                }
                showAddChooser = true
            },
            onSelect = {
                tool = EditorTool.Clips
                if (selectedClip == it) {
                    selectedClip = NO_CLIP_SELECTED
                } else {
                    selectedClip = it
                    val start = CutTimeline.outputStartMs(review.keep, it)
                    playheadMs = start
                    seek = PreviewSeek(start, ++seekId)
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 138.dp, max = 178.dp)
                .background(EditorPanel)
        ) {
            when (tool) {
                EditorTool.Clips -> ClipControls(
                    clips = review.keep,
                    selected = selectedClip,
                    enabled = !busy,
                    onSplit = { onSplit(playheadMs) },
                    onRemove = onRemoveClip
                )
                EditorTool.Cut -> CutControls(
                    settings = review.settings,
                    manualEdits = review.manualEdits,
                    enabled = !busy,
                    onSettingsChange = onSettingsChange,
                    onRedetect = onRedetect
                )
                EditorTool.Captions -> CaptionControls(
                    review = review,
                    enabled = !busy,
                    engineLabel = engineLabel,
                    needsKey = needsKey,
                    onAdd = { if (uploads) pendingCloud = onAddCaptions else onAddCaptions() },
                    onOpenSettings = onOpenSettings,
                    onEnabled = onCaptionsEnabled
                )
                EditorTool.Transcript -> TranscriptControls(
                    segments = review.captions,
                    enabled = !busy,
                    transcribing = status?.startsWith("Transcri") == true,
                    engineLabel = engineLabel,
                    needsKey = needsKey,
                    onTranscribe = { if (uploads) pendingCloud = onTranscribe else onTranscribe() },
                    onRegenerate = { if (uploads) pendingCloud = onRegenerateTranscript else onRegenerateTranscript() },
                    onDelete = onDeleteTranscript,
                    onOpenSettings = onOpenSettings,
                    onCancel = onCancelTranscription
                )
            }
        }

        CloudConsentGate(provider = cloudProvider, pending = pendingCloud, onSettled = { pendingCloud = null })

        error?.let { message ->
            Text(
                text = message,
                color = Accent,
                fontFamily = TikTokSans,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EditorPanel)
                    .padding(horizontal = 16.dp, vertical = 5.dp)
            )
        }
        ToolBar(selected = tool, enabled = !busy) { tool = it }
    }
}

@Composable
private fun EditorHeader(
    title: String,
    canSave: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    val saved = title.startsWith("Saved")
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderAction("‹", Color.White, onBack)
        Text(
            text = title,
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        HeaderAction("↶", if (canUndo) Color.White else EditorFaint, onUndo, width = 44.dp)
        HeaderAction("↷", if (canRedo) Color.White else EditorFaint, onRedo, width = 44.dp)
        HeaderAction(
            text = if (saved) "DONE" else "EXPORT",
            color = if (saved || canSave) Accent else EditorFaint,
            onClick = if (saved) onBack else onSave
        )
    }
}

@Composable
private fun HeaderAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 70.dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = when (text) {
                "‹" -> 32.sp
                "↶", "↷" -> 22.sp
                else -> 11.sp
            }
        )
    }
}

@Composable
private fun TimelineHeader(review: EditorViewModel.Review, playheadMs: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${review.keep.size} CLIPS  •  ${summary(review)}",
            color = EditorMuted,
            fontFamily = TikTokSans,
            fontSize = 10.sp
        )
        Text(
            text = "OUT ${timecode(playheadMs)} / ${timecode(review.keptMs)}",
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

@Composable
internal fun TimelineScrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onScrubbingChange: (Boolean) -> Unit,
    onSeek: (Long) -> Unit
) {
    Slider(
        value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
        onValueChange = {
            onScrubbingChange(true)
            onSeek(it.roundToLong())
        },
        onValueChangeFinished = { onScrubbingChange(false) },
        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Accent,
            inactiveTrackColor = EditorLine
        )
    )
}

@Composable
private fun ClipTimeline(
    source: Uri,
    clips: List<Span>,
    totalMs: Long,
    playheadMs: Long,
    selected: Int,
    enabled: Boolean,
    onClipChange: (Int, Long, Long) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAdd: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val frames by videoFrames(source, clips)
    // A long press lifts a clip; it is tracked by span rather than slot because every swap
    // changes its index under the finger.
    var lifted by remember { mutableStateOf<Span?>(null) }
    var liftOffset by remember { mutableFloatStateOf(0f) }
    val latestClips by rememberUpdatedState(clips)
    val listState = rememberLazyListState()
    val map = remember(clips) { TimeMap(clips) }
    // A binary search per playhead tick, so no remember is needed to keep this cheap.
    val activeClip = if (clips.isEmpty()) NO_CLIP_SELECTED else map.clipAt(playheadMs)

    // Only scroll when the caret crosses a clip boundary. Moving the list every 50 ms would fight
    // a user's manual swipe and make playback visibly jitter.
    LaunchedEffect(activeClip, clips.size) {
        if (activeClip in clips.indices) listState.animateScrollToItem(activeClip)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(88.dp).background(Color.Black),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP)
    ) {
        itemsIndexed(clips, key = { _, span -> "${span.startMs}:${span.endMs}" }) { index, span ->
            // The draft is what the handles are dragging; it becomes the span on release. Keeping
            // it here lets the box and its label follow the drag instead of freezing until commit.
            var draft by remember(span) { mutableStateOf(span) }
            var dragging by remember { mutableStateOf(false) }
            val width = tileWidth(span)
            var widthPx by remember { mutableIntStateOf(1) }
            val latestIndex by rememberUpdatedState(index)
            val isLifted = lifted == span
            Box(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .animateItem()
                    .zIndex(if (isLifted) 1f else 0f)
                    .graphicsLayer { translationX = if (isLifted) liftOffset else 0f }
                    .pointerInput(span, enabled) {
                        if (!enabled) return@pointerInput
                        val gap = TILE_GAP.toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                lifted = span
                                liftOffset = 0f
                            },
                            onDragEnd = { lifted = null },
                            onDragCancel = { lifted = null },
                            onDrag = { change, amount ->
                                change.consume()
                                liftOffset += amount.x
                                val at = latestIndex
                                val next = latestClips.getOrNull(at + 1)
                                val previous = latestClips.getOrNull(at - 1)
                                // Past half the neighbour it slides under; the offset is rebased
                                // so the lifted tile stays under the finger.
                                if (next != null && liftOffset > (tileWidth(next).toPx() + gap) / 2) {
                                    onMove(at, at + 1)
                                    liftOffset -= tileWidth(next).toPx() + gap
                                } else if (previous != null && liftOffset < -(tileWidth(previous).toPx() + gap) / 2) {
                                    onMove(at, at - 1)
                                    liftOffset += tileWidth(previous).toPx() + gap
                                }
                            }
                        )
                    }
                    .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                    .clip(RoundedCornerShape(3.dp))
                    .background(EditorTrack)
                    .border(
                        if (index == selected) 2.dp else 1.dp,
                        when {
                            dragging -> AccentPressed
                            index == selected -> Accent
                            else -> EditorLine
                        },
                        RoundedCornerShape(3.dp)
                    )
                    .clickable { onSelect(index) }
                    .semantics { role = Role.Button }
            ) {
                frames.getOrNull(index)?.let { frame ->
                    Image(
                        bitmap = frame,
                        contentDescription = "Clip ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = "${index + 1}  ${seconds(draft.durationMs)}",
                    color = Color.White,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000))
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                )
                if (index == selected) {
                    val (before, after) = CutTimeline.sourceBounds(clips, index, totalMs)
                    TrimHandle(
                        draft = draft,
                        beforeMs = before,
                        afterMs = after,
                        widthPx = widthPx,
                        start = true,
                        enabled = enabled,
                        dragging = dragging,
                        onDraft = { draft = it },
                        onDragging = { dragging = it },
                        onCommit = { onClipChange(index, draft.startMs, draft.endMs) },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    TrimHandle(
                        draft = draft,
                        beforeMs = before,
                        afterMs = after,
                        widthPx = widthPx,
                        start = false,
                        enabled = enabled,
                        dragging = dragging,
                        onDraft = { draft = it },
                        onDragging = { dragging = it },
                        onCommit = { onClipChange(index, draft.startMs, draft.endMs) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
                if (index == activeClip) {
                    val outputStart = map.outputStart(index)
                    val progress = ((playheadMs - outputStart).toFloat() / span.durationMs)
                        .coerceIn(0f, 1f)
                    TimelineCaret(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (width - 10.dp) * progress)
                    )
                }
            }
        }
        if (clips.isEmpty()) {
            item {
                Box(Modifier.width(220.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("Nothing on the timeline", color = EditorMuted, fontSize = 12.sp)
                }
            }
        }
        // One add tile trails the strip; the caller decides the slot (after the selected clip, or
        // at the edge nearest the caret) and names it in the chooser before anything is added.
        if (enabled) {
            item(key = "add") {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, EditorLine, RoundedCornerShape(3.dp))
                        .clickable(onClick = onAdd)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Add a clip"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Color.White,
                        fontFamily = TikTokSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp
                    )
                }
            }
        }
    }
}

/** Tiles grow with the clip, within limits, so a long take does not become one endless bar. */
private fun tileWidth(span: Span): Dp = (72 + (span.durationMs / 1000f * 9f)).coerceIn(72f, 156f).dp

private val TILE_GAP = 3.dp

@Composable
private fun TimelineCaret(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 10.dp, height = 72.dp)) {
        val center = size.width / 2f
        val stroke = 2.dp.toPx()
        val head = 4.dp.toPx()
        drawLine(
            color = Color.White,
            start = Offset(center, head),
            end = Offset(center, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawCircle(color = Color.White, radius = head, center = Offset(center, head))
    }
}

@Composable
private fun TrimHandle(
    draft: Span,
    beforeMs: Long,
    afterMs: Long,
    widthPx: Int,
    start: Boolean,
    enabled: Boolean,
    dragging: Boolean,
    onDraft: (Span) -> Unit,
    onDragging: (Boolean) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val minimum = minOf(100L, afterMs - beforeMs).coerceAtLeast(1L)
    val current by rememberUpdatedState(draft)

    Box(
        modifier = modifier
            .width(20.dp)
            .fillMaxHeight()
            .background(
                when {
                    !enabled -> EditorFaint
                    dragging -> AccentPressed
                    else -> Accent
                }
            )
            .pointerInput(beforeMs, afterMs, widthPx, enabled, start) {
                detectHorizontalDragGestures(
                    onDragStart = { if (enabled) onDragging(true) },
                    onDragEnd = {
                        if (enabled) {
                            onDragging(false)
                            onCommit()
                        }
                    },
                    onDragCancel = { if (enabled) onDragging(false) }
                ) { change, dragAmount ->
                    if (enabled) {
                        change.consume()
                        val span = current
                        // Pixels map to the committed span's width, so the scale is stable mid-drag.
                        val deltaMs = (dragAmount * span.durationMs / widthPx).roundToLong()
                        onDraft(
                            if (start) {
                                span.copy(startMs = (span.startMs + deltaMs).coerceIn(beforeMs, span.endMs - minimum))
                            } else {
                                span.copy(endMs = (span.endMs + deltaMs).coerceIn(span.startMs + minimum, afterMs))
                            }
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(10.dp, 18.dp)) {
            val outerX = if (start) size.width * 0.72f else size.width * 0.28f
            val pointX = if (start) size.width * 0.28f else size.width * 0.72f
            val stroke = 2.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(outerX, size.height * 0.20f),
                end = Offset(pointX, size.height * 0.50f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(pointX, size.height * 0.50f),
                end = Offset(outerX, size.height * 0.80f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun videoFrames(source: Uri, clips: List<Span>): androidx.compose.runtime.State<List<ImageBitmap?>> {
    val context = LocalContext.current.applicationContext
    // Keyed by the frame's source time, so a trimmed neighbour does not re-decode every clip.
    val cache = remember(source) { mutableMapOf<Long, ImageBitmap?>() }
    return produceState(initialValue = emptyList(), source, clips) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                clips.map { span ->
                    val midMs = (span.startMs + span.endMs) / 2
                    cache.getOrPut(midMs) {
                        runCatching {
                            retriever.getScaledFrameAtTime(
                                midMs * 1_000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                180,
                                100
                            )?.asImageBitmap()
                        }.getOrNull()
                    }.also {
                        // A superseded edit cancels this producer; without a suspension point it
                        // would still decode every remaining frame before noticing.
                        yield()
                    }
                }
            } finally {
                retriever.release()
            }
        }
    }
}

@Composable
private fun ClipControls(
    clips: List<Span>,
    selected: Int,
    enabled: Boolean,
    onSplit: () -> Unit,
    onRemove: (Int) -> Unit
) {
    val clip = clips.getOrNull(selected)
    if (clip == null) {
        EmptyControl(
            if (clips.isEmpty()) "Cut settings removed every clip. Open Cut and lower the threshold."
            else "Tap a clip to select it for trimming. Tap it again to unselect it."
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CLIP ${selected + 1}  •  SOURCE ${timecode(clip.startMs)}–${timecode(clip.endMs)}",
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "SPLIT",
                color = if (enabled) Accent else EditorFaint,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onSplit)
                    .semantics { role = Role.Button }
                    .padding(8.dp)
            )
            Text(
                "DELETE",
                color = if (enabled) Accent else EditorFaint,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable(enabled = enabled) { onRemove(selected) }
                    .semantics { role = Role.Button }
                    .padding(8.dp)
            )
        }
        Spacer(Modifier.height(13.dp))
        Text(
            text = "Drag the edge handles to trim. Split cuts the clip at the white caret.",
            color = EditorMuted,
            fontFamily = TikTokSans,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "The white caret follows playback across the complete timeline.",
            color = EditorFaint,
            fontFamily = TikTokSans,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun CutControls(
    settings: SilenceSettings,
    manualEdits: Boolean,
    enabled: Boolean,
    onSettingsChange: (SilenceSettings) -> Unit,
    onRedetect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        if (manualEdits) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Hand edits kept. Sliders will not move clips.",
                    color = EditorMuted,
                    fontFamily = TikTokSans,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "RE-DETECT",
                    color = if (enabled) Accent else EditorFaint,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable(enabled = enabled, onClick = onRedetect)
                        .semantics { role = Role.Button }
                        .padding(8.dp)
                )
            }
        }
        CompactKnob(
            "Silence threshold", settings.thresholdDb, -70f..-15f, enabled,
            { "${it.roundToInt()} dB" }
        ) { onSettingsChange(settings.copy(thresholdDb = it)) }
        CompactKnob(
            "Shortest pause", settings.minSilenceMs.toFloat(), 100f..1500f, enabled,
            { "${it.roundToInt()} ms" }
        ) { onSettingsChange(settings.copy(minSilenceMs = it.roundToInt().toLong())) }
        CompactKnob(
            "Breathing room", settings.padMs.toFloat(), 0f..300f, enabled,
            { "${it.roundToInt()} ms" }
        ) { onSettingsChange(settings.copy(padMs = it.roundToInt().toLong())) }
    }
}

@Composable
private fun CaptionControls(
    review: EditorViewModel.Review,
    enabled: Boolean,
    engineLabel: String,
    needsKey: Boolean,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    val transcript = review.captions
    val needsKey = transcript == null && needsKey
    ControlAction(
        title = when {
            transcript == null -> "Generate captions"
            review.captionsEnabled -> "Captions applied"
            else -> "Apply saved transcript"
        },
        detail = when {
            needsKey -> "Cloud transcription is selected. Add your Groq, OpenAI or Gemini key first."
            transcript == null -> "Transcribes $engineLabel, then burns captions into the export."
            transcript.isEmpty() -> "No speech was found in this project."
            review.captionsEnabled -> "${transcript.size} lines will appear in the saved video."
            else -> "${transcript.size} transcript lines are ready."
        },
        action = when {
            needsKey -> "ADD KEY"
            transcript == null -> "GENERATE"
            review.captionsEnabled -> "REMOVE"
            else -> "APPLY"
        },
        enabled = enabled && (transcript == null || transcript.isNotEmpty()),
        onClick = {
            when {
                needsKey -> onOpenSettings()
                transcript == null -> onAdd()
                review.captionsEnabled -> onEnabled(false)
                else -> onEnabled(true)
            }
        }
    )
}

@Composable
private fun TranscriptControls(
    segments: List<Segment>?,
    enabled: Boolean,
    transcribing: Boolean,
    engineLabel: String,
    needsKey: Boolean,
    onTranscribe: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    // A regeneration shows the same progress card as a first transcript; the old text is
    // still in the review and comes back the moment the request is cancelled.
    if (segments == null || transcribing) {
        val needsKey = needsKey && !transcribing
        ControlAction(
            title = if (transcribing) "Transcribing project" else "Transcribe project",
            detail = when {
                transcribing -> "Cutly retries a few times, then reports the error."
                needsKey -> "Cloud transcription is selected. Add your Groq, OpenAI or Gemini key first."
                else -> "Transcribes $engineLabel. The transcript is saved with this project."
            },
            action = when {
                transcribing -> "CANCEL"
                needsKey -> "ADD KEY"
                else -> "TRANSCRIBE"
            },
            enabled = if (transcribing) true else enabled,
            onClick = when {
                transcribing -> onCancel
                needsKey -> onOpenSettings
                else -> onTranscribe
            }
        )
    } else {
        val context = LocalContext.current
        val transcript = remember(segments) { Segment.render(segments) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SAVED TRANSCRIPT",
                    color = Accent,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Row {
                    HeaderAction("COPY", enabled = true) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cutly transcript", transcript))
                    }
                    HeaderAction("REDO", enabled = enabled, onClick = onRegenerate)
                    HeaderAction("DELETE", enabled = enabled, onClick = onDelete)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                transcript,
                color = Color.White,
                fontFamily = TikTokSans,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

/** The small caps text buttons in a panel header. Undo covers what they do, so no confirm. */
@Composable
private fun HeaderAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else EditorFaint,
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(8.dp)
    )
}

@Composable
private fun ControlAction(
    title: String,
    detail: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontFamily = TikTokSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(detail, color = EditorMuted, fontFamily = TikTokSans, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            action,
            color = if (enabled) Color.White else EditorFaint,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(7.dp))
                .background(if (enabled) Accent else EditorTrack)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun EmptyControl(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = EditorMuted, fontFamily = TikTokSans, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompactKnob(
    label: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    value: (Float) -> String,
    onCommit: (Float) -> Unit
) {
    var draft by remember(position) { mutableFloatStateOf(position) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = EditorMuted, fontFamily = TikTokSans, fontSize = 10.sp)
            Text(value(draft), color = Color.White, fontFamily = TikTokSans, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
        }
        Slider(
            value = draft.coerceIn(range.start, range.endInclusive),
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            enabled = enabled,
            valueRange = range,
            modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Accent,
                inactiveTrackColor = EditorLine
            )
        )
    }
}

@Composable
private fun ToolBar(selected: EditorTool, enabled: Boolean, onSelect: (EditorTool) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(66.dp).background(Color(0xFF0B0B0D))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        EditorTool.entries.forEach { tool ->
            Column(
                modifier = Modifier.width(88.dp).fillMaxHeight()
                    .clickable(enabled = enabled) { onSelect(tool) }
                    .semantics { role = Role.Tab },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(if (selected == tool) Accent else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tool.mark,
                        color = if (selected == tool) Color.White else EditorMuted,
                        fontFamily = TikTokSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (tool == EditorTool.Captions) 9.sp else 14.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tool.label,
                    color = if (selected == tool) Color.White else EditorMuted,
                    fontFamily = TikTokSans,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun summary(review: EditorViewModel.Review): String = when {
    review.keep.isEmpty() -> "nothing kept"
    !review.hasSomethingToCut -> "original length"
    else -> "${review.cutCount} cuts • ${seconds(review.removedMs)} removed"
}

private fun seconds(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val whole = safe / 1000
    return if (whole < 60) "$whole.${(safe / 100) % 10}s" else "${whole / 60}m ${whole % 60}s"
}

private fun timecode(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val totalSeconds = safe / 1000
    return "%02d:%02d.%d".format(totalSeconds / 60, totalSeconds % 60, (safe / 100) % 10)
}
