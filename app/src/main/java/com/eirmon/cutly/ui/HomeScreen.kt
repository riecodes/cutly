package com.eirmon.cutly.ui

import android.animation.ValueAnimator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eirmon.cutly.CleanupViewModel
import com.eirmon.cutly.TranscribeViewModel
import com.eirmon.cutly.transcribe.TranscriptionLanguage
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.AccentTint
import com.eirmon.cutly.ui.theme.Canvas
import com.eirmon.cutly.ui.theme.EaseOut
import com.eirmon.cutly.ui.theme.Faint
import com.eirmon.cutly.ui.theme.Hairline
import com.eirmon.cutly.ui.theme.Ink
import com.eirmon.cutly.ui.theme.Muted
import com.eirmon.cutly.ui.theme.Panel
import com.eirmon.cutly.ui.theme.Scrim
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlinx.coroutines.delay

/**
 * The hub. Cutly is three services now — a camera, a transcriber and a cut — so the first screen
 * is a chooser rather than a viewfinder.
 *
 * Deliberately the opposite surface from the rest of the app: warm off-white canvas, one oversized
 * display headline, and white cards floating on hairline borders. The camera is a dark tool; the
 * launcher is a light page, and the size jump between the headline and everything else is the only
 * hierarchy it needs.
 */
@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit,
    viewModel: TranscribeViewModel = viewModel(),
    cleanupViewModel: CleanupViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cleanup by cleanupViewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> picked?.let(viewModel::transcribe) }

    // A second launcher rather than one shared behind a mode flag: the picker result arrives with
    // no memory of which card opened it, so a flag would be one stale boolean away from handing a
    // video to the wrong service.
    val cutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> picked?.let(cleanupViewModel::analyze) }

    val busy = state.isBusy || cleanup.isBusy
    var languageSheetOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(28.dp))

            Reveal(delayMillis = 0) {
                Text(
                    text = "Cutly",
                    color = Ink,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    letterSpacing = (-0.2).sp
                )
            }

            Spacer(Modifier.height(56.dp))

            // One headline, two lines, ending in a period. Everything under it is small and quiet.
            Reveal(delayMillis = 60) {
                Text(
                    text = "Record it.\nRead it. Cut it.",
                    color = Ink,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 52.sp,
                    // Tracking tightens as the type grows; at 52sp that is about −4.5%.
                    letterSpacing = (-2.3).sp,
                    lineHeight = 48.sp
                )
            }

            Spacer(Modifier.height(18.dp))

            Reveal(delayMillis = 60) {
                Text(
                    text = "A segmented video camera, a transcriber that runs on the phone's " +
                        "own recogniser, and a cut that drops the dead air.",
                    color = Muted,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.Normal,
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    letterSpacing = (-0.4).sp
                )
            }

            Spacer(Modifier.height(36.dp))

            Reveal(delayMillis = 140) {
                ServiceCard(
                    label = "Camera",
                    title = "Shoot a take.",
                    body = "Record it as separate clips. Pause between them, discard the last " +
                        "one, double-tap to flip, export each clip or the whole take.",
                    enabled = !busy,
                    onClick = onOpenCamera,
                    mark = { RecordMark() }
                )
            }

            Spacer(Modifier.height(14.dp))

            Reveal(delayMillis = 200) {
                ServiceCard(
                    label = "Video to text",
                    title = "Transcribe a video.",
                    body = "Pick any video on the phone. The transcript comes back as " +
                        "editable text, timestamped, in whichever language you choose below.",
                    enabled = !busy,
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.VideoOnly
                            )
                        )
                    },
                    mark = { TextMark() }
                )
            }

            Spacer(Modifier.height(10.dp))

            // Directly under the card it belongs to, because the recogniser loads one model per
            // run: this is a choice that has to be made before picking the video, not after.
            Reveal(delayMillis = 220) {
                LanguageField(
                    languages = state.languages,
                    selected = state.language,
                    enabled = !busy,
                    onClick = { languageSheetOpen = true }
                )
            }

            Spacer(Modifier.height(14.dp))

            Reveal(delayMillis = 240) {
                ServiceCard(
                    label = "Cut",
                    title = "Take out the pauses.",
                    body = "Pick a video and Cutly finds the dead air by how quiet it is, not " +
                        "by what was said. Nothing is uploaded, and nothing is cut until you " +
                        "like the numbers.",
                    enabled = !busy,
                    onClick = {
                        cutPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.VideoOnly
                            )
                        )
                    },
                    mark = { CutMark() }
                )
            }

            state.error?.let { message ->
                Spacer(Modifier.height(14.dp))
                ErrorBanner(message = message, onDismiss = viewModel::dismissError)
            }

            cleanup.error?.takeIf { cleanup.review == null }?.let { message ->
                Spacer(Modifier.height(14.dp))
                ErrorBanner(message = message, onDismiss = cleanupViewModel::dismissError)
            }

            Spacer(Modifier.height(24.dp))

            // The honest chips: what it does not do, stated before you tap anything.
            Reveal(delayMillis = 300) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("No account")
                    Chip("Runs offline")
                }
            }

            Spacer(Modifier.height(40.dp))

            Reveal(delayMillis = 360) {
                Text(
                    text = "Transcription runs on the phone's own recogniser. Burning captions " +
                        "into a cut is the one thing that goes online, and only when you ask.",
                    color = Faint,
                    fontFamily = TikTokSans,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        // Not while the review sheet is up: that is a separate window on top of this one, so an
        // overlay drawn here would be invisible and would only look like the app had frozen.
        if (busy && cleanup.review == null) {
            BusyOverlay(status = state.status ?: cleanup.status)
        }
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
        // Rebuilt only when the kept spans or the captions actually change, so dragging a slider
        // reloads the player once it settles rather than on every pixel of the drag.
        val preview = remember(review.keep, review.captions) {
            cleanupViewModel.previewComposition()
        }

        CleanupSheet(
            review = review,
            status = cleanup.status,
            error = cleanup.error,
            preview = preview,
            onSettingsChange = cleanupViewModel::updateSettings,
            onAddCaptions = cleanupViewModel::addCaptions,
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

/**
 * One service. White panel on the canvas, hairline border, wide soft shadow, device-corner radius.
 * The press state is a scale, never a shadow change.
 */
@Composable
private fun ServiceCard(
    label: String,
    title: String,
    body: String,
    enabled: Boolean,
    onClick: () -> Unit,
    mark: @Composable () -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseOut),
        label = "cardPress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .clip(RoundedCornerShape(28.dp))
            .background(Panel)
            .border(1.dp, Hairline, RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentTint),
                contentAlignment = Alignment.Center
            ) {
                mark()
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = label.uppercase(),
                color = Faint,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = title,
            color = Ink,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.Bold,
            fontSize = 29.sp,
            letterSpacing = (-1.2).sp,
            lineHeight = 32.sp
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = body,
            color = Muted,
            fontFamily = TikTokSans,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.2).sp
        )
    }
}

