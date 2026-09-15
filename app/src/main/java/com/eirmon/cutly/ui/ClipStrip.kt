package com.eirmon.cutly.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.eirmon.cutly.R
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.ui.theme.ChromePill
import com.eirmon.cutly.ui.theme.ModeLabelStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The take's clips in recording order, one thumbnail each, in the black band under the
 * viewfinder. Tap opens the clip in the player, the corner cross discards it after confirming,
 * and a long press picks a clip up to drag it into a new position.
 */
@Composable
internal fun ClipStrip(
    clips: List<Clip>,
    enabled: Boolean,
    onOpen: (Int) -> Unit,
    onDiscard: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // The lifted clip is tracked by file rather than index: a swap changes its index mid-drag,
    // and the gesture has to follow the clip, not the slot.
    var lifted by remember { mutableStateOf<String?>(null) }
    var liftOffset by remember { mutableFloatStateOf(0f) }

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(THUMB_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(clips, key = { _, clip -> clip.file.path }) { index, clip ->
            val latestIndex by rememberUpdatedState(index)
            val latestCount by rememberUpdatedState(clips.size)
            val isLifted = lifted == clip.file.path
            ClipThumb(
                clip = clip,
                enabled = enabled,
                onOpen = { onOpen(index) },
                onDiscard = { onDiscard(index) },
                modifier = Modifier
                    .animateItem()
                    .zIndex(if (isLifted) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (isLifted) liftOffset else 0f
                        scaleX = if (isLifted) 1.08f else 1f
                        scaleY = scaleX
                    }
                    .pointerInput(clip.file.path, enabled) {
                        if (!enabled) return@pointerInput
                        val step = (THUMB_WIDTH + THUMB_GAP).toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                lifted = clip.file.path
                                liftOffset = 0f
                            },
                            onDragEnd = { lifted = null },
                            onDragCancel = { lifted = null },
                            onDrag = { change, amount ->
                                change.consume()
                                liftOffset += amount.x
                                // Past half a slot the neighbour slides under; the offset is
                                // rebased so the lifted clip stays under the finger.
                                if (liftOffset > step / 2 && latestIndex < latestCount - 1) {
                                    onMove(latestIndex, latestIndex + 1)
                                    liftOffset -= step
                                } else if (liftOffset < -step / 2 && latestIndex > 0) {
                                    onMove(latestIndex, latestIndex - 1)
                                    liftOffset += step
                                }
                            }
                        )
                    }
            )
        }
    }
}

@Composable
private fun ClipThumb(
    clip: Clip,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frame by produceState<ImageBitmap?>(initialValue = null, key1 = clip.file) {
        value = withContext(Dispatchers.IO) { firstFrame(clip.file)?.asImageBitmap() }
    }

    Box(
        modifier = modifier
            .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(ChromePill)
            .pointerInput(enabled) { detectTapGestures(onTap = { if (enabled) onOpen() }) }
            .semantics { role = Role.Button }
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = formatDuration(clip.durationMs),
            style = ModeLabelStyle,
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDiscard() }) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Discard clip",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/** The first key frame, scaled to thumbnail size. Null when the file cannot be read. */
private fun firstFrame(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 180, 320)
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private val THUMB_WIDTH = 60.dp
private val THUMB_HEIGHT = 92.dp
private val THUMB_GAP = 10.dp
