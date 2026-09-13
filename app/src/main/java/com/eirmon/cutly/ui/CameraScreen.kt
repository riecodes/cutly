package com.eirmon.cutly.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eirmon.cutly.CameraViewModel
import com.eirmon.cutly.R
import com.eirmon.cutly.camera.FormatCatalog
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.model.VideoFormat
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.ChromePill
import com.eirmon.cutly.ui.theme.ModeLabelStyle
import com.eirmon.cutly.ui.theme.Scrim
import com.eirmon.cutly.ui.theme.TimerStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(context.isGranted(Manifest.permission.CAMERA))
    }
    var cameraDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] ?: hasCameraPermission
        if (!hasCameraPermission) cameraDenied = true
    }

    LaunchedEffect(Unit) {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot { context.isGranted(it) }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }
    var focusTap by remember { mutableStateOf<FocusTap?>(null) }
    var linearZoom by remember { mutableFloatStateOf(0f) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var pinchStartRatio by remember { mutableFloatStateOf(1f) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showSizePanel by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    var showCountdownSheet by remember { mutableStateOf(false) }
    var pendingCloud by remember { mutableStateOf<(() -> Unit)?>(null) }
    val anyPanelOpen = showSizePanel || showSpeedPicker || showCountdownSheet

    // Rebinds on first grant, on every lens switch, and on every format change. A bound
    // VideoCapture cannot survive a rebind, so the ViewModel is handed the new one each time.
    LaunchedEffect(hasCameraPermission, state.lensFacing, state.preferredFormat) {
        if (!hasCameraPermission) return@LaunchedEffect

        val provider = context.awaitCameraProvider()
        val selector = CameraSelector.Builder().requireLensFacing(state.lensFacing).build()
        val cameraInfo = runCatching { provider.getCameraInfo(selector) }.getOrNull()
        val formats = cameraInfo?.let { FormatCatalog.forCamera(it) }.orEmpty()
        val target = viewModel.resolveFormat(formats)

        // The capability list is advisory: a device can still reject a resolution/frame-rate
        // pair at bind time, so each candidate is tried in turn before giving up.
        var boundCamera: Camera? = null
        var boundCapture: VideoCapture<Recorder>? = null
        var boundFormat: VideoFormat? = null

        for (candidate in bindCandidates(target, formats)) {
            val fps = candidate?.fps
            // A rate the device never advertises has to be pinned on the capture request; asking
            // the CameraX session for it would just throw.
            val forced = fps != null && FormatCatalog.needsCamera2Override(cameraInfo, fps)

            val mainExecutor = ContextCompat.getMainExecutor(context)
            val preview = FormatCatalog
                .previewFor(
                    forceFrameRate = if (forced) fps else null,
                    onAchievedRange = if (forced) {
                        { achieved ->
                            // Arrives on a camera thread; the ViewModel is only touched on main.
                            mainExecutor.execute { viewModel.onAchievedFrameRate(fps, achieved) }
                        }
                    } else {
                        null
                    }
                )
                .build()
                .apply { setSurfaceProvider { request -> surfaceRequest = request } }
            val capture = FormatCatalog.videoCaptureFor(
                quality = candidate?.quality ?: Quality.FHD,
                forceFrameRate = if (forced) fps else null
            )

            val session = SessionConfig.Builder(preview, capture)
                .apply {
                    if (!forced && fps != null) setFrameRateRange(Range(fps, fps))
                }
                .build()

            val result = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, session)
            }
            FormatCatalog.logResolvedFormat(candidate, forced, state.lensFacing)
            if (result.isSuccess) {
                boundCamera = result.getOrNull()
                boundCapture = capture
                boundFormat = candidate
                break
            }
        }

        camera = boundCamera
        videoCapture = boundCapture
        boundCapture?.let {
            viewModel.onCameraReady(
                capture = it,
                availableFormats = formats,
                activeFormat = boundFormat,
                hasFlash = cameraInfo?.hasFlashUnit() == true
            )
        }
    }

    // CameraX resets zoom when use cases are rebound, so the UI's copy has to reset with it —
    // otherwise the readout claims 3x on a lens that just snapped back to wide.
    LaunchedEffect(camera) {
        linearZoom = 0f
        zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    }

    // Torch is re-applied after every rebind: a flip drops it, and the lens may not have one.
    LaunchedEffect(camera, state.flashOn, state.hasFlash) {
        runCatching { camera?.cameraControl?.enableTorch(state.flashOn && state.hasFlash) }
    }

    // The activity is locked to portrait, so CameraX never learns about physical rotation on its
    // own. Without this, clips shot sideways are saved sideways.
    DisposableEffect(videoCapture) {
        val capture = videoCapture
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || capture == null) return
                capture.targetRotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
            }
        }
        if (capture != null) listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(state.status) {
        if (state.status != null) {
            delay(2500)
            viewModel.clearStatus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val request = surfaceRequest
        if (request != null) {
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewfinderSize = it }
                    // Declared before the tap detector so it sees events first and can claim a
                    // swipe or pinch before the tap detector turns the release into a focus point.
                    .viewfinderGestures(
                        onFlip = viewModel::switchLens,
                        onPinchStart = {
                            pinchStartRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                        },
                        onPinchScale = { scale ->
                            val zoomState = camera?.cameraInfo?.zoomState?.value
                            if (zoomState != null) {
                                val target = (pinchStartRatio * scale)
                                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                                camera?.cameraControl?.setZoomRatio(target)
                                zoomRatio = target
                                // Keep the record-button slide in step with the pinch, so the two
                                // controls never disagree about the current zoom.
                                linearZoom =
                                    camera?.cameraInfo?.zoomState?.value?.linearZoom ?: linearZoom
                            }
                        }
                    )
                    .pointerInput(camera, viewfinderSize) {
                        detectTapGestures(
                            onDoubleTap = { viewModel.switchLens() },
                            onTap = { offset ->
                                val control = camera?.cameraControl ?: return@detectTapGestures
                                if (viewfinderSize == IntSize.Zero) return@detectTapGestures

                                // Mark the tap before metering starts — focus can take a moment,
                                // and the user needs to know the tap registered right away.
                                focusTap = FocusTap(offset, System.nanoTime())

                                val factory = SurfaceOrientedMeteringPointFactory(
                                    viewfinderSize.width.toFloat(),
                                    viewfinderSize.height.toFloat()
                                )
                                control.startFocusAndMetering(
                                    FocusMeteringAction.Builder(
                                        factory.createPoint(offset.x, offset.y)
                                    ).build()
                                )
                            }
                        )
                    }
            )
        } else if (!hasCameraPermission) {
            PermissionGate(
                denied = cameraDenied,
                onAllow = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        focusTap?.let { tap ->
            FocusReticle(tap = tap, onFinished = { if (focusTap?.id == tap.id) focusTap = null })
        }

        TopBar(
            canClear = state.hasClips && !state.isRecording && !state.isExporting,
            formatLabel = state.formatLabel,
            formatEnabled = !state.isRecording && !state.isExporting &&
                state.availableFormats.isNotEmpty(),
            onClear = viewModel::discardAll,
            onOpenFormat = { showSizePanel = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        )

        SideRail(
            flashOn = state.flashOn,
            hasFlash = state.hasFlash,
            speed = state.speed,
            enabled = !state.isRecording && !state.isExporting,
            onFlip = viewModel::switchLens,
            onFlash = viewModel::toggleFlash,
            onTimer = { showCountdownSheet = true },
            onSpeed = { showSpeedPicker = !showSpeedPicker },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 64.dp, end = 6.dp)
        )

        if (state.isCountingDown) {
            Text(
                text = state.countdownRemaining.toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 96.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        state.status?.let { message ->
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .background(ChromePill, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        CameraControls(
            clips = state.clips,
            currentClipMs = state.currentClipMs,
            recordedMs = state.recordedMs,
            maxTakeMs = state.maxTakeMs,
            isRecording = state.isRecording,
            isExporting = state.isExporting,
            canUndo = state.hasClips && !state.isRecording && !state.isExporting,
            canRecord = videoCapture != null && !state.isFull && !state.isExporting,
            canExport = state.hasClips && !state.isRecording && !state.isExporting,
            linearZoom = linearZoom,
            zoomRatio = zoomRatio,
            onSelectLimit = viewModel::setTakeLimit,
            onUndo = { showDiscardDialog = true },
            onRecordPress = viewModel::onRecordPressed,
            onRecordReleaseAfterHold = viewModel::stopClip,
            onZoomChange = { value ->
                linearZoom = value
                camera?.cameraControl?.setLinearZoom(value)
                zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: zoomRatio
            },
            onExport = { showExportDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        )

        if (state.isExporting) {
            CircularProgressIndicator(
                color = Accent,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // A transparent catcher closes whichever panel is open without swallowing the gesture
        // when nothing is open — the viewfinder keeps its tap-to-focus and double-tap flip.
        if (anyPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (showCountdownSheet) Scrim else Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            showSizePanel = false
                            showSpeedPicker = false
                            showCountdownSheet = false
                        })
                    }
            )
        }

        if (showSpeedPicker) {
            SpeedPicker(
                options = CameraViewModel.SPEED_OPTIONS,
                selected = state.speed,
                onSelect = {
                    viewModel.setSpeed(it)
                    showSpeedPicker = false
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 150.dp, end = 62.dp)
            )
        }

        if (showSizePanel) {
            VideoSizePanel(
                available = state.availableFormats,
                active = state.activeFormat,
                onSelectQuality = viewModel::selectQuality,
                onSelectFps = viewModel::selectFps,
                onClose = { showSizePanel = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 62.dp, start = 12.dp, end = 12.dp)
            )
        }

        if (showCountdownSheet) {
            CountdownSheet(
                seconds = state.timerSeconds,
                secondsOptions = CameraViewModel.TIMER_OPTIONS,
                limitMs = state.clipLimitMs,
                maxLimitMs = state.maxTakeMs,
                onSelectSeconds = viewModel::setTimerSeconds,
                onLimitChange = viewModel::setClipLimit,
                onStart = {
                    showCountdownSheet = false
                    viewModel.startCountdownNow()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            )
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "Discard the last clip?",
            confirmLabel = "Discard",
            dismissLabel = "Cancel",
            onConfirm = {
                showDiscardDialog = false
                viewModel.discardLast()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }

    if (showExportDialog) {
        ChoiceDialog(
            // Not "Export N clips" any more: transcribing saves nothing, so the title names the
            // take rather than the action.
            title = "${state.clips.size} clip${if (state.clips.size == 1) "" else "s"}",
            choices = listOf(
                "Save each clip separately" to {
                    showExportDialog = false
                    viewModel.exportSeparateClips()
                },
                "Save as one video" to {
                    showExportDialog = false
                    viewModel.exportMerged()
                },
                "Transcribe to text" to {
                    showExportDialog = false
                    pendingCloud = viewModel::transcribe
                }
            ),
            dismissLabel = "Cancel",
            onDismiss = { showExportDialog = false }
        )
    }

    CloudConsentGate(
        provider = viewModel.cloudProvider(),
        pending = pendingCloud,
        onSettled = { pendingCloud = null }
    )

    state.transcript?.let { transcript ->
        TranscriptDialog(
            text = transcript,
            onTextChange = viewModel::editTranscript,
            onDismiss = viewModel::closeTranscript
        )
    }
}

/**
 * Ordered bind attempts. The preferred format first, then the same resolution at 30 fps, then a
 * plain FHD 30 as the floor — every device that can record at all can do that.
 */
private fun bindCandidates(
    target: VideoFormat?,
    formats: List<VideoFormat>
): List<VideoFormat?> = buildList {
    target?.let { add(it) }
    target?.takeIf { it.fps != 30 }?.let { candidate ->
        formats.firstOrNull { it.quality == candidate.quality && it.fps == 30 }?.let { add(it) }
    }
    formats.firstOrNull { it.quality == Quality.FHD && it.fps == 30 }?.let { add(it) }
    // A null candidate means "let CameraX pick", which is the last resort rather than a crash.
    add(null)
}.distinct()

/**
 * Stateless overlays, split out from [CameraScreen] so they render in Compose Preview without a
 * camera, a ViewModel, or a device.
 */
@Composable
internal fun TopBar(
    canClear: Boolean,
    formatLabel: String,
    formatEnabled: Boolean,
    onClear: () -> Unit,
    onOpenFormat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (canClear) {
            GlyphButton(iconRes = R.drawable.ic_close, onClick = onClear)
        } else {
            Spacer(Modifier.size(44.dp))
        }
        FormatChip(label = formatLabel, enabled = formatEnabled, onClick = onOpenFormat)
    }
}

/** Resolution and frame rate, tappable between clips. */
@Composable
private fun FormatChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChromePill)
            .pointerInput(enabled) { detectTapGestures(onTap = { if (enabled) onClick() }) }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_resolution),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = ModeLabelStyle,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.4f)
        )
    }
}

