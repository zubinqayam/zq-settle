package com.zq.commprep.domain

/**
 * Pure rules taken from Communication_Prep_Protocol_v2.md. Every default lives here so the
 * UI and the tests use exactly the same numbers.
 */
object BaselineRules {
    const val DEFAULT_SECONDS = 60          // checklist 2.1
    const val MIN_SECONDS = 30              // checklist 2.2
    const val MAX_SECONDS = 120
    const val STEP_SECONDS = 15             // checklist 2.3

    /** 30, 45, 60, 75, 90, 105, 120 */
    val allowedValues: List<Int> = (MIN_SECONDS..MAX_SECONDS step STEP_SECONDS).toList()

    fun increase(current: Int): Int = (current + STEP_SECONDS).coerceAtMost(MAX_SECONDS)
    fun decrease(current: Int): Int = (current - STEP_SECONDS).coerceAtLeast(MIN_SECONDS)
    fun isValid(seconds: Int): Boolean = seconds in allowedValues
}

data class Pace(val inCount: Int, val outCount: Int) {
    val label: String get() = "$inCount/$outCount"
    /** Each phase length is in seconds (1 count = 1 second), so breaths per minute = 60 / (in + out). */
    val breathsPerMinute: Double get() = 60.0 / (inCount + outCount)
}

object BreathingRules {
    const val MIN_COUNT = 3                 // checklist 3.4 / 3.10
    const val MAX_COUNT = 7
    const val OUT_OF_RANGE_MESSAGE = "Choose between 3 and 7 seconds per phase"
    const val COMFORT_NOTE =
        "If this feels uncomfortable, choose a shorter pace, breathe naturally or stop – any comfortable pace is fine."

    val DEFAULT_PACE = Pace(5, 5)           // checklist 3.2
    val PRESETS = listOf(Pace(4, 4), Pace(4, 6), Pace(5, 5), Pace(6, 6))   // checklist 3.3

    /** Length options in seconds; null = "until I stop". checklist 3.5 */
    val LENGTH_OPTIONS: List<Int?> = listOf(60, 90, 120, 180, null)
    const val DEFAULT_LENGTH_SECONDS = 90   // checklist 3.1

    /** Breath-hold phase length. Protocol: no breath-holding by default. checklist 3.9 */
    const val DEFAULT_HOLD_SECONDS = 0

    /**
     * Custom pace input rule (checklist 3.10): 3–7 seconds per phase. Returns the value only
     * if the text is a whole number from 3 to 7 (surrounding whitespace allowed). Anything
     * else – "8", "2", "4.5", "-3", "", "abc", "05x" – returns null and must not be accepted.
     * Behaviour is unchanged: 1 count = 1 second.
     */
    fun parseCount(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 2 || !t.all { it.isDigit() }) return null
        val v = t.toIntOrNull() ?: return null
        return if (v in MIN_COUNT..MAX_COUNT) v else null
    }

    fun isValidCount(v: Int): Boolean = v in MIN_COUNT..MAX_COUNT
    fun increase(v: Int): Int = (v + 1).coerceAtMost(MAX_COUNT)
    fun decrease(v: Int): Int = (v - 1).coerceAtLeast(MIN_COUNT)

    /**
     * Result of a user edit of a custom seconds-per-phase field: the value that stays selected
     * and an optional inline error. An invalid edit keeps [lastValid].
     */
    data class EditResult(val selected: Int, val error: String?)

    fun applyEdit(text: String, lastValid: Int): EditResult {
        val parsed = parseCount(text)
        return if (parsed != null) EditResult(parsed, null) else EditResult(lastValid, OUT_OF_RANGE_MESSAGE)
    }

    /** A session may start only if both phase fields currently hold valid text. */
    fun canStart(inText: String, outText: String): Boolean =
        parseCount(inText) != null && parseCount(outText) != null

    data class Phase(val name: String, val count: Int, val ofCount: Int)

    /** Phase at a given elapsed second (phase lengths are in seconds; 1 count = 1 s). Only in/out phases – no hold. */
    fun phaseAt(elapsedSeconds: Int, pace: Pace): Phase {
        val cycle = pace.inCount + pace.outCount + DEFAULT_HOLD_SECONDS
        val pos = elapsedSeconds % cycle
        return if (pos < pace.inCount) Phase("Breathe in", pos + 1, pace.inCount)
        else Phase("Breathe out", pos - pace.inCount + 1, pace.outCount)
    }
}

object RehearsalRules {
    const val DEFAULT_SECONDS = 30          // checklist 4.1
    const val EXTEND_SECONDS = 30           // checklist 4.2
    const val CEILING_SECONDS = 600         // checklist 4.3 (10 min)
    const val DISTRESS_MIN = 0              // checklist 4.6
    const val DISTRESS_MAX = 10
    const val HIGH_DISTRESS_THRESHOLD = 7

    val PROMPTS = listOf(
        "Picture the opening of the conversation.",
        "Notice any tension in your body.",
        "Use one coping skill – a slow breath, loosening your shoulders, a calm phrase.",
        "Picture a \"good-enough\" outcome."
    )

    val HIGH_DISTRESS_SUGGESTIONS = listOf(
        "End the rehearsal for now",
        "Return to breathing",
        "Rehearse a smaller part of the situation"
    )

    fun canExtend(currentTotal: Int): Boolean = currentTotal + EXTEND_SECONDS <= CEILING_SECONDS
    fun extend(currentTotal: Int): Int =
        if (canExtend(currentTotal)) currentTotal + EXTEND_SECONDS else currentTotal

    fun suggestionsFor(distress: Int?): List<String> =
        if (distress != null && distress >= HIGH_DISTRESS_THRESHOLD) HIGH_DISTRESS_SUGGESTIONS else emptyList()
}

fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}
