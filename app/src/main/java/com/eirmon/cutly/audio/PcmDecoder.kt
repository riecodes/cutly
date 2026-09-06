package com.eirmon.cutly.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decodes compressed audio and hands the samples to whoever asked for them.
 *
 * `MediaExtractor` + `MediaCodec`, both in the platform, so neither measuring loudness nor
 * preparing audio for the speech recogniser adds a dependency or a native binary — the same
 * reason [com.eirmon.cutly.export.ClipExporter] uses Transformer instead of FFmpeg.
 *
 * Two callers, one decode loop: [levels] sums the samples into loudness readings and throws the
 * PCM away, [toMonoPcm] writes it out. Keeping the loop in one place is what stops the two from
 * disagreeing about sample rates or channel order.
 */
object PcmDecoder {

    /** Per-window loudness in dBFS, plus what it took to produce it. */
    class Levels(
        val db: FloatArray,
        val frameMs: Long,
        val durationMs: Long
    )

    /** What [toMonoPcm] actually wrote, which the speech recogniser has to be told. */
    class PcmSpec(val sampleRate: Int, val channelCount: Int, val bytes: Long)

    /**
     * @param audio an M4A from [com.eirmon.cutly.export.ClipExporter.extractAudio].
     * @param frameMs the measurement window. 20 ms is about a syllable's onset — long enough to
     *        be a stable RMS, short enough that a cut boundary lands inside a pause, not a word.
     * @throws IOException when the file has no audio track, or the decoder emits PCM before it
     *         reports its format. Failing loudly beats reporting a file as silent.
     */
    suspend fun levels(audio: File, frameMs: Long = DEFAULT_FRAME_MS): Levels =
        withContext(Dispatchers.IO) {
            val meter = Meter(frameMs)
            val declaredMs = decode(audio, meter)
            Levels(
                db = meter.finish(),
                frameMs = frameMs,
                // The container's own duration covers the trailing partial window and any encoder
                // delay the decoder swallowed, so it beats counting samples when it is present.
                durationMs = maxOf(declaredMs, meter.measuredMs)
            )
        }

    /**
     * Writes the audio out as raw mono PCM at [targetRate], which is what a speech recogniser
     * expects to be handed.
     *
     * Downmixed and resampled rather than passed through at the source rate: 16 kHz mono is what
     * on-device speech models are trained on, and it makes the temp file about a fifth the size,
     * which matters when a ten-minute take would otherwise be a hundred megabytes of cache.
     */
    suspend fun toMonoPcm(audio: File, output: File, targetRate: Int = SPEECH_RATE): PcmSpec =
        withContext(Dispatchers.IO) {
            output.outputStream().buffered().use { stream ->
                val writer = MonoPcmWriter(stream, targetRate)
                decode(audio, writer)
                writer.finish()
                PcmSpec(targetRate, 1, writer.bytesWritten)
            }
        }

    /** Something that wants the decoded samples. */
    private interface Sink {
        fun configure(format: MediaFormat)
        fun accept(buffer: ByteBuffer)
    }

    /**
     * Runs the extractor through the decoder into [sink].
     *
     * @return the duration the container declares, in milliseconds, or 0 when it declares none.
     */
    private fun decode(audio: File, sink: Sink): Long {
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

            drain(extractor, codec, sink)

            return inputFormat
                .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.let { it.getLong(MediaFormat.KEY_DURATION) / 1000 }
                ?: 0L
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Pumps the extractor through the decoder, handing every output buffer to [sink]. */
    private fun drain(extractor: MediaExtractor, codec: MediaCodec, sink: Sink) {
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
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.configure(codec.outputFormat)
                index >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        // Position and limit are not guaranteed to be set for us.
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.accept(buffer)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    }

    /** Reads the sample format a decoder reports, which both sinks need in the same way. */
    private abstract class FormatAwareSink : Sink {
        protected var sampleRate = 0
        protected var channels = 1
        protected var isFloat = false

        override fun configure(format: MediaFormat) {
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            isFloat = format
                .takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }
                ?.getInteger(MediaFormat.KEY_PCM_ENCODING) == ENCODING_PCM_FLOAT
            onConfigured()
        }

        protected open fun onConfigured() = Unit

        /** Walks one buffer as normalised -1..1 samples, whatever the decoder's encoding is. */
        protected fun forEachSample(buffer: ByteBuffer, action: (Double) -> Unit) {
            buffer.order(ByteOrder.nativeOrder())
            if (isFloat) {
                val floats = buffer.asFloatBuffer()
                while (floats.hasRemaining()) action(floats.get().toDouble())
            } else {
                val shorts = buffer.asShortBuffer()
                while (shorts.hasRemaining()) action(shorts.get() / FULL_SCALE_16_BIT)
            }
        }
    }

