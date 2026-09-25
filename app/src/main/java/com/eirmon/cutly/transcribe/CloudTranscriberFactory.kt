package com.eirmon.cutly.transcribe

import com.eirmon.cutly.data.AppSettings

/**
 * Resolves the configured cloud backend once, so every captioning entry point behaves alike.
 *
 * Groq wins because its free tier needs no billing and returns the same verbose Whisper segments as
 * OpenAI. OpenAI comes next for its recognizer-native timings; Gemini remains the zero-migration
 * fallback for existing installations.
 */
internal object CloudTranscriberFactory {
    fun create(groqApiKey: String, openAiApiKey: String, geminiApiKey: String): Transcriber? = when {
        groqApiKey.isNotBlank() -> OpenAiTranscriber.groq(groqApiKey)
        openAiApiKey.isNotBlank() -> OpenAiTranscriber(openAiApiKey)
        geminiApiKey.isNotBlank() -> GeminiTranscriber(geminiApiKey)
        else -> null
    }

    /** Resolved at request time, so a key pasted in Settings works without a restart. */
    fun create(settings: AppSettings): Transcriber? = create(settings.groqKey, settings.openAiKey, settings.geminiKey)
}
