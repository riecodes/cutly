package com.eirmon.cutly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.video.Quality
import com.eirmon.cutly.R
import com.eirmon.cutly.model.VideoFormat
import com.eirmon.cutly.ui.theme.SheetMuted
import com.eirmon.cutly.ui.theme.SheetSurface
import com.eirmon.cutly.ui.theme.SheetTrack
import com.eirmon.cutly.ui.theme.TikTokSans

/**
 * Video size panel, laid out like the stock Samsung camera the user pointed at: a Size row and an
 * FPS row over one description line.
 *
 * Every option shown is one the *current lens* reported. A frame rate that the selected size
 * cannot reach is rendered disabled rather than hidden, so the user can see that FHD 60 exists on
 * the back lens even while the front one is bound.
 */
@Composable
internal fun VideoSizePanel(
    available: List<VideoFormat>,
    active: VideoFormat?,
    onSelectQuality: (Quality) -> Unit,
    onSelectFps: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val qualities = available.map { it.quality }.distinct()
    val frameRates = available.map { it.fps }.distinct().sortedDescending()
    val fpsForActiveQuality = available.filter { it.quality == active?.quality }.map { it.fps }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SheetSurface)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Video size",
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SheetTrack)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OptionRow(
            label = "Size",
            options = qualities.map { quality ->
                PanelOption(
                    text = VideoFormat.qualityLabel(quality),
                    selected = quality == active?.quality,
                    enabled = true,
                    onClick = { onSelectQuality(quality) }
                )
            }
        )

        Spacer(Modifier.height(10.dp))

        OptionRow(
            label = "FPS",
            options = frameRates.map { fps ->
                PanelOption(
                    text = fps.toString(),
                    selected = fps == active?.fps,
                    // A rate the chosen size cannot deliver on this lens stays visible but dead.
                    enabled = fps in fpsForActiveQuality,
                    onClick = { onSelectFps(fps) }
                )
            }
        )

        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SheetTrack)
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = active?.let { describe(it) } ?: "Detecting camera…",
            color = Color.White,
            fontFamily = TikTokSans,
            fontSize = 14.sp
        )
    }
}

private data class PanelOption(
    val text: String,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun OptionRow(label: String, options: List<PanelOption>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = SheetMuted,
            fontFamily = TikTokSans,
            fontSize = 15.sp,
            modifier = Modifier.width(58.dp)
        )
        // A lens can report five sizes; scrolling keeps them on one line instead of wrapping
        // a label like "SD" onto two.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            options.forEach { option ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (option.selected) Color.White else Color.Transparent)
                        .pointerInput(option.text, option.enabled) {
                            detectTapGestures(onTap = { if (option.enabled) option.onClick() })
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = option.text,
                        color = when {
                            option.selected -> Color.Black
                            !option.enabled -> Color.White.copy(alpha = 0.28f)
                            else -> Color.White
                        },
                        fontFamily = TikTokSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

private fun describe(format: VideoFormat): String = when (format.quality) {
    Quality.UHD -> "Ultra HD resolution · ${format.fps} fps"
    Quality.FHD -> "Full HD resolution · ${format.fps} fps"
    Quality.HD -> "HD resolution · ${format.fps} fps"
    else -> "${format.heightPx}p · ${format.fps} fps"
}