    /**
     * Folds interleaved PCM into one RMS reading per window.
     *
     * Channels are summed rather than separated: a stereo take is silent only when both sides
     * are, and a cut applies to the whole timeline either way.
     */
    private class Meter(private val frameMs: Long) : FormatAwareSink() {
        private val readings = ArrayList<Float>()
        private var samplesPerFrame = 0

        private var sumOfSquares = 0.0
        private var count = 0
        private var totalSamples = 0L

        /** Milliseconds actually decoded, used when the container declares no duration. */
        val measuredMs: Long
            get() = if (sampleRate == 0) 0L
            else totalSamples * 1000 / (sampleRate.toLong() * channels)

        override fun onConfigured() {
            samplesPerFrame =
                ((sampleRate.toLong() * frameMs / 1000) * channels).toInt().coerceAtLeast(1)
        }

        override fun accept(buffer: ByteBuffer) {
            // A wrong window size would silently misattribute every reading to the wrong time,
            // which moves the cuts. Better to fail than to cut in the wrong place.
            if (samplesPerFrame == 0) throw IOException("Decoder emitted PCM before its format")

            forEachSample(buffer) { sample ->
                sumOfSquares += sample * sample
                count++
                totalSamples++
                if (count == samplesPerFrame) flush()
            }
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

    /**
     * Downmixes to mono, resamples, and writes little-endian 16-bit PCM.
     *
     * ponytail: nearest-sample resampling with no anti-alias filter. Speech models are trained on
     * real-world audio and are unbothered by the artefacts; if this ever feeds something pickier,
     * a low-pass before the decimation is the upgrade.
     */
    private class MonoPcmWriter(
        private val stream: OutputStream,
        private val targetRate: Int
    ) : FormatAwareSink() {

        var bytesWritten = 0L
            private set

        private val out = ByteBuffer.allocate(WRITE_CHUNK).order(ByteOrder.LITTLE_ENDIAN)

        private var frameSum = 0.0
        private var frameCount = 0
        private var sourceFrames = 0L
        private var writtenFrames = 0L

        override fun accept(buffer: ByteBuffer) {
            if (sampleRate == 0) throw IOException("Decoder emitted PCM before its format")

            forEachSample(buffer) { sample ->
                frameSum += sample
                frameCount++
                if (frameCount >= channels) {
                    // One interleaved frame is complete: average its channels down to mono.
                    val mono = frameSum / channels
                    frameSum = 0.0
                    frameCount = 0
                    sourceFrames++

                    // Emit whenever the output clock catches up with the input clock. Integer
                    // maths on running counts, so the two rates cannot drift apart over a long
                    // take the way repeated floating-point accumulation would.
                    val due = sourceFrames * targetRate / sampleRate
                    while (writtenFrames < due) {
                        write(mono)
                        writtenFrames++
                    }
                }
            }
        }

        private fun write(sample: Double) {
            if (out.remaining() < 2) flushChunk()
            out.putShort((sample.coerceIn(-1.0, 1.0) * MAX_16_BIT).roundToInt().toShort())
        }

        fun finish() {
            flushChunk()
            stream.flush()
        }

        private fun flushChunk() {
            if (out.position() == 0) return
            stream.write(out.array(), 0, out.position())
            bytesWritten += out.position()
            out.clear()
        }
    }

    private const val DEFAULT_FRAME_MS = 20L
    private const val TIMEOUT_US = 10_000L
    private const val FULL_SCALE_16_BIT = 32768.0
    private const val MAX_16_BIT = 32767.0
    private const val WRITE_CHUNK = 8192

    /** What on-device speech models are trained on. */
    const val SPEECH_RATE = 16_000

    /** `AudioFormat.ENCODING_PCM_FLOAT`, inlined so this file needs no AudioFormat import. */
    private const val ENCODING_PCM_FLOAT = 4

    /** True digital silence is negative infinity dB, which no comparison survives. */
    private const val SILENCE_FLOOR_DB = -120f
}
