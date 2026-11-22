package com.zq.commprep.domain

import java.util.Locale

/**
 * Live-signal proof shown during sessions (0.1.3). Pure Kotlin so it is unit-testable without the SDK.
 * Every value here comes from the SmartSpectra SDK: processing status, metrics updates and the
 * SDK's own per-sample confidence / stable fields. SETTLE never invents a confidence.
 */
enum class SdkStatus { NOT_STARTED, STARTING, RUNNING, STOPPING, IDLE, ERROR }

/** One rate sample as delivered by the SDK (MeasurementWithConfidence). */
data class RateSample(val value: Float, val confidence: Float, val stable: Boolean, val timestamp: Long)

/** The parts of one SDK metrics update that SETTLE uses. Either sample may be absent in a given update. */
data class MetricsUpdate(val pulse: RateSample?, val breathing: RateSample?)

data class LiveSignalState(
    val status: SdkStatus = SdkStatus.NOT_STARTED,
    /** Number of metrics updates received from the SDK since the current start. */
    val samples: Int = 0,
    /** How many of those updates carried a new pulse-rate / breathing-rate sample. */
    val pulseCount: Int = 0,
    val breathingCount: Int = 0,
    val lastPulse: RateSample? = null,
    val lastBreathing: RateSample? = null,
    /** App clock (ms) when [lastPulse] / [lastBreathing] arrived; used for the freshness rule (0.2.0). */
    val pulseAtMs: Long? = null,
    val breathingAtMs: Long? = null,
    /** SDK validation code name (e.g. NO_FACE_FOUND) and its hint text, if any. */
    val validationCode: String? = null,
    val validationHint: String? = null,
    /** SDK error code name (e.g. AUTHENTICATION_FAILED), if the SDK reported one. */
    val errorCode: String? = null,
    /** SDK error message (already passed through [LiveSignal.sanitize]) and retryable flag. */
    val errorMessage: String? = null,
    val errorRetryable: Boolean? = null,
    /** Last non-OK validation code seen in this run (kept after it clears, for diagnosis). */
    val lastIssueCode: String? = null,
    /** Timing (app clock, ms) for diagnosis: start request, first RUNNING, first/last update, error. */
    val startMs: Long = 0L,
    val runningAtMs: Long? = null,
    val firstUpdateMs: Long? = null,
    val lastUpdateMs: Long? = null,
    val errorAtMs: Long? = null,
) {
    /** A live signal exists only while the SDK reports RUNNING and has delivered at least one metrics update. */
    val isLive: Boolean get() = status == SdkStatus.RUNNING && samples > 0
}

object LiveSignal {
    const val TIMER_ONLY = "Timer only - no live signal"

    /**
     * 0.2.0 freshness rule: a pulse value may be shown only if it arrived within this window
     * (app clock). Older values are cleared, never shown as current. Tune on the real phone.
     */
    const val FRESH_WINDOW_MS = 5_000L
    /** Plausibility bounds for showing a pulse estimate (outside → treated as not valid). */
    const val PULSE_MIN_BPM = 30f
    const val PULSE_MAX_BPM = 220f
    /** SDK validation code for a camera below 25 fps; warned about, but no longer hides a fresh pulse. */
    const val FRAME_RATE_TOO_LOW = "FRAME_RATE_TOO_LOW"

    /** Fresh state for a new start: counters and retained values cleared (per Presage metrics guidance). */
    fun startRequested(nowMs: Long = 0L): LiveSignalState = LiveSignalState(status = SdkStatus.STARTING, startMs = nowMs)

    fun onStatus(s: LiveSignalState, status: SdkStatus, nowMs: Long = 0L): LiveSignalState = s.copy(
        status = status,
        errorCode = if (status == SdkStatus.STARTING) null else s.errorCode,
        runningAtMs = if (status == SdkStatus.RUNNING && s.runningAtMs == null) nowMs else s.runningAtMs,
        errorAtMs = if (status == SdkStatus.ERROR && s.errorAtMs == null) nowMs else s.errorAtMs,
    )

