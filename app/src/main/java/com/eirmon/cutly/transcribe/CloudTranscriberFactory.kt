package com.eirmon.cutly.transcribe

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

    /** The video-to-text screen exposes Gemini explicitly instead of applying cloud precedence. */
    fun createGemini(geminiApiKey: String): Transcriber? =
        geminiApiKey.takeIf(String::isNotBlank)?.let(::GeminiTranscriber)
}
