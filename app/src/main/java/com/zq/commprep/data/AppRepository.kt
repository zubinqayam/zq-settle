package com.zq.commprep.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zq.commprep.domain.AppData
import com.zq.commprep.domain.Consent
import com.zq.commprep.domain.ConsentPolicy
import com.zq.commprep.domain.SessionRecord
import com.zq.commprep.domain.SessionSettings
import com.zq.commprep.domain.TimelineEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * All app data lives in one local Preferences DataStore file inside the app's private storage
 * (files/datastore/commprep.preferences_pb). The app itself sends none of this anywhere. (During camera
 * sessions the Presage SmartSpectra SDK does send data to Presage; see the consent notice and README.)
 */
class AppRepository(private val store: DataStore<Preferences>) {

    private object Keys {
        val CONSENT = stringPreferencesKey("consent")
        val LAST_SETTINGS = stringPreferencesKey("last_settings")
        val HISTORY = stringPreferencesKey("history")
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val CONSENT_VERSION = intPreferencesKey("consent_version")
        val ADULT_CONFIRMED = booleanPreferencesKey("adult_confirmed")
    }

    val data: Flow<AppData> = store.data.map { p ->
        AppData(
            consent = p[Keys.CONSENT]?.let { runCatching { Consent.valueOf(it) }.getOrNull() } ?: Consent.UNDECIDED,
            lastSettings = p[Keys.LAST_SETTINGS]?.let { runCatching { SessionSettings.fromJson(JSONObject(it)) }.getOrNull() },
            history = runCatching { AppData.historyFromJson(p[Keys.HISTORY]) }.getOrDefault(emptyList()),
            backupEnabled = p[Keys.BACKUP_ENABLED] ?: false,
            consentVersion = p[Keys.CONSENT_VERSION] ?: 0,
            adultConfirmed = p[Keys.ADULT_CONFIRMED] ?: false,
        )
    }

    suspend fun snapshot(): AppData = data.first()

    /**
     * Records a decision made on the consent screen, against notice [version].
     * Acceptance requires [adultConfirmed]; without it the decision is stored as DECLINED.
     */
    suspend fun recordConsent(accepted: Boolean, adultConfirmed: Boolean, version: Int = ConsentPolicy.CURRENT_VERSION) {
        val ok = accepted && adultConfirmed
        store.edit {
            it[Keys.CONSENT] = (if (ok) Consent.ACCEPTED else Consent.DECLINED).name
            it[Keys.CONSENT_VERSION] = version
            it[Keys.ADULT_CONFIRMED] = ok
        }
    }

    suspend fun saveLastSettings(s: SessionSettings) {
        store.edit { it[Keys.LAST_SETTINGS] = s.toJson().toString() }
    }

    suspend fun setBackupEnabled(on: Boolean) { store.edit { it[Keys.BACKUP_ENABLED] = on } }

    /** Appends an entry to the session [sessionId], creating the session if needed. */
    suspend fun addEntry(sessionId: Long, startedAt: Long, rawEntry: TimelineEntry) {
        val entry = rawEntry.copy(settingsLabel = TimelineEntry.scrubLabel(rawEntry.step, rawEntry.settingsLabel))
        store.edit { p ->
            val history = runCatching { AppData.historyFromJson(p[Keys.HISTORY]) }.getOrDefault(emptyList()).toMutableList()
            val idx = history.indexOfFirst { it.id == sessionId }
            if (idx >= 0) history[idx] = history[idx].copy(entries = history[idx].entries + entry)
            else history += SessionRecord(sessionId, startedAt, listOf(entry))
            p[Keys.HISTORY] = AppData.historyToJson(history).toString()
        }
    }

    /** "Delete all data" (checklist 6.4, 6.5) – removes every stored key, consent included. */
    suspend fun deleteAll() { store.edit { it.clear() } }

    /**
     * Withdraw consent (checklist 5.4): deletes all stored data, then records only the
     * decision itself (DECLINED) so the app keeps working without sensor measurement.
     */
    suspend fun withdrawConsent() {
        store.edit { it.clear(); it[Keys.CONSENT] = Consent.DECLINED.name }
    }

    /** Replace everything with restored data (checklist 6.10). */
    suspend fun replaceAll(d: AppData) {
        store.edit { p ->
            p.clear()
            p[Keys.CONSENT] = d.consent.name
            d.lastSettings?.let { p[Keys.LAST_SETTINGS] = it.toJson().toString() }
            p[Keys.HISTORY] = AppData.historyToJson(d.history).toString()
            p[Keys.BACKUP_ENABLED] = d.backupEnabled
            p[Keys.CONSENT_VERSION] = d.consentVersion
            p[Keys.ADULT_CONFIRMED] = d.adultConfirmed
        }
    }
}
