package com.eirmon.cutly.transcribe

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiTranscriberTest {

    @Test
    fun parsesSortsAndTrimsVerboseSegments() {
        val segments = OpenAiTranscriber.parseSegments(
            """
            {
              "text": "Hello world",
              "segments": [
                {"start": 1.5, "end": 2.5, "text": "world"},
                {"start": 0.0, "end": 2.0, "text": " Hello "}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("Hello", "world"), segments.map { it.text })
        assertEquals(1_500, segments.first().endMs)
    }

    @Test
    fun explicitEmptyArrayMeansNoSpeech() {
        assertTrue(
            OpenAiTranscriber.parseSegments("""{"text":"","segments":[]}""").isEmpty()
        )
    }

    @Test
    fun textWithoutSegmentsIsAnError() {
        assertThrows(IOException::class.java) {
            OpenAiTranscriber.parseSegments("""{"text":"heard speech"}""")
        }
    }

    @Test
    fun apiErrorMessageIsPreserved() {
        val error = assertThrows(IOException::class.java) {
            OpenAiTranscriber.parseSegments(
                """{"error":{"message":"Invalid API key"}}"""
            )
        }

        assertEquals("Invalid API key", error.message)
    }

    @Test
    fun unusableTimedTranscriptIsAnError() {
        assertThrows(IOException::class.java) {
            OpenAiTranscriber.parseSegments(
                """{"text":"bad","segments":[{"start":2,"end":1,"text":"bad"}]}"""
            )
        }
    }
}
