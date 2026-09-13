package com.eirmon.cutly.transcribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs [block] up to [attempts] times, waiting [baseDelayMs] doubled per failure in between.
 *
 * Bounded on purpose: an unbounded retry against a rejected API key re-uploads the whole take
 * every few seconds until the phone dies. Cancellation is never retried and never swallowed.
 *
 * @param block receives the zero-based attempt number, so a caller can show "retrying" from the
 *        second try onward.
 * @throws Throwable the last failure, once every attempt has been used.
 */
internal suspend fun <T> retry(
    attempts: Int,
    baseDelayMs: Long,
    block: suspend (attempt: Int) -> T
): T {
    require(attempts > 0) { "attempts must be positive" }
    var last: Throwable? = null
    repeat(attempts) { attempt ->
        try {
            return block(attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            last = failure
        }
        if (attempt < attempts - 1) delay(baseDelayMs shl attempt)
    }
    throw last!!
}
