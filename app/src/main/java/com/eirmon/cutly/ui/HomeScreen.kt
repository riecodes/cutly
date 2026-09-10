package com.eirmon.cutly.ui

import android.animation.ValueAnimator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.eirmon.cutly.CleanupViewModel
import com.eirmon.cutly.TranscribeViewModel
import com.eirmon.cutly.TranscriptionEngine
import com.eirmon.cutly.transcribe.SherpaModelState
import com.eirmon.cutly.transcribe.TranscriptionLanguage
import com.eirmon.cutly.ui.theme.EaseOut
import com.eirmon.cutly.ui.theme.Scrim
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlinx.coroutines.delay

/**
 * One compact launcher for Cutly's three jobs.
 */
@OptIn(UnstableApi::class)
@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit,
    viewModel: TranscribeViewModel = viewModel(),
    cleanupViewModel: CleanupViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cleanup by cleanupViewModel.state.collectAsStateWithLifecycle()
    val colors = homeColors()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> picked?.let(viewModel::transcribe) }

    // Separate launchers retain their intent while Android's picker is outside this process.
    val cutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> picked?.let(cleanupViewModel::analyze) }

    val busy = state.isBusy || cleanup.isBusy
    var languageSheetOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            Reveal(0) { HomeHeader(colors) }

            Spacer(Modifier.height(24.dp))

            Reveal(70) {
                HomeSection(
                    number = "01",
                    label = "camera",
                    colors = colors
                ) {
                    ToolRow(
                        title = "Shoot a take",
                        detail = "Record in clips. Merge when ready.",
                        action = "OPEN ↗",
                        enabled = !busy,
                        colors = colors,
                        onClick = onOpenCamera
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Reveal(140) {
                HomeSection(
                    number = "02",
                    label = "transcribe",
                    colors = colors
                ) {
                    ToolRow(
                        title = "Video to text",
                        detail = "Editable, timestamped transcript.",
                        action = "CHOOSE ↗",
                        enabled = !busy,
                        colors = colors,
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                )
                            )
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    TranscriptionPanel(
                        engine = state.engine,
                        languages = state.languages,
                        language = state.language,
                        sherpaModel = state.sherpaModel,
                        geminiConfigured = state.geminiConfigured,
                        enabled = !busy,
                        colors = colors,
                        onLanguage = { languageSheetOpen = true },
                        onPhone = viewModel::useSystemRecognizer,
                        onWhisper = viewModel::useSherpaModel,
                        onDownload = viewModel::downloadSherpaModel,
                        onCancelOrDelete = viewModel::deleteSherpaModel,
                        onGemini = viewModel::useGemini
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Reveal(210) {
                HomeSection(
                    number = "03",
                    label = "cut",
                    colors = colors
                ) {
                    if (cleanup.hasProject) {
                        ToolRow(
                            title = "Resume project",
                            detail = "Your source, clips, and transcript are saved.",
                            action = "RESUME ↗",
                            enabled = !busy,
                            colors = colors,
                            onClick = cleanupViewModel::openProject
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    ToolRow(
                        title = if (cleanup.hasProject) "New video" else "Edit a video",
                        detail = "Timeline, clips, captions, and export.",
                        action = if (cleanup.hasProject) "NEW ↗" else "CHOOSE ↗",
                        enabled = !busy,
                        colors = colors,
                        onClick = {
                            cutPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                )
                            )
                        }
                    )
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(18.dp))
                ErrorBanner(message, colors, viewModel::dismissError)
            }

            cleanup.error?.takeIf { cleanup.review == null }?.let { message ->
                Spacer(Modifier.height(18.dp))
                ErrorBanner(message, colors, cleanupViewModel::dismissError)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Private by default · Cloud only when selected",
                color = colors.faint,
                fontFamily = TikTokSans,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(28.dp))
        }

        // The cut dialog owns its own progress surface, so the page overlay stays behind it.
        if (busy && cleanup.review == null) {
            BusyOverlay(state.status ?: cleanup.status)
        }
    }

    if (cleanup.isBusy && cleanup.review == null) {
        ProjectOpening(cleanup.status)
    }

    if (languageSheetOpen) {
        LanguageSheet(
            languages = state.languages,
            selected = state.language,
            onSelect = {
                viewModel.setLanguage(it)
                languageSheetOpen = false
            },
            onDismiss = { languageSheetOpen = false }
        )
    }

    cleanup.review?.let { review ->
        // Slider drafts commit only on release, so this rebuilds once per edit.
        val preview = remember(review.keep, review.captions, review.captionsEnabled) {
            cleanupViewModel.previewComposition()
        }

        CleanupSheet(
            review = review,
            status = cleanup.status,
            error = cleanup.error,
            preview = preview,
            onSettingsChange = cleanupViewModel::updateSettings,
            onClipChange = cleanupViewModel::updateClip,
            onRemoveClip = cleanupViewModel::removeClip,
            onAddCaptions = cleanupViewModel::addCaptions,
            onCaptionsEnabled = cleanupViewModel::setCaptionsEnabled,
            onTranscribe = cleanupViewModel::transcribe,
            onSave = cleanupViewModel::save,
            onDismiss = cleanupViewModel::closeReview
        )
    }

    state.transcript?.let { transcript ->
        TranscriptDialog(
            text = transcript,
            onTextChange = viewModel::editTranscript,
            onDismiss = viewModel::closeTranscript
        )
    }
}

@Composable
private fun HomeHeader(colors: HomeColors) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CutlyMark(colors)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Cutly",
                color = colors.ink,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = (-0.8).sp
            )
        }
        Spacer(Modifier.height(30.dp))
        Text(
            text = "Make the take.\nKeep the good bits.",
            color = colors.ink,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            lineHeight = 38.sp,
            letterSpacing = (-1.2).sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Shoot, transcribe, and clean up video in a few taps.",
            color = colors.muted,
            fontFamily = TikTokSans,
            fontSize = 15.sp,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun CutlyMark(colors: HomeColors) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(colors.ink)
            .padding(horizontal = 10.dp)
    ) {
        Box(
            Modifier
                .width(13.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Box(
            Modifier
                .width(9.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.75f))
        )
        Box(
            Modifier
                .width(6.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
    }
}

@Composable
private fun SectionBadge(number: String, colors: HomeColors) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(colors.softAccent)
    ) {
        Text(
            text = number,
            color = colors.accent,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ActionPill(label: String, colors: HomeColors) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = label.replace(" ↗", ""),
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CardLabel(number: String, label: String, colors: HomeColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionBadge(number, colors)
        Spacer(Modifier.width(10.dp))
        Text(
            text = label.replaceFirstChar { it.uppercase() },
            color = colors.ink,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFF0E7EA), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun HomeSection(
    number: String,
    label: String,
    colors: HomeColors,
    content: @Composable () -> Unit
) {
    CardSurface {
        CardLabel(number, label, colors)
        Spacer(Modifier.height(7.dp))
        content()
    }
}

@Composable
private fun ToolRow(
    title: String,
    detail: String,
    action: String,
    enabled: Boolean,
    colors: HomeColors,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(top = 10.dp, bottom = 2.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.ink,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = detail,
                color = colors.muted,
                fontFamily = TikTokSans,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        ActionPill(action, colors)
    }
}

/** All transcription choices in one compact surface instead of three independent cards. */
@Composable
private fun TranscriptionPanel(
    engine: TranscriptionEngine,
    languages: List<TranscriptionLanguage>,
    language: TranscriptionLanguage?,
    sherpaModel: SherpaModelState,
    geminiConfigured: Boolean,
    enabled: Boolean,
    colors: HomeColors,
    onLanguage: () -> Unit,
    onPhone: () -> Unit,
    onWhisper: () -> Unit,
    onDownload: () -> Unit,
    onCancelOrDelete: () -> Unit,
    onGemini: () -> Unit
) {
    val whisperLabel = when (sherpaModel) {
        SherpaModelState.Missing -> "GET WHISPER"
        is SherpaModelState.Downloading -> {
            val percent = if (sherpaModel.totalBytes <= 0) 0
            else (sherpaModel.downloadedBytes * 100 / sherpaModel.totalBytes).coerceIn(0, 100)
            "CANCEL $percent%"
        }
        SherpaModelState.Verifying -> "CHECKING"
        SherpaModelState.Ready -> "WHISPER"
        is SherpaModelState.Failed -> "RETRY"
    }
    val whisperAction = when (sherpaModel) {
        SherpaModelState.Missing, is SherpaModelState.Failed -> onDownload
        is SherpaModelState.Downloading -> onCancelOrDelete
        SherpaModelState.Verifying -> ({})
        SherpaModelState.Ready -> onWhisper
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.softAccent)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Color.White)
        ) {
            EngineChoice(
                label = "PHONE",
                selected = engine == TranscriptionEngine.SYSTEM,
                enabled = enabled,
                colors = colors,
                onClick = onPhone,
                modifier = Modifier.weight(1f)
            )
            EngineChoice(
                label = whisperLabel,
                selected = engine == TranscriptionEngine.SHERPA,
                enabled = enabled && sherpaModel !is SherpaModelState.Verifying,
                colors = colors,
                onClick = whisperAction,
                modifier = Modifier.weight(1f)
            )
            EngineChoice(
                label = if (geminiConfigured) "GEMINI" else "GEMINI / KEY",
                selected = engine == TranscriptionEngine.GEMINI,
                enabled = enabled,
                colors = colors,
                onClick = onGemini,
                modifier = Modifier.weight(1f)
            )
        }

        when (sherpaModel) {
            is SherpaModelState.Downloading -> DownloadStatus(sherpaModel, colors)
            SherpaModelState.Verifying -> MetaText("VERIFYING DOWNLOADED MODEL", colors)
            is SherpaModelState.Failed -> MetaText("WHISPER MODEL UNAVAILABLE · TAP RETRY", colors)
            else -> when (engine) {
                TranscriptionEngine.SYSTEM -> LanguageMeta(
                    languages = languages,
                    language = language,
                    enabled = enabled,
                    colors = colors,
                    onClick = onLanguage
                )
                TranscriptionEngine.SHERPA -> EngineMeta(
                    text = "OFFLINE / AUTO LANGUAGE",
                    action = "DELETE MODEL",
                    enabled = enabled,
                    colors = colors,
                    onClick = onCancelOrDelete
                )
                TranscriptionEngine.GEMINI -> MetaText(
                    "CLOUD / AUTO LANGUAGE / AUDIO UPLOAD",
                    colors
                )
            }
        }
    }
}

@Composable
private fun EngineChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    colors: HomeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 5.dp)
    ) {
        Text(
            text = label,
            color = when {
                selected -> Color.White
                enabled -> colors.ink
                else -> colors.faint
            },
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun LanguageMeta(
    languages: List<TranscriptionLanguage>,
    language: TranscriptionLanguage?,
    enabled: Boolean,
    colors: HomeColors,
    onClick: () -> Unit
) {
    if (languages.isEmpty()) {
        MetaText("PHONE RECOGNISER UNAVAILABLE", colors)
        return
    }
    EngineMeta(
        text = "LANGUAGE / ${language?.label?.uppercase() ?: "CHOOSE"}",
        action = "CHANGE",
        enabled = enabled,
        colors = colors,
        onClick = onClick
    )
}

@Composable
private fun EngineMeta(
    text: String,
    action: String,
    enabled: Boolean,
    colors: HomeColors,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 7.dp)
    ) {
        Text(
            text = text,
            color = colors.muted,
            fontFamily = TikTokSans,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = action,
            color = if (enabled) colors.accent else colors.faint,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(vertical = 14.dp, horizontal = 3.dp)
        )
    }
}

