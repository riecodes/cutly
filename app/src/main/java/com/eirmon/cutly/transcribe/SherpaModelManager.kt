package com.eirmon.cutly.transcribe

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface SherpaModelState {
    data object Missing : SherpaModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : SherpaModelState
    data object Verifying : SherpaModelState
    data object Ready : SherpaModelState
    data class Failed(val message: String) : SherpaModelState
}

/**
 * Owns the optional Whisper model without putting its hundred megabytes in the APK.
 *
 * Android's DownloadManager keeps the three transfers alive across process death. Their ids are
 * persisted so a new process can recover progress, verify every SHA-256, and only then copy the
 * model into filesDir. The marker is written last, so a killed verification is never mistaken for
 * an install that sherpa may open.
 *
 * Tiny multilingual INT8 is the default because it is the smallest Whisper model that understands
 * both Tagalog and English. Base is more accurate but makes the opt-in download and working set
 * substantially larger; tiny keeps the first offline path practical on ordinary phones.
 */
internal class SherpaModelManager(context: Context) {
    private val context = context.applicationContext
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    data class Files(val encoder: File, val decoder: File, val tokens: File)

    suspend fun state(): SherpaModelState = withContext(Dispatchers.IO) {
        if (files() != null) return@withContext SherpaModelState.Ready
        preferences.getString(FAILURE, null)?.let {
            return@withContext SherpaModelState.Failed(it)
        }

        val ids = ARTIFACTS.mapNotNull { artifact ->
            preferences.getLong(artifact.preferenceKey, NO_DOWNLOAD)
                .takeIf { it != NO_DOWNLOAD }
                ?.let { artifact to it }
        }
        if (ids.isEmpty()) return@withContext SherpaModelState.Missing
        if (ids.size != ARTIFACTS.size) {
            return@withContext SherpaModelState.Failed("The model download record is incomplete")
        }

        var downloaded = 0L
        var allComplete = true
        for ((artifact, id) in ids) {
            val row = query(id)
                ?: return@withContext SherpaModelState.Failed("A model download was lost")
            downloaded += row.downloaded.coerceAtLeast(0)
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> Unit
                DownloadManager.STATUS_FAILED -> return@withContext SherpaModelState.Failed(
                    "Model download failed (${row.reason})"
                )
                else -> allComplete = false
            }
            if (row.total > 0 && row.total != artifact.bytes) {
                return@withContext SherpaModelState.Failed("The model host reported a wrong size")
            }
        }

