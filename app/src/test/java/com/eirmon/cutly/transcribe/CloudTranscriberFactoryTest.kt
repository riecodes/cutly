package com.eirmon.cutly.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranscriberFactoryTest {

    @Test
    fun groqTakesPrecedenceOverPaidKeys() {
        val transcriber = CloudTranscriberFactory.create("groq", "openai", "gemini")
        assertEquals("Groq", (transcriber as OpenAiTranscriber).provider)
    }

    @Test
    fun openAiTakesPrecedenceOverGemini() {
        val transcriber = CloudTranscriberFactory.create("", "openai", "gemini")
        assertEquals("OpenAI", (transcriber as OpenAiTranscriber).provider)
    }

    @Test
    fun existingGeminiConfigurationStillWorks() {
        assertTrue(CloudTranscriberFactory.create("", "", "gemini") is GeminiTranscriber)
    }

    @Test
    fun noKeyLeavesCloudTranscriptionUnavailableAtUseTime() {
        assertNull(CloudTranscriberFactory.create("", "", ""))
    }
}
