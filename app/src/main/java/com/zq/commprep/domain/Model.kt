package com.zq.commprep.domain

import org.json.JSONArray
import org.json.JSONObject

enum class Consent { UNDECIDED, ACCEPTED, DECLINED }

/**
 * Versioned consent (0.2.0). Bump [CURRENT_VERSION] whenever the consent notice text changes in a
 * way users must see again: an acceptance recorded against an older notice no longer allows the
 * camera/SDK, and the full current notice is shown again. Acceptance also needs the user's own
 * 18+ confirmation (Presage SDK privacy policy §13.1: the SDK must not be used with anyone under 18).
 */
object ConsentPolicy {
    /**
     * v1 = 0.1.x notice (no version stored). v2 = 0.2.0 notice: SDK data flow per Presage policy, 18+.
     * v3 = ZQ Settle submission notice: same disclosures trimmed to three points (not medical / 18+,
     * camera and data, AI use and retention). Earlier acceptances are asked again.
     */
    const val CURRENT_VERSION = 3

    /** Consent that actually counts now: ACCEPTED only if given against the current notice with 18+ confirmed. */
    fun effective(consent: Consent, version: Int, adultConfirmed: Boolean): Consent = when {
        consent != Consent.ACCEPTED -> consent
        version >= CURRENT_VERSION && adultConfirmed -> Consent.ACCEPTED
        else -> Consent.UNDECIDED
    }

    /** True when the consent notice must be shown (never decided, or accepted against an older notice). */
    fun needsPrompt(consent: Consent, version: Int, adultConfirmed: Boolean): Boolean =
        effective(consent, version, adultConfirmed) == Consent.UNDECIDED
}

enum class StepType(val title: String) { BASELINE("Quiet baseline"), BREATHING("Guided breathing"), REHEARSAL("Rehearsal") }

/** Settings for one full session. These are what "Use my settings" remembers (checklist 1.x). */
data class SessionSettings(
    val baselineSeconds: Int = BaselineRules.DEFAULT_SECONDS,
    val pace: Pace = BreathingRules.DEFAULT_PACE,
    /** null = "until I stop" */
    val breathingSeconds: Int? = BreathingRules.DEFAULT_LENGTH_SECONDS,
    val rehearsalSeconds: Int = RehearsalRules.DEFAULT_SECONDS,
) {
    fun summary(): String =
        "Baseline ${formatDuration(baselineSeconds)} · Breathing ${pace.label}, " +
            (breathingSeconds?.let { formatDuration(it) } ?: "until I stop") +
            " · Rehearsal ${formatDuration(rehearsalSeconds)}"

    fun toJson(): JSONObject = JSONObject()
        .put("baselineSeconds", baselineSeconds)
        .put("paceIn", pace.inCount)
        .put("paceOut", pace.outCount)
        .put("breathingSeconds", breathingSeconds ?: -1)
        .put("rehearsalSeconds", rehearsalSeconds)

    companion object {
        val DEFAULTS = SessionSettings()
        fun fromJson(o: JSONObject): SessionSettings = SessionSettings(
            baselineSeconds = o.getInt("baselineSeconds"),
            pace = Pace(o.getInt("paceIn"), o.getInt("paceOut")),
            breathingSeconds = o.getInt("breathingSeconds").takeIf { it >= 0 },
            rehearsalSeconds = o.getInt("rehearsalSeconds"),
        )
    }
}

