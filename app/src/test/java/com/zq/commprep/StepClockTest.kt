package com.zq.commprep

import com.zq.commprep.domain.StepClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepClockTest {
    @Test fun elapsed_comes_from_timestamps_not_tick_count() {
        val c = StepClock().start(10_000L)
        // However often or rarely the UI looks, the answer depends only on the clock.
        assertEquals(0, c.elapsedSeconds(10_999L))
        assertEquals(1, c.elapsedSeconds(11_000L))
        assertEquals(59, c.elapsedSeconds(69_999L))   // e.g. a UI that skipped many refreshes
        assertEquals(60, c.elapsedSeconds(70_000L))
    }

    @Test fun pause_excludes_paused_time_and_resume_continues() {
        var c = StepClock().start(0L)
        c = c.pause(20_000L)
        assertTrue(c.isPaused); assertFalse(c.isRunning)
        assertEquals(20, c.elapsedSeconds(500_000L))  // nothing counts while paused (e.g. app in background)
        c = c.resume(500_000L)
        assertEquals(30, c.elapsedSeconds(510_000L))
        c = c.pause(515_500L).resume(600_000L).pause(601_000L)
        assertEquals(36_500L, c.elapsedMs(999_999L))
    }

    @Test fun pause_and_resume_are_idempotent_and_safe() {
        var c = StepClock().start(0L)
        c = c.pause(5_000L).pause(9_000L)              // second pause changes nothing
        assertEquals(5_000L, c.elapsedMs(9_000L))
        c = c.resume(10_000L).resume(12_000L)          // second resume does not restart the interval
        assertEquals(7_000L, c.elapsedMs(12_000L))
        assertEquals(StepClock(), StepClock().resume(1_000L))  // cannot resume a clock that never started
        assertEquals(0L, StepClock().start(5_000L).elapsedMs(4_000L))  // never negative
    }

    @Test fun remaining_and_done() {
        val c = StepClock().start(0L)
        assertEquals(60, c.remainingSeconds(0L, 60))
        assertEquals(1, c.remainingSeconds(59_001L, 60))
        assertEquals(0, c.remainingSeconds(60_000L, 60))
        assertFalse(c.isDone(59_999L, 60)); assertTrue(c.isDone(60_000L, 60))
        assertFalse(c.isDone(10_000_000L, null))       // "until I stop" never finishes by itself
        val p = c.pause(30_000L)
        assertFalse(p.isDone(10_000_000L, 60))         // a paused step never completes on its own
    }

    @Test fun state_survives_being_held_across_recreation() {
        // The clock is a plain value; keeping the same value (rotation keeps the activity) gives the same time.
        val c = StepClock().start(1_000L).pause(4_000L).resume(10_000L)
        val copy = c.copy()
        assertEquals(c.elapsedMs(20_000L), copy.elapsedMs(20_000L))
        assertEquals(13_000L, copy.elapsedMs(20_000L))
    }
}
