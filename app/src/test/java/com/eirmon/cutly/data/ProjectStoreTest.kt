package com.eirmon.cutly.data

import com.eirmon.cutly.audio.Span
import com.eirmon.cutly.camera.ClipProbe
import com.eirmon.cutly.transcribe.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val info = ClipProbe.Info(
        width = 1080, height = 1920, frameRate = 30f, bitrate = null, frameCount = 0, durationUs = 12_000_000L
    )

    private fun store() = ProjectStore(
        root = folder.newFolder("projects"),
        probe = { info },
        thumbnailer = { _, out -> out.writeText("thumb") }
    )

    private fun video(name: String = "in.mp4"): File =
        folder.newFile(name).apply { writeBytes(ByteArray(1024) { 1 }) }

    @Test
    fun createCopiesTheSourceAndProbesIt() {
        val store = store()
        val input = video()
        val project = store.create("Take one") { input.inputStream() }

        assertTrue(store.source(project.id).isFile)
        assertTrue(input.isFile)
        assertEquals(12_000L, project.durationMs)
        assertEquals(1920, project.height)
        assertEquals("thumb", store.thumb(project.id).readText())
        assertEquals(project, store.load(project.id))
    }

    @Test
    fun adoptMovesTheFile() {
        val store = store()
        val temp = video("merged.mp4")
        val project = store.adopt(temp, "Take")
        assertFalse(temp.exists())
        assertTrue(store.source(project.id).isFile)
    }

    @Test
    fun roundTripKeepsEveryFieldIncludingTheTwoKindsOfNoCaptions() {
        val store = store()
        val base = store.create("x") { video().inputStream() }

        val never = base.copy(captions = null)
        store.save(never)
        assertNull(store.load(base.id)!!.captions)

        val silent = base.copy(captions = emptyList())
        store.save(silent)
        assertEquals(emptyList<Segment>(), store.load(base.id)!!.captions)

        val full = base.copy(
            keep = listOf(Span(0, 1_000), Span(2_000, 5_000)),
            manualEdits = true,
            captions = listOf(Segment(100, 900, "hello")),
            captionsEnabled = true,
            savedName = "cutly_cut_1.mp4"
        )
        store.save(full)
        assertEquals(full, store.load(base.id))
    }

    @Test
    fun listIsNewestFirstAndSkipsJunkDirectories() {
        val store = store()
        val a = store.create("a") { video("a.mp4").inputStream() }
        Thread.sleep(2)
        val b = store.create("b") { video("b.mp4").inputStream() }
        File(store.dir("junk"), "project.json").apply { parentFile!!.mkdirs(); writeText("not json") }

        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
    }

    @Test
    fun renameDuplicateAndDelete() {
        val store = store()
        val project = store.create("orig") { video().inputStream() }
            .copy(keep = listOf(Span(0, 500)), savedName = "done.mp4")
        store.save(project)

        assertEquals("new name", store.rename(project.id, "new name")!!.name)

        val copy = store.duplicate(project.id)!!
        assertNotEquals(project.id, copy.id)
        assertEquals(project.keep, copy.keep)
        assertNull(copy.savedName)
        assertTrue(store.source(copy.id).isFile)
        assertNotNull(store.load(copy.id))

        store.delete(project.id)
        assertNull(store.load(project.id))
        assertFalse(store.dir(project.id).exists())
        assertEquals(listOf(copy.id), store.list().map { it.id })
    }

    @Test
    fun outOfOrderSpansAreDroppedRatherThanTrusted() {
        val json = Project(
            id = "p1", name = "n", createdAt = 1, updatedAt = 1, durationMs = 10_000, width = 1, height = 1,
            keep = listOf(Span(5_000, 6_000), Span(1_000, 2_000))
        ).toJson()
        assertEquals(emptyList<Span>(), Project.fromJson(json)!!.keep)
    }
}
