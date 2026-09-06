package com.eirmon.cutly.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import com.eirmon.cutly.ui.theme.TikTokSans

/**
 * Plays the cut before it is encoded.
 *
 * `CompositionPlayer` takes the same [Composition] the export does, so this is not a second
 * rendering path that can drift: the cuts, captions and scaling are whatever the saved file will
 * have. That is the whole reason the preview is worth having — a preview built another way would
 * need its own bugs found.
 *
 * @param composition the current cut, or null when there is nothing to play.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun CutPreview(composition: Composition?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { CompositionPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Reloading whenever the composition object changes is what makes the preview follow the
    // sliders. Keyed on the object, so a moved slider reloads and a bare recomposition does not.
    LaunchedEffect(composition) {
        player.pause()
        if (composition != null) {
            player.setComposition(composition)
            player.prepare()
        }
    }

    Box(
        modifier = modifier
            // ponytail: fixed 9:16, which is what short-form footage is. A landscape source will
            // sit oddly in it until the preview reads the real aspect ratio off the source.
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (composition == null) return@clickable
                if (playing) {
                    player.pause()
                } else {
                    // Restart from the top once it has run to the end, so a second tap replays
                    // instead of sitting on the last frame doing nothing.
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            // The player needs the surface *and* its size: it renders at whatever
                            // size it is told rather than measuring the surface itself.
                            player.setVideoSurface(holder.surface, Size(width, height))
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // setVideoSurface takes a non-null Surface, so detaching goes through
                            // Player.clearVideoSurface(). Rendering into a destroyed surface is a
                            // crash, not a no-op, so this cannot simply be skipped.
                            player.pause()
                            player.clearVideoSurface()
                        }
                    })
                }
            }
        )

        if (!playing) {
            Text(
                text = if (composition == null) "Nothing to play" else "▶",
                color = Color.White,
                fontFamily = TikTokSans,
                fontWeight = FontWeight.Bold,
                fontSize = if (composition == null) 12.sp else 34.sp
            )
        }
    }
}
