package com.eirmon.cutly.transcribe

import android.content.Context
import com.eirmon.cutly.audio.PcmDecoder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Runs multilingual Whisper through sherpa-onnx without uploading the take.
 *
 * PcmDecoder deliberately writes 16 kHz mono PCM16; sherpa accepts normalised float samples, so
 * the raw shorts are converted in bounded chunks rather than loading a long take twice in memory.
 * The native recognizer is blocking and remains on Dispatchers.Default for its entire lifetime.
 */
internal class SherpaTranscriber(
    context: Context,
    private val model: SherpaModelManager.Files
) : Transcriber {
    private val context = context.applicationContext

    override suspend fun transcribe(audio: File): List<Segment> {
        val pcm = File(context.cacheDir, "cutly_sherpa_${System.currentTimeMillis()}.pcm")
        val spec = PcmDecoder.toMonoPcm(audio, pcm, PcmDecoder.SPEECH_RATE)
        if (spec.bytes == 0L) {
            pcm.delete()
            return emptyList()
        }

        return try {
            val result = withContext(Dispatchers.Default) { recognize(pcm, spec.sampleRate) }
            val durationMs = spec.bytes / 2 * 1000 / spec.sampleRate

            // 1.13.7's Kotlin result exposes token timestamps but not the native segment arrays.
            // Standard Whisper exports may still return an empty timestamp array, so timings are
            // trusted only when they form usable segments; otherwise the shared amplitude fallback
            // supplies the clock instead of inventing model timings.
            SherpaSegments.fromTokens(
                result.tokens,
                result.timestamps,
                durationMs
            ).ifEmpty {
                SegmentAligner.align(
                    SegmentAligner.captionLines(result.text),
                    PcmDecoder.levels(audio)
                )
            }
        } finally {
            pcm.delete()
        }
    }

    private fun recognize(pcm: File, sampleRate: Int) =
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
            val stream = recognizer.createStream()
            try {
                pcm.inputStream().buffered().use { input ->
                    val bytes = ByteArray(PCM_CHUNK_BYTES)
                    var carried = 0
                    while (true) {
                        val read = input.read(bytes, carried, bytes.size - carried)
                        if (read < 0) break
                        val count = carried + read
                        val evenCount = count - count % 2
                        if (evenCount > 0) {
                            stream.acceptWaveform(bytes.toFloats(evenCount), sampleRate)
                        }
                        carried = count - evenCount
                        if (carried == 1) bytes[0] = bytes[evenCount]
                    }
                }
                recognizer.decode(stream)
                recognizer.getResult(stream).also {
                    if (it.text.isBlank() && it.tokens.isNotEmpty()) {
                        throw IOException("The offline model returned tokens without text")
                    }
                }
            } finally {
                stream.release()
                recognizer.release()
            }
        }

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
        const val PCM_CHUNK_BYTES = 32_768
    }
}
