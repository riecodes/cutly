package com.eirmon.cutly.transcribe

import java.util.Locale

/**
 * A language the phone's own recogniser will actually accept.
 *
 * Read off the device rather than written down here. A hardcoded list is a promise the app cannot
 * keep: the on-device recogniser is Android System Intelligence, its language set differs by
 * device and by build, and it is not the same set as Gboard's offline voice typing. A Galaxy A56
 * on Android 16 reports `en-US` installed and thirty others available, with no Filipino among them
 * at all, despite Filipino being downloaded for voice typing on the same phone.
 *
 * @param installed true when the model is on the phone now. False means the recogniser says it
 *        could have it, so choosing it asks the system to fetch it first.
 */
data class TranscriptionLanguage(
    val tag: String,
    val label: String,
    val installed: Boolean
) {
    companion object {
        /**
         * Names a BCP 47 tag the way the user's own phone would.
         *
         * `Locale` already knows all of these, so there is no table here to fall out of date when
         * the recogniser gains a language.
         */
        fun of(tag: String, installed: Boolean): TranscriptionLanguage {
            val label = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
            return TranscriptionLanguage(
                tag = tag,
                label = label.ifBlank { tag },
                installed = installed
            )
        }
    }
}
