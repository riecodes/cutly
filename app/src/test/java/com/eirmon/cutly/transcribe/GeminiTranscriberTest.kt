package com.eirmon.cutly.transcribe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Gemini answers a refusal, a truncation and a real transcript all with HTTP 200, so the parser is
 * the only thing standing between "the model would not do it" and "you recorded silence".
 *
 * It can also satisfy the response schema while returning nonsense inside it — ends before starts,
 * overlapping neighbours, blank lines — and a bad timing is worse than a missing one, because it
 * puts a caption over the wrong words.
 */
class GeminiTranscriberTest {

    @Test
    fun `joins every text part before parsing`() {
        // The array arrives split across parts, which is how a longer transcript actually comes back.
        val body = """
            {"candidates":[{"content":{"parts":[
              {"text":"[{\"start\":0.0,\"end\":1.5,\"text\":\"Kumusta, \"},"},
              {"text":"{\"start\":1.5,\"end\":3.0,\"text\":\"how are you?\"}]"}
            ]},"finishReason":"STOP"}]}
        """.trimIndent()

        assertEquals(
            listOf(
                Segment(0, 1500, "Kumusta,"),
                Segment(1500, 3000, "how are you?")
            ),
            GeminiTranscriber.parseSegments(body)
        )
    }

    /** An empty array is a real answer — the model heard no speech — not a failure. */
    @Test
    fun `accepts an empty transcript`() {
        val segments = GeminiTranscriber.parseSegments(candidate("[]"))

        assertTrue(segments.toString(), segments.isEmpty())
        assertEquals(Segment.NO_SPEECH, Segment.render(segments))
    }

    @Test
    fun `drops segments with unusable timings`() {
        val body = candidate(
            """[
              {"start":2.0,"end":1.0,"text":"backwards"},
              {"start":-1.0,"end":2.0,"text":"negative"},
              {"start":3.0,"end":3.0,"text":"zero length"},
              {"start":4.0,"end":5.0,"text":"   "},
              {"start":6.0,"end":7.0,"text":"the only good one"}
            ]"""
        )

        assertEquals(
            listOf(Segment(6000, 7000, "the only good one")),
            GeminiTranscriber.parseSegments(body)
        )
    }

    /** Overlaps would break the binary search the caption overlay runs on every frame. */
    @Test
    fun `sorts and trims overlapping segments`() {
        val body = candidate(
            """[
              {"start":4.0,"end":6.0,"text":"second"},
              {"start":0.0,"end":5.0,"text":"first"}
            ]"""
        )

        assertEquals(
            listOf(Segment(0, 4000, "first"), Segment(4000, 6000, "second")),
            GeminiTranscriber.parseSegments(body)
        )
    }

    @Test
    fun `rejects a body that is not the shape asked for`() {
        assertThrowsIO { GeminiTranscriber.parseSegments(candidate("sorry, I cannot help")) }
    }

    @Test
    fun `surfaces the API error message`() {
        val body = """{"error":{"code":400,"message":"API key not valid"}}"""

        val failure = assertThrowsIO { GeminiTranscriber.parseSegments(body) }
        assertTrue(failure.message, failure.message!!.contains("API key not valid"))
    }

    @Test
    fun `rejects a blocked prompt`() {
        val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""

        val failure = assertThrowsIO { GeminiTranscriber.parseSegments(body) }
        assertTrue(failure.message, failure.message!!.contains("SAFETY"))
    }

    /** A candidate with no parts is the shape a truncated or filtered answer arrives in. */
    @Test
    fun `rejects an empty candidate and names the finish reason`() {
        val body = """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""

        val failure = assertThrowsIO { GeminiTranscriber.parseSegments(body) }
        assertTrue(failure.message, failure.message!!.contains("MAX_TOKENS"))
    }

    @Test
    fun `rejects an empty candidate list`() {
        assertThrowsIO { GeminiTranscriber.parseSegments("""{"candidates":[]}""") }
    }

    @Test
    fun `rejects a non-JSON body`() {
        assertThrowsIO { GeminiTranscriber.parseSegments("<html>502 Bad Gateway</html>") }
    }

    @Test
    fun `renders the timestamped form the transcript sheet shows`() {
        assertEquals(
            "[00:00] Kumusta\n[01:05] how are you?",
            Segment.render(
                listOf(Segment(0, 900, "Kumusta"), Segment(65_000, 67_000, "how are you?"))
            )
        )
    }

    /** Wraps a transcript body in the candidate envelope Gemini returns it in. */
    private fun candidate(text: String): String =
        """{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(text)}}]},""" +
            """"finishReason":"STOP"}]}"""

    private fun assertThrowsIO(block: () -> Unit): IOException =
        try {
            block()
            throw AssertionError("Expected an IOException")
        } catch (e: IOException) {
            e
        }
}
