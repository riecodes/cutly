package com.eirmon.cutly.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomStopsTest {

    @Test
    fun `lens with a 1x floor keeps the 1x stop`() {
        assertEquals(listOf(1f, 2f, 3f, 5f), zoomStopsFor(minRatio = 1f, maxRatio = 8f))
    }

    @Test
    fun `floor a hair above 1x still offers 1x`() {
        assertEquals(listOf(1f, 2f), zoomStopsFor(minRatio = 1.01f, maxRatio = 2f))
    }

    @Test
    fun `ultra wide adds its own minimum in front`() {
        assertEquals(listOf(0.6f, 1f, 2f, 3f, 5f), zoomStopsFor(minRatio = 0.6f, maxRatio = 10f))
    }

    @Test
    fun `no range means no pill`() {
        assertEquals(listOf(1f), zoomStopsFor(minRatio = 1f, maxRatio = 1f))
    }
}
