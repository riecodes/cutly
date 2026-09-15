package com.eirmon.cutly.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.eirmon.cutly.audio.PcmDecoder
import com.eirmon.cutly.audio.SilenceSettings
import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.transcribe.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Projects on disk, one directory each: `source.mp4`, `project.json`, `thumb.jpg`, `levels.bin`.
 *
 * A directory per project and a JSON file per directory is the whole database. Tens of projects
 * is the realistic ceiling for an app that copies every source video into private storage, and
 * listing tens of small files is instant. Nothing here needs a query.
 *
 * Everything is blocking I/O; callers run it on [kotlinx.coroutines.Dispatchers.IO]. The two
 * Android-only steps, probing and thumbnailing, are injected so the JVM tests can run the rest.
 */
class ProjectStore(
    private val root: File,
    private val probe: (File) -> ClipProbe.Info? = ClipProbe::probeMetadata,
    private val thumbnailer: (source: File, out: File) -> Unit = ::thumbnail
) {
    fun dir(id: String): File = File(root, id)
    fun source(id: String): File = File(dir(id), SOURCE)
    fun thumb(id: String): File = File(dir(id), THUMB)
    private fun document(id: String): File = File(dir(id), DOCUMENT)
    private fun levels(id: String): File = File(dir(id), LEVELS)

    /** Every readable project, newest edit first. A directory that does not parse is skipped. */
    fun list(): List<Project> =
        root.listFiles { file -> file.isDirectory }
            ?.mapNotNull { load(it.name) }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()

    fun load(id: String): Project? {
        val file = document(id)
        if (!file.isFile || !source(id).isFile) return null
        return runCatching { Project.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    /** Written next to the document and renamed over it, so a crash mid-write leaves the old one. */
    fun save(project: Project) {
        val file = document(project.id)
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "$DOCUMENT.tmp")
        pending.writeText(project.toJson().toString())
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Copies a picked video in and creates the project for it.
     *
     * @param open yields the video's bytes; a content resolver stream in the app.
     * @throws IOException when the stream cannot be opened or is empty.
     */
    fun create(name: String, open: () -> InputStream?): Project {
        val id = newId()
        val target = source(id)
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, "$SOURCE.pending")
        try {
            val input = open() ?: throw IOException("The selected video cannot be opened")
            input.use { stream -> pending.outputStream().use(stream::copyTo) }
            if (pending.length() == 0L) throw IOException("The selected video is empty")
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            dir(id).deleteRecursively()
            throw failure
        } finally {
            pending.delete()
        }
        return register(id, name)
    }

    /**
     * Takes ownership of a video the app already wrote, such as a merged take in the cache.
     *
     * A move, not a copy: cache and files live on the same volume, so this is a rename. The
     * caller's file is gone afterwards.
     */
    fun adopt(file: File, name: String): Project {
        val id = newId()
        val target = source(id)
        target.parentFile?.mkdirs()
        try {
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        } catch (failure: Throwable) {
            dir(id).deleteRecursively()
            throw failure
        }
        return register(id, name)
    }

    /**
     * Swaps the project's video for [file], which is moved in the way [adopt] moves. The cached
     * audio measurement is dropped so the next open measures the new length; the cut list and
     * captions are left to the caller, whose timeline they belong to.
     */
    fun replaceSource(id: String, file: File): Project? {
        val project = load(id) ?: return null
        val target = source(id)
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        levels(id).delete()
        val info = probe(target)
        return project.copy(
            updatedAt = now(),
            durationMs = (info?.durationUs ?: project.durationMs * 1000) / 1000,
            width = info?.width ?: project.width,
            height = info?.height ?: project.height
        ).also(::save)
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun rename(id: String, name: String): Project? =
        load(id)?.copy(name = name.trim().ifBlank { id }, updatedAt = now())?.also(::save)

    fun duplicate(id: String): Project? {
        val original = load(id) ?: return null
        val copyId = newId()
        dir(id).copyRecursively(dir(copyId), overwrite = true)
        val copy = original.copy(
            id = copyId,
            name = "${original.name} copy",
            createdAt = now(),
            updatedAt = now(),
            savedName = null
        )
        save(copy)
        return copy
    }

    fun loadLevels(id: String): PcmDecoder.Levels? = runCatching {
        val file = levels(id)
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val frameMs = input.readLong()
            val durationMs = input.readLong()
            val size = input.readInt()
            require(frameMs > 0 && durationMs > 0 && size in 1..MAX_LEVELS)
            PcmDecoder.Levels(FloatArray(size) { input.readFloat() }, frameMs, durationMs)
        }
    }.getOrNull()

    fun saveLevels(id: String, levels: PcmDecoder.Levels) {
        DataOutputStream(levels(id).outputStream().buffered()).use { output ->
            output.writeLong(levels.frameMs)
            output.writeLong(levels.durationMs)
            output.writeInt(levels.db.size)
            levels.db.forEach(output::writeFloat)
        }
    }

    /**
     * Moves the single-slot `cut-project` of earlier builds into the first real project.
     *
     * Runs once: the old directory is removed at the end, so a second call finds nothing. The
     * old preference keys are read here and nowhere else.
     */
    fun migrateLegacy(context: Context) {
        val legacyDir = File(context.filesDir, "cut-project")
        val legacySource = File(legacyDir, SOURCE)
        if (!legacySource.isFile) {
            legacyDir.deleteRecursively()
            return
        }
        val prefs = context.getSharedPreferences("cut-project", Context.MODE_PRIVATE)
        val project = runCatching { adopt(legacySource, "Imported project") }.getOrNull() ?: return
        File(legacyDir, LEVELS).takeIf { it.isFile }?.renameTo(levels(project.id))

        val defaults = SilenceSettings()
        val keep = prefs.getString("spans", null)
            ?.split(';')
            ?.mapNotNull { value ->
                val parts = value.split(',')
                val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                Span(start, end).takeIf { start >= 0 && end > start && end <= project.durationMs }
            }
            ?.takeIf { spans -> spans.zipWithNext().all { (a, b) -> a.endMs <= b.startMs } }
            .orEmpty()
        val captions = if (!prefs.getBoolean("has-transcript", false)) null else runCatching {
            val json = JSONArray(prefs.getString("captions", "[]"))
            List(json.length()) { index ->
                val item = json.getJSONObject(index)
                Segment(item.getLong("start"), item.getLong("end"), item.getString("text"))
            }
        }.getOrDefault(emptyList())

        save(
            project.copy(
                settings = SilenceSettings(
                    thresholdDb = prefs.getFloat("threshold", defaults.thresholdDb),
                    minSilenceMs = prefs.getLong("min-silence", defaults.minSilenceMs),
                    padMs = prefs.getLong("pad", defaults.padMs)
                ),
                keep = keep,
                captions = captions,
                captionsEnabled = prefs.getBoolean("captions-enabled", false)
            )
        )
        prefs.edit().clear().apply()
        legacyDir.deleteRecursively()
    }

    private fun register(id: String, name: String): Project {
        val file = source(id)
        val info = probe(file)
        runCatching { thumbnailer(file, thumb(id)) }
        val project = Project(
            id = id,
            name = name.trim().ifBlank { id },
            createdAt = now(),
            updatedAt = now(),
            durationMs = (info?.durationUs ?: 0L) / 1000,
            width = info?.width ?: 0,
            height = info?.height ?: 0
        )
        save(project)
        return project
    }

    private fun newId(): String {
        var id = "p${now()}"
        while (dir(id).exists()) id += "x"
        return id
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val SOURCE = "source.mp4"
        private const val DOCUMENT = "project.json"
        private const val THUMB = "thumb.jpg"
        private const val LEVELS = "levels.bin"
        private const val MAX_LEVELS = 3_600_000

        /** A frame from one second in, scaled to a card, as JPEG. */
        private fun thumbnail(source: File, out: File) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val frame = retriever.getScaledFrameAtTime(
                    1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640
                ) ?: return
                out.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } finally {
                retriever.release()
            }
        }
    }
}