/** The camera's mark: the record dot, the one thing that button has always been. */
@Composable
private fun RecordMark() {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Accent)
    )
}

/** The transcriber's mark: three text rules, the last one short, the way a paragraph ends. */
@Composable
private fun TextMark() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(16.dp, 16.dp, 9.dp).forEach { width ->
            Box(
                modifier = Modifier
                    .width(width)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Accent)
            )
        }
    }
}

/** The cut's mark: two bars with a bite taken out between them, which is the whole operation. */
@Composable
private fun CutMark() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(7.dp, 11.dp).forEach { width ->
            Box(
                modifier = Modifier
                    .width(width)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Accent)
            )
        }
    }
}

/**
 * The chosen language, as one tappable field rather than a row of chips.
 *
 * The recogniser reports thirty-odd languages on some phones, which a horizontal strip of chips
 * turns into blind swiping. One field that states the current choice and opens a searchable list
 * is both smaller on the page and quicker to use.
 */
@Composable
private fun LanguageField(
    languages: List<TranscriptionLanguage>,
    selected: TranscriptionLanguage?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (languages.isEmpty()) {
        Text(
            text = "This phone has no on-device speech recogniser.",
            color = Faint,
            fontFamily = TikTokSans,
            fontSize = 12.sp
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            // Comfortably past the 48dp minimum target, since this is the only control here.
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Transcription language",
                color = Faint,
                fontFamily = TikTokSans,
                fontSize = 11.sp
            )
            Text(
                text = selected?.label ?: "Choose one",
                color = if (enabled) Ink else Muted,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
        Text(
            text = "Change",
            color = if (enabled) Accent else Faint,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text = text,
        color = Muted,
        fontFamily = TikTokSans,
        fontSize = 12.sp,
        letterSpacing = (-0.1).sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Hairline, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/**
 * Errors stay put — a toast that vanishes in 3.5s is a bad fit for a message the user needs to
 * act on (set an API key, retry a failed upload). This sits inline until dismissed or the next
 * transcribe attempt.
 */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFDECEC))
            .border(1.dp, Color(0xFFF5C2C2), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = message,
            color = Color(0xFFB3261E),
            fontFamily = TikTokSans,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Dismiss",
            color = Color(0xFFB3261E),
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
        )
    }
}

/** Extraction and upload both hold the whole screen, so the wait is stated rather than implied. */
@Composable
private fun BusyOverlay(status: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim)
            // Swallows taps so a card cannot be pressed behind the overlay.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(
                text = status ?: "Working…",
                color = Color.White,
                fontFamily = TikTokSans,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Fade and rise, on the shared expo-out curve, staggered by [delayMillis] so the page assembles
 * top-down instead of appearing all at once.
 *
 * Honours the system animation scale — Android's equivalent of prefers-reduced-motion — by
 * skipping straight to the resting state.
 */
@Composable
private fun Reveal(delayMillis: Int, content: @Composable () -> Unit) {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    var shown by remember { mutableStateOf(!animate) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 520, easing = EaseOut),
        label = "reveal"
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
            translationY = (1f - progress) * 24.dp.toPx()
        }
    ) {
        content()
    }
}