/** One step in a session: either it ran (real seconds + settings label) or it was skipped (neutral gap). */
data class TimelineEntry(
    val step: StepType,
    val skipped: Boolean,
    val ranSeconds: Int,
    val settingsLabel: String,
) {
    fun displayText(): String = if (skipped) "${step.title} – skipped" else when (step) {
        StepType.BASELINE -> "Baseline, ${formatDuration(ranSeconds)}"
        StepType.BREATHING -> "Breathing, $settingsLabel, ${formatDuration(ranSeconds)}"
        StepType.REHEARSAL -> "Rehearsal, ${formatDuration(ranSeconds)}" +
            if (settingsLabel.isNotEmpty()) " ($settingsLabel)" else ""
    }

    fun toJson(): JSONObject = JSONObject().put("step", step.name).put("skipped", skipped)
        .put("ranSeconds", ranSeconds).put("settingsLabel", settingsLabel)

    companion object {
        /** 0.1.x stored an optional self-rating as "distress N/10". 0.2.0 never stores it (session-only). */
        private val LEGACY_RATING = Regex("""^distress \d{1,2}/10$""")

        /** Drops a legacy self-rating label so it never reappears in the timeline, export or backup. */
        fun scrubLabel(step: StepType, label: String): String =
            if (step == StepType.REHEARSAL && LEGACY_RATING.matches(label.trim())) "" else label

        fun fromJson(o: JSONObject): TimelineEntry {
            val step = StepType.valueOf(o.getString("step"))
            return TimelineEntry(step, o.getBoolean("skipped"), o.getInt("ranSeconds"),
                scrubLabel(step, o.getString("settingsLabel")))
        }
    }
}

data class SessionRecord(val id: Long, val startedAtMillis: Long, val entries: List<TimelineEntry>) {
    val anyRan: Boolean get() = entries.any { !it.skipped }
    /** Protocol: if the baseline did not run, the timeline is labelled "no baseline" (checklist 2.6). */
    val noBaseline: Boolean get() = entries.none { it.step == StepType.BASELINE && !it.skipped }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("startedAt", startedAtMillis)
        .put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): SessionRecord {
            val arr = o.getJSONArray("entries")
            return SessionRecord(o.getLong("id"), o.getLong("startedAt"),
                (0 until arr.length()).map { TimelineEntry.fromJson(arr.getJSONObject(it)) })
        }
    }
}

/** Everything the app stores. Used for export, encrypted backup and restore. */
data class AppData(
    val consent: Consent = Consent.UNDECIDED,
    val lastSettings: SessionSettings? = null,
    val history: List<SessionRecord> = emptyList(),
    val backupEnabled: Boolean = false,      // checklist 6.7: off by default
    /** Version of the consent notice the stored decision was made against (0 = before 0.2.0). */
    val consentVersion: Int = 0,
    /** User's own 18+ confirmation, given together with acceptance of the current notice. */
    val adultConfirmed: Boolean = false,
) {
    /** The consent that counts for camera/SDK use (see [ConsentPolicy]). */
    val effectiveConsent: Consent get() = ConsentPolicy.effective(consent, consentVersion, adultConfirmed)
    val needsConsentPrompt: Boolean get() = ConsentPolicy.needsPrompt(consent, consentVersion, adultConfirmed)

    fun toJson(): JSONObject = JSONObject()
        .put("format", "commprep-data-v1")
        .put("consent", consent.name)
        .put("lastSettings", lastSettings?.toJson() ?: JSONObject.NULL)
        .put("history", historyToJson(history))
        .put("backupEnabled", backupEnabled)
        .put("consentVersion", consentVersion)
        .put("adultConfirmed", adultConfirmed)

    companion object {
        fun historyToJson(h: List<SessionRecord>) = JSONArray().apply { h.forEach { put(it.toJson()) } }
        fun historyFromJson(s: String?): List<SessionRecord> {
            if (s.isNullOrBlank()) return emptyList()
            val arr = JSONArray(s)
            return (0 until arr.length()).map { SessionRecord.fromJson(arr.getJSONObject(it)) }
        }
        fun fromJson(o: JSONObject): AppData {
            require(o.optString("format") == "commprep-data-v1") { "Not a ZQ Settle data file" }
            return AppData(
                consent = Consent.valueOf(o.getString("consent")),
                lastSettings = if (o.isNull("lastSettings")) null else SessionSettings.fromJson(o.getJSONObject("lastSettings")),
                history = historyFromJson(o.getJSONArray("history").toString()),
                backupEnabled = o.optBoolean("backupEnabled", false),
                // Older files have no version: a restored acceptance then asks for consent again.
                consentVersion = o.optInt("consentVersion", 0),
                adultConfirmed = o.optBoolean("adultConfirmed", false),
            )
        }
    }
}
