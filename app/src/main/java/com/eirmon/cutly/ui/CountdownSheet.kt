package com.eirmon.cutly.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.SheetMuted
import com.eirmon.cutly.ui.theme.SheetSurface
import com.eirmon.cutly.ui.theme.SheetTrack
import com.eirmon.cutly.ui.theme.TikTokSans
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The self-timer sheet from the reference: pick 3s or 10s, drag the handle to cap how long the
 * clip runs once the countdown ends, then start. The cap is optional — dragged to the far right
 * it comes off, and the clip runs until it is stopped or the phone runs out of space.
 *
 * The bar pattern stands in for the reference's music waveform — there is no soundtrack here, so
 * it is drawn from a fixed function rather than pretending to sample audio.
 */
@Composable
internal fun CountdownSheet(
    seconds: Int,
    secondsOptions: List<Int>,
    limitMs: Long?,
    maxLimitMs: Long,
    onSelectSeconds: (Int) -> Unit,
    onLimitChange: (Long?) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(SheetSurface)
            .padding(horizontal = 22.dp)
            .padding(top = 26.dp, bottom = 30.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SheetTitle("Set countdown")
            SecondsToggle(
                seconds = seconds,
                options = secondsOptions,
                onSelect = onSelectSeconds
            )
        }

        Spacer(Modifier.height(26.dp))
        SheetTitle("Drag to set recording limit")
        Spacer(Modifier.height(14.dp))

        Text(
            text = formatClipLimit(limitMs),
            color = Color.White,
            fontFamily = TikTokSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.End)
        )

        Spacer(Modifier.height(8.dp))
        LimitTrack(limitMs = limitMs, maxLimitMs = maxLimitMs, onLimitChange = onLimitChange)
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Accent)
                .pointerInput(Unit) { detectTapGestures(onTap = { onStart() }) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Start countdown",
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp
    )
}

/** Two-up pill: the selected half is a white thumb, the other stays on the grey track. */
@Composable
private fun SecondsToggle(seconds: Int, options: List<Int>, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(SheetTrack)
    ) {
        options.forEach { option ->
            val isSelected = option == seconds
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .pointerInput(option) { detectTapGestures(onTap = { onSelect(option) }) }
                    .padding(horizontal = 26.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${option}s",
                    color = if (isSelected) Color.Black else SheetMuted,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
    }
}

@Composable
private fun LimitTrack(limitMs: Long?, maxLimitMs: Long, onLimitChange: (Long?) -> Unit) {
    // No cap parks the handle at the far right, which is also where dragging takes it off again.
    val fraction = limitMs?.let { (it.toFloat() / maxLimitMs).coerceIn(MIN_FRACTION, 1f) } ?: 1f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .pointerInput(maxLimitMs) {
                detectHorizontalDragGestures { change, _ ->
                    onLimitChange(limitAt(change.position.x / size.width, maxLimitMs))
                }
            }
            .pointerInput(maxLimitMs) {
                detectTapGestures { offset ->
                    onLimitChange(limitAt(offset.x / size.width, maxLimitMs))
                }
            }
    ) {
        val corner = CornerRadius(10.dp.toPx())
        drawRoundRect(
            color = Color.White,
            size = size,
            cornerRadius = corner,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )

        val barWidth = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val inset = 10.dp.toPx()
        val usable = size.width - inset * 2
        val count = (usable / (barWidth + gap)).toInt().coerceAtLeast(1)
        val handleX = inset + usable * fraction

        for (index in 0 until count) {
            val x = inset + index * (barWidth + gap)
            // Two interfering sine waves give the beaded, uneven look of the reference bars.
            val wave = abs(sin(index * 0.36f)) * 0.65f + abs(sin(index * 0.11f)) * 0.35f
            val barHeight = (size.height - inset) * (0.16f + wave * 0.78f)
            drawRoundRect(
                color = if (x <= handleX) Color.White else Color.White.copy(alpha = 0.25f),
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }

        val handleWidth = 8.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(handleX - handleWidth / 2f, inset / 2f),
            size = Size(handleWidth, size.height - inset),
            cornerRadius = CornerRadius(handleWidth / 2f)
        )
    }
}

/** Where along the track a gesture lands, in milliseconds — or null for the no-cap end of it. */
private fun limitAt(rawRatio: Float, maxLimitMs: Long): Long? {
    val ratio = rawRatio.coerceIn(MIN_FRACTION, 1f)
    return if (ratio >= NO_LIMIT_FRACTION) null else (ratio * maxLimitMs).roundToLong()
}

/** "No limit", "45s", "02:30" — seconds while they read naturally, the clock past that. */
internal fun formatClipLimit(limitMs: Long?): String = when {
    limitMs == null -> "No limit"
    limitMs < 60_000L -> "${limitMs / 1000}s"
    else -> formatDuration(limitMs)
}

/** A limit below this is not a usable clip, and a zero-width handle cannot be grabbed. */
private const val MIN_FRACTION = 0.03f

/** The last sliver of the track is the cap coming off rather than a ten-minute clip. */
private const val NO_LIMIT_FRACTION = 0.97f
