package com.eirmon.cutly.data

import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.transcribe.Segment
import org.json.JSONArray
import org.json.JSONObject

/**
 * One project on disk: a copied source video plus everything the editor has decided about it.
 *
 * Spans and captions are on the source clock. The output clock is derived at export and preview
 * time from [keep], so nothing here has to be rewritten when a cut moves.
 *
 * @property captions null until a transcript has been asked for; empty when one came back with no
 *           speech. The two are different answers and the UI shows them differently.
 */
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val settings: SilenceSettings = SilenceSettings(),
    val keep: List<Span> = emptyList(),
    /** True once a clip has been trimmed, split or deleted by hand; the detector then keeps off. */
    val manualEdits: Boolean = false,
    val captions: List<Segment>? = null,
    val captionsEnabled: Boolean = false,
    /** The file name in Movies/Cutly once this exact cut has been exported. */
    val savedName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("durationMs", durationMs)
        put("width", width)
        put("height", height)
        put("settings", JSONObject().apply {
            put("thresholdDb", settings.thresholdDb.toDouble())
            put("minSilenceMs", settings.minSilenceMs)
            put("padMs", settings.padMs)
        })
        put("keep", JSONArray().apply {
            keep.forEach { put(JSONArray().put(it.startMs).put(it.endMs)) }
        })
        put("manualEdits", manualEdits)
        captions?.let { list ->
            put("captions", JSONArray().apply {
                list.forEach { segment ->
                    put(JSONObject().apply {
                        put("start", segment.startMs)
                        put("end", segment.endMs)
                        put("text", segment.text)
                    })
                }
            })
        }
        put("captionsEnabled", captionsEnabled)
        savedName?.let { put("savedName", it) }
    }

    companion object {
        const val VERSION = 1

        /** Null when the document is not a project this version understands. */
        fun fromJson(json: JSONObject): Project? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val durationMs = json.optLong("durationMs", -1L).takeIf { it > 0 } ?: return null
            val defaults = SilenceSettings()
            val settings = json.optJSONObject("settings")
            val keep = json.optJSONArray("keep")?.let { array ->
                List(array.length()) { index ->
                    val pair = array.optJSONArray(index) ?: return null
                    Span(pair.optLong(0, -1L), pair.optLong(1, -1L))
                }
            }.orEmpty().filter { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= durationMs }
            val ordered = keep.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }
            val captions = json.optJSONArray("captions")?.let { array ->
                List(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return null
                    Segment(item.optLong("start"), item.optLong("end"), item.optString("text"))
                }
            }
            return Project(
                id = id,
                name = json.optString("name").ifBlank { id },
                createdAt = json.optLong("createdAt"),
                updatedAt = json.optLong("updatedAt"),
                durationMs = durationMs,
                width = json.optInt("width"),
                height = json.optInt("height"),
                settings = SilenceSettings(
                    thresholdDb = settings?.optDouble("thresholdDb", defaults.thresholdDb.toDouble())
                        ?.toFloat() ?: defaults.thresholdDb,
                    minSilenceMs = settings?.optLong("minSilenceMs", defaults.minSilenceMs)
                        ?: defaults.minSilenceMs,
                    padMs = settings?.optLong("padMs", defaults.padMs) ?: defaults.padMs
                ),
                keep = if (ordered) keep else emptyList(),
                manualEdits = json.optBoolean("manualEdits", false),
                captions = captions,
                captionsEnabled = json.optBoolean("captionsEnabled", false),
                savedName = json.optString("savedName").takeIf { it.isNotBlank() }
            )
        }
    }
}
