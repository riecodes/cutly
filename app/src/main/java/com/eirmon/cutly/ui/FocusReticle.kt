package com.eirmon.cutly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** One tap-to-focus event. The id makes a repeat tap in the same spot restart the animation. */
internal data class FocusTap(val position: Offset, val id: Long)

/**
 * The reticle that confirms a tap-to-focus landed. Without it the tap is invisible — the
 * viewfinder just quietly refocuses and the user cannot tell whether the app registered anything.
 *
 * Snaps in slightly oversized, settles, holds, then fades. [onFinished] clears the owning state so
 * the composable leaves the tree instead of sitting at zero alpha.
 */
@Composable
internal fun FocusReticle(tap: FocusTap, onFinished: () -> Unit) {
    val scale = remember { Animatable(1.3f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(tap.id) {
        alpha.snapTo(1f)
        scale.snapTo(1.3f)
        scale.animateTo(1f, tween(180))
        delay(600)
        alpha.animateTo(0f, tween(260))
        onFinished()
    }

    val sizeDp = RETICLE_SIZE

    Canvas(
        modifier = Modifier
            .offset {
                // The tap arrives in pixels from the viewfinder, so the reticle is positioned in
                // pixels too and simply centred on the touch point.
                val half = sizeDp.toPx() / 2f
                IntOffset(
                    (tap.position.x - half).roundToInt(),
                    (tap.position.y - half).roundToInt()
                )
            }
            .size(sizeDp)
    ) {
        val stroke = 1.5.dp.toPx()
        val radius = (size.minDimension / 2f - stroke) * scale.value
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = Color.White,
            radius = radius,
            center = center,
            alpha = alpha.value,
            style = Stroke(width = stroke)
        )

        // Four ticks reading inward, the conventional camera focus mark.
        val tickOuter = radius
        val tickInner = radius * 0.78f
        listOf(
            Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)
        ).forEach { direction ->
            drawLine(
                color = Color.White,
                start = center + Offset(direction.x * tickInner, direction.y * tickInner),
                end = center + Offset(direction.x * tickOuter, direction.y * tickOuter),
                strokeWidth = stroke,
                alpha = alpha.value
            )
        }

        drawCircle(
            color = Color.White,
            radius = radius * 0.09f,
            center = center,
            alpha = alpha.value * 0.9f
        )
    }
}

private val RETICLE_SIZE = 76.dp