        if (allComplete) SherpaModelState.Verifying
        else SherpaModelState.Downloading(downloaded, TOTAL_BYTES)
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        removeDownloadsAndTemps()
        preferences.edit().remove(FAILURE).apply()
        val editor = preferences.edit()
        for (artifact in ARTIFACTS) {
            val request = DownloadManager.Request(Uri.parse(artifact.url))
                .setTitle("Cutly offline transcription")
                .setDescription(artifact.name)
                .setAllowedOverMetered(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "$DOWNLOAD_DIRECTORY/${artifact.tempName}"
                )
            editor.putLong(artifact.preferenceKey, downloads.enqueue(request))
        }
        editor.apply()
    }

    suspend fun install(): Files = withContext(Dispatchers.IO) {
        try {
            if (state() !is SherpaModelState.Verifying) {
                throw IOException("The model has not finished downloading")
            }

            val directory = modelDirectory().apply { mkdirs() }
            for (artifact in ARTIFACTS) {
                val source = tempFile(artifact)
                if (!source.isFile || source.length() != artifact.bytes) {
                    throw IOException("${artifact.name} has the wrong size")
                }
                val destination = File(directory, artifact.name)
                source.copyTo(destination, overwrite = true)
                val actual = destination.sha256()
                if (!actual.equals(artifact.sha256, ignoreCase = true)) {
                    destination.delete()
                    throw IOException("${artifact.name} failed its integrity check")
                }
            }

            File(directory, MARKER).writeText(REVISION)
            removeDownloadRecords()
            preferences.edit().remove(FAILURE).apply()
            files() ?: throw IOException("The verified model could not be opened")
        } catch (failure: Exception) {
            preferences.edit()
                .putString(FAILURE, failure.message ?: "Model verification failed")
                .apply()
            throw failure
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        removeDownloadsAndTemps()
        modelDirectory().deleteRecursively()
        preferences.edit().putBoolean(SELECTED, false).remove(FAILURE).apply()
    }

    fun files(): Files? {
        val directory = modelDirectory()
        if (File(directory, MARKER).takeIf { it.isFile }?.readText() != REVISION) return null
        val byName = ARTIFACTS.associate { it.name to File(directory, it.name) }
        if (ARTIFACTS.any { byName.getValue(it.name).length() != it.bytes }) return null
        return Files(
            encoder = byName.getValue(ENCODER_NAME),
            decoder = byName.getValue(DECODER_NAME),
            tokens = byName.getValue(TOKENS_NAME)
        )
    }

    fun isSelected(): Boolean = preferences.getBoolean(SELECTED, false) && files() != null

    fun select(selected: Boolean) {
        preferences.edit().putBoolean(SELECTED, selected && files() != null).apply()
    }

    private fun query(id: Long): DownloadRow? {
        val cursor = downloads.query(DownloadManager.Query().setFilterById(id)) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            DownloadRow(
                status = it.long(DownloadManager.COLUMN_STATUS).toInt(),
                reason = it.long(DownloadManager.COLUMN_REASON).toInt(),
                downloaded = it.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                total = it.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
        }
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun removeDownloadsAndTemps() {
        removeDownloadRecords()
        ARTIFACTS.forEach { artifact -> runCatching { tempFile(artifact).delete() } }
    }

    private fun removeDownloadRecords() {
        val ids = ARTIFACTS.mapNotNull {
            preferences.getLong(it.preferenceKey, NO_DOWNLOAD).takeIf { id -> id != NO_DOWNLOAD }
        }
        if (ids.isNotEmpty()) downloads.remove(*ids.toLongArray())
        preferences.edit().also { editor ->
            ARTIFACTS.forEach { editor.remove(it.preferenceKey) }
        }.apply()
    }

    private fun modelDirectory(): File = File(context.filesDir, MODEL_DIRECTORY)

    private fun tempFile(artifact: Artifact): File = File(
        checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "External download storage is unavailable"
        },
        "$DOWNLOAD_DIRECTORY/${artifact.tempName}"
    )

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class DownloadRow(
        val status: Int,
        val reason: Int,
        val downloaded: Long,
        val total: Long
    )

    private data class Artifact(
        val name: String,
        val bytes: Long,
        val sha256: String
    ) {
        val tempName: String get() = "$name.download"
        val preferenceKey: String get() = "download.$name"
        val url: String get() = "$MODEL_URL/$name?download=true"
    }

    companion object {
        const val TOTAL_BYTES = 103_609_903L

        private const val PREFERENCES = "sherpa-model"
        private const val SELECTED = "selected"
        private const val FAILURE = "failure"
        private const val NO_DOWNLOAD = -1L
        private const val MODEL_DIRECTORY = "sherpa-whisper-tiny"
        private const val DOWNLOAD_DIRECTORY = "cutly-model"
        private const val MARKER = ".verified"
        private const val REVISION = "65176e2deb88badc814a94058666cadccc29b61c"
        private const val MODEL_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/$REVISION"

        private const val ENCODER_NAME = "tiny-encoder.int8.onnx"
        private const val DECODER_NAME = "tiny-decoder.int8.onnx"
        private const val TOKENS_NAME = "tiny-tokens.txt"

        private val ARTIFACTS = listOf(
            Artifact(
                ENCODER_NAME,
                12_937_772,
                "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
            ),
            Artifact(
                DECODER_NAME,
                89_855_401,
                "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
            ),
            Artifact(
                TOKENS_NAME,
                816_730,
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
            )
        )
    }
}