/** The vertical control rail from the reference: flip, flash, timer, speed. */
@Composable
internal fun SideRail(
    flashOn: Boolean,
    hasFlash: Boolean,
    speed: Float,
    enabled: Boolean,
    onFlip: () -> Unit,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RailButton(
            iconRes = R.drawable.ic_flip_camera,
            contentDescription = "Flip camera",
            enabled = enabled,
            onClick = onFlip
        )
        RailButton(
            iconRes = if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
            contentDescription = "Flash",
            enabled = enabled && hasFlash,
            active = flashOn,
            onClick = onFlash
        )
        // The reference separates the lens controls from the creative ones with a hairline.
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .size(width = 22.dp, height = 1.dp)
                .background(Color.White.copy(alpha = 0.35f))
        )
        RailButton(
            iconRes = R.drawable.ic_timer,
            contentDescription = "Self timer",
            enabled = enabled,
            onClick = onTimer
        )
        RailButton(
            iconRes = R.drawable.ic_speed,
            contentDescription = "Speed",
            label = formatSpeed(speed),
            enabled = enabled,
            active = speed != 1f,
            onClick = onSpeed
        )
    }
}

@Composable
private fun RailButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    active: Boolean = false
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        active -> Accent
        else -> Color.White
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 48.dp, height = if (label != null) 52.dp else 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(enabled) { detectTapGestures(onTap = { if (enabled) onClick() }) },
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(27.dp)
        )
        if (label != null) {
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun CameraControls(
    clips: List<Clip>,
    currentClipMs: Long,
    recordedMs: Long,
    maxTakeMs: Long,
    isRecording: Boolean,
    isExporting: Boolean,
    canUndo: Boolean,
    canRecord: Boolean,
    canExport: Boolean,
    linearZoom: Float,
    zoomRatio: Float,
    onSelectLimit: (Long) -> Unit,
    onUndo: () -> Unit,
    onRecordPress: () -> Unit,
    onRecordReleaseAfterHold: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTake = clips.isNotEmpty() || isRecording

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Only while zoomed in — a permanent "1.0x" is noise on a camera that starts at wide.
        AnimatedVisibility(visible = linearZoom > 0f, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = "%.1fx".format(zoomRatio),
                style = ModeLabelStyle,
                color = Color.White,
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .background(ChromePill, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }

        // Timer replaces the length selector as soon as the take starts, exactly as in the
        // reference — the cap is no longer changeable at that point anyway.
        AnimatedVisibility(visible = hasTake, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = formatDuration(recordedMs),
                style = TimerStyle,
                color = Color.White
            )
        }
        AnimatedVisibility(visible = !hasTake, enter = fadeIn(), exit = fadeOut()) {
            TakeLimitSelector(selected = maxTakeMs, onSelect = onSelectLimit)
        }

        Spacer(Modifier.height(18.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            RecordButton(
                clips = clips,
                currentClipMs = currentClipMs,
                maxMs = maxTakeMs,
                isRecording = isRecording,
                enabled = canRecord,
                linearZoom = linearZoom,
                onPress = onRecordPress,
                onReleaseAfterHold = onRecordReleaseAfterHold,
                onZoomChange = onZoomChange,
                modifier = Modifier.align(Alignment.Center)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AnimatedVisibility(visible = canUndo, enter = fadeIn(), exit = fadeOut()) {
                    TagButton(iconRes = R.drawable.ic_discard_clip, onClick = onUndo)
                }
                AnimatedVisibility(
                    visible = canExport || isExporting,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ConfirmButton(enabled = canExport, onClick = onExport)
                }
            }
        }
    }
}

@Composable
private fun TakeLimitSelector(selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CameraViewModel.TAKE_LIMITS.forEach { limit ->
            val isSelected = limit == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .pointerInput(limit) { detectTapGestures(onTap = { onSelect(limit) }) }
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text(
                    text = formatTakeLimit(limit),
                    style = ModeLabelStyle,
                    color = if (isSelected) Color.Black else Color.White
                )
            }
        }
    }
}

