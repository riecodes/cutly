package com.eirmon.cutly.data

import android.content.Context
import com.eirmon.cutly.model.Clip
import java.io.File

/**
 * Owns the on-disk clip directory and a tiny index file so an in-progress take survives
 * process death. Android kills backgrounded camera apps aggressively; without this the user
 * loses everything they shot when they answer a phone call.
 */
class ClipStore(context: Context) {

    // Private files, not cache: the OS may purge the cache under storage pressure, and a
    // half-recorded take is the last thing that should go. Earlier builds used the cache.
    private val dir = File(context.filesDir, "clips").also { dir ->
        val legacy = File(context.cacheDir, "clips")
        if (legacy.isDirectory && !dir.exists()) legacy.renameTo(dir)
        dir.mkdirs()
    }
    private val index = File(dir, "session.idx")

    fun newClipFile(): File = File(dir, "clip_${System.currentTimeMillis()}.mp4")

    /**
     * Free bytes on the volume the clips live on. A take runs until this runs out, so the
     * recorder checks it before every clip and periodically while one is running.
     */
    fun usableSpaceBytes(): Long = dir.usableSpace

    fun save(clips: List<Clip>) {
        index.writeText(
            clips.joinToString("\n") {
                "${it.file.name}|${it.durationMs}|${it.lensFacing}|${it.speed}|${it.heightPx}"
            }
        )
    }

    fun load(): List<Clip> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val file = File(dir, parts[0])
            // A clip that was mid-write when the process died is unplayable — drop it.
            if (!file.exists() || file.length() == 0L) return@mapNotNull null
            val duration = parts[1].toLongOrNull() ?: return@mapNotNull null
            val lens = parts[2].toIntOrNull() ?: return@mapNotNull null
            Clip(
                file = file,
                durationMs = duration,
                lensFacing = lens,
                speed = parts.getOrNull(3)?.toFloatOrNull() ?: 1f,
                heightPx = parts.getOrNull(4)?.toIntOrNull() ?: 1080
            )
        }
    }

    /** Deletes any clip file not referenced by [keep], plus stale export temp files. */
    fun pruneOrphans(keep: List<Clip>) {
        val keepNames = keep.map { it.file.name }.toSet() + index.name
        dir.listFiles()?.forEach { if (it.name !in keepNames) it.delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
