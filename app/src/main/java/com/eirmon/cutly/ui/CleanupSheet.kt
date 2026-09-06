package com.eirmon.cutly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.transformer.Composition
import com.eirmon.cutly.CleanupViewModel
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.DialogDivider
import com.eirmon.cutly.ui.theme.DialogNeutral
import com.eirmon.cutly.ui.theme.DialogSurface
import com.eirmon.cutly.ui.theme.DialogTitle
import com.eirmon.cutly.ui.theme.Faint
import com.eirmon.cutly.ui.theme.Hairline
import com.eirmon.cutly.ui.theme.Muted
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlin.math.roundToInt

/**
 * What the cut would do, before anything is encoded.
 *
 * The sliders are the point of this sheet. There is no threshold that is right twice: a phone mic
 * in a quiet room floors near -55 dBFS and the same phone on a street floors near -30, so a fixed
 * -40 either trims nothing or eats speech depending on where the video was shot. Detection is
 * pure arithmetic over readings already in memory, so the bar and the numbers track the slider
 * with no spinner and no re-decode.
 */
@Composable
internal fun CleanupSheet(
    review: CleanupViewModel.Review,
    /** Non-null while this sheet's own work is running. */
    status: String?,
    error: String?,
    /** The current cut, rebuilt by the caller whenever the kept spans change. */
    preview: Composition?,
    onSettingsChange: (SilenceSettings) -> Unit,
    onAddCaptions: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val saved = review.savedName != null
    val busy = status != null

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DialogSurface)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = if (saved) "Saved to Movies/Cutly" else "Cut the dead air",
                    color = DialogTitle,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // The headline number, sized like a result rather than a label.
                Text(
                    text = "${seconds(review.originalMs)} → ${seconds(review.keptMs)}",
                    color = DialogTitle,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 34.sp,
                    letterSpacing = (-1.4).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = summary(review),
                    color = Muted,
                    fontFamily = TikTokSans,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // Above the bar rather than below: the bar says where the cuts are, the preview
                // says whether they sound right, and the second question is the one people have.
                CutPreview(
                    composition = preview,
                    modifier = Modifier.height(200.dp).align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(14.dp))

                KeepBar(keep = review.keep, totalMs = review.originalMs)

                if (!saved) {
                    Spacer(Modifier.height(20.dp))

                    Knob(
                        label = "Threshold",
                        value = "${review.settings.thresholdDb.roundToInt()} dB",
                        position = review.settings.thresholdDb,
                        range = -70f..-15f,
                        onChange = { onSettingsChange(review.settings.copy(thresholdDb = it)) }
                    )

                    Spacer(Modifier.height(10.dp))

                    Knob(
                        label = "Shortest cut",
                        value = "${review.settings.minSilenceMs} ms",
                        position = review.settings.minSilenceMs.toFloat(),
                        range = 100f..1500f,
                        onChange = {
                            onSettingsChange(
                                review.settings.copy(minSilenceMs = it.roundToInt().toLong())
                            )
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    Knob(
                        label = "Breathing room",
                        value = "${review.settings.padMs} ms",
                        position = review.settings.padMs.toFloat(),
                        range = 0f..300f,
                        onChange = {
                            onSettingsChange(
                                review.settings.copy(padMs = it.roundToInt().toLong())
                            )
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Breathing room is silence left at each boundary. At zero the " +
                            "first consonant of every sentence gets shaved.",
                        color = Faint,
                        fontFamily = TikTokSans,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(18.dp))

                    CaptionRow(
                        review = review,
                        enabled = !busy,
                        onAddCaptions = onAddCaptions
                    )
                }

                if (status != null) {
                    Spacer(Modifier.height(16.dp))
                    Notice(text = status, tone = DialogNeutral)
                }

                if (error != null) {
                    Spacer(Modifier.height(16.dp))
                    Notice(text = error, tone = Accent)
                }
            }

            HairLine()

            Row(modifier = Modifier.height(52.dp)) {
                DialogAction(
                    label = if (saved) "Done" else "Cancel",
                    color = DialogNeutral,
                    bold = saved,
                    onClick = { if (!busy) onDismiss() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                if (!saved) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(DialogDivider)
                    )
                    DialogAction(
                        label = "Save cut",
                        // Greyed rather than hidden: a disabled Save with "nothing to cut" above
                        // it explains itself, a missing button looks like a broken sheet.
                        color = if (review.hasSomethingToCut && !busy) Accent else DialogNeutral,
                        bold = true,
                        onClick = { if (review.hasSomethingToCut && !busy) onSave() },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * The timeline as one bar: accent for what survives, hairline for what goes.
 *
 * Cheaper to read than a cut count. Dead air bunched at the end looks completely different from
 * dead air spread through the middle, and the fix for each is a different slider.
 */
@Composable
private fun KeepBar(keep: List<Span>, totalMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Hairline)
    ) {
        if (totalMs <= 0) return@Row
        var cursor = 0L
        keep.forEach { span ->
            // A zero-weight Row child throws, so empty leading or trailing gaps are skipped.
            if (span.startMs > cursor) {
                Spacer(Modifier.weight((span.startMs - cursor).toFloat()))
            }
            Box(
                modifier = Modifier
                    .weight(span.durationMs.toFloat())
                    .fillMaxHeight()
                    .background(Accent)
            )
            cursor = span.endMs
        }
        if (cursor < totalMs) Spacer(Modifier.weight((totalMs - cursor).toFloat()))
    }
}

@Composable
private fun Knob(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Muted,
                fontFamily = TikTokSans,
                fontSize = 12.sp
            )
            Text(
                text = value,
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Hairline
            )
        )
    }
}

/**
 * Captions are opt-in, and the row says why: everything else in this sheet is offline, and this is
 * the one action that sends the audio somewhere.
 */
@Composable
private fun CaptionRow(
    review: CleanupViewModel.Review,
    enabled: Boolean,
    onAddCaptions: () -> Unit
) {
    val captions = review.captions

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Captions",
                color = DialogTitle,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = when {
                    captions == null -> "Off. Uploads the audio to transcribe it."
                    captions.isEmpty() -> "No speech found."
                    else -> "${captions.size} lines, burned in on save."
                },
                color = Faint,
                fontFamily = TikTokSans,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        if (captions == null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Add",
                color = if (enabled) Accent else DialogNeutral,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (enabled) onAddCaptions() }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** A line of status or failure, inside the dialog where it can actually be seen. */
@Composable
private fun Notice(text: String, tone: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = tone,
        fontFamily = TikTokSans,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun summary(review: CleanupViewModel.Review): String = when {
    review.keep.isEmpty() -> "No audio above the threshold. Try a lower one."
    !review.hasSomethingToCut -> "Nothing long enough to cut at these settings."
    else -> "${review.cutCount} cuts, ${seconds(review.removedMs)} removed"
}

private fun seconds(ms: Long): String {
    val total = ms / 1000
    return if (total < 60) "${total}s" else "${total / 60}m ${total % 60}s"
}