/** Round translucent chrome button, used for the top-bar controls. */
@Composable
private fun GlyphButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

/** The grey backspace tag that discards the last clip. */
@Composable
private fun TagButton(iconRes: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(ChromePill)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = "Discard last clip",
            tint = Color.White,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun ConfirmButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (enabled) Accent else Accent.copy(alpha = 0.45f))
            .pointerInput(enabled) { detectTapGestures(onTap = { if (enabled) onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = "Finish take",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** Bridges CameraX's ListenableFuture into a suspend call without pulling in coroutines-guava. */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * What the viewfinder shows instead of black when the camera is not allowed yet.
 *
 * After a refusal Android may stop showing the system prompt at all, so the second refusal gets a
 * way into the app's settings page rather than a button that silently does nothing.
 */
@Composable
private fun PermissionGate(
    denied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Cutly needs the camera to record a take. The microphone is optional; without " +
                "it clips are silent.",
            color = Color.White,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        GateButton(label = "Allow camera", onClick = onAllow)
        if (denied) {
            GateButton(label = "Open settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun GateButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Accent)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp, vertical = 14.dp)
    )
}

/** mm:ss, matching the reference clock. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Take lengths read as "15s", "60s", "10m" — the reference keeps 60 in seconds and only switches
 * to minutes for the long option, so "600s" would look wrong next to it.
 */
internal fun formatTakeLimit(limitMs: Long): String =
    if (limitMs >= 120_000L) "${limitMs / 60_000}m" else "${limitMs / 1000}s"

/** 1x, 0.5x, 2x — trimmed so whole numbers do not read as "2.0x". */
internal fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "${text}x"
}
