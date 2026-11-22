package com.zq.commprep

import com.zq.commprep.domain.LiveSignal
import com.zq.commprep.domain.LiveSignalState
import com.zq.commprep.domain.MetricsUpdate
import com.zq.commprep.domain.RateSample
import com.zq.commprep.domain.SdkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSignalTest {
    private val pulse = RateSample(71.6f, 0.83f, true, 1000L)
    private val breath = RateSample(13.25f, 0.61f, false, 1000L)

    @Test fun initial_state_is_not_live_and_says_timer_only() {
        val s = LiveSignalState()
        assertFalse(s.isLive)
        assertEquals("SDK: not started", LiveSignal.statusText(s))
        assertEquals("SDK updates received: 0", LiveSignal.samplesText(s))
        assertEquals("Timer only - no live signal", LiveSignal.TIMER_ONLY)
    }

    @Test fun running_without_metrics_is_not_live() {
        val s = LiveSignal.onStatus(LiveSignal.startRequested(), SdkStatus.RUNNING)
        assertFalse(s.isLive)
        assertEquals("SDK: running, waiting for first measurement", LiveSignal.statusText(s))
    }

    @Test fun sample_counter_rises_with_each_sdk_update() {
        var s = LiveSignal.onStatus(LiveSignal.startRequested(), SdkStatus.RUNNING)
        s = LiveSignal.onMetrics(s, MetricsUpdate(null, null))
        s = LiveSignal.onMetrics(s, MetricsUpdate(pulse, null))
        s = LiveSignal.onMetrics(s, MetricsUpdate(null, breath))
        assertEquals(3, s.samples)
        assertEquals(1, s.pulseCount)
        assertEquals(1, s.breathingCount)
        assertTrue(s.isLive)
        assertEquals("SDK: running, receiving measurements", LiveSignal.statusText(s))
    }

    @Test fun confidence_comes_only_from_sdk_fields() {
        var s = LiveSignal.onStatus(LiveSignal.startRequested(), SdkStatus.RUNNING)
        s = LiveSignal.onMetrics(s, MetricsUpdate(null, null))
        assertEquals("SDK confidence: no value yet", LiveSignal.confidenceText(s))
        assertNull(LiveSignal.vitalsText(s, 0L))
        s = LiveSignal.onMetrics(s, MetricsUpdate(pulse, breath))
        assertEquals("SDK confidence: pulse 0.83, stable · breathing 0.61", LiveSignal.confidenceText(s))
        // 0.2.0: default line is pulse-only, never breathing, never a confidence/score.
        assertEquals("Pulse estimate: 72 bpm (updated just now)", LiveSignal.vitalsText(s, 0L))
    }

    @Test fun empty_update_keeps_sample_only_while_fresh_and_ignores_zero_timestamps() {
        var s = LiveSignal.onMetrics(LiveSignal.startRequested(), MetricsUpdate(pulse, null), 10_000L)
        s = LiveSignal.onMetrics(s, MetricsUpdate(RateSample(99f, 0.1f, false, 0L), null), 12_000L)
        assertEquals(pulse, s.lastPulse)          // 2 s old: still fresh
        assertEquals(1, s.pulseCount)
        s = LiveSignal.onMetrics(s, MetricsUpdate(null, null), 10_000L + LiveSignal.FRESH_WINDOW_MS + 1)
        assertNull(s.lastPulse)                   // older than the window: cleared, not retained
        assertNull(s.pulseAtMs)
    }

    @Test fun stale_breathing_is_cleared_too() {
        var s = LiveSignal.onMetrics(LiveSignal.startRequested(), MetricsUpdate(null, breath), 0L)
        s = LiveSignal.onMetrics(s, MetricsUpdate(pulse, null), LiveSignal.FRESH_WINDOW_MS + 1)
        assertNull(s.lastBreathing)
        assertEquals(pulse, s.lastPulse)
    }

    @Test fun fresh_pulse_shown_only_while_fresh_and_valid() {
        var s = LiveSignal.onStatus(LiveSignal.startRequested(0L), SdkStatus.RUNNING, 100L)
        s = LiveSignal.onMetrics(s, MetricsUpdate(pulse, null), 1_000L)
        assertEquals(pulse, LiveSignal.freshPulse(s, 1_000L))
        // No further SDK updates at all: the value disappears once the window passes.
        assertNull(LiveSignal.freshPulse(s, 1_000L + LiveSignal.FRESH_WINDOW_MS + 1))
        assertNull(LiveSignal.vitalsText(s, 1_000L + LiveSignal.FRESH_WINDOW_MS + 1))
        // SDK check not OK → not shown.
        assertNull(LiveSignal.freshPulse(LiveSignal.onValidation(s, "CHEST_NOT_VISIBLE", "Chest not visible"), 1_000L))
        assertEquals(pulse, LiveSignal.freshPulse(LiveSignal.onValidation(s, "OK", ""), 1_000L))
        // Not running / error → not shown.
        assertNull(LiveSignal.freshPulse(LiveSignal.onStatus(s, SdkStatus.IDLE), 1_000L))
        assertNull(LiveSignal.freshPulse(LiveSignal.onError(s, "PROCESSING_FAILED"), 1_000L))
        // Zero confidence or implausible value → not shown.
        val zero = LiveSignal.onMetrics(s, MetricsUpdate(RateSample(70f, 0f, false, 2000L), null), 2_000L)
        assertNull(LiveSignal.freshPulse(zero, 2_000L))
        val odd = LiveSignal.onMetrics(s, MetricsUpdate(RateSample(250f, 0.9f, true, 2000L), null), 2_000L)
        assertNull(LiveSignal.freshPulse(odd, 2_000L))
    }

    @Test fun plain_status_has_no_codes_or_scores() {
        var s = LiveSignal.onStatus(LiveSignal.startRequested(0L), SdkStatus.RUNNING, 0L)
        assertEquals("Looking for a steady signal. Keep still, face the camera, with good light.", LiveSignal.plainStatus(s, 0L))
        s = LiveSignal.onMetrics(s, MetricsUpdate(pulse, breath), 500L)
        assertEquals("Camera check-in running.", LiveSignal.plainStatus(s, 600L))
        assertEquals("Camera check-in unavailable right now. The timer keeps going.",
            LiveSignal.plainStatus(LiveSignal.onError(s, "AUTHENTICATION_FAILED"), 600L))
        for (t in listOf(LiveSignal.plainStatus(s, 600L), LiveSignal.vitalsText(s, 600L)!!)) {
            assertFalse(t.contains("confidence", true)); assertFalse(t.contains("stress", true)); assertFalse(t.contains("breath", true))
        }
    }

    @Test fun new_start_clears_counters_and_values() {
        var s = LiveSignal.onMetrics(LiveSignal.startRequested(), MetricsUpdate(pulse, breath))
        s = LiveSignal.onError(s, "AUTHENTICATION_FAILED")
        assertFalse(s.isLive)
        assertEquals("SDK: error (authentication failed)", LiveSignal.statusText(s))
        val r = LiveSignal.startRequested()
        assertEquals(0, r.samples); assertNull(r.lastPulse); assertNull(r.errorCode)
        assertEquals(SdkStatus.STARTING, r.status)
    }

    @Test fun validation_hint_shown_only_when_not_ok() {
        val s = LiveSignal.onValidation(LiveSignalState(), "NO_FACE_FOUND", "")
        assertEquals("no face found [NO_FACE_FOUND]", LiveSignal.hintText(s))
        assertNull(LiveSignal.hintText(LiveSignal.onValidation(s, "OK", "fine")))
    }

    @Test fun log_line_has_status_and_counts_only() {
        var s = LiveSignal.onStatus(LiveSignal.startRequested(), SdkStatus.RUNNING)
        s = LiveSignal.onMetrics(s, MetricsUpdate(pulse, null))
        assertEquals("event=status status=RUNNING samples=1 pulseSamples=1 breathingSamples=0", LiveSignal.logLine("status", s))
    }

    @Test fun error_code_and_message_are_shown() {
        val s = LiveSignal.onError(LiveSignal.startRequested(1000L), "PROCESSING_FAILED", "SmartSpectra processing failed.", false, 13000L)
        assertEquals("SDK error code: PROCESSING_FAILED (not retryable) - SmartSpectra processing failed.", LiveSignal.errorText(s))
        assertEquals("SDK: error (processing failed)", LiveSignal.statusText(s))
        assertTrue(LiveSignal.diagnosticsText(s).contains("error at 12.0 s"))
        assertTrue(LiveSignal.logLine("error", s).contains("error=PROCESSING_FAILED retryable=false"))
    }

    @Test fun sanitize_removes_secret_and_caps_length() {
        val secret = "TESTSECRET123"
        assertEquals("auth failed for [redacted] now", LiveSignal.sanitize("auth  failed for TESTSECRET123\n now", secret))
        assertNull(LiveSignal.sanitize("  ", secret))
        assertEquals(201, LiveSignal.sanitize("x".repeat(500), secret)!!.length)
        assertFalse(LiveSignal.logLine("e", LiveSignal.onError(LiveSignalState(), "X", LiveSignal.sanitize("k=TESTSECRET123", secret))).contains(secret))
    }

    @Test fun diagnostics_measure_update_rate_and_keep_last_issue() {
        var s = LiveSignal.startRequested(0L)
        s = LiveSignal.onStatus(s, SdkStatus.RUNNING, 8000L)
        s = LiveSignal.onValidation(s, "FRAME_RATE_TOO_LOW", "Input frame rate is too low.")
        s = LiveSignal.onValidation(s, "OK", "")
        for (i in 0..10) s = LiveSignal.onMetrics(s, MetricsUpdate(null, null), 9000L + i * 200L)
        assertEquals("Diagnostics: running after 8.0 s; 11 updates in 2.0 s (5.0/s); last SDK check: frame rate too low",
            LiveSignal.diagnosticsText(s))
    }

    @Test fun no_secret_in_default_log_lines() {
        assertFalse(LiveSignal.logLine("status", LiveSignalState()).contains("key", ignoreCase = true))
    }
}
