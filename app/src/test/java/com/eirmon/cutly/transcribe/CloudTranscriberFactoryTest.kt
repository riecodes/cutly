package com.eirmon.cutly.transcribe

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranscriberFactoryTest {

    @Test
    fun openAiTakesPrecedenceWhenBothKeysExist() {
        assertTrue(CloudTranscriberFactory.create("openai", "gemini") is OpenAiTranscriber)
    }

    @Test
    fun existingGeminiConfigurationStillWorks() {
        assertTrue(CloudTranscriberFactory.create("", "gemini") is GeminiTranscriber)
    }

    @Test
    fun noKeyLeavesCloudTranscriptionUnavailableAtUseTime() {
        assertNull(CloudTranscriberFactory.create("", ""))
    }

    @Test
    fun explicitGeminiSelectionRequiresItsOwnKey() {
        assertTrue(CloudTranscriberFactory.createGemini("gemini") is GeminiTranscriber)
        assertNull(CloudTranscriberFactory.createGemini(""))
    }
}
