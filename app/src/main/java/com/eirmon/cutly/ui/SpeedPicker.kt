package com.eirmon.cutly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eirmon.cutly.ui.theme.SheetSurface
import com.eirmon.cutly.ui.theme.TikTokSans

/**
 * The stacked speed column from the reference — fastest at the top, selected row inverted to
 * white. Anchored beside the rail rather than centred, so the rail icon stays visible.
 */
@Composable
internal fun SpeedPicker(
    options: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SheetSurface)
    ) {
        options.forEach { speed ->
            val isSelected = speed == selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .pointerInput(speed) { detectTapGestures(onTap = { onSelect(speed) }) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatSpeed(speed),
                    color = if (isSelected) Color.Black else Color.White,
                    fontFamily = TikTokSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            }
        }
    }
}
