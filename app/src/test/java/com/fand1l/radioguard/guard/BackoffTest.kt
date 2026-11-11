package com.fand1l.radioguard.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun `first attempt is allowed at once`() {
        assertTrue(Backoff().canAttempt(0))
    }

    @Test
    fun `interval doubles after each failure up to the limit`() {
        val backoff = Backoff(minMs = 1_500, maxMs = 15_000)
        var now = 0L
        for (expected in listOf(1_500L, 3_000L, 6_000L, 12_000L, 15_000L, 15_000L)) {
            backoff.onFailure(now)
            assertFalse(backoff.canAttempt(now + expected - 1))
            assertTrue(backoff.canAttempt(now + expected))
            now += expected
        }
    }

    @Test
    fun `success resets the interval`() {
        val backoff = Backoff(minMs = 1_500, maxMs = 15_000)
        backoff.onFailure(0)
        backoff.onFailure(1_500)
        backoff.onSuccess()
        assertTrue(backoff.canAttempt(1_500))
        backoff.onFailure(2_000)
        assertTrue(backoff.canAttempt(3_500))
    }
}