    /**
     * Count every update. A sample is kept only while fresh: when an update has no new sample, the
     * previous one is kept only if it is younger than [FRESH_WINDOW_MS]; otherwise it is cleared (0.2.0).
     */
    fun onMetrics(s: LiveSignalState, u: MetricsUpdate, nowMs: Long = 0L): LiveSignalState {
        val p = u.pulse?.takeIf { it.timestamp > 0 }
        val b = u.breathing?.takeIf { it.timestamp > 0 }
        val keepP = p == null && s.pulseAtMs != null && nowMs - s.pulseAtMs <= FRESH_WINDOW_MS
        val keepB = b == null && s.breathingAtMs != null && nowMs - s.breathingAtMs <= FRESH_WINDOW_MS
        return s.copy(
            samples = s.samples + 1,
            pulseCount = s.pulseCount + (if (p != null) 1 else 0),
            breathingCount = s.breathingCount + (if (b != null) 1 else 0),
            lastPulse = p ?: if (keepP) s.lastPulse else null,
            lastBreathing = b ?: if (keepB) s.lastBreathing else null,
            pulseAtMs = if (p != null) nowMs else if (keepP) s.pulseAtMs else null,
            breathingAtMs = if (b != null) nowMs else if (keepB) s.breathingAtMs else null,
            firstUpdateMs = s.firstUpdateMs ?: nowMs,
            lastUpdateMs = nowMs,
        )
    }

    fun onValidation(s: LiveSignalState, code: String?, hint: String?): LiveSignalState =
        s.copy(validationCode = code, validationHint = hint?.takeIf { it.isNotBlank() },
            lastIssueCode = if (code != null && code != "OK") code else s.lastIssueCode)

    fun onError(s: LiveSignalState, code: String, message: String? = null, retryable: Boolean? = null, nowMs: Long = 0L): LiveSignalState =
        s.copy(status = SdkStatus.ERROR, errorCode = code, errorMessage = message?.takeIf { it.isNotBlank() },
            errorRetryable = retryable, errorAtMs = s.errorAtMs ?: nowMs)

    /**
     * Make an SDK message safe to show/log: removes any occurrence of [secret] (the configured key),
     * collapses whitespace and caps the length. Never returns the secret.
     */
    fun sanitize(message: String?, secret: String): String? {
        if (message.isNullOrBlank()) return null
        var m = message
        if (secret.isNotEmpty()) m = m.replace(secret, "[redacted]")
        m = m.replace(Regex("\\s+"), " ").trim()
        return if (m.length > 200) m.take(200) + "…" else m
    }

    /** Plain-words error detail with the SDK's own code (and message when it adds anything). */
    fun errorText(s: LiveSignalState): String? {
        val code = s.errorCode ?: return null
        val msg = s.errorMessage?.let { " - $it" } ?: ""
        val retry = when (s.errorRetryable) { true -> " (retryable)"; false -> " (not retryable)"; null -> "" }
        return "SDK error code: $code$retry$msg"
    }

    /** Timing/rate line for diagnosis, measured by the app from SDK callbacks. */
    fun diagnosticsText(s: LiveSignalState): String {
        fun sec(t: Long?) = t?.let { String.format(Locale.ROOT, "%.1f s", (it - s.startMs) / 1000.0) }
        val parts = mutableListOf<String>()
        parts += "running after " + (sec(s.runningAtMs) ?: "-")
        val first = s.firstUpdateMs; val last = s.lastUpdateMs
        if (first != null && last != null && s.samples > 1 && last > first) {
            val rate = (s.samples - 1) * 1000.0 / (last - first)
            parts += String.format(Locale.ROOT, "%d updates in %.1f s (%.1f/s)", s.samples, (last - first) / 1000.0, rate)
        }
        sec(s.errorAtMs)?.let { parts += "error at $it" }
        s.lastIssueCode?.let { parts += "last SDK check: " + plain(it) }
        return "Diagnostics: " + parts.joinToString("; ")
    }

    /** Plain-words connection / processing state. */
    fun statusText(s: LiveSignalState): String = when (s.status) {
        SdkStatus.NOT_STARTED -> "SDK: not started"
        SdkStatus.STARTING -> "SDK: connecting and starting camera…"
        SdkStatus.RUNNING -> if (s.samples > 0) "SDK: running, receiving measurements" else "SDK: running, waiting for first measurement"
        SdkStatus.STOPPING -> "SDK: stopping"
        SdkStatus.IDLE -> "SDK: stopped (not measuring)"
        SdkStatus.ERROR -> "SDK: error" + (s.errorCode?.let { " (${plain(it)})" } ?: "")
    }

