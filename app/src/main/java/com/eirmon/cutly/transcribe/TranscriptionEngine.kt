package com.eirmon.cutly.transcribe

import android.content.Context
import com.eirmon.cutly.data.AppSettings

/** Which recogniser handles the next transcription. Chosen in Settings, stored in [AppSettings]. */
enum class TranscriptionEngine { SYSTEM, SHERPA, CLOUD }

/** Turns the Settings choices into a transcriber, failing with the sentence the user needs. */
internal object TranscriberFactory {

    fun create(context: Context, settings: AppSettings, sherpa: SherpaModelManager): Transcriber =
        when (settings.engine) {
            TranscriptionEngine.SYSTEM -> OnDeviceTranscriber(
                context,
                TranscriptionLanguage.of(
                    settings.languageTag ?: error("Choose a transcription language in Settings."),
                    installed = false
                )
            )
            TranscriptionEngine.SHERPA -> SherpaTranscriber(
                context,
                sherpa.files() ?: error("Download the offline Whisper model in Settings.")
            )
            TranscriptionEngine.CLOUD -> CloudTranscriberFactory.create(settings)
                ?: error("Add a Groq, OpenAI or Gemini key in Settings.")
        }

    /** "on device", "offline Whisper", "OpenAI": what a status line or a consent dialog names. */
    fun label(settings: AppSettings): String = when (settings.engine) {
        TranscriptionEngine.SYSTEM -> "on device"
        TranscriptionEngine.SHERPA -> "offline Whisper"
        TranscriptionEngine.CLOUD -> settings.cloudProvider ?: "the cloud"
    }
}
