package com.zq.commprep

import com.zq.commprep.domain.AppData
import com.zq.commprep.domain.ConversationType
import com.zq.commprep.domain.OpeningBuilder
import com.zq.commprep.domain.OpeningInputs
import com.zq.commprep.domain.RehearsalGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningBuilderTest {
    private val inputs = OpeningInputs("The last two reports were late.", "  I can't plan my week   when they slip. ", "agree on a Thursday deadline")

    @Test fun feedback_template_is_exact_and_deterministic() {
        val d = OpeningBuilder.build(ConversationType.FEEDBACK, inputs)
        assertEquals("I'd like to talk with you about the last two reports were late. I can't plan my week when they slip.", d.opening)
        assertEquals("What I'd like is: agree on a Thursday deadline.", d.request)
        assertEquals("I hear you. Can we come back to what I'm asking for: agree on a Thursday deadline?", d.ifDifficult)
        assertEquals(d, OpeningBuilder.build(ConversationType.FEEDBACK, inputs))   // same words → same text
    }

    @Test fun every_type_builds_and_uses_only_the_users_words() {
        ConversationType.entries.forEach { t ->
            val d = OpeningBuilder.build(t, inputs)
            assertTrue(t.name, d.opening.contains("I can't plan my week when they slip"))
            assertTrue(t.name, d.request.contains("agree on a Thursday deadline"))
            assertEquals(t, d.type)
        }
        assertEquals("What I'm asking for is: agree on a Thursday deadline.", OpeningBuilder.build(ConversationType.BOUNDARY, inputs).request)
        assertTrue(OpeningBuilder.build(ConversationType.INTERVIEW, inputs).opening.startsWith("Thank you for having me for"))
    }

    @Test fun situation_is_optional_point_and_request_are_required() {
        assertFalse(OpeningBuilder.canBuild(OpeningInputs("x", "", "y")))
        assertFalse(OpeningBuilder.canBuild(OpeningInputs("x", "y", "  ")))
        val d = OpeningBuilder.build(ConversationType.CUSTOM, OpeningInputs("", "I need more notice.", "a week's warning"))
        assertEquals("I need more notice.", d.opening)
        assertEquals("I'd like to talk with you about something. I need more notice.",
            OpeningBuilder.build(ConversationType.FEEDBACK, OpeningInputs("", "I need more notice", "a week's warning")).opening)
    }

    @Test fun inputs_are_cleaned_and_capped() {
        assertEquals("hello there", OpeningBuilder.clean("  hello \n\t there!!  "))
        assertEquals(OpeningBuilder.MAX_INPUT_CHARS, OpeningBuilder.clean("a".repeat(5000)).length)
        assertEquals("NASA launch", OpeningBuilder.clean("NASA launch."))
    }

    @Test fun card_text_is_exactly_the_three_card_lines() {
        val d = OpeningBuilder.build(ConversationType.ASK_FOR_HELP, inputs).copy(opening = " Edited opening. ")
        assertEquals("My opening\nEdited opening.\n\nMy request\n${d.request}\n\nIf the conversation becomes difficult…\n${d.ifDifficult}",
            OpeningBuilder.cardText(d))
    }

    @Test fun nothing_from_the_journey_is_part_of_stored_data() {
        // The stored/exported model has exactly these keys; the opening/card are never among them.
        assertEquals(setOf("format", "consent", "lastSettings", "history", "backupEnabled", "consentVersion", "adultConfirmed"),
            AppData().toJson().keys().asSequence().toSet())
    }

    @Test fun rehearsal_waits_until_the_camera_is_off() {
        assertFalse(RehearsalGate.canStart(cameraActive = true))
        assertTrue(RehearsalGate.canStart(cameraActive = false))
        assertTrue(RehearsalGate.cameraText(false).startsWith("Camera off"))
        assertTrue(RehearsalGate.cameraText(true).contains("Turning the camera off"))
    }
}
