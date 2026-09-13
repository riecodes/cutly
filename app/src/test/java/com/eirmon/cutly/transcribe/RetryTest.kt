package com.eirmon.cutly.transcribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RetryTest {

    @Test
    fun `returns first success and stops`() = runTest {
        var calls = 0
        val result = retry(attempts = 3, baseDelayMs = 100) { calls++; "ok" }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries with doubling delay and then succeeds`() = runTest {
        var calls = 0
        val result = retry(attempts = 3, baseDelayMs = 1_500) { attempt ->
            calls++
            if (attempt < 2) throw IOException("HTTP 500") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        // 1500 after the first failure, 3000 after the second, nothing after success.
        assertEquals(4_500L, testScheduler.currentTime)
    }

    @Test
    fun `rethrows the last failure once attempts are used up`() = runTest {
        var calls = 0
        val last = IOException("HTTP 401")
        try {
            retry<Unit>(attempts = 3, baseDelayMs = 10) { calls++; throw last }
            fail("expected the last failure")
        } catch (failure: IOException) {
            assertSame(last, failure)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `cancellation is neither retried nor swallowed`() = runTest {
        var calls = 0
        try {
            retry<Unit>(attempts = 3, baseDelayMs = 10) { calls++; throw CancellationException("stop") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }
}
