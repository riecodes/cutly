package com.eirmon.cutly.transcribe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.eirmon.cutly.audio.PcmDecoder
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
 *
 * [language] is fixed at construction rather than appearing on [transcribe]. Cloud and
 * multilingual backends do not need a language choice, so putting it on the common interface
 * would make half the implementations accept and ignore a misleading argument.
 */
class OnDeviceTranscriber(
    private val context: Context,
    private val language: TranscriptionLanguage
) : Transcriber {

    /**
     * @param audio an M4A from [com.eirmon.cutly.export.ClipExporter.extractAudio].
     * @return the transcript in order, empty when nothing intelligible was heard.
     * @throws IOException when the device cannot do this, or the recogniser gives up.
     */
    override suspend fun transcribe(audio: File): List<Segment> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw IOException("On-device transcription needs Android 13 or newer")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IOException("This phone has no on-device speech recogniser")
        }

        ensureInstalled(language)

        val pcm = File(context.cacheDir, "cutly_speech_${System.currentTimeMillis()}.pcm")
        val heard = try {
            val spec = PcmDecoder.toMonoPcm(audio, pcm)
            if (spec.bytes == 0L) return emptyList()
            withContext(Dispatchers.Main) { recognize(pcm, spec, language) }
        } finally {
            pcm.delete()
        }

        // The recogniser's own timings win whenever it gives any; only reconstruct when it does not.
        return if (heard.words.isNotEmpty()) group(heard.words)
        else SegmentAligner.align(heard.lines, PcmDecoder.levels(audio))
    }

    /**
     * Checks the recogniser has this language before any audio is decoded, and asks for it if not.
     *
     * The on-device recogniser is Android System Intelligence, which keeps its own much shorter
     * list of languages. Downloading a language for Gboard's offline voice typing does not add it
     * here, which is why "I already downloaded Filipino" and "language unavailable" are both true
     * at the same time. Asking the recogniser directly is the only way to know.
     */
    private suspend fun ensureInstalled(language: TranscriptionLanguage) {
        val support = support(context, language.tag)
            ?: return  // No answer: let the attempt report it.

        val installed = support.installedOnDeviceLanguages
        val supported = support.supportedOnDeviceLanguages
        val pending = support.pendingOnDeviceLanguages
        Log.i(TAG, "installed=$installed supported=$supported pending=$pending")

        if (installed.any { it.matches(language) }) return

        if (pending.any { it.matches(language) }) {
            throw IOException("${language.label} is still downloading. Try again in a minute.")
        }

        if (supported.any { it.matches(language) }) {
            // Supported but absent, so it can be fetched. This is a request to the recogniser,
            // not a download we control, hence "asked for" rather than a progress bar.
            withContext(Dispatchers.Main) { requestDownload(language) }
            throw IOException(
                "${language.label} is not on this phone yet. Cutly has asked the system to " +
                    "download it — try again in a minute."
            )
        }

        throw IOException(
            "${language.label} is not available for on-device recognition on this phone. " +
                "It offers: ${installed.joinToString().ifBlank { "nothing" }}. Note that Gboard " +
                "voice-typing languages are a separate set and do not count here."
        )
    }

    private fun requestDownload(language: TranscriptionLanguage) {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.triggerModelDownload(
            languageIntent(language.tag),
            context.mainExecutor,
            object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = Unit
                override fun onSuccess() {
                    Log.i(TAG, "model download finished for ${language.tag}")
                    recognizer.destroy()
                }

                override fun onScheduled() {
                    Log.i(TAG, "model download scheduled for ${language.tag}")
                }

                override fun onError(error: Int) {
                    Log.i(TAG, "model download failed for ${language.tag} error=$error")
                    recognizer.destroy()
                }
            }
        )
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

    companion object {

        private const val TAG = "Cutly"

        /** The lists come back device-wide, so the probe's tag only has to be a valid one. */
        private const val PROBE_TAG = "en-US"

        /**
         * Every language this phone's recogniser will accept, the ones already downloaded first.
         *
         * Empty when the recogniser will not answer, which the caller should treat as "on-device
         * transcription is unavailable here" rather than as "no languages exist".
         */
        suspend fun languages(context: Context): List<TranscriptionLanguage> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return emptyList()

            val support = support(context, PROBE_TAG) ?: return emptyList()
            val installed = support.installedOnDeviceLanguages
            Log.i(
                TAG,
                "installed=$installed supported=${support.supportedOnDeviceLanguages} " +
                    "pending=${support.pendingOnDeviceLanguages}"
            )

            val downloadable =
                (support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages)
                    .filterNot { candidate ->
                        installed.any { it.equals(candidate, ignoreCase = true) }
                    }
                    .distinct()

            // Installed first: those work now, the rest cost a download before they do.
            return installed.map { TranscriptionLanguage.of(it, installed = true) }
                .sortedBy { it.label } +
                downloadable.map { TranscriptionLanguage.of(it, installed = false) }
                    .sortedBy { it.label }
        }

        /** Asks the recogniser what it can do, or null when it will not say. */
        private suspend fun support(context: Context, tag: String): RecognitionSupport? =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    var done = false
                    fun settle(value: RecognitionSupport?) {
                        if (done) return
                        done = true
                        recognizer.destroy()
                        continuation.resume(value)
                    }
                    recognizer.checkRecognitionSupport(
                        languageIntent(tag),
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) =
                                settle(recognitionSupport)

                            override fun onError(error: Int) {
                                Log.i(TAG, "checkRecognitionSupport error=$error")
                                settle(null)
                            }
                        }
                    )
                    continuation.invokeOnCancellation { settle(null) }
                }
            }

        private fun languageIntent(tag: String): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            }

        /**
         * Recognisers report tags inconsistently: fil-PH, fil_PH, and bare fil all mean Filipino.
         * Comparing on the language subtag alone avoids rejecting a model that is actually there.
         */
        private fun String.matches(language: TranscriptionLanguage): Boolean {
            val theirs = replace('_', '-').lowercase()
            val ours = language.tag.lowercase()
            return theirs == ours || theirs.substringBefore('-') == ours.substringBefore('-')
        }

        /** The recogniser's best transcript for one segment, if it reported one. */
        private fun Bundle.lines(): List<String> =
            getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                .orEmpty()

        private fun Bundle.words(): List<Word> =
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
        private fun group(words: List<Word>): List<Segment> {
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

        private fun message(error: Int, language: TranscriptionLanguage): String = when (error) {
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
        private const val SILENCE_TOLERANCE_MS = 15_000
        private const val MIN_SESSION_MS = 600_000

        /** A pause this long reads as the end of a thought, so the caption breaks there. */
        private const val BREAK_GAP_MS = 700L
        private const val MAX_WORDS = 8

        /** Floors for timings the recogniser does not give: a last word's length, and a line's. */
        private const val LAST_WORD_MS = 400L
        private const val MIN_LINE_MS = 300L
    }
}
