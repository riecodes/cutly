package com.eirmon.cutly.export

import com.eirmon.cutly.audio.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeMapTest {

    private val keep = listOf(Span(0, 1_000), Span(2_000, 3_500), Span(8_000, 9_000))
    private val map = TimeMap(keep)

    @Test
    fun `prefix sums give each clip's output start and the total`() {
        assertEquals(0L, map.outputStart(0))
        assertEquals(1_000L, map.outputStart(1))
        assertEquals(2_500L, map.outputStart(2))
        assertEquals(3_500L, map.totalOutputMs)
    }

    @Test
    fun `clipAt lands on boundaries and clamps past the end`() {
        assertEquals(0, map.clipAt(0))
        assertEquals(0, map.clipAt(999))
        assertEquals(1, map.clipAt(1_000))
        assertEquals(1, map.clipAt(2_200))
        assertEquals(2, map.clipAt(2_500))
        assertEquals(2, map.clipAt(3_500))
        assertEquals(2, map.clipAt(99_999))
        assertEquals(0, map.clipAt(-5))
    }

    @Test
    fun `boundaryNear snaps to the closest clip edge`() {
        assertEquals(0, map.boundaryNear(0))
        assertEquals(0, map.boundaryNear(400))
        assertEquals(1, map.boundaryNear(600))
        assertEquals(1, map.boundaryNear(1_100))
        assertEquals(2, map.boundaryNear(2_400))
        assertEquals(3, map.boundaryNear(3_500))
        assertEquals(3, map.boundaryNear(99_999))
        assertEquals(0, map.boundaryNear(-5))
        assertEquals(0, TimeMap(emptyList()).boundaryNear(500))
    }

    @Test
    fun `toSource and toOutput round trip for kept instants`() {
        for (sourceMs in listOf(0L, 500L, 2_000L, 2_750L, 8_000L, 8_999L)) {
            val outputMs = map.toOutput(sourceMs)!!
            assertEquals(sourceMs, map.toSource(outputMs))
        }
        assertEquals(2_200L, map.toSource(1_200))
    }

    @Test
    fun `toOutput is null inside a removed gap and at a span's exclusive end`() {
        assertNull(map.toOutput(1_500))
        assertNull(map.toOutput(1_000))
        assertNull(map.toOutput(9_000))
        assertNull(map.toOutput(-1))
    }

    @Test
    fun `toOutputRange keeps both ends of a range that straddles a cut`() {
        assertEquals(800L to 1_200L, map.toOutputRange(800, 2_200))
        assertEquals(1_100L to 1_400L, map.toOutputRange(2_100, 2_400))
        assertNull(map.toOutputRange(1_200, 1_800))
        assertNull(map.toOutputRange(9_500, 9_900))
    }

    @Test
    fun `an empty timeline has no output`() {
        val empty = TimeMap(emptyList())
        assertEquals(0L, empty.totalOutputMs)
        assertEquals(0L, empty.toSource(500))
        assertNull(empty.toOutput(0))
        assertNull(empty.toOutputRange(0, 100))
    }
}
