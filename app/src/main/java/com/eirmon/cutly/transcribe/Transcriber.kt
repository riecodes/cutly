package com.eirmon.cutly.transcribe

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A transcription backend that turns one extracted audio file into timed caption segments. */
interface Transcriber {
    suspend fun transcribe(audio: File): List<Segment>
}

/** The most a transcript response is allowed to be. A ten-minute verbose_json is under 200 KB. */
internal const val MAX_RESPONSE_BYTES = 4 shl 20

/**
 * Reads a response body with a ceiling, because the other end is not ours and `readText()` on an
 * unbounded stream is an out-of-memory waiting for a bad day.
 */
internal fun InputStream.readCapped(maxBytes: Int = MAX_RESPONSE_BYTES): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.size() + read > maxBytes) throw IOException("Response too large")
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}
