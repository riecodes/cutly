package com.eirmon.cutly.data

import com.eirmon.cutly.transcribe.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "transcripts.json")

    private fun entry(id: String, segments: List<Segment> = emptyList()) =
        TranscriptEntry(id, "Video $id", createdAt = 1_000L, durationMs = 15_000L, engine = "Groq", segments = segments)

    @Test
    fun addRoundTripsEveryFieldNewestFirst() {
        val store = TranscriptStore(file)
        val first = entry("t1", listOf(Segment(0L, 5_000L, "Hello world"), Segment(6_000L, 9_000L, "Bye")))
        store.add(first)
        store.add(entry("t2"))

        val listed = TranscriptStore(file).list()
        assertEquals(listOf("t2", "t1"), listed.map { it.id })
        assertEquals(first, listed[1])
    }

    @Test
    fun deleteRemovesOnlyThatId() {
        val store = TranscriptStore(file)
        store.add(entry("t1"))
        store.add(entry("t2"))
        store.delete("t2")
        store.delete("missing")
        assertEquals(listOf("t1"), store.list().map { it.id })
    }

    @Test
    fun missingOrCorruptFileIsAnEmptyHistory() {
        assertTrue(TranscriptStore(file).list().isEmpty())
        file.writeText("not json")
        assertTrue(TranscriptStore(file).list().isEmpty())
    }
}
