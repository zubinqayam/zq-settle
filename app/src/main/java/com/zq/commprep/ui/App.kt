package com.zq.commprep.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zq.commprep.backup.BackupCrypto
import com.zq.commprep.data.AppRepository
import com.zq.commprep.domain.AppData
import com.zq.commprep.domain.CameraGate
import com.zq.commprep.domain.Consent
import com.zq.commprep.domain.SessionSettings
import com.zq.commprep.domain.StepType
import com.zq.commprep.sensors.HeartRateSource
import com.zq.commprep.sensors.NoOpHeartRateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private sealed interface Screen {
    data object Home : Screen
    data class Step(val step: StepType, val key: Long = System.nanoTime()) : Screen
    data class After(val step: StepType, val skipped: Boolean) : Screen
    data object Timeline : Screen
    data object Settings : Screen
    /** Full current consent notice, reached from Settings (re-consent never bypasses the notice). */
    data object Consent : Screen
    /** 0.2.0-prep2 preparation journey (session-only text). */
    data object Choose : Screen
    data class Build(val type: com.zq.commprep.domain.ConversationType) : Screen
    data object Card : Screen
}

private val RECOMMENDED_ORDER = listOf(StepType.BASELINE, StepType.BREATHING, StepType.REHEARSAL)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CommPrepApp(repo: AppRepository, sensor: HeartRateSource) {
    val data by repo.data.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    var working by remember { mutableStateOf(SessionSettings.DEFAULTS) }
    // Session-only preparation text: in memory only, never stored, exported or logged.
    var draft by remember { mutableStateOf<com.zq.commprep.domain.PrepDraft?>(null) }
    val ctx = LocalContext.current
    fun cameraGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var cameraOk by remember { mutableStateOf(cameraGranted()) }
    var cameraDenied by remember { mutableStateOf(false) }
    // The Android camera prompt is only ever launched from the "Allow camera" button (after consent).
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraOk = granted; cameraDenied = !granted
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                val d = data
                if (d == null) { Body("Loading…"); return@Column }
                if (d.needsConsentPrompt || screen == Screen.Consent) {
                    // Step 0 – consent is the only required step (checklist 5.1). Shown on first run, when the
                    // stored acceptance was given against an older notice (0.2.0 versioned consent), and when the
                    // user chooses to give consent from Settings. Always the full current notice + 18+ check.
                    ConsentScreen(isReconsent = d.consent == Consent.ACCEPTED) { accepted, adult ->
                        scope.launch { repo.recordConsent(accepted, adult) }
                        screen = Screen.Home
                    }
                    return@Column
                }
                val consent = d.effectiveConsent
                // SDK may only be used after consent + granted camera permission + configured key.
                val activeSensor: HeartRateSource =
                    if (CameraGate.canUseSdk(consent, cameraOk, sensor.isAvailable)) sensor else NoOpHeartRateSource
                val sensorText = CameraGate.statusText(consent, cameraOk, cameraDenied, sensor.isAvailable, sensor.name)
                if (screen != Screen.Home) BackHandler { screen = Screen.Home }

                fun ensureSession(): Long = sessionId ?: System.currentTimeMillis().also { sessionId = it }
                fun startRecommended(s: SessionSettings) {
                    sessionId = System.currentTimeMillis(); working = s; screen = Screen.Step(StepType.BASELINE)
                }
                fun onStepResult(r: StepResult) {
                    val sid = ensureSession()
                    working = when (r) {
                        is StepResult.Baseline -> working.copy(baselineSeconds = r.seconds)
                        is StepResult.Breathing -> working.copy(pace = r.pace, breathingSeconds = r.seconds)
                        is StepResult.Rehearsal -> working.copy(rehearsalSeconds = r.seconds)
                    }
                    val remembered = working
                    scope.launch {
                        repo.addEntry(sid, sid, r.entry)
                        // Remember last-used settings after a step actually ran (checklist 1.1).
                        if (!r.entry.skipped) repo.saveLastSettings(remembered)
                    }
                    screen = if (r is StepResult.Rehearsal && r.goToBreathing) Screen.Step(StepType.BREATHING)
                    else Screen.After(r.entry.step, r.entry.skipped)
                }

                when (val s = screen) {
                    Screen.Home -> HomeScreen(d, sensorText,
                        offerCamera = CameraGate.offerAllowCamera(consent, cameraOk, sensor.isAvailable),
                        onAllowCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                        onPrepare = { draft = null; screen = Screen.Choose },
                        onRecommended = { draft = null; startRecommended(SessionSettings.DEFAULTS) },
                        onUseMine = { draft = null; d.lastSettings?.let { startRecommended(it) } },
                        onStep = { ensureSession(); screen = Screen.Step(it) },
                        onTimeline = { screen = Screen.Timeline },
                        onSettings = { screen = Screen.Settings })
                    is Screen.Step -> androidx.compose.runtime.key(s.key) {
                        when (s.step) {
                            StepType.BASELINE -> BaselineStep(working.baselineSeconds, sensorText, activeSensor, ::onStepResult)
                            StepType.BREATHING -> BreathingStep(working.pace, working.breathingSeconds, sensorText, activeSensor, ::onStepResult)
                            StepType.REHEARSAL -> RehearsalStep(working.rehearsalSeconds, sensorText, cameraSource = sensor, draft = draft, onDone = ::onStepResult)
                        }
                    }
                    is Screen.After -> AfterStepScreen(s.step, s.skipped,
                        onNext = {
                            val i = RECOMMENDED_ORDER.indexOf(s.step)
                            screen = if (i < RECOMMENDED_ORDER.lastIndex) Screen.Step(RECOMMENDED_ORDER[i + 1])
                            else if (draft != null) { sessionId = null; Screen.Card }
                            else { sessionId = null; Screen.Timeline }
                        },
                        onChoose = { screen = Screen.Home })
                    Screen.Timeline -> TimelineScreen(d,
                        onDeleteAll = { scope.launch { repo.deleteAll() }; sessionId = null; working = SessionSettings.DEFAULTS; screen = Screen.Home },
                        onHome = { screen = Screen.Home })
                    Screen.Settings -> SettingsScreen(d, repo, onDeleted = {
                        sessionId = null; working = SessionSettings.DEFAULTS; screen = Screen.Home
                    }, onGiveConsent = { screen = Screen.Consent }, onHome = { screen = Screen.Home })
                    Screen.Consent -> {}   // handled above
                    Screen.Choose -> ChooseConversationScreen(onChoose = { screen = Screen.Build(it) }, onCancel = { screen = Screen.Home })
                    is Screen.Build -> BuildOpeningScreen(s.type, draft,
                        onReady = { d0 -> draft = d0; startRecommended(d.lastSettings ?: SessionSettings.DEFAULTS) },
                        onBack = { screen = Screen.Choose })
                    Screen.Card -> {
                        val d0 = draft
                        if (d0 != null) TakeawayCardScreen(d0, onDone = { draft = null; screen = Screen.Home })
                        else androidx.compose.runtime.LaunchedEffect(Unit) { screen = Screen.Home }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Step 0: Consent
/**
 * Consent notice v3 (three points, [com.zq.commprep.domain.ConsentPolicy.CURRENT_VERSION]). Wording follows the
 * Presage SmartSpectra SDK Privacy Policy (effective 3 Nov 2026) §3, §4.2–4.4, §7 and §13. Whether SDK
 * 3.4.0 itself triggers AI Insights is not verified, so the notice says what the current policy says.
 */
@Composable
fun ConsentScreen(isReconsent: Boolean = false, onDecision: (accepted: Boolean, adultConfirmed: Boolean) -> Unit) {
    var more by remember { mutableStateOf(false) }
    var adult by remember { mutableStateOf(false) }
    Section {
        Title(if (isReconsent) "Updated notice – please read again" else "Before you start")
        if (isReconsent) Body("The camera notice has changed since you last agreed. The camera check-in stays off until you accept this version.", "consent_updated")
        Body("1. Not medical, adults only. ZQ Settle helps you prepare for a difficult conversation with a quiet pause, " +
            "guided breathing and a short rehearsal. It does not assess health, stress, emotions, confidence or readiness, " +
            "and it is not a medical device. The optional camera check-in is for adults 18+ only.", "consent_point_purpose")
        Body("2. Camera and data. The camera stays off unless you accept below and then tap Allow camera. The Presage SmartSpectra SDK " +
            "then uses the front camera to estimate pulse and breathing. Images are processed on your phone; ZQ Settle does not record, " +
            "save or upload video or images. During a check-in the SDK sends Presage your derived measurements, session and usage " +
            "records, app and device authentication data (including hashed device identifiers) and your IP address. " +
            "SDK diagnostic telemetry is off.", "consent_sdk_data")
        Body("3. AI use and retention. Presage's SDK privacy policy says it automatically generates AI Insights from the measurements " +
            "using Anthropic; ZQ Settle does not show or store them, and we have not confirmed this for SDK 3.4.0. ZQ Settle has no " +
            "account, analytics or advertising. Your settings and session timeline stay on this phone until you delete them or " +
            "withdraw consent; that does not delete data held by Presage or files you exported. You can decline and still use every " +
            "step with the timer.", "consent_point_ai_retention")
        if (more) Body("More detail: timings and the settings you choose are stored only in this app's private storage. " +
            "You can delete everything at any time in Settings or on the timeline. You can withdraw consent later in Settings; " +
            "withdrawing stops measurement and deletes stored data on this phone. To ask Presage about data it holds, see Presage's SDK privacy policy. " +
            "Backup, export and restore are optional and only happen when you act. Encrypted backup is off by default: turn it on in Settings, " +
            "tap Back up now, set a passphrase and choose where to save the file with the phone's file picker (for example your own cloud drive). " +
            "The passphrase is never stored, and the backup can't be opened without it. Export saves an unencrypted copy of your data to a place you choose. " +
            "Restore reads a backup file you pick and needs its passphrase.", "consent_more_text")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(adult, { adult = it }, modifier = Modifier.testTag("consent_adult"))
            Body("I confirm I am 18 or older.")
        }
        Primary("Accept", "consent_accept", enabled = adult) { onDecision(true, true) }
        if (!adult) Body("To accept the camera check-in, first confirm you are 18 or older. You can decline and use the timers.", "consent_adult_needed")
        Secondary("Decline", "consent_decline") { onDecision(false, false) }
        SmallAction(if (more) "Show less" else "Read more", "consent_read_more") { more = !more }
    }
}

// ---------------------------------------------------------------- Home
@Composable
private fun HomeScreen(
    d: AppData, sensorText: String, offerCamera: Boolean, onAllowCamera: () -> Unit,
    onPrepare: () -> Unit, onRecommended: () -> Unit, onUseMine: () -> Unit,
    onStep: (StepType) -> Unit, onTimeline: () -> Unit, onSettings: () -> Unit,
) {
    Section {
        Title("ZQ Settle")
        SensorLine(sensorText)
        if (offerCamera) {
            Body("Optional: allow the front camera so the app can show a pulse estimate during the steps. " +
                "Android will ask you to confirm. You can say no and keep using the timers.", "camera_explainer")
            Secondary("Allow camera", "allow_camera", onClick = onAllowCamera)
        }
        Primary("Prepare a conversation", "start_prepare", onClick = onPrepare)
        Body("Choose a conversation → write your opening and request in your own words → optional pulse check-in → " +
            "breathing → camera-off rehearsal → your conversation card. Every step can be skipped; the camera is optional.")
        Secondary("Start recommended session (timers only, no opening)", "start_recommended", onClick = onRecommended)
        d.lastSettings?.let {
            Secondary("Use my settings", "use_my_settings", onClick = onUseMine)
            Body(it.summary(), "my_settings_summary")
        }
        HorizontalDivider()
        Body("Or start any step on its own:")
        Secondary("Quiet baseline", "step_list_baseline") { onStep(StepType.BASELINE) }
        Secondary("Guided breathing", "step_list_breathing") { onStep(StepType.BREATHING) }
        Secondary("Rehearsal", "step_list_rehearsal") { onStep(StepType.REHEARSAL) }
        HorizontalDivider()
        if (d.history.any { it.anyRan }) SmallAction("View timeline", "open_timeline", onClick = onTimeline)
        SmallAction("Settings", "open_settings", onClick = onSettings)
    }
}

@Composable
private fun AfterStepScreen(step: StepType, skipped: Boolean, onNext: () -> Unit, onChoose: () -> Unit) {
    Section {
        Title(if (skipped) "${step.title} skipped" else "${step.title} done")
        Primary("Next recommended step", "next_recommended", onClick = onNext)
        Secondary("Choose another / Finish", "choose_another", onClick = onChoose)
    }
}

// ---------------------------------------------------------------- Timeline (non-evaluative)
@Composable
private fun TimelineScreen(d: AppData, onDeleteAll: () -> Unit, onHome: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Section {
        Title("Timeline")
        val sessions = d.history.filter { it.entries.isNotEmpty() }.sortedByDescending { it.startedAtMillis }
        if (sessions.isEmpty()) Body("Nothing here yet.", "timeline_empty")
        sessions.forEachIndexed { idx, s ->
            Text(df.format(Date(s.startedAtMillis)), style = MaterialTheme.typography.titleSmall)
            if (s.noBaseline) Body("no baseline", "label_no_baseline_$idx")
            s.entries.forEachIndexed { j, e ->
                Text(e.displayText(),
                    color = if (e.skipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("entry_${idx}_$j"))
            }
            HorizontalDivider()
        }
        Secondary("Delete all data", "timeline_delete_all") { confirm = true }
        SmallAction("Home", "go_home", onClick = onHome)
    }
    if (confirm) ConfirmDelete(onConfirm = { confirm = false; onDeleteAll() }, onDismiss = { confirm = false })
}

@Composable
private fun ConfirmDelete(onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete all data?") },
    text = { Text("This removes your timeline, remembered settings, consent choice and backup setting from this device. Backup files you saved elsewhere are not touched.") },
    confirmButton = { TextButton(onConfirm, Modifier.testTag("confirm_delete")) { Text("Delete") } },
    dismissButton = { TextButton(onDismiss, Modifier.testTag("cancel_delete")) { Text("Cancel") } },
)

// ---------------------------------------------------------------- Settings
private enum class PassMode { BACKUP, RESTORE }

@Composable
private fun SettingsScreen(d: AppData, repo: AppRepository, onDeleted: () -> Unit, onGiveConsent: () -> Unit, onHome: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var passMode by remember { mutableStateOf<PassMode?>(null) }
    var pendingPass by remember { mutableStateOf<CharArray?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Export (checklist 6.6): only when the user taps it; location chosen in the system picker.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) { status = "Export cancelled."; return@rememberLauncherForActivityResult }
        scope.launch {
            status = runCatching {
                writeUri(ctx, uri, repo.snapshot().toJson().toString(2).toByteArray()); "Exported (not encrypted)."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    // Encrypted backup save (checklist 6.8, 6.9): encrypted before it is written to the chosen location.
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = pendingPass; pendingPass = null
        if (uri == null || pass == null) { pass?.fill('\u0000'); status = "Backup cancelled."; return@rememberLauncherForActivityResult }
        status = "Encrypting…"
        scope.launch {
            status = runCatching {
                val plain = repo.snapshot().toJson().toString().toByteArray()
                val enc = withContext(Dispatchers.Default) { BackupCrypto.encrypt(plain, pass) }
                writeUri(ctx, uri, enc); "Encrypted backup saved."
            }.getOrElse { "Backup failed: ${it.message}" }
            pass.fill('\u0000')
        }
    }
    // Restore (checklist 6.10): pick file, then ask for the passphrase.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { status = "Restore cancelled."; return@rememberLauncherForActivityResult }
        restoreBytes = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
        if (restoreBytes == null) status = "Could not read that file." else passMode = PassMode.RESTORE
    }

    Section {
        Title("Settings")
        Body("Consent: " + when (d.effectiveConsent) { Consent.ACCEPTED -> "given"; Consent.DECLINED -> "declined"; else -> "not decided" }, "consent_status")
        if (d.effectiveConsent == Consent.ACCEPTED) {
            Secondary("Withdraw consent (deletes stored data)", "withdraw_consent") {
                scope.launch { repo.withdrawConsent() }; status = "Consent withdrawn. Stored data deleted."; onDeleted()
            }
        } else {
            // 0.2.0: never records consent directly; opens the full current notice with the 18+ check.
            Secondary("Read the camera notice and give consent", "give_consent", onClick = onGiveConsent)
        }
        HorizontalDivider()
        Secondary("Delete all data", "settings_delete_all") { confirm = true }
        Secondary("Export my data (unencrypted file)", "export_data") { exportLauncher.launch("zq-settle-export.json") }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(d.backupEnabled, { on -> scope.launch { repo.setBackupEnabled(on) } }, Modifier.testTag("backup_switch"))
            Body("  Encrypted backup " + if (d.backupEnabled) "on" else "off", "backup_state")
        }
        Body("Backups are encrypted on this phone with a passphrase you choose. The passphrase is never stored; " +
            "without it the backup cannot be opened. You choose where to save it (for example your own cloud drive).")
        if (d.backupEnabled) {
            Primary("Back up now", "backup_now") { passMode = PassMode.BACKUP }
            // TODO(scheduled-backup): a schedule needs WorkManager + a persisted SAF tree URI, and – because the
            //  passphrase must never be stored – a way to encrypt without the user present (e.g. a public-key
            //  scheme). Not implemented; only "Back up now" is available.
            Body("Scheduled backups: not available in this version.", "schedule_stub")
        }
        Secondary("Restore from backup file", "restore_backup") { restoreLauncher.launch(arrayOf("*/*")) }
        status?.let { Text(it, modifier = Modifier.testTag("status_message"), color = MaterialTheme.colorScheme.primary) }
        SmallAction("Home", "go_home", onClick = onHome)
    }

    if (confirm) ConfirmDelete(onConfirm = {
        confirm = false; scope.launch { repo.deleteAll() }; onDeleted()
    }, onDismiss = { confirm = false })

    passMode?.let { mode ->
        PassphraseDialog(confirmField = mode == PassMode.BACKUP, onCancel = { passMode = null; restoreBytes = null }) { pass ->
            passMode = null
            if (mode == PassMode.BACKUP) {
                pendingPass = pass; backupLauncher.launch("zq-settle-backup.cpb")
            } else {
                val bytes = restoreBytes; restoreBytes = null
                status = "Decrypting…"
                scope.launch {
                    status = try {
                        val plain = withContext(Dispatchers.Default) { BackupCrypto.decrypt(bytes!!, pass) }
                        repo.replaceAll(AppData.fromJson(JSONObject(String(plain))))
                        "Restored settings, timeline and remembered settings."
                    } catch (e: BackupCrypto.WrongPassphraseOrCorruptException) {
                        e.message   // wrong passphrase fails safely: existing data untouched
                    } catch (e: Exception) {
                        "Could not restore: the file is not a valid backup. Nothing was changed."
                    } finally { pass.fill('\u0000') }
                }
            }
        }
    }
}

@Composable
private fun PassphraseDialog(confirmField: Boolean, onCancel: () -> Unit, onOk: (CharArray) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val tooShort = p1.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = confirmField && p1 != p2
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (confirmField) "Set a backup passphrase" else "Enter the backup passphrase") },
        text = {
            Column {
                OutlinedTextField(p1, { p1 = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("pass1"))
                if (confirmField) OutlinedTextField(p2, { p2 = it }, label = { Text("Repeat passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("pass2"))
                if (confirmField) Text("At least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters. If you forget it, the backup cannot be opened.")
                if (mismatch && p2.isNotEmpty()) Text("Passphrases don't match.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onOk(p1.toCharArray()) }, enabled = !tooShort && !mismatch, modifier = Modifier.testTag("pass_ok")) { Text("OK") }
        },
        dismissButton = { TextButton(onCancel, Modifier.testTag("pass_cancel")) { Text("Cancel") } },
    )
}

private fun writeUri(ctx: Context, uri: Uri, bytes: ByteArray) {
    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
}
