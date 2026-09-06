package com.eirmon.cutly.transcribe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.eirmon.cutly.audio.PcmDecoder
import com.eirmon.cutly.audio.SilenceDetector
import com.eirmon.cutly.audio.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcribes a file with the recogniser already on the phone. Nothing is uploaded.
 *
 * Three platform pieces have to line up, and all three are Android 13 (API 33) or newer:
 *
 * - `createOnDeviceSpeechRecognizer` runs the model locally instead of calling Google's servers.
 * - `EXTRA_AUDIO_SOURCE` feeds it a file descriptor rather than the microphone, which is what
 *   makes transcribing an already-recorded video possible at all.
 * - `EXTRA_SEGMENTED_SESSION` asks for results as [RecognitionPart]s carrying
 *   `getTimestampMillis()`.
 *
 * That last one is a request, not a guarantee. Measured on a Galaxy A56 running Android 16, the
 * SODA recogniser fills `RESULTS_RECOGNITION` and leaves `RECOGNITION_PARTS` null, so a reader
 * that only looks at parts sees nothing while the transcript sits right there in the bundle. Both
 * shapes are read here, and when only strings come back the timings are recovered from
 * [SilenceDetector]: the recogniser splits where the speaker pauses, and a run of non-silence is
 * exactly that. Amplitude supplies the clock the recogniser would not.
 *
 * The recogniser is a bound service driven from the main thread, so the session stays on it; the
 * decode inside [PcmDecoder.toMonoPcm] moves itself off.
 */
class OnDeviceTranscriber(private val context: Context) {

