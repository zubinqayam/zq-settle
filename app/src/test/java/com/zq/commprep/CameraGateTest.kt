package com.zq.commprep

import com.zq.commprep.domain.CameraGate
import com.zq.commprep.domain.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGateTest {
    @Test fun no_allow_camera_button_before_or_without_consent() {
        assertFalse(CameraGate.offerAllowCamera(Consent.UNDECIDED, false))
        assertFalse(CameraGate.offerAllowCamera(Consent.DECLINED, false))
    }
    @Test fun allow_camera_button_after_accept_until_granted() {
        assertTrue(CameraGate.offerAllowCamera(Consent.ACCEPTED, false))
        assertFalse(CameraGate.offerAllowCamera(Consent.ACCEPTED, true))
    }
    @Test fun keyless_build_never_offers_camera_and_says_unavailable() {
        assertFalse(CameraGate.offerAllowCamera(Consent.ACCEPTED, false, keyConfigured = false))
        assertEquals(CameraGate.NO_KEY_TEXT, CameraGate.statusText(Consent.ACCEPTED, true, false, false, "x"))
        assertEquals(CameraGate.NO_KEY_TEXT, CameraGate.statusText(Consent.UNDECIDED, false, false, false, "x"))
        assertTrue(CameraGate.NO_KEY_TEXT.contains("timer only"))
    }
    @Test fun sdk_only_with_consent_permission_and_key() {
        assertTrue(CameraGate.canUseSdk(Consent.ACCEPTED, true, true))
        assertFalse(CameraGate.canUseSdk(Consent.ACCEPTED, true, false))
        assertFalse(CameraGate.canUseSdk(Consent.ACCEPTED, false, true))
        assertFalse(CameraGate.canUseSdk(Consent.DECLINED, true, true))
        assertFalse(CameraGate.canUseSdk(Consent.UNDECIDED, true, true))
    }
    @Test fun status_text_keeps_timer_path_clear() {
        assertEquals("Camera check-in off (consent not given). Steps run on the timer only.", CameraGate.statusText(Consent.DECLINED, false, false, true, "x"))
        assertEquals("Camera not allowed. Steps run on the timer only.", CameraGate.statusText(Consent.ACCEPTED, false, true, true, "x"))
        assertTrue(CameraGate.statusText(Consent.ACCEPTED, true, false, false, "x").contains("timer only"))
        assertEquals("Camera check-in available: x", CameraGate.statusText(Consent.ACCEPTED, true, false, true, "x"))
    }
}
