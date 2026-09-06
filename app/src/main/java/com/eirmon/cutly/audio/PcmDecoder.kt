package com.eirmon.cutly.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Reads an audio file and reports how loud it is, one reading per fixed window.
 *
 * `MediaExtractor` + `MediaCodec`, both in the platform, so measuring loudness adds no dependency
 * and no native binary — the same reason [com.eirmon.cutly.export.ClipExporter] uses Transformer
 * instead of FFmpeg.
 *
 * Only the level array is kept. Decoded PCM is summed and thrown away a window at a time, so a
 * ten-minute take costs about 120 KB here rather than the ~100 MB the samples would.
 */
object PcmDecoder {

    /** Per-window loudness in dBFS, plus what it took to produce it. */
    class Levels(
        val db: FloatArray,
        val frameMs: Long,
        val durationMs: Long
    )

    /**
     * @param audio an M4A from [com.eirmon.cutly.export.ClipExporter.extractAudio].
     * @param frameMs the measurement window. 20 ms is about a syllable's onset — long enough to
     *        be a stable RMS, short enough that a cut boundary lands inside a pause, not a word.
     * @throws IOException when the file has no audio track, or the decoder emits PCM before it
     *         reports its format. Failing loudly beats reporting a file as silent.
     */
    suspend fun levels(audio: File, frameMs: Long = DEFAULT_FRAME_MS): Levels =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(audio.absolutePath)

                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IOException("No audio track in " + audio.name)

                extractor.selectTrack(track)
                val inputFormat = extractor.getTrackFormat(track)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }

                val meter = Meter(frameMs)
                drain(extractor, codec, meter)

                // The container's own duration covers the trailing partial window and any encoder
                // delay the decoder swallowed, so it beats counting samples when it is present.
                val declaredMs = inputFormat
                    .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                    ?.let { it.getLong(MediaFormat.KEY_DURATION) / 1000 }
                    ?: 0L

                Levels(
                    db = meter.finish(),
                    frameMs = frameMs,
                    durationMs = maxOf(declaredMs, meter.measuredMs)
                )
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }

    /** Pumps the extractor through the decoder, handing every output window to [meter]. */
    private fun drain(extractor: MediaExtractor, codec: MediaCodec, meter: Meter) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> meter.configure(codec.outputFormat)
                index >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        // Position and limit are not guaranteed to be set for us.
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        meter.accept(buffer)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    }

    /**
     * Folds interleaved PCM into one RMS reading per window.
     *
     * Channels are summed rather than separated: a stereo take is silent only when both sides
     * are, and a cut applies to the whole timeline either way.
     */
    private class Meter(private val frameMs: Long) {
        private val readings = ArrayList<Float>()
        private var samplesPerFrame = 0
        private var isFloat = false
        private var sampleRate = 0
        private var channels = 1

        private var sumOfSquares = 0.0
        private var count = 0
        private var totalSamples = 0L

        /** Milliseconds actually decoded, used when the container declares no duration. */
        val measuredMs: Long
            get() = if (sampleRate == 0) 0L
            else totalSamples * 1000 / (sampleRate.toLong() * channels)

        fun configure(format: MediaFormat) {
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            isFloat = format
                .takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }
                ?.getInteger(MediaFormat.KEY_PCM_ENCODING) == ENCODING_PCM_FLOAT
            samplesPerFrame =
                ((sampleRate.toLong() * frameMs / 1000) * channels).toInt().coerceAtLeast(1)
        }

        fun accept(buffer: ByteBuffer) {
            // A wrong window size would silently misattribute every reading to the wrong time,
            // which moves the cuts. Better to fail than to cut in the wrong place.
            if (samplesPerFrame == 0) throw IOException("Decoder emitted PCM before its format")

            buffer.order(ByteOrder.nativeOrder())
            if (isFloat) {
                val floats = buffer.asFloatBuffer()
                while (floats.hasRemaining()) add(floats.get().toDouble())
            } else {
                val shorts = buffer.asShortBuffer()
                while (shorts.hasRemaining()) add(shorts.get() / FULL_SCALE_16_BIT)
            }
        }

        private fun add(sample: Double) {
            sumOfSquares += sample * sample
            count++
            totalSamples++
            if (count == samplesPerFrame) flush()
        }

        /** Emits the trailing partial window so the tail of the file is not read as silence. */
        fun finish(): FloatArray {
            if (count > 0) flush()
            return readings.toFloatArray()
        }

        private fun flush() {
            val rms = sqrt(sumOfSquares / count)
            readings += if (rms <= 0.0) SILENCE_FLOOR_DB
            else (20 * log10(rms)).toFloat().coerceAtLeast(SILENCE_FLOOR_DB)
            sumOfSquares = 0.0
            count = 0
        }
    }

    private const val DEFAULT_FRAME_MS = 20L
    private const val TIMEOUT_US = 10_000L
    private const val FULL_SCALE_16_BIT = 32768.0

    /** `AudioFormat.ENCODING_PCM_FLOAT`, inlined so this file needs no AudioFormat import. */
    private const val ENCODING_PCM_FLOAT = 4

    /** True digital silence is negative infinity dB, which no comparison survives. */
    private const val SILENCE_FLOOR_DB = -120f
}
