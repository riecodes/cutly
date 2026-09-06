package com.eirmon.cutly.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.ui.theme.Accent
import com.eirmon.cutly.ui.theme.RecordBacking
import kotlin.math.abs

/**
 * The record control and the take's progress in one object, the way the reference does it: the
 * clip timeline is a ring around the button rather than a separate bar. Committed clips are
 * separated by white ticks, the clip being recorded extends the accent arc.
 */
@Composable
internal fun RecordButton(
    clips: List<Clip>,
    currentClipMs: Long,
    maxMs: Long,
    isRecording: Boolean,
    enabled: Boolean,
    linearZoom: Float,
    onPress: () -> Unit,
    onReleaseAfterHold: () -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTake = clips.isNotEmpty() || isRecording

    // Fresh take shows a bare red dot in a white ring; once a take exists the control gains the
    // translucent backing disc that the progress ring sits on.
    val innerSize by animateDpAsState(
        targetValue = when {
            isRecording -> 30.dp
            hasTake -> 58.dp
            else -> 60.dp
        },
        animationSpec = tween(160),
        label = "recordInnerSize"
    )
    val cornerPercent by animateFloatAsState(
        targetValue = if (isRecording) 0.28f else 0.5f,
        animationSpec = tween(160),
        label = "recordCorner"
    )
    val backingAlpha by animateFloatAsState(
        targetValue = if (hasTake) 1f else 0f,
        animationSpec = tween(200),
        label = "recordBacking"
    )

    val recordedMs = clips.sumOf { it.durationMs } + currentClipMs
    val dim = if (enabled) 1f else 0.4f

    Canvas(
        modifier = modifier
            .size(BUTTON_SIZE)
            .pointerInput(enabled, isRecording, linearZoom) {
                // Hand-rolled rather than detectTapGestures, because the button has to serve
                // three behaviours at once: tap to toggle hands-free, hold to record only while
                // held, and slide up mid-press to zoom.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!enabled) return@awaitEachGesture

                    val wasRecording = isRecording
                    val startY = down.position.y
                    val startZoom = linearZoom
                    val pressedAt = System.currentTimeMillis()
                    var slid = false

                    // Press acts immediately: starts a clip when idle, ends one when running.
                    onPress()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            val heldMs = System.currentTimeMillis() - pressedAt
                            // A quick tap leaves recording running; a deliberate hold ends on
                            // release, which is what makes both styles work on one control.
                            if (!wasRecording && heldMs >= HOLD_TO_RECORD_MS) onReleaseAfterHold()
                            break
                        }

                        val travel = startY - change.position.y
                        if (!slid && abs(travel) > viewConfiguration.touchSlop) slid = true
                        if (slid && !wasRecording) {
                            onZoomChange((startZoom + travel / ZOOM_TRAVEL.toPx()).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = size.minDimension / 2f - RING_STROKE.toPx() / 2f

        if (backingAlpha > 0f) {
            drawCircle(
                color = RecordBacking,
                radius = ringRadius - RING_STROKE.toPx() / 2f,
                center = center,
                alpha = backingAlpha * dim
            )
        }

        if (hasTake) {
            drawTakeRing(
                center = center,
                radius = ringRadius,
                clips = clips,
                currentClipMs = currentClipMs,
                maxMs = maxMs,
                alpha = backingAlpha * dim
            )
        } else {
            // Idle state: the thin white outline from the reference's first-launch camera.
            drawCircle(
                color = Color.White,
                radius = ringRadius,
                center = center,
                alpha = dim,
                style = Stroke(width = IDLE_RING_STROKE.toPx())
            )
        }

        val innerPx = innerSize.toPx()
        val innerColor = if (isRecording || !hasTake) Accent else Color.White
        drawRoundRect(
            color = innerColor,
            topLeft = Offset(center.x - innerPx / 2f, center.y - innerPx / 2f),
            size = Size(innerPx, innerPx),
            cornerRadius = CornerRadius(innerPx * cornerPercent),
            alpha = dim
        )
    }
}

/**
 * Draws the accent progress arc plus a white tick at every clip boundary, so the user can see
 * how many cuts the take already contains without a separate timeline.
 */
private fun DrawScope.drawTakeRing(
    center: Offset,
    radius: Float,
    clips: List<Clip>,
    currentClipMs: Long,
    maxMs: Long,
    alpha: Float
) {
    val stroke = Stroke(width = RING_STROKE.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Butt)
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)

    val recordedMs = clips.sumOf { it.durationMs } + currentClipMs
    val sweep = (recordedMs.toFloat() / maxMs).coerceIn(0f, 1f) * 360f

    drawArc(
        color = Accent,
        startAngle = START_ANGLE,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = stroke,
        alpha = alpha
    )

    // Ticks sit at the end of every committed clip. The final boundary is skipped while
    // recording, because that edge is the live arc head and already visible.
    var cumulative = 0L
    clips.forEachIndexed { index, clip ->
        cumulative += clip.durationMs
        val isLast = index == clips.lastIndex
        if (isLast && currentClipMs == 0L) return@forEachIndexed

        val angle = START_ANGLE + (cumulative.toFloat() / maxMs).coerceIn(0f, 1f) * 360f
        drawArc(
            color = Color.White,
            startAngle = angle - TICK_DEGREES / 2f,
            sweepAngle = TICK_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
            alpha = alpha
        )
    }
}

private val BUTTON_SIZE = 96.dp
private val RING_STROKE = 5.dp
private val IDLE_RING_STROKE = 4.dp
private const val START_ANGLE = -90f
private const val TICK_DEGREES = 3f

/** Press shorter than this is a tap (recording continues); longer ends on release. */
private const val HOLD_TO_RECORD_MS = 350L

/** Upward travel from the button that spans the whole zoom range. */
private val ZOOM_TRAVEL = 260.dp
