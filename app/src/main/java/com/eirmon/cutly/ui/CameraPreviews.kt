package com.eirmon.cutly.ui

import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eirmon.cutly.CameraViewModel
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.model.VideoFormat
import com.eirmon.cutly.ui.theme.CutlyTheme
import java.io.File

/**
 * Design-time previews. These render inside Android Studio with no device, no emulator and no
 * camera — the viewfinder is stubbed with a gradient so the overlays can be judged on their own.
 */

private fun fakeClips(vararg durationsMs: Long): List<Clip> =
    durationsMs.mapIndexed { index, duration ->
        Clip(
            file = File("clip_$index.mp4"),
            durationMs = duration,
            lensFacing = CameraSelector.LENS_FACING_BACK
        )
    }

@Composable
private fun PreviewShell(content: @Composable BoxScope.() -> Unit) {
    CutlyTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF3A3F44), Color(0xFF15171A)))
                )
        ) { content() }
    }
}

@Composable
private fun BoxScope.PreviewControls(
    clips: List<Clip>,
    currentClipMs: Long = 0L,
    isRecording: Boolean = false,
    canRecord: Boolean = true,
    zoomRatio: Float = 1f,
    zoomStops: List<Float> = listOf(0.5f, 1f, 2f, 3f, 5f)
) {
    val hasTake = clips.isNotEmpty() || isRecording
    TopBar(
        canClear = hasTake && !isRecording,
        formatLabel = "FHD · 60",
        formatEnabled = !isRecording,
        onClear = {},
        onOpenFormat = {},
        modifier = Modifier.align(Alignment.TopCenter)
    )
    SideRail(
        flashOn = false,
        hasFlash = true,
        speed = if (hasTake) 2f else 1f,
        enabled = !isRecording,
        onFlip = {},
        onFlash = {},
        onTimer = {},
        onSpeed = {},
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 64.dp, end = 6.dp)
    )
    CameraControls(
        clips = clips,
        currentClipMs = currentClipMs,
        recordedMs = clips.sumOf { it.durationMs } + currentClipMs,
        isRecording = isRecording,
        isExporting = false,
        canUndo = hasTake && !isRecording,
        canRecord = canRecord,
        canExport = hasTake && !isRecording,
        linearZoom = if (zoomRatio > 1f) 0.4f else 0f,
        zoomRatio = zoomRatio,
        zoomStops = zoomStops,
        onSelectZoom = {},
        onUndo = {},
        onRecordPress = {},
        onRecordReleaseAfterHold = {},
        onZoomChange = {},
        onExport = {},
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}

@Preview(name = "1 · Idle, zoom selector", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewIdle() = PreviewShell {
    PreviewControls(clips = emptyList())
}

@Preview(name = "2 · Recording the 4th clip", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewRecording() = PreviewShell {
    PreviewControls(
        clips = fakeClips(4_200, 1_100, 8_600),
        currentClipMs = 2_400L,
        isRecording = true,
        zoomRatio = 2.4f
    )
}

@Preview(name = "3 · Paused, ready to export", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewPaused() = PreviewShell {
    PreviewControls(clips = fakeClips(4_200, 1_100, 8_600, 12_000, 3_300))
}

@Preview(name = "4 · Long take, single-lens camera", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewLongTake() = PreviewShell {
    PreviewControls(
        clips = fakeClips(120_000, 95_500, 184_500),
        zoomStops = emptyList()
    )
}

@Preview(name = "5 · Countdown sheet", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewCountdownSheet() = PreviewShell {
    CountdownSheet(
        seconds = 3,
        secondsOptions = CameraViewModel.TIMER_OPTIONS,
        limitMs = 42_000L,
        maxLimitMs = CameraViewModel.MAX_CLIP_LIMIT_MS,
        onSelectSeconds = {},
        onLimitChange = {},
        onStart = {},
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}

@Preview(name = "6 · Speed picker", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewSpeedPicker() = PreviewShell {
    SpeedPicker(
        options = CameraViewModel.SPEED_OPTIONS,
        selected = 1f,
        onSelect = {},
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 150.dp, end = 62.dp)
    )
}

@Preview(name = "7 · Video size panel", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewVideoSizePanel() = PreviewShell {
    val formats = listOf(
        VideoFormat(Quality.UHD, 30, 2160),
        VideoFormat(Quality.FHD, 60, 1080),
        VideoFormat(Quality.FHD, 30, 1080),
        VideoFormat(Quality.HD, 30, 720)
    )
    VideoSizePanel(
        available = formats,
        active = formats[1],
        onSelectQuality = {},
        onSelectFps = {},
        onClose = {},
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 62.dp, start = 12.dp, end = 12.dp)
    )
}

@Preview(name = "8 · Discard confirmation", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewDiscardDialog() = CutlyTheme {
    ConfirmDialog(
        title = "Discard the last clip?",
        confirmLabel = "Discard",
        dismissLabel = "Cancel",
        onConfirm = {},
        onDismiss = {}
    )
}

@Preview(name = "9 · Export choices", widthDp = 360, heightDp = 780)
@Composable
private fun PreviewExportDialog() = CutlyTheme {
    ChoiceDialog(
        title = "Export 5 clips",
        choices = listOf(
            "Save each clip separately" to {},
            "Save as one video" to {}
        ),
        dismissLabel = "Cancel",
        onDismiss = {}
    )
}
