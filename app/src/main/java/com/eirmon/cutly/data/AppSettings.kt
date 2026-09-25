package com.eirmon.cutly.data

import android.content.Context
import com.eirmon.cutly.BuildConfig
import com.eirmon.cutly.transcribe.TranscriptionEngine

/**
 * The user's own cloud credentials, the transcription engine choice, and where the policy lives.
 *
 * Keys live in app-private SharedPreferences, excluded from backup and device transfer by
 * `data_extraction_rules.xml`. That is the threat model: the phone's owner can read their own key,
 * nobody else can, and it never leaves in an APK. Debug builds fall back to `local.properties` so
 * development does not need the Settings screen; release builds have empty BuildConfig fields.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var groqKey: String
        get() = prefs.getString(KEY_GROQ, null)?.trim().orEmpty().ifBlank { BuildConfig.GROQ_API_KEY }
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var openAiKey: String
        get() = prefs.getString(KEY_OPENAI, null)?.trim().orEmpty().ifBlank { BuildConfig.OPENAI_API_KEY }
        set(value) = prefs.edit().putString(KEY_OPENAI, value.trim()).apply()

    var geminiKey: String
        get() = prefs.getString(KEY_GEMINI, null)?.trim().orEmpty().ifBlank { BuildConfig.GEMINI_API_KEY }
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var engine: TranscriptionEngine
        get() = prefs.getString(KEY_ENGINE, null)
            ?.let { name -> TranscriptionEngine.entries.firstOrNull { it.name == name } }
            ?: TranscriptionEngine.SYSTEM
        set(value) = prefs.edit().putString(KEY_ENGINE, value.name).apply()

    /** BCP 47 tag for the phone's own recogniser, which takes one language per run. */
    var languageTag: String?
        get() = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** The name of whichever cloud backend a request would go to, or null when there is none. */
    val cloudProvider: String?
        get() = when {
            groqKey.isNotBlank() -> "Groq"
            openAiKey.isNotBlank() -> "OpenAI"
            geminiKey.isNotBlank() -> "Google Gemini"
            else -> null
        }

    val hasCloudKey: Boolean get() = cloudProvider != null

    companion object {
        const val PRIVACY_URL = "https://riecodes.github.io/cutly/privacy"
        const val SOURCE_URL = "https://github.com/riecodes/cutly"
        private const val KEY_GROQ = "groq-key"
        private const val KEY_OPENAI = "openai-key"
        private const val KEY_GEMINI = "gemini-key"
        private const val KEY_ENGINE = "engine"
        private const val KEY_LANGUAGE = "language"
    }
}
