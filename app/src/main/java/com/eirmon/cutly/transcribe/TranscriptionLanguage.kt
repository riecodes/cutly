package com.eirmon.cutly.transcribe

/**
 * What the on-device recogniser is told to listen for.
 *
 * One language per run, because that is the shape of the API: `EXTRA_LANGUAGE` takes a single
 * BCP 47 tag and the recogniser loads one model. That is also the honest limitation to design
 * around — real Filipino speech switches to English inside a sentence, and no single-language
 * model transcribes both halves well. Picking the language of the majority of the take is the
 * best this engine can do; the Gemini path exists for when that is not good enough.
 */
enum class TranscriptionLanguage(val tag: String, val label: String) {
    /** Tagalog-based Filipino. English words mid-sentence come back approximated or wrong. */
    FILIPINO("fil-PH", "Filipino"),

    /** Philippine-accented English. Better on local accents and place names than en-US. */
    ENGLISH_PH("en-PH", "English (PH)"),

    ENGLISH_US("en-US", "English (US)");

    companion object {
        val DEFAULT = FILIPINO
    }
}
