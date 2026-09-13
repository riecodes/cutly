package com.eirmon.cutly.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Streams one extracted M4A to OpenAI and returns its recognizer-native timed segments.
 *
 * whisper-1 is deliberate: the current audio API only offers verbose_json and segment timestamps
 * for Whisper, while gpt-4o-transcribe accepts plain JSON only. Multipart bytes are streamed from
 * disk because buffering a long take beside the video and extracted audio would create a needless
 * memory spike.
 */
class OpenAiTranscriber(private val apiKey: String) : Transcriber {

    override suspend fun transcribe(audio: File): List<Segment> = withContext(Dispatchers.IO) {
        // whisper-1 rejects uploads over 25 MB; refuse before sending the whole file to learn that.
        if (audio.length() > MAX_UPLOAD_BYTES) {
            throw IOException("Take is too long to transcribe in one request")
        }
        val boundary = "Cutly-${UUID.randomUUID()}"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 15_000
            readTimeout = 180_000
            doOutput = true
            setChunkedStreamingMode(UPLOAD_CHUNK_BYTES)
        }

        try {
            BufferedOutputStream(connection.outputStream).use { body ->
                body.field(boundary, "model", MODEL)
                body.field(boundary, "response_format", "verbose_json")
                body.field(boundary, "timestamp_granularities[]", "segment")
                body.field(boundary, "temperature", "0")
                body.field(boundary, "prompt", PROMPT)
                body.file(boundary, audio)
                body.utf8("--$boundary--\r\n")
            }

            val failed = connection.responseCode !in 200..299
            val stream = if (failed) connection.errorStream else connection.inputStream
            val response = stream?.use { it.readCapped() }.orEmpty()
            if (failed && response.isBlank()) {
                throw IOException("OpenAI returned HTTP ${connection.responseCode}")
            }
            parseSegments(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun OutputStream.field(boundary: String, name: String, value: String) {
        utf8("--$boundary\r\n")
        utf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        utf8(value)
        utf8("\r\n")
    }

    private fun OutputStream.file(boundary: String, audio: File) {
        utf8("--$boundary\r\n")
        utf8(
            "Content-Disposition: form-data; name=\"file\"; " +
                "filename=\"${audio.name.replace("\"", "")}\"\r\n"
        )
        utf8("Content-Type: audio/mp4\r\n\r\n")
        audio.inputStream().buffered().use { it.copyTo(this, UPLOAD_CHUNK_BYTES) }
        utf8("\r\n")
    }

    private fun OutputStream.utf8(value: String) = write(value.toByteArray(Charsets.UTF_8))

    internal companion object {
        private const val MODEL = "whisper-1"
        private const val MAX_UPLOAD_BYTES = 25L * 1000 * 1000
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val UPLOAD_CHUNK_BYTES = 64 * 1024
        private const val PROMPT =
            "English and Filipino/Tagalog speech, often code-switching. Transcribe exactly; " +
                "do not translate."

        /**
         * Reads verbose_json without letting an API failure masquerade as an empty recording.
         *
         * An explicit empty segments array is the one real no-speech answer. Missing arrays,
         * non-empty text without timings, and responses whose every timing is invalid are errors.
         */
        internal fun parseSegments(body: String): List<Segment> {
            val root = try {
                JSONObject(body)
            } catch (error: Exception) {
                throw IOException("OpenAI sent a malformed response", error)
            }

            root.optJSONObject("error")?.let { error ->
                throw IOException(
                    error.optString("message").ifBlank { "OpenAI rejected the request" }
                )
            }

            val segments = root.optJSONArray("segments")
                ?: throw IOException("OpenAI returned no timed transcript")
            val fullText = root.optString("text").trim()
            if (segments.length() == 0) {
                if (fullText.isNotEmpty()) {
                    throw IOException("OpenAI returned transcript text without timings")
                }
                return emptyList()
            }
            if (fullText.isEmpty()) throw IOException("OpenAI returned no transcript text")

            val parsed = (0 until segments.length()).mapNotNull { index ->
                val item = segments.optJSONObject(index) ?: return@mapNotNull null
                val text = item.optString("text").trim()
                if (text.isEmpty()) return@mapNotNull null

                val startSeconds = item.optDouble("start", Double.NaN)
                val endSeconds = item.optDouble("end", Double.NaN)
                if (!startSeconds.isFinite() || !endSeconds.isFinite()) return@mapNotNull null
                val startMs = (startSeconds * 1000).toLong()
                val endMs = (endSeconds * 1000).toLong()
                if (startMs < 0 || endMs <= startMs) null else Segment(startMs, endMs, text)
            }.sortedBy { it.startMs }

            if (parsed.isEmpty()) {
                throw IOException("OpenAI returned a transcript with no usable timings")
            }

            // Segment.at binary-searches these on every rendered frame, so overlaps are trimmed
            // to preserve the sorted, disjoint invariant rather than assigning two captions to
            // the same instant.
            return parsed.mapIndexed { index, segment ->
                val next = parsed.getOrNull(index + 1) ?: return@mapIndexed segment
                if (segment.endMs > next.startMs) segment.copy(endMs = next.startMs) else segment
            }.filter { it.endMs > it.startMs }
        }
    }
}
