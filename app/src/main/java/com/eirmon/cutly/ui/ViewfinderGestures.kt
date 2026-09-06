package com.eirmon.cutly.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One gesture arbiter for the viewfinder, because the three gestures overlap and cannot be layered
 * as independent detectors:
 *
 *  - **one finger, horizontal flick** flips the lens
 *  - **two fingers, pinch** zooms
 *  - **one finger, stationary** falls through to the tap detector for focus and double-tap flip
 *
 * A gesture that ever sees a second finger is committed to pinching and can never end as a flip,
 * so spreading two fingers unevenly does not also swap the camera. Movement is consumed once it
 * passes slop, which is what keeps the end of a swipe or pinch from registering as a focus tap.
 */
internal fun Modifier.viewfinderGestures(
    onFlip: () -> Unit,
    onPinchStart: () -> Unit,
    onPinchScale: (Float) -> Unit
): Modifier = pointerInput(Unit) {
    val flipThresholdPx = SWIPE_FLIP_THRESHOLD.toPx()
    val slop = viewConfiguration.touchSlop

    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)

        var travelled = 0f
        var pinching = false
        var startDistance = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                val distance = hypot(
                    pressed[0].position.x - pressed[1].position.x,
                    pressed[0].position.y - pressed[1].position.y
                )

                if (!pinching) {
                    pinching = true
                    startDistance = distance
                    onPinchStart()
                } else if (startDistance > 0f && distance > 0f) {
                    onPinchScale(distance / startDistance)
                }

                pressed.forEach { it.consume() }
            } else if (!pinching) {
                val change = pressed.first()
                travelled += change.position.x - change.previousPosition.x
                // Claim the gesture once it is clearly a drag; the tap detector then cancels.
                if (abs(travelled) > slop) change.consume()
            }
        }

        if (!pinching && abs(travelled) >= flipThresholdPx) onFlip()
    }
}

/**
 * How far a horizontal swipe must travel before it flips the lens. Well above touch slop, so an
 * imprecise tap stays a tap.
 */
private val SWIPE_FLIP_THRESHOLD = 64.dp
