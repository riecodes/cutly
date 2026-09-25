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
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.model.Clip
import com.eirmon.cutly.transcribe.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    suspend fun merge(clips: List<Clip>): File {
        require(clips.isNotEmpty()) { "Nothing to merge" }
        val output = File(context.cacheDir, "cutly_merged_${System.currentTimeMillis()}.mp4")
        val sources = withContext(Dispatchers.IO) {
            clips.map { clip -> clip to ClipProbe.probeMetadata(clip.file) }
        }
        val quality = ExportQuality.merge(sources.map { (clip, info) ->
            ExportQuality.Source(info?.shortSide ?: clip.heightPx, info?.bitrate)
        })
        val outputHeight = requireNotNull(quality.height)

        // The first clip sets the frame. A clip of another shape (a landscape gallery video in a
        // portrait take) is letterboxed into it; left alone, the encoder would stretch it.
        val first = sources.first().second
        val frame = first?.let {
            ExportQuality.fitFrame(it.displayWidth, it.displayHeight, outputHeight)
        }
        // Once one clip needs the fixed frame, every clip gets it, so no two items can round to
        // sizes a pixel apart.
        val mixed = frame != null && sources.any { (_, info) ->
            info != null && !ExportQuality.sameShape(
                info.displayWidth, info.displayHeight, first.displayWidth, first.displayHeight
            )
        }
        val items = sources.map { (clip, info) ->
            editedItem(clip, info?.shortSide ?: clip.heightPx, outputHeight, frame.takeIf { mixed })
        }

        // Declaring both track types makes Transformer fill silence for any clip recorded without
        // audio, so a mic-denied clip cannot desync the rest of the take.
        val sequence = EditedMediaItemSequence.Builder(AUDIO_AND_VIDEO)
            .addItems(items)
            .build()

        return runTransformer(Composition.Builder(sequence).build(), output, quality.bitrate)
    }

    /**
     * Re-encodes one clip on its own, retaining its captured resolution and bitrate while applying
     * its speed. A single-source export has no mixed formats to normalise against.
     */
    suspend fun normalize(clip: Clip): File {
        val output = File(context.cacheDir, "cutly_part_${clip.file.nameWithoutExtension}.mp4")
        val info = withContext(Dispatchers.IO) { ClipProbe.probeMetadata(clip.file) }
        val quality = ExportQuality.single(
            ExportQuality.Source(info?.shortSide ?: clip.heightPx, info?.bitrate)
        )

        val sequence = EditedMediaItemSequence.Builder(AUDIO_AND_VIDEO)
            .addItem(editedItem(clip, info?.shortSide ?: clip.heightPx, quality.height))
            .build()

        return runTransformer(Composition.Builder(sequence).build(), output, quality.bitrate)
    }

    /**
     * Rebuilds one video out of only the spans worth keeping, dropping the silence between them.
     *
     * Every kept span becomes its own [EditedMediaItem] over the same source URI, and the
     * sequence concatenates them — the same machinery [merge] already uses for the clip list,
     * pointed at one file instead of many.
     *
     * Precise cuts need re-encoding, because a transmux can only start on a key frame and would
     * drag every boundary backwards by up to a key-frame interval. Composition video is therefore
     * left in its default transcode mode even when no resize effect is necessary.
     */
    suspend fun exportCut(
        source: Uri,
        keep: List<Span>,
        captions: List<Segment> = emptyList(),
        sourceInfo: ClipProbe.Info? = null
    ): File {
        val output = File(context.cacheDir, "cutly_cut_${System.currentTimeMillis()}.mp4")
        val info = sourceInfo ?: withContext(Dispatchers.IO) { ClipProbe.probe(context, source) }
        val quality = ExportQuality.single(ExportQuality.Source(info?.height, info?.bitrate))
        return runTransformer(
            cutComposition(
                source = source,
                keep = keep,
                captions = captions,
                sourceHeight = info?.height,
                outputHeight = quality.height,
                sourceDurationUs = info?.durationUs
            ),
            output,
            quality.bitrate
        )
    }

    /**
     * The cut, as a [Composition], before anything decides what to do with it.
     *
     * Split out from [exportCut] so the preview player and the export are driven by the same
     * object. `CompositionPlayer` takes exactly this, so what the user watches is the file they
     * will get rather than a second rendering path that can drift away from the first.
     */
    fun cutComposition(
        source: Uri,
        keep: List<Span>,
        captions: List<Segment> = emptyList(),
        sourceHeight: Int? = null,
        outputHeight: Int? = sourceHeight,
        sourceDurationUs: Long? = null
    ): Composition {
        val declaredDurationUs = CutTimeline.sourceDurationUs(keep, sourceDurationUs)

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
                    Effects(emptyList(), resizeEffects(sourceHeight, outputHeight))
                )
                // CompositionPlayer computes the clipped duration up front. Media3 expects the
                // original source duration here; giving it span.durationMs makes every later clip
                // fail because its absolute end position is past that falsely shortened source.
                .setDurationUs(declaredDurationUs)
                .build()
        }

        val sequence = EditedMediaItemSequence.Builder(AUDIO_AND_VIDEO)
            .addItems(items)
            .build()

        return Composition.Builder(sequence)
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
    private fun editedItem(
        clip: Clip,
        sourceHeight: Int,
        outputHeight: Int?,
        frame: Pair<Int, Int>? = null
    ): EditedMediaItem {
        val video = if (frame != null) {
            listOf(
                Presentation.createForWidthAndHeight(
                    frame.first, frame.second, Presentation.LAYOUT_SCALE_TO_FIT
                )
            )
        } else {
            resizeEffects(sourceHeight, outputHeight)
        }
        val builder = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(clip.file)))
            .setEffects(Effects(emptyList(), video))

        if (clip.speed != 1f) {
            // Pitch rides along with the speed, which is what a sped-up clip is expected to sound
            // like; maintaining pitch would make 3x footage sound oddly untouched.
            builder.setSpeed(SpeedParameters(ConstantSpeed(clip.speed), false))
        }

        return builder.build()
    }

    /**
     * Scaling is an encode effect, so do not add it when it would be an identity transform. The
     * heights are short sides (resolution tiers), so a portrait frame is scaled by its width.
     */
    private fun resizeEffects(sourceHeight: Int?, outputHeight: Int?): List<Presentation> =
        if (outputHeight != null && sourceHeight != outputHeight) {
            listOf(Presentation.createForShortSide(outputHeight))
        } else {
            emptyList()
        }

    /** A speed that never changes mid-clip — each clip carries exactly one factor. */
    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private suspend fun runTransformer(
        composition: Composition,
        output: File,
        videoBitrate: Int? = null
    ): File =
        suspendCancellableCoroutine { continuation ->
            val builder = Transformer.Builder(context)
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

            if (videoBitrate != null) {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(videoBitrate)
                            .build()
                    )
                    .build()
                builder.setEncoderFactory(encoderFactory)
            }

            val transformer = builder.build()

            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }

            transformer.start(composition, output.absolutePath)
        }

    private companion object {
        const val TAG = "Cutly"
        val AUDIO_AND_VIDEO = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        val AUDIO_ONLY = setOf(C.TRACK_TYPE_AUDIO)
    }
}
