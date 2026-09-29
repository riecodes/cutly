package com.eirmon.cutly.data

import com.eirmon.cutly.transcribe.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One quick transcription of a video picked off the phone. Nothing else about the video is kept. */
data class TranscriptEntry(
    val id: String,
    /** The picked file's display name without its extension, e.g. "VID_20260927_101500". */
    val name: String,
    val createdAt: Long,
    /** Length of the picked video, 0 when it could not be read. */
    val durationMs: Long,
    /** Which engine produced it, from TranscriberFactory.label, e.g. "Groq" or "offline Whisper". */
    val engine: String,
    val segments: List<Segment>
)

/**
 * The whole quick transcription history as one JSON array file, newest first.
 *
 * Blocking I/O; callers run it on [kotlinx.coroutines.Dispatchers.IO].
 */
// ponytail: the whole file is rewritten on every add and delete, fine for hundreds of entries;
// move to one file per entry if the history ever grows to thousands.
class TranscriptStore(private val file: File) {

    /** A missing or unreadable file is an empty history; an entry without an id is skipped. */
    fun list(): List<TranscriptEntry> {
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.optJSONObject(it)?.let(::fromJson) }.filterNotNull()
    }

    fun add(entry: TranscriptEntry) = write(listOf(entry) + list())

    /** No-op when [id] is not there. */
    fun delete(id: String) {
        val current = list()
        val kept = current.filter { it.id != id }
        if (kept.size != current.size) write(kept)
    }

    /** Written next to the file and renamed over it, so a crash mid-write leaves the old history. */
    private fun write(entries: List<TranscriptEntry>) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.tmp")
        pending.writeText(JSONArray().apply { entries.forEach { put(toJson(it)) } }.toString())
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun toJson(entry: TranscriptEntry) = JSONObject().apply {
        put("id", entry.id)
        put("name", entry.name)
        put("createdAt", entry.createdAt)
        put("durationMs", entry.durationMs)
        put("engine", entry.engine)
        put("segments", JSONArray().apply {
            entry.segments.forEach { segment ->
                put(JSONObject().apply {
                    put("start", segment.startMs)
                    put("end", segment.endMs)
                    put("text", segment.text)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): TranscriptEntry? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val segments = json.optJSONArray("segments")?.let { array ->
            List(array.length()) { index ->
                array.optJSONObject(index)?.let {
                    Segment(it.optLong("start"), it.optLong("end"), it.optString("text"))
                }
            }.filterNotNull()
        }.orEmpty()
        return TranscriptEntry(
            id = id,
            name = json.optString("name").ifBlank { id },
            createdAt = json.optLong("createdAt"),
            durationMs = json.optLong("durationMs"),
            engine = json.optString("engine"),
            segments = segments
        )
    }
}
