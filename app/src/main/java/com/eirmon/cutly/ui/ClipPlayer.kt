package com.eirmon.cutly.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.eirmon.cutly.R
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.ui.theme.ChromePill
import com.eirmon.cutly.ui.theme.ModeLabelStyle
import kotlinx.coroutines.delay

/**
 * Full-screen loop of one clip, drawn over the camera. Tap toggles play and pause, the cross
 * and the system back both return to the viewfinder.
 */
@Composable
internal fun ClipPlayer(clip: Clip, index: Int, count: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    // Portrait until the first frame reports otherwise; the box reshapes to the real video.
    var aspect by remember { mutableFloatStateOf(9f / 16f) }

    val player = remember(clip.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(clip.file)))
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    BackHandler(onBack = onClose)

    // The slider follows playback at a steady tick; while the thumb is held it leads instead,
    // and the player is asked to catch up.
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) positionMs = player.currentPosition
            delay(POSITION_TICK_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(player) {
                detectTapGestures(onTap = { player.playWhenReady = !player.playWhenReady })
            }
    ) {
        AndroidView(
            factory = { viewContext -> SurfaceView(viewContext).also(player::setVideoSurfaceView) },
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(aspect)
        )

        Text(
            text = "Clip ${index + 1} of $count",
            style = ModeLabelStyle,
            fontSize = 13.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 14.dp)
                .background(ChromePill, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(ChromePill)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Back to camera",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(positionMs),
                    style = ModeLabelStyle,
                    fontSize = 12.sp,
                    color = Color.White
                )
                Text(
                    text = formatDuration(clip.durationMs),
                    style = ModeLabelStyle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            TimelineScrubber(
                positionMs = positionMs,
                durationMs = clip.durationMs,
                enabled = true,
                onScrubbingChange = { scrubbing = it },
                onSeek = { target ->
                    positionMs = target
                    player.seekTo(target)
                }
            )
        }
    }
}

/** How often the slider re-reads the player's position while it plays. */
private const val POSITION_TICK_MS = 50L