    /**
     * @param audio an M4A from [com.eirmon.cutly.export.ClipExporter.extractAudio].
     * @return the transcript in order, empty when nothing intelligible was heard.
     * @throws IOException when the device cannot do this, or the recogniser gives up.
     */
    suspend fun transcribe(audio: File, language: TranscriptionLanguage): List<Segment> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw IOException("On-device transcription needs Android 13 or newer")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IOException("This phone has no on-device speech recogniser")
        }

        val pcm = File(context.cacheDir, "cutly_speech_${System.currentTimeMillis()}.pcm")
        val spec = PcmDecoder.toMonoPcm(audio, pcm)
        if (spec.bytes == 0L) {
            pcm.delete()
            return emptyList()
        }

        val heard = try {
            withContext(Dispatchers.Main) { recognize(pcm, spec, language) }
        } finally {
            pcm.delete()
        }

        // The recogniser's own timings win whenever it gives any; only reconstruct when it does not.
        return if (heard.words.isNotEmpty()) group(heard.words)
        else align(heard.lines, PcmDecoder.levels(audio))
    }

    /**
     * Places recognised lines on the timeline using the audio's own silences.
     *
     * The recogniser splits where a speaker pauses, and [SilenceDetector] finds those same pauses
     * by amplitude, so when the counts agree the nth line belongs to the nth run of speech. That
     * is a real timing rather than an interpolation.
     *
     * When the counts disagree the pairing would be guesswork, so the lines are spread evenly
     * across the take instead. That result is honestly approximate: fine to read as a transcript,
     * too rough to burn in as captions.
     */
    private fun align(lines: List<String>, levels: PcmDecoder.Levels): List<Segment> {
        val text = lines.filter { it.isNotBlank() }
        if (text.isEmpty()) return emptyList()

        val speech: List<Span> =
            SilenceDetector.keepSpans(levels.db, levels.frameMs, levels.durationMs)
        Log.i(TAG, "align lines=${text.size} speech=${speech.size} ms=${levels.durationMs}")

        if (speech.size == text.size) {
            return text.mapIndexed { index, line ->
                Segment(speech[index].startMs, speech[index].endMs, line)
            }
        }

        val slice = (levels.durationMs / text.size).coerceAtLeast(1)
        return text.mapIndexed { index, line ->
            Segment(index * slice, (index + 1) * slice, line)
        }
    }

    private suspend fun recognize(
        pcm: File,
        spec: PcmDecoder.PcmSpec,
        language: TranscriptionLanguage
    ): Heard = suspendCancellableCoroutine { continuation ->
        val descriptor = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val words = mutableListOf<Word>()
        val lines = mutableListOf<String>()
        var finished = false

        // The recogniser calls back more than once for one session, and a coroutine may only be
        // resumed once, so every exit path goes through here.
        fun finish(block: () -> Unit) {
            if (finished) return
            finished = true
            runCatching { descriptor.close() }
            recognizer.destroy()
            block()
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(results: Bundle) {
                words += results.words()
                lines += results.lines()
            }

            override fun onEndOfSegmentedSession() {
                finish { continuation.resume(Heard(words, lines)) }
            }

            override fun onError(error: Int) {
                // Audio with no speech in it reports "no match" rather than an empty result, and
                // an empty transcript is a real answer, not a failure.
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    finish { continuation.resume(Heard(words, lines)) }
                } else {
                    finish {
                        continuation.resumeWithException(IOException(message(error, language)))
                    }
                }
            }

            override fun onResults(results: Bundle) {
                words += results.words()
                lines += results.lines()
                finish { continuation.resume(Heard(words, lines)) }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        continuation.invokeOnCancellation { finish {} }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // The descriptor, and the format describing it. Raw PCM carries no header, so getting
            // any of these three wrong produces confident nonsense rather than an error.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, spec.sampleRate)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, spec.channelCount)

            // Naming the audio-source extra is what asks for per-word timestamps.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)

            // Endpointing is tuned for a person talking to a phone, where a couple of seconds of
            // quiet means they have finished. A recorded take pauses between sentences and is not
            // finished, so without these the session ends at the first real gap and everything
            // after it is silently lost. Documented as best-effort, hence the fallback that spots
            // a short transcript rather than trusting these to be honoured.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TOLERANCE_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TOLERANCE_MS
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SESSION_MS)
        }

        recognizer.startListening(intent)
    }

    /** One recognised word and when it was said, relative to the start of the audio. */
    private class Word(val startMs: Long, val text: String)

    /**
     * What came back, in whichever shape this device supplies.
     *
     * [words] carry their own timestamps and are always preferred; [lines] are the fallback for a
     * recogniser that reports text without timings.
     */
    private class Heard(val words: List<Word>, val lines: List<String>)

    private companion object {

        const val TAG = "Cutly"

        /** The recogniser's best transcript for one segment, if it reported one. */
        fun Bundle.lines(): List<String> =
            getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                .orEmpty()

        fun Bundle.words(): List<Word> =
            getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
                .orEmpty()
                .map { Word(it.timestampMillis, it.formattedText ?: it.rawText) }

        /**
         * Groups words into caption-sized lines.
         *
         * The recogniser reports words, not lines. A caption has to be short enough to read in
         * the time it is on screen and should break where the speaker actually paused, so this
         * breaks on either a real gap or a word count.
         */
        fun group(words: List<Word>): List<Segment> {
            val ordered = words.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
            if (ordered.isEmpty()) return emptyList()

            val segments = mutableListOf<Segment>()
            var line = mutableListOf<Word>()

            fun flush(endMs: Long) {
                if (line.isEmpty()) return
                segments += Segment(
                    startMs = line.first().startMs,
                    endMs = maxOf(endMs, line.first().startMs + MIN_LINE_MS),
                    text = line.joinToString(" ") { it.text }
                )
                line = mutableListOf()
            }

            ordered.forEachIndexed { index, word ->
                val previous = line.lastOrNull()
                if (previous != null &&
                    (word.startMs - previous.startMs > BREAK_GAP_MS || line.size >= MAX_WORDS)
                ) {
                    flush(word.startMs)
                }
                line += word
                // A word has a start but no end, so it runs until the next one starts. The last
                // word of all has nothing after it, hence the fixed tail.
                if (index == ordered.lastIndex) flush(word.startMs + LAST_WORD_MS)
            }
            return segments
        }

        fun message(error: Int, language: TranscriptionLanguage): String = when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                "${language.label} is not installed for offline recognition. Add it in the " +
                    "phone's speech settings, then try again."

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "The speech recogniser was denied permission"

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "The speech recogniser is busy. Try again in a moment."

            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                "The speech recogniser is rate limiting. Try again in a moment."

            else -> "The speech recogniser failed (code $error)"
        }

        /** How long a gap the recogniser should sit through before calling the take finished. */
        const val SILENCE_TOLERANCE_MS = 15_000
        const val MIN_SESSION_MS = 600_000

        /** A pause this long reads as the end of a thought, so the caption breaks there. */
        const val BREAK_GAP_MS = 700L
        const val MAX_WORDS = 8

        /** Floors for timings the recogniser does not give: a last word's length, and a line's. */
        const val LAST_WORD_MS = 400L
        const val MIN_LINE_MS = 300L
    }
}
