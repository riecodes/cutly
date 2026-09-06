package com.eirmon.cutly.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.transcribe.Segment
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns the clip list into deliverables.
 *
 * Media3 Transformer, not FFmpeg: it uses the device's hardware codecs, adds no native binaries
 * to the APK, and can transmux without re-encoding when clip formats already match.
 *
 * Must be called from the main thread — Transformer requires a Looper.
 */
@OptIn(UnstableApi::class)
class ClipExporter(private val context: Context) {

    /**
     * Concatenates every clip into one MP4.
     *
     * Clips can differ in resolution — front and back lenses report different maxima, and the
     * format chip lets the user change resolution between clips — so each item is normalised to
     * one height with a [Presentation] effect before the sequence is built.
     */
    suspend fun merge(clips: List<Clip>, outputHeight: Int = DEFAULT_HEIGHT): File {
        require(clips.isNotEmpty()) { "Nothing to merge" }
        val output = File(context.cacheDir, "cutly_merged_${System.currentTimeMillis()}.mp4")

        val items = clips.map { editedItem(it, outputHeight) }

        // Declaring both track types makes Transformer fill silence for any clip recorded without
        // audio, so a mic-denied clip cannot desync the rest of the take.
        val sequence = EditedMediaItemSequence.Builder(AUDIO_AND_VIDEO)
            .addItems(items)
            .build()

        return runTransformer(Composition.Builder(sequence).build(), output)
    }

    /**
     * Re-encodes one clip on its own, used for per-clip export so every delivered file has a
     * consistent size, speed and orientation regardless of which lens shot it.
     */
    suspend fun normalize(clip: Clip, outputHeight: Int = DEFAULT_HEIGHT): File {
        val output = File(context.cacheDir, "cutly_part_${clip.file.nameWithoutExtension}.mp4")

        val sequence = EditedMediaItemSequence.Builder(AUDIO_AND_VIDEO)
            .addItem(editedItem(clip, outputHeight))
            .build()

        return runTransformer(Composition.Builder(sequence).build(), output)
    }

    /**
     * Rebuilds one video out of only the spans worth keeping, dropping the silence between them.
     *
     * Every kept span becomes its own [EditedMediaItem] over the same source URI, and the
     * sequence concatenates them — the same machinery [merge] already uses for the clip list,
     * pointed at one file instead of many.
     *
     * Precise cuts need re-encoding, because a transmux can only start on a key frame and would
     * drag every boundary backwards by up to a key-frame interval. The [Presentation] effect
     * forces the re-encode anyway, so exact boundaries cost nothing extra here.
     */
    suspend fun exportCut(
        source: Uri,
        keep: List<Span>,
        captions: List<Segment> = emptyList(),
        outputHeight: Int = DEFAULT_HEIGHT
    ): File {
        require(keep.isNotEmpty()) { "Nothing left to keep" }
        val output = File(context.cacheDir, "cutly_cut_${System.currentTimeMillis()}.mp4")

        val items = keep.map { span ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(span.startMs)
                            .setEndPositionMs(span.endMs)
                            .build()
                    )
                    .build()
            )
                .setEffects(
                    Effects(emptyList(), listOf(Presentation.createForHeight(outputHeight)))
                )
                .build()
        }

        val sequence = EditedMediaItemSequence.Builder(AUDIO_AND_VIDEO)
            .addItems(items)
            .build()

        val composition = Composition.Builder(sequence)
            // Captions go on the composition, not on each item: the items are many windows onto
            // one source, so a per-item presentation time restarts at every join and every caption
            // after the first cut would be placed against the wrong clock.
            .apply {
                if (captions.isNotEmpty()) {
                    val overlay = OverlayEffect(listOf<TextureOverlay>(CaptionOverlay(captions)))
                    setEffects(Effects(emptyList(), listOf(overlay)))
                }
            }
            .build()

        return runTransformer(composition, output)
    }

    /**
     * Strips the video track and concatenates every clip's audio into one M4A, for transcription.
     *
     * The speed effect is deliberately not applied: a 3x take is unintelligible to a speech model,
     * and the transcript is of what was said, not of what the export will sound like.
     */
    suspend fun extractAudio(clips: List<Clip>): File {
        require(clips.isNotEmpty()) { "Nothing to transcribe" }
        return extractAudioFrom(clips.map { Uri.fromFile(it.file) })
    }

    /**
     * The same thing for one video the user picked off the device, which is the second service's
     * whole path — it never becomes a [Clip], so it never touches the take or the clip store.
     */
    suspend fun extractAudio(video: Uri): File = extractAudioFrom(listOf(video))

    private suspend fun extractAudioFrom(sources: List<Uri>): File {
        // Gemini wants the audio/m4a label, and the default muxer writes an MP4 container, so the
        // extension is the only thing that has to be right here.
        val output = File(context.cacheDir, "cutly_audio_${System.currentTimeMillis()}.m4a")

        val items = sources.map {
            EditedMediaItem.Builder(MediaItem.fromUri(it))
                .setRemoveVideo(true)
                .build()
        }

        // Forcing the audio track makes Transformer fill silence for any source recorded without a
        // mic, so a silent clip cannot swallow the clips after it.
        val sequence = EditedMediaItemSequence.Builder(AUDIO_ONLY)
            .addItems(items)
            .build()

        return runTransformer(Composition.Builder(sequence).build(), output)
    }

    /**
     * Capture always runs at real time; the speed the user picked is applied here.
     *
     * [EditedMediaItem.Builder.setSpeed] drives video and audio from the same provider, which is
     * why this no longer pairs a video effect with a separate audio processor — that older split
     * was easy to desync.
     */
    private fun editedItem(clip: Clip, outputHeight: Int): EditedMediaItem {
        val builder = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(clip.file)))
            .setEffects(
                Effects(emptyList(), listOf(Presentation.createForHeight(outputHeight)))
            )

        if (clip.speed != 1f) {
            // Pitch rides along with the speed, which is what a sped-up clip is expected to sound
            // like; maintaining pitch would make 3x footage sound oddly untouched.
            builder.setSpeed(SpeedParameters(ConstantSpeed(clip.speed), false))
        }

        return builder.build()
    }

    /** A speed that never changes mid-clip — each clip carries exactly one factor. */
    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private suspend fun runTransformer(composition: Composition, output: File): File =
        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        continuation.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        // ExportException's message is only the error-code name, so the thing that
                        // actually went wrong lives in the cause and would otherwise never be seen.
                        Log.e(TAG, "Export failed", exception)
                        output.delete()
                        continuation.resumeWithException(exception)
                    }
                })
                .build()

            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }

            transformer.start(composition, output.absolutePath)
        }

    private companion object {
        const val TAG = "Cutly"
        const val DEFAULT_HEIGHT = 1080
        val AUDIO_AND_VIDEO = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        val AUDIO_ONLY = setOf(C.TRACK_TYPE_AUDIO)
    }
}
