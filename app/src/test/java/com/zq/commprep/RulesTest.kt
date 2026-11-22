package com.zq.commprep

import com.zq.commprep.domain.BaselineRules
import com.zq.commprep.domain.BreathingRules
import com.zq.commprep.domain.Pace
import com.zq.commprep.domain.RehearsalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineRulesTest {
    @Test fun default_is_60() = assertEquals(60, BaselineRules.DEFAULT_SECONDS)
    @Test fun allowed_values_are_30_to_120_in_15s() =
        assertEquals(listOf(30, 45, 60, 75, 90, 105, 120), BaselineRules.allowedValues)
    @Test fun increase_and_decrease_clamp() {
        assertEquals(75, BaselineRules.increase(60))
        assertEquals(120, BaselineRules.increase(120))
        assertEquals(30, BaselineRules.decrease(30))
        assertEquals(45, BaselineRules.decrease(60))
        assertEquals(30, BaselineRules.decrease(60).let(BaselineRules::decrease))
    }
    @Test fun invalid_values() {
        assertFalse(BaselineRules.isValid(29)); assertFalse(BaselineRules.isValid(50)); assertFalse(BaselineRules.isValid(135))
    }
}

class BreathingRulesTest {
    @Test fun defaults() {
        assertEquals(Pace(5, 5), BreathingRules.DEFAULT_PACE)
        assertEquals(6.0, BreathingRules.DEFAULT_PACE.breathsPerMinute, 0.0001)
        assertEquals(90, BreathingRules.DEFAULT_LENGTH_SECONDS)
        assertEquals(listOf(60, 90, 120, 180, null), BreathingRules.LENGTH_OPTIONS)
        assertEquals(listOf(Pace(4, 4), Pace(4, 6), Pace(5, 5), Pace(6, 6)), BreathingRules.PRESETS)
        assertEquals(0, BreathingRules.DEFAULT_HOLD_SECONDS)
    }
    @Test fun accepts_whole_seconds_per_phase_3_to_7() {
        (3..7).forEach { assertEquals(it, BreathingRules.parseCount(it.toString())) }
        assertEquals(4, BreathingRules.parseCount(" 4 "))
    }
    @Test fun rejects_out_of_range_and_non_whole() {
        listOf("0", "1", "2", "8", "9", "10", "77", "-3", "4.5", "4,5", "", " ", "abc", "5x", "+5", "007", "99999999999")
            .forEach { assertNull("should reject '$it'", BreathingRules.parseCount(it)) }
    }
    @Test fun invalid_edit_keeps_last_valid_and_shows_message() {
        val r = BreathingRules.applyEdit("9", lastValid = 4)
        assertEquals(4, r.selected)
        assertEquals("Choose between 3 and 7 seconds per phase", r.error)
        val ok = BreathingRules.applyEdit("6", lastValid = 4)
        assertEquals(6, ok.selected); assertNull(ok.error)
    }
    @Test fun cannot_start_with_invalid_value() {
        assertFalse(BreathingRules.canStart("2", "5"))
        assertFalse(BreathingRules.canStart("5", "8"))
        assertFalse(BreathingRules.canStart("", "5"))
        assertTrue(BreathingRules.canStart("3", "7"))
    }
    @Test fun stepper_cannot_leave_range() {
        assertEquals(7, BreathingRules.increase(7)); assertEquals(3, BreathingRules.decrease(3))
    }
    @Test fun phases_have_no_hold() {
        val p = Pace(4, 6)
        val names = (0 until 10).map { BreathingRules.phaseAt(it, p).name }
        assertEquals(List(4) { "Breathe in" } + List(6) { "Breathe out" }, names)
        assertEquals("Breathe in", BreathingRules.phaseAt(10, p).name)
    }
}

class RehearsalRulesTest {
    @Test fun defaults() {
        assertEquals(30, RehearsalRules.DEFAULT_SECONDS); assertEquals(30, RehearsalRules.EXTEND_SECONDS)
        assertEquals(600, RehearsalRules.CEILING_SECONDS)
    }
    @Test fun extend_adds_30_until_ceiling() {
        var t = RehearsalRules.DEFAULT_SECONDS
        var taps = 0
        while (RehearsalRules.canExtend(t)) { t = RehearsalRules.extend(t); taps++ }
        assertEquals(600, t); assertEquals(19, taps)
        assertEquals(600, RehearsalRules.extend(600))
        assertEquals(60, RehearsalRules.extend(30))
    }
    @Test fun distress_suggestions_only_when_high() {
        assertTrue(RehearsalRules.suggestionsFor(null).isEmpty())
        assertTrue(RehearsalRules.suggestionsFor(6).isEmpty())
        assertEquals(3, RehearsalRules.suggestionsFor(7).size)
        assertEquals(3, RehearsalRules.suggestionsFor(10).size)
    }
}
