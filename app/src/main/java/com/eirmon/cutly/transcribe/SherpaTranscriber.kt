package com.eirmon.cutly.transcribe

import android.content.Context
import com.eirmon.cutly.audio.PcmDecoder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Runs multilingual Whisper through sherpa-onnx without uploading the take.
 *
 * PcmDecoder deliberately writes 16 kHz mono PCM16; sherpa accepts normalised float samples, so
 * the raw shorts are converted in bounded chunks rather than loading a long take twice in memory.
 * The native recognizer is blocking and remains on Dispatchers.Default for its entire lifetime.
 *
 * The take is decoded one window at a time because Whisper's context is fixed at 30 seconds and
 * sherpa silently discards everything past it: "We process only the first 30 seconds and discard
 * the remaining data". Feeding a whole take as one stream therefore captioned its first half
 * minute and nothing else. Each window's timestamps are shifted onto the take's clock afterwards.
 */
internal class SherpaTranscriber(
    context: Context,
    private val model: SherpaModelManager.Files
) : Transcriber {
    private val context = context.applicationContext

    override suspend fun transcribe(audio: File): List<Segment> {
        val pcm = File(context.cacheDir, "cutly_sherpa_${System.currentTimeMillis()}.pcm")
        return try {
            val spec = PcmDecoder.toMonoPcm(audio, pcm, PcmDecoder.SPEECH_RATE)
            if (spec.bytes == 0L) return emptyList()

            val windows = withContext(Dispatchers.Default) { recognize(pcm, spec.sampleRate) }
            val durationMs = spec.bytes / 2 * 1000 / spec.sampleRate

            // 1.13.7's Kotlin result exposes token timestamps but not the native segment arrays.
            // Standard Whisper exports may still return an empty timestamp array, so timings are
            // trusted only when they form usable segments; otherwise the shared amplitude fallback
            // supplies the clock instead of inventing model timings.
            SherpaSegments.fromTokens(
                windows.flatMap { it.tokens }.toTypedArray(),
                windows.flatMap { it.timestamps }.toFloatArray(),
                durationMs
            ).ifEmpty {
                SegmentAligner.align(
                    SegmentAligner.captionLines(windows.joinToString(" ") { it.text }.trim()),
                    PcmDecoder.levels(audio)
                )
            }
        } finally {
            pcm.delete()
        }
    }

    private suspend fun recognize(pcm: File, sampleRate: Int): List<Window> =
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        // Empty asks multilingual Whisper to detect rather than locking a take to
                        // English or Tagalog before it hears code-switching speech.
                        language = "",
                        task = "transcribe",
                        enableTokenTimestamps = true
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    modelType = "whisper"
                )
            )
        ).let { recognizer ->
            val windows = mutableListOf<Window>()
            try {
                // One buffer, reused: a window is under a megabyte, and the model is the slow part.
                val bytes = ByteArray(WINDOW_MS * sampleRate / 1000 * 2)
                pcm.inputStream().buffered().use { input ->
                    var offsetMs = 0L
                    while (true) {
                        // Each window is seconds of native inference; a cancelled transcription
                        // should stop at the next one, not run the rest of the take.
                        currentCoroutineContext().ensureActive()
                        val filled = input.fill(bytes)
                        val sampleBytes = filled - filled % 2
                        if (sampleBytes <= 0) break
                        windows += recognizer.window(bytes, sampleBytes, sampleRate, offsetMs)
                        offsetMs += sampleBytes / 2 * 1000L / sampleRate
                    }
                }
            } finally {
                recognizer.release()
            }
            windows
        }

    /** One Whisper context worth of audio, with its timestamps moved onto the take's clock. */
    private fun OfflineRecognizer.window(
        bytes: ByteArray,
        sampleBytes: Int,
        sampleRate: Int,
        offsetMs: Long
    ): Window {
        val stream = createStream()
        return try {
            stream.acceptWaveform(bytes.toFloats(sampleBytes), sampleRate)
            decode(stream)
            val result = getResult(stream)
            if (result.text.isBlank() && result.tokens.isNotEmpty()) {
                throw IOException("The offline model returned tokens without text")
            }
            val offsetSeconds = offsetMs / 1000f
            Window(
                text = result.text.trim(),
                tokens = result.tokens.toList(),
                timestamps = result.timestamps.map { it + offsetSeconds }
            )
        } finally {
            stream.release()
        }
    }

    /** Reads until the buffer is full or the file ends, so a window is never short mid-file. */
    private fun InputStream.fill(bytes: ByteArray): Int {
        var filled = 0
        while (filled < bytes.size) {
            val read = read(bytes, filled, bytes.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private class Window(
        val text: String,
        val tokens: List<String>,
        val timestamps: List<Float>
    )

    private fun ByteArray.toFloats(count: Int): FloatArray {
        val samples = FloatArray(count / 2)
        for (index in samples.indices) {
            val low = this[index * 2].toInt() and 0xff
            val high = this[index * 2 + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort() / 32768f
        }
        return samples
    }

    private companion object {
        /** Two seconds under Whisper's fixed 30 second context, so no window is ever clipped. */
        const val WINDOW_MS = 28_000
    }
}
