package com.eirmon.cutly.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Sends the take's audio to the Gemini API and returns the transcript.
 *
 * Gemini rather than a dedicated speech API because real Filipino speech is Taglish — English and
 * Tagalog switch inside one sentence — and per-utterance language locking mangles that. A general
 * model transcribes the mix as spoken.
 *
 * HttpURLConnection and org.json, both in the platform, so transcription adds no dependency.
 */
class GeminiTranscriber(private val apiKey: String) : Transcriber {

    /**
     * @param audio an M4A produced by [com.eirmon.cutly.export.ClipExporter.extractAudio].
     * @return the transcript in order, empty when the model heard no speech.
     * @throws IOException on a transport failure, an API error, or an empty response.
     */
    override suspend fun transcribe(audio: File): List<Segment> = withContext(Dispatchers.IO) {
        // Base64 inflates by 4/3, and the whole request — prompt included — has to fit Gemini's
        // 20 MB inline cap. Refuse early with something the user can act on rather than posting
        // fifteen megabytes to get a 400 back.
        if (audio.length() * 4 / 3 > MAX_INLINE_BYTES) {
            throw IOException("Take is too long to transcribe in one request")
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
            connectTimeout = 15_000
            // The model reads the audio before it answers, so a long take is a long wait.
            readTimeout = 180_000
            doOutput = true
            // Streams the body instead of buffering the whole base64 payload in memory.
            setChunkedStreamingMode(0)
        }

        try {
            BufferedOutputStream(connection.outputStream).use { body ->
                body.write(REQUEST_PREFIX.toByteArray())
                // The encoder emits its padding on close and closes what it wraps, so the shield
                // downgrades that close to a flush and the JSON suffix can still be written.
                Base64.getEncoder().wrap(CloseShield(body)).use { encoder ->
                    audio.inputStream().buffered().copyTo(encoder)
                }
                body.write(REQUEST_SUFFIX.toByteArray())
            }

            val failed = connection.responseCode !in 200..299
            val stream = if (failed) connection.errorStream else connection.inputStream
            val response = stream?.use { it.readCapped() }.orEmpty()
            if (failed && response.isBlank()) {
                throw IOException("Gemini returned HTTP ${connection.responseCode}")
            }
            parseSegments(response)
        } finally {
            connection.disconnect()
        }
    }

    /** Passes writes through but never closes the stream underneath. */
    private class CloseShield(private val target: OutputStream) : FilterOutputStream(target) {
        // FilterOutputStream's default writes one byte at a time, which is unusably slow here.
        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            target.write(bytes, offset, length)

        override fun close() = flush()
    }

    internal companion object {
        /** Flash keeps a ten-minute take affordable; audio understanding is not the hard part. */
        private const val MODEL = "gemini-3.7-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        /** Gemini's inline request cap is 20 MB; leave room for the prompt and JSON overhead. */
        private const val MAX_INLINE_BYTES = 18_000_000L

        private const val PROMPT =
            "Transcribe this audio verbatim. The speakers use English (United States) and " +
                "Tagalog/Filipino, and often switch between the two inside a single sentence. " +
                "Write each language in its own words and spelling — never translate. " +
                "Split the transcript into short segments, each one caption long: at most a " +
                "dozen words, broken at a natural pause. Give every segment the seconds at " +
                "which it starts and ends in the audio. " +
                "Return an empty array if there is no intelligible speech."

        private val REQUEST_PREFIX =
            "{\"contents\":[{\"parts\":[" +
                "{\"text\":${JSONObject.quote(PROMPT)}}," +
                "{\"inline_data\":{\"mime_type\":\"audio/m4a\",\"data\":\""

        /**
         * Temperature zero: a transcript is a reading of the audio, not a piece of writing.
         *
         * The response schema is what makes the timings usable. Asking for `[MM:SS]` prefixes in
         * prose gave whole-second starts and no ends at all, which is enough to read a transcript
         * and not enough to place a caption.
         */
        private const val REQUEST_SUFFIX =
            "\"}}]}],\"generationConfig\":{" +
                "\"temperature\":0,\"maxOutputTokens\":8192," +
                "\"responseMimeType\":\"application/json\"," +
                "\"responseSchema\":{\"type\":\"ARRAY\",\"items\":{\"type\":\"OBJECT\"," +
                "\"properties\":{" +
                "\"start\":{\"type\":\"NUMBER\"}," +
                "\"end\":{\"type\":\"NUMBER\"}," +
                "\"text\":{\"type\":\"STRING\"}}," +
                "\"required\":[\"start\",\"end\",\"text\"]}}}}"

        /**
         * Pulls the transcript out of a generateContent response.
         *
         * Gemini reports refusals and truncation with HTTP 200 and no text, so every empty-text
         * shape has to become an exception here — otherwise a blocked take looks like silence.
         * An empty *array*, on the other hand, is a real answer: the model heard no speech.
         */
        internal fun parseSegments(body: String): List<Segment> {
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("Gemini sent a malformed response", e)
            }

            root.optJSONObject("error")?.let { error ->
                throw IOException(
                    error.optString("message").ifBlank { "Gemini rejected the request" }
                )
            }
            root.optJSONObject("promptFeedback")?.optString("blockReason")
                ?.takeIf { it.isNotBlank() }
                ?.let { throw IOException("Gemini blocked the audio ($it)") }

            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IOException("Gemini returned no transcript")
            }

            val candidate = candidates.getJSONObject(0)
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                for (index in 0 until (parts?.length() ?: 0)) {
                    append(parts?.optJSONObject(index)?.optString("text").orEmpty())
                }
            }.trim()

            if (text.isEmpty()) {
                val reason = candidate.optString("finishReason").ifBlank { "unknown" }
                throw IOException("Gemini returned no transcript ($reason)")
            }
            return toSegments(text)
        }

        /**
         * Turns the schema-shaped array into segments.
         *
         * Everything here is defensive because a model can satisfy a schema and still return
         * nonsense: ends before starts, overlapping neighbours, blank text. A bad timing is worse
         * than a missing one — it puts a caption over the wrong words — so anything unusable is
         * dropped rather than patched into something plausible.
         */
        private fun toSegments(text: String): List<Segment> {
            val array = try {
                JSONArray(text)
            } catch (e: Exception) {
                throw IOException("Gemini sent a transcript that was not the shape asked for", e)
            }

            val segments = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val line = item.optString("text").trim()
                if (line.isEmpty()) return@mapNotNull null

                val start = (item.optDouble("start", -1.0) * 1000).toLong()
                val end = (item.optDouble("end", -1.0) * 1000).toLong()
                if (start < 0 || end <= start) null else Segment(start, end, line)
            }.sortedBy { it.startMs }

            // Overlaps would break the binary search the caption overlay does per frame, so a
            // segment that runs into the next one is trimmed to end where that one begins.
            return segments.mapIndexed { index, segment ->
                val next = segments.getOrNull(index + 1) ?: return@mapIndexed segment
                if (segment.endMs > next.startMs) segment.copy(endMs = next.startMs) else segment
            }.filter { it.endMs > it.startMs }
        }
    }
}