@Composable
private fun MetaText(text: String, colors: HomeColors) {
    Text(
        text = text,
        color = colors.muted,
        fontFamily = TikTokSans,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(horizontal = 7.dp, vertical = 14.dp)
    )
}

@Composable
private fun DownloadStatus(state: SherpaModelState.Downloading, colors: HomeColors) {
    val progress = if (state.totalBytes <= 0) 0f
    else (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
    Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 11.dp)) {
        Text(
            text = "DOWNLOADING OFFLINE MODEL",
            color = colors.muted,
            fontFamily = TikTokSans,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.hairline)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(colors.accent)
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, colors: HomeColors, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.softAccent)
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = message,
            color = colors.ink,
            fontFamily = TikTokSans,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "DISMISS",
            color = colors.accent,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun BusyOverlay(status: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFFFF3B5C), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = (status ?: "Working…").uppercase(),
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
    }
}

@Immutable
private data class HomeColors(
    val background: Color,
    val ink: Color,
    val accent: Color,
    val softAccent: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val muted: Color,
    val faint: Color
)

@Composable
private fun homeColors(): HomeColors {
    return remember {
        HomeColors(
            background = Color(0xFFFFFBFC),
            ink = Color(0xFF0F0F12),
            accent = Color(0xFFFF3B5C),
            softAccent = Color(0xFFFFEDF1),
            hairline = Color(0xFFF4DCE2),
            hairlineStrong = Color(0xFFFFB8C6),
            muted = Color(0xFF65656B),
            faint = Color(0xFF8C8C92)
        )
    }
}

/** Brief fade-up entrance; disabled with the platform animation scale. */
@Composable
private fun Reveal(delayMillis: Int, content: @Composable () -> Unit) {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    var shown by remember { mutableStateOf(!animate) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = EaseOut),
        label = "homeReveal"
    )

    LaunchedEffect(Unit) {
        if (animate) {
            delay(delayMillis.toLong())
            shown = true
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 12.dp.toPx()
        }
    ) {
        content()
    }
}
