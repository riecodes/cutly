package com.eirmon.cutly.transcribe

import com.eirmon.cutly.data.AppSettings

/**
 * Resolves the configured cloud backend once, so every captioning entry point behaves alike.
 *
 * OpenAI wins when both keys exist because its verbose Whisper response carries recognizer-native
 * segment timings; Gemini remains the zero-migration fallback for existing installations.
 */
internal object CloudTranscriberFactory {
    fun create(openAiApiKey: String, geminiApiKey: String): Transcriber? = when {
        openAiApiKey.isNotBlank() -> OpenAiTranscriber(openAiApiKey)
        geminiApiKey.isNotBlank() -> GeminiTranscriber(geminiApiKey)
        else -> null
    }

    /** Resolved at request time, so a key pasted in Settings works without a restart. */
    fun create(settings: AppSettings): Transcriber? = create(settings.openAiKey, settings.geminiKey)
}