    fun samplesText(s: LiveSignalState): String = "SDK updates received: ${s.samples}"

    /** Confidence strictly from the SDK's own confidence/stable fields; "no value yet" otherwise. */
    fun confidenceText(s: LiveSignalState): String {
        val parts = listOfNotNull(
            s.lastPulse?.let { "pulse ${fmt2(it.confidence)}${if (it.stable) ", stable" else ""}" },
            s.lastBreathing?.let { "breathing ${fmt2(it.confidence)}${if (it.stable) ", stable" else ""}" },
        )
        return if (parts.isEmpty()) "SDK confidence: no value yet" else "SDK confidence: " + parts.joinToString(" · ")
    }

    /**
     * The pulse estimate that may be shown right now, or null. Valid only when all hold: the SDK is
     * RUNNING with no error, its latest check is OK (or none reported), the value arrived within
     * [FRESH_WINDOW_MS] of [nowMs], the SDK gave it a confidence above zero and it is within
     * [PULSE_MIN_BPM]..[PULSE_MAX_BPM]. Confidence is used only as a gate, never shown as a score.
     */
    fun freshPulse(s: LiveSignalState, nowMs: Long): RateSample? {
        val p = s.lastPulse ?: return null
        val at = s.pulseAtMs ?: return null
        if (s.status != SdkStatus.RUNNING || s.errorCode != null) return null
        // FRAME_RATE_TOO_LOW no longer hides a fresh, valid pulse; the warning is shown next to it (vitalsText).
        if (s.validationCode != null && s.validationCode != "OK" && s.validationCode != FRAME_RATE_TOO_LOW) return null
        if (nowMs - at > FRESH_WINDOW_MS || nowMs < at) return null
        if (p.confidence <= 0f || p.value < PULSE_MIN_BPM || p.value > PULSE_MAX_BPM) return null
        return p
    }

    /** Pulse-only line shown by default (0.2.0): only a fresh, valid estimate; never breathing, never a score. */
    fun vitalsText(s: LiveSignalState, nowMs: Long): String? =
        freshPulse(s, nowMs)?.let {
            val line = "Pulse estimate: ${Math.round(it.value)} bpm (updated just now)"
            if (s.validationCode == FRAME_RATE_TOO_LOW)
                "$line · Warning: ${s.validationHint?.takeIf { h -> h.isNotBlank() } ?: plain(FRAME_RATE_TOO_LOW)}"
            else line
        }

    /** Plain-language status shown by default; SDK codes and counters live under "Signal details". */
    fun plainStatus(s: LiveSignalState, nowMs: Long): String = when (s.status) {
        SdkStatus.NOT_STARTED -> "Camera check-in not started."
        SdkStatus.STARTING -> "Starting the camera check-in…"
        SdkStatus.RUNNING -> when {
            freshPulse(s, nowMs) != null -> "Camera check-in running."
            s.validationCode != null && s.validationCode != "OK" ->
                "Looking for a steady signal: ${s.validationHint ?: plain(s.validationCode)}."
            else -> "Looking for a steady signal. Keep still, face the camera, with good light."
        }
        SdkStatus.STOPPING -> "Stopping the camera check-in…"
        SdkStatus.IDLE -> "Camera check-in stopped."
        SdkStatus.ERROR -> "Camera check-in unavailable right now. The timer keeps going."
    }

    fun hintText(s: LiveSignalState): String? =
        if (s.validationCode == null || s.validationCode == "OK") null
        else "${s.validationHint ?: plain(s.validationCode)} [${s.validationCode}]"

    /** Logcat line for a transition: status and counts only, never configuration values. */
    fun logLine(event: String, s: LiveSignalState): String =
        "event=$event status=${s.status} samples=${s.samples} pulseSamples=${s.pulseCount}" +
            " breathingSamples=${s.breathingCount}" + (s.errorCode?.let { " error=$it" } ?: "") +
            (s.errorRetryable?.let { " retryable=$it" } ?: "") + (s.errorMessage?.let { " message=\"$it\"" } ?: "") +
            (s.lastIssueCode?.let { " lastIssue=$it" } ?: "")

    private fun plain(code: String) = code.lowercase(Locale.ROOT).replace('_', ' ')
    private fun fmt2(v: Float) = String.format(Locale.ROOT, "%.2f", v)
}
