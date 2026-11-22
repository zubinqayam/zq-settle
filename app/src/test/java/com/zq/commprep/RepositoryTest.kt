package com.zq.commprep

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zq.commprep.data.AppRepository
import com.zq.commprep.domain.AppData
import com.zq.commprep.domain.Consent
import com.zq.commprep.domain.ConsentPolicy
import com.zq.commprep.domain.Pace
import com.zq.commprep.domain.SessionSettings
import com.zq.commprep.domain.StepType
import com.zq.commprep.domain.TimelineEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun <T> withRepo(file: File, block: suspend (AppRepository) -> T): T {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            return runBlocking { block(AppRepository(store)) }
        } finally {
            // Close this DataStore fully before another instance opens the same file ("app restart").
            runBlocking { job.cancelAndJoin() }
        }
    }

    private val custom = SessionSettings(baselineSeconds = 90, pace = Pace(4, 6), breathingSeconds = 120, rehearsalSeconds = 60)

    @Test fun defaults_on_fresh_install() {
        val d = withRepo(File(tmp.root, "a.preferences_pb")) { it.snapshot() }
        assertEquals(Consent.UNDECIDED, d.consent); assertNull(d.lastSettings)
        assertFalse(d.backupEnabled); assertTrue(d.history.isEmpty())
    }

    @Test fun last_settings_survive_reopen_exactly() {
        val f = File(tmp.root, "b.preferences_pb")
        withRepo(f) { it.saveLastSettings(custom) }
        // New DataStore instance on the same file == app process restarted.
        assertEquals(custom, withRepo(f) { it.snapshot().lastSettings })
        val untilStop = custom.copy(breathingSeconds = null)
        withRepo(f) { it.saveLastSettings(untilStop) }
        assertEquals(untilStop, withRepo(f) { it.snapshot().lastSettings })
    }

    @Test fun delete_all_and_withdraw() {
        val f = File(tmp.root, "c.preferences_pb")
        withRepo(f) {
            it.recordConsent(accepted = true, adultConfirmed = true); it.saveLastSettings(custom); it.setBackupEnabled(true)
            it.addEntry(1, 1, TimelineEntry(StepType.BASELINE, true, 0, ""))
            it.withdrawConsent()
            val d = it.snapshot()
            assertEquals(Consent.DECLINED, d.consent); assertNull(d.lastSettings); assertTrue(d.history.isEmpty()); assertFalse(d.backupEnabled)
            it.deleteAll()
            assertEquals(AppData(), it.snapshot())
        }
    }

    @Test fun export_json_round_trip_and_restore() {
        val f = File(tmp.root, "d.preferences_pb")
        val original = withRepo(f) {
            it.recordConsent(accepted = true, adultConfirmed = true); it.saveLastSettings(custom)
            it.addEntry(7, 7, TimelineEntry(StepType.BASELINE, true, 0, ""))
            it.addEntry(7, 7, TimelineEntry(StepType.BREATHING, false, 120, "4/6"))
            it.snapshot()
        }
        assertTrue(original.history.single().noBaseline)
        val parsed = AppData.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, parsed)
        val restored = withRepo(File(tmp.root, "fresh.preferences_pb")) { it.replaceAll(parsed); it.snapshot() }
        assertEquals(original, restored)
        assertEquals("Breathing, 4/6, 2 min", restored.history.single().entries[1].displayText())
    }

    @Test fun consent_is_versioned_and_needs_18_plus() {
        val f = File(tmp.root, "e.preferences_pb")
        withRepo(f) {
            assertTrue(it.snapshot().needsConsentPrompt)
            // Accept without 18+ confirmation is never stored as acceptance.
            it.recordConsent(accepted = true, adultConfirmed = false)
            assertEquals(Consent.DECLINED, it.snapshot().effectiveConsent)
            it.recordConsent(accepted = true, adultConfirmed = true)
            val d = it.snapshot()
            assertEquals(Consent.ACCEPTED, d.effectiveConsent)
            assertEquals(ConsentPolicy.CURRENT_VERSION, d.consentVersion)
            assertFalse(d.needsConsentPrompt)
            // An acceptance recorded against an older notice (0.1.x had no version) asks again.
            it.recordConsent(accepted = true, adultConfirmed = true, version = 1)
            assertEquals(Consent.UNDECIDED, it.snapshot().effectiveConsent)
            assertTrue(it.snapshot().needsConsentPrompt)
        }
        // Old 0.1.x backup (no consentVersion field) restores as "ask again", not as accepted.
        val old = JSONObject(AppData(consent = Consent.ACCEPTED).toJson().toString()).apply { remove("consentVersion"); remove("adultConfirmed") }
        val restored = AppData.fromJson(old)
        assertEquals(Consent.ACCEPTED, restored.consent)
        assertTrue(restored.needsConsentPrompt)
        // Declined stays declined (no nagging) even with an old version.
        assertFalse(ConsentPolicy.needsPrompt(Consent.DECLINED, 0, false))
    }

    @Test fun three_point_notice_v3_asks_v2_acceptances_again() {
        assertEquals(3, ConsentPolicy.CURRENT_VERSION)
        // Accepted against the previous (v2) notice: camera stays off until the v3 notice is accepted.
        assertEquals(Consent.UNDECIDED, ConsentPolicy.effective(Consent.ACCEPTED, 2, true))
        assertTrue(ConsentPolicy.needsPrompt(Consent.ACCEPTED, 2, true))
        assertEquals(Consent.ACCEPTED, ConsentPolicy.effective(Consent.ACCEPTED, 3, true))
        // 18+ confirmation is still required for v3.
        assertEquals(Consent.UNDECIDED, ConsentPolicy.effective(Consent.ACCEPTED, 3, false))
    }

    @Test fun self_rating_is_never_saved_or_exported() {
        val f = File(tmp.root, "g.preferences_pb")
        val d = withRepo(f) {
            it.addEntry(9, 9, TimelineEntry(StepType.REHEARSAL, false, 30, "distress 8/10"))
            it.snapshot()
        }
        val e = d.history.single().entries.single()
        assertEquals("", e.settingsLabel)
        assertEquals("Rehearsal, 30 s", e.displayText())
        assertFalse(d.toJson().toString().contains("distress"))
        // A 0.1.x export/backup that still holds a rating is scrubbed on restore.
        val legacy = JSONObject(d.toJson().toString())
        legacy.getJSONArray("history").getJSONObject(0).getJSONArray("entries").getJSONObject(0).put("settingsLabel", "distress 10/10")
        assertFalse(AppData.fromJson(legacy).toJson().toString().contains("distress"))
    }
}
