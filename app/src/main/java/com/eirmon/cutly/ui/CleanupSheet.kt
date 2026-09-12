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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.transformer.Composition
import com.eirmon.cutly.CleanupViewModel
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.export.CutTimeline
import com.eirmon.cutly.transcribe.Segment
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val EditorSurface = Color(0xFF111114)
private val EditorPanel = Color(0xFF1B1B1F)
private val EditorTrack = Color(0xFF2A2A2F)
private val EditorMuted = Color(0xFFA0A0A8)
private val EditorFaint = Color(0xFF6F6F77)
private val EditorLine = Color(0xFF35353B)
private const val NO_CLIP_SELECTED = -1

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

/** Full-screen project editor. Preview, clips, tools and export now share one timeline. */
@Composable
internal fun CleanupSheet(
    review: CleanupViewModel.Review,
    status: String?,
    error: String?,
    preview: Composition?,
    onSettingsChange: (SilenceSettings) -> Unit,
    onClipChange: (Int, Long, Long) -> Unit,
    onRemoveClip: (Int) -> Unit,
    onAddCaptions: () -> Unit,
    onCaptionsEnabled: (Boolean) -> Unit,
    onTranscribe: () -> Unit,
    onCancelTranscription: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val saved = review.savedName != null
    var tool by rememberSaveable { mutableStateOf(EditorTool.Clips) }
    var selectedClip by rememberSaveable { mutableIntStateOf(NO_CLIP_SELECTED) }
    var editing by remember { mutableStateOf(false) }
    var saveRequested by remember { mutableStateOf(false) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var seekId by remember { mutableIntStateOf(0) }
    var seek by remember { mutableStateOf<PreviewSeek?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    val busy = status != null || saveRequested

    LaunchedEffect(review.keep.size) {
        if (selectedClip !in review.keep.indices) selectedClip = NO_CLIP_SELECTED
    }
    LaunchedEffect(review.keep) {
        playheadMs = playheadMs.coerceIn(0L, review.keptMs.coerceAtLeast(0L))
        seek = PreviewSeek(playheadMs, ++seekId)
    }
    LaunchedEffect(busy) { if (busy) editing = false }
    LaunchedEffect(saveRequested) {
        if (saveRequested) {
            onSave()
            saveRequested = false
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EditorSurface)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            EditorHeader(
                saved = saved,
                canSave = review.canSave && !busy,
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
                if (busy || editing) {
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
            ClipTimeline(
                source = review.source,
                clips = review.keep,
                totalMs = review.originalMs,
                playheadMs = playheadMs,
                selected = selectedClip,
                enabled = !busy && !saved,
                onEditingChange = { editing = it },
                onClipChange = onClipChange,
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
                        enabled = !busy && !saved,
                        onRemove = onRemoveClip
                    )
                    EditorTool.Cut -> CutControls(
                        settings = review.settings,
                        enabled = !busy && !saved,
                        onEditingChange = { editing = it },
                        onSettingsChange = onSettingsChange
                    )
                    EditorTool.Captions -> CaptionControls(
                        review = review,
                        enabled = !busy && !saved,
                        onAdd = onAddCaptions,
                        onEnabled = onCaptionsEnabled
                    )
                    EditorTool.Transcript -> TranscriptControls(
                        segments = review.captions,
                        enabled = !busy && !saved,
                        transcribing = status?.startsWith("Transcri") == true,
                        onTranscribe = onTranscribe,
                        onCancel = onCancelTranscription
                    )
                }
            }

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
}

@Composable
private fun EditorHeader(saved: Boolean, canSave: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderAction("‹", Color.White, onBack)
        Text(
            text = if (saved) "Saved to Movies/Cutly" else "Project",
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        HeaderAction(
            text = if (saved) "DONE" else "EXPORT",
            color = if (saved || canSave) Accent else EditorFaint,
            onClick = if (saved) onBack else onSave
        )
    }
}

@Composable
private fun HeaderAction(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(70.dp)
            .height(48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = if (text == "‹") 32.sp else 11.sp
        )
    }
}

@Composable
private fun TimelineHeader(review: CleanupViewModel.Review, playheadMs: Long) {
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
            text = "${timecode(playheadMs)} / ${timecode(review.keptMs)}",
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TimelineScrubber(
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
    onEditingChange: (Boolean) -> Unit,
    onClipChange: (Int, Long, Long) -> Unit,
    onSelect: (Int) -> Unit
) {
    val frames by videoFrames(source, clips)
    val listState = rememberLazyListState()
    val activeClip = remember(clips, playheadMs) {
        if (clips.isEmpty()) NO_CLIP_SELECTED
        else CutTimeline.clipAtOutputPosition(clips, playheadMs)
    }

    // Only scroll when the caret crosses a clip boundary. Moving the list every 50 ms would fight
    // a user's manual swipe and make playback visibly jitter.
    LaunchedEffect(activeClip, clips.size) {
        if (activeClip in clips.indices) listState.animateScrollToItem(activeClip)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(88.dp).background(Color.Black),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        itemsIndexed(clips, key = { index, _ -> index }) { index, span ->
            val width = (72 + (span.durationMs / 1000f * 9f)).coerceIn(72f, 156f).dp
            var widthPx by remember { mutableIntStateOf(1) }
            Box(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                    .clip(RoundedCornerShape(3.dp))
                    .background(EditorTrack)
                    .border(
                        if (index == selected) 2.dp else 1.dp,
                        if (index == selected) Accent else EditorLine,
                        RoundedCornerShape(3.dp)
                    )
                    .clickable { onSelect(index) }
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
                    text = "${index + 1}  ${seconds(span.durationMs)}",
                    color = Color.White,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000))
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                )
                if (index == selected) {
                    val before = clips.getOrNull(index - 1)?.endMs ?: 0L
                    val after = clips.getOrNull(index + 1)?.startMs ?: totalMs
                    TrimHandle(
                        span = span,
                        beforeMs = before,
                        afterMs = after,
                        widthPx = widthPx,
                        start = true,
                        enabled = enabled,
                        onEditingChange = onEditingChange,
                        onCommit = { start, end -> onClipChange(index, start, end) },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    TrimHandle(
                        span = span,
                        beforeMs = before,
                        afterMs = after,
                        widthPx = widthPx,
                        start = false,
                        enabled = enabled,
                        onEditingChange = onEditingChange,
                        onCommit = { start, end -> onClipChange(index, start, end) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
                if (index == activeClip) {
                    val outputStart = CutTimeline.outputStartMs(clips, index)
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
    }
}

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
    span: Span,
    beforeMs: Long,
    afterMs: Long,
    widthPx: Int,
    start: Boolean,
    enabled: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onCommit: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var draftStart by remember(span) { mutableLongStateOf(span.startMs) }
    var draftEnd by remember(span) { mutableLongStateOf(span.endMs) }
    val minimum = minOf(100L, afterMs - beforeMs).coerceAtLeast(1L)

    Box(
        modifier = modifier
            .width(20.dp)
            .fillMaxHeight()
            .background(if (enabled) Accent else EditorFaint)
            .pointerInput(span, beforeMs, afterMs, widthPx, enabled, start) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (enabled) {
                            draftStart = span.startMs
                            draftEnd = span.endMs
                            onEditingChange(true)
                        }
                    },
                    onDragEnd = {
                        if (enabled) {
                            onCommit(draftStart, draftEnd)
                            onEditingChange(false)
                        }
                    },
                    onDragCancel = { if (enabled) onEditingChange(false) }
                ) { change, dragAmount ->
                    if (enabled) {
                        change.consume()
                        val deltaMs = (dragAmount * span.durationMs / widthPx).roundToLong()
                        if (start) {
                            draftStart = (draftStart + deltaMs)
                                .coerceIn(beforeMs, draftEnd - minimum)
                        } else {
                            draftEnd = (draftEnd + deltaMs)
                                .coerceIn(draftStart + minimum, afterMs)
                        }
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
    return produceState(initialValue = emptyList(), source, clips) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                clips.map { span ->
                    runCatching {
                        retriever.getScaledFrameAtTime(
                            ((span.startMs + span.endMs) / 2) * 1_000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            180,
                            100
                        )?.asImageBitmap()
                    }.getOrNull()
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
                "CLIP ${selected + 1}  •  ${timecode(clip.startMs)}–${timecode(clip.endMs)}",
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
            Text(
                "DELETE",
                color = if (enabled) Accent else EditorFaint,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.clickable(enabled = enabled) { onRemove(selected) }.padding(8.dp)
            )
        }
        Spacer(Modifier.height(13.dp))
        Text(
            text = "Drag the edge handles on the selected clip to trim its start and end.",
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
    enabled: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onSettingsChange: (SilenceSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        CompactKnob(
            "Silence threshold", settings.thresholdDb, -70f..-15f, enabled,
            { "${it.roundToInt()} dB" }, onEditingChange
        ) { onSettingsChange(settings.copy(thresholdDb = it)) }
        CompactKnob(
            "Shortest pause", settings.minSilenceMs.toFloat(), 100f..1500f, enabled,
            { "${it.roundToInt()} ms" }, onEditingChange
        ) { onSettingsChange(settings.copy(minSilenceMs = it.roundToInt().toLong())) }
        CompactKnob(
            "Breathing room", settings.padMs.toFloat(), 0f..300f, enabled,
            { "${it.roundToInt()} ms" }, onEditingChange
        ) { onSettingsChange(settings.copy(padMs = it.roundToInt().toLong())) }
    }
}

@Composable
private fun CaptionControls(
    review: CleanupViewModel.Review,
    enabled: Boolean,
    onAdd: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    val transcript = review.captions
    ControlAction(
        title = when {
            transcript == null -> "Generate captions"
            review.captionsEnabled -> "Captions applied"
            else -> "Apply saved transcript"
        },
        detail = when {
            transcript == null -> "Transcribes the project, then burns captions into the export."
            transcript.isEmpty() -> "No speech was found in this project."
            review.captionsEnabled -> "${transcript.size} lines will appear in the saved video."
            else -> "${transcript.size} transcript lines are ready."
        },
        action = when {
            transcript == null -> "GENERATE"
            review.captionsEnabled -> "REMOVE"
            else -> "APPLY"
        },
        enabled = enabled && (transcript == null || transcript.isNotEmpty()),
        onClick = {
            when {
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
    onTranscribe: () -> Unit,
    onCancel: () -> Unit
) {
    if (segments == null) {
        ControlAction(
            title = if (transcribing) "Transcribing project" else "Transcribe project",
            detail = if (transcribing) "If it fails, Cutly will keep retrying until you cancel."
            else "The transcript is saved with this project, even if you leave without copying it.",
            action = if (transcribing) "CANCEL" else "TRANSCRIBE",
            enabled = if (transcribing) true else enabled,
            onClick = if (transcribing) onCancel else onTranscribe
        )
    } else {
        val context = LocalContext.current
        val transcript = Segment.render(segments)
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
                Text(
                    "COPY",
                    color = Color.White,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cutly transcript", transcript))
                    }.padding(8.dp)
                )
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
    onEditingChange: (Boolean) -> Unit,
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
            onValueChange = {
                draft = it
                onEditingChange(true)
            },
            onValueChangeFinished = {
                onCommit(draft)
                onEditingChange(false)
            },
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
                modifier = Modifier.width(88.dp).fillMaxHeight().clickable(enabled = enabled) { onSelect(tool) },
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

private fun summary(review: CleanupViewModel.Review): String = when {
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
