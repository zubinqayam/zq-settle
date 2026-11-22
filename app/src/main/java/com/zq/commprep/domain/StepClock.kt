package com.zq.commprep.domain

/**
 * Step timer based on elapsed monotonic time (0.2.0-prep2), replacing the 0.1.x approach of counting
 * one-second callbacks. Pure and immutable so it is unit-testable with a fake clock; the UI passes
 * SystemClock.elapsedRealtime(), which keeps counting in deep sleep and is unaffected by wall-clock changes.
 * Paused time is excluded. Because the value is derived from timestamps, a late or skipped UI tick never
 * loses or adds time, and a configuration change (rotation) does not reset it.
 */
data class StepClock(
    /** Time accumulated in earlier running intervals, ms. */
    val accumulatedMs: Long = 0L,
    /** Monotonic timestamp when the current running interval began; null while not running or paused. */
    val runningSinceMs: Long? = null,
    val started: Boolean = false,
) {
    val isRunning: Boolean get() = runningSinceMs != null
    val isPaused: Boolean get() = started && runningSinceMs == null

    fun start(nowMs: Long): StepClock = StepClock(accumulatedMs = 0L, runningSinceMs = nowMs, started = true)

    fun pause(nowMs: Long): StepClock {
        val since = runningSinceMs ?: return this
        return copy(accumulatedMs = accumulatedMs + (nowMs - since).coerceAtLeast(0L), runningSinceMs = null)
    }

    fun resume(nowMs: Long): StepClock = if (!started || isRunning) this else copy(runningSinceMs = nowMs)

    fun elapsedMs(nowMs: Long): Long =
        accumulatedMs + (runningSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)

    /** Whole seconds elapsed (floor). */
    fun elapsedSeconds(nowMs: Long): Int = (elapsedMs(nowMs) / 1000L).toInt()

    /** Whole seconds left of [lengthSeconds] (ceiling, so "1 s left" shows until the end), never negative. */
    fun remainingSeconds(nowMs: Long, lengthSeconds: Int): Int {
        val leftMs = lengthSeconds * 1000L - elapsedMs(nowMs)
        return if (leftMs <= 0) 0 else ((leftMs + 999) / 1000).toInt()
    }

    fun isDone(nowMs: Long, lengthSeconds: Int?): Boolean =
        lengthSeconds != null && elapsedMs(nowMs) >= lengthSeconds * 1000L
}
