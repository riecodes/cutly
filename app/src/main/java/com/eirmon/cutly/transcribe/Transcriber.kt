package com.eirmon.cutly.transcribe

import java.io.File

/** A transcription backend that turns one extracted audio file into timed caption segments. */
interface Transcriber {
    suspend fun transcribe(audio: File): List<Segment>
}
