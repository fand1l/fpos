package com.fand1l.radioguard.guard

/**
 * Якщо порушення не виправляється, повторюємо дедалі рідше (1.5 с → 15 с),
 * щоб не палити батарею марними спробами.
 */
class Backoff(private val minMs: Long = 1_500, private val maxMs: Long = 15_000) {

    private var intervalMs = minMs
    private var nextAttemptAt = Long.MIN_VALUE

    fun canAttempt(nowMs: Long): Boolean = nowMs >= nextAttemptAt

    fun onFailure(nowMs: Long) {
        nextAttemptAt = nowMs + intervalMs
        intervalMs = (intervalMs * 2).coerceAtMost(maxMs)
    }

    fun onSuccess() {
        intervalMs = minMs
        nextAttemptAt = Long.MIN_VALUE
    }
}
