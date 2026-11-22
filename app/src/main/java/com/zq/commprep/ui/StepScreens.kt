package com.zq.commprep.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zq.commprep.domain.BaselineRules
import com.zq.commprep.domain.BreathingRules
import com.zq.commprep.domain.Pace
import com.zq.commprep.domain.RehearsalRules
import com.zq.commprep.domain.StepType
import com.zq.commprep.domain.TimelineEntry
import com.zq.commprep.domain.formatDuration
import com.zq.commprep.sensors.HeartRateSource
import com.zq.commprep.sensors.NoOpHeartRateSource
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zq.commprep.domain.PrepDraft
import com.zq.commprep.domain.RehearsalGate
import com.zq.commprep.domain.StepClock

/** Result of a step: the timeline entry plus the value the user chose (for "Use my settings"). */
sealed interface StepResult {
    val entry: TimelineEntry
    data class Baseline(override val entry: TimelineEntry, val seconds: Int) : StepResult
    data class Breathing(override val entry: TimelineEntry, val pace: Pace, val seconds: Int?) : StepResult
    data class Rehearsal(override val entry: TimelineEntry, val seconds: Int, val goToBreathing: Boolean = false) : StepResult
}

/** Monotonic "now" (elapsedRealtime), refreshed every 200 ms while [active]. Display refresh only: */
/** elapsed time always comes from [StepClock] timestamps, so a late refresh never loses time. */
@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        now = SystemClock.elapsedRealtime()
        while (active) { delay(200); now = SystemClock.elapsedRealtime() }
    }
    return now
}

private fun mono() = SystemClock.elapsedRealtime()

/** Pauses the step timer when the app goes to the background (screen off, Home, incoming call). */
@Composable
private fun PauseOnBackground(onBackground: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) onBackground() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
private fun PauseResume(clock: StepClock, tag: String, onChange: (StepClock) -> Unit) {
    if (clock.isPaused) Body("Paused. The timer and any camera check-in are stopped.", "${tag}_paused")
    ButtonRow {
        SmallAction(if (clock.isPaused) "Resume" else "Pause", "${tag}_pause") {
            onChange(if (clock.isPaused) clock.resume(mono()) else clock.pause(mono()))
        }
    }
}

@Composable
fun SensorLine(text: String) = Text(text, style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sensor_status"))

// ---------------------------------------------------------------- Step 1: Quiet baseline
@Composable
fun BaselineStep(initialSeconds: Int, sensorText: String, sensor: HeartRateSource = NoOpHeartRateSource, onDone: (StepResult) -> Unit) {
    var seconds by remember { mutableIntStateOf(initialSeconds.takeIf(BaselineRules::isValid) ?: BaselineRules.DEFAULT_SECONDS) }
    var clock by remember { mutableStateOf(StepClock()) }
    var finished by remember { mutableStateOf(false) }
    val running = clock.started && !finished
    val now = rememberNow(clock.isRunning)
    val elapsed = clock.elapsedSeconds(now)
    fun finish(skipped: Boolean) {
        if (finished) return
        val ran = if (skipped) 0 else clock.elapsedSeconds(mono()).coerceAtMost(seconds)
        finished = true; clock = clock.pause(mono())
        onDone(StepResult.Baseline(TimelineEntry(StepType.BASELINE, skipped, ran, ""), seconds))
    }
    LaunchedEffect(now, clock) { if (running && clock.isDone(now, seconds)) finish(false) }
    PauseOnBackground { clock = clock.pause(mono()) }

    Section {
        Title("Quiet pause (optional pulse check-in)")
        Why("A minute of stillness before you start. If the camera check-in is on, a pulse estimate is shown only while it is fresh. It is not a health or stress measure.")
        SensorLine(sensorText)
        // Camera check-in only while the timer actually runs (stopped while paused).
        LiveSignalPanel(sensor, running && clock.isRunning)
        if (!running) {
            Body("Length: ${formatDuration(seconds)}", "baseline_length")
            ButtonRow {
                SmallAction("−15 s", "baseline_minus", seconds > BaselineRules.MIN_SECONDS) { seconds = BaselineRules.decrease(seconds) }
                SmallAction("+15 s", "baseline_plus", seconds < BaselineRules.MAX_SECONDS) { seconds = BaselineRules.increase(seconds) }
            }
            Body("Choose 30 to 120 seconds, in 15-second steps.")
            Primary("Start", "baseline_start") { clock = StepClock().start(mono()) }
        } else {
            Body("Sit still and breathe normally.")
            Text("${clock.remainingSeconds(now, seconds)} s left", style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("baseline_remaining"))
            PauseResume(clock, "baseline") { clock = it }
            Secondary("End early", "step_end_early") { finish(false) }
        }
        // Skip is always visible, before and during (checklist 2.4).
        SmallAction("Skip", "step_skip") { finish(true) }
    }
}

// ---------------------------------------------------------------- Step 2: Guided breathing
@Composable
fun BreathingStep(initialPace: Pace, initialSeconds: Int?, sensorText: String, sensor: HeartRateSource = NoOpHeartRateSource, onDone: (StepResult) -> Unit) {
    var pace by remember { mutableStateOf(initialPace) }
    var inText by remember { mutableStateOf(initialPace.inCount.toString()) }
    var outText by remember { mutableStateOf(initialPace.outCount.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var length by remember { mutableStateOf(initialSeconds) }
    var clock by remember { mutableStateOf(StepClock()) }
    var finished by remember { mutableStateOf(false) }
    val running = clock.started && !finished
    val paused = clock.isPaused
    val now = rememberNow(clock.isRunning)
    val elapsed = clock.elapsedSeconds(now)

    fun finish(skipped: Boolean) {
        if (finished) return
        val l = length
        val ran = if (skipped) 0 else clock.elapsedSeconds(mono()).let { if (l != null) it.coerceAtMost(l) else it }
        finished = true; clock = clock.pause(mono())
        onDone(StepResult.Breathing(TimelineEntry(StepType.BREATHING, skipped, ran, pace.label), pace, length))
    }
    LaunchedEffect(now, clock) { if (running && clock.isDone(now, length)) finish(false) }
    PauseOnBackground { clock = clock.pause(mono()) }

    fun setPace(p: Pace) { pace = p; inText = p.inCount.toString(); outText = p.outCount.toString(); error = null }
    fun currentError(): String? =
        if (BreathingRules.canStart(inText, outText)) null else BreathingRules.OUT_OF_RANGE_MESSAGE
    fun editIn(t: String) {
        inText = t
        pace = pace.copy(inCount = BreathingRules.applyEdit(t, pace.inCount).selected)
        error = currentError()
    }
    fun editOut(t: String) {
        outText = t
        pace = pace.copy(outCount = BreathingRules.applyEdit(t, pace.outCount).selected)
        error = currentError()
    }

    Section {
        Title("Guided breathing")
        Why("Follow a comfortable pace, or breathe naturally. You can stop at any time.")
        SensorLine(sensorText)
        // 0.2.0-prep2: pausing stops the camera check-in; it restarts (and re-acquires) on resume.
        LiveSignalPanel(sensor, running && !paused)
        if (running && paused) Body("Camera check-in stopped while paused.", "breathing_camera_paused")
        Body(BreathingRules.COMFORT_NOTE, "comfort_note")
        if (!running) {
            Body("Pace (seconds per phase in / out):")
            Row {
                BreathingRules.PRESETS.forEach { p ->
                    FilterChip(selected = pace == p && error == null, onClick = { setPace(p) },
                        label = { Text(p.label + if (p == BreathingRules.DEFAULT_PACE) " (default)" else "") },
                        modifier = Modifier.testTag("pace_${p.inCount}_${p.outCount}").width(if (p == BreathingRules.DEFAULT_PACE) 112.dp else 64.dp))
                }
            }
            Body("Custom pace (whole seconds 3–7 per phase):")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction("−", "custom_in_minus", pace.inCount > 3) { setPace(pace.copy(inCount = BreathingRules.decrease(pace.inCount))) }
                OutlinedTextField(inText, ::editIn, label = { Text("In") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(72.dp).testTag("custom_in"))
                SmallAction("+", "custom_in_plus", pace.inCount < 7) { setPace(pace.copy(inCount = BreathingRules.increase(pace.inCount))) }
                SmallAction("−", "custom_out_minus", pace.outCount > 3) { setPace(pace.copy(outCount = BreathingRules.decrease(pace.outCount))) }
                OutlinedTextField(outText, ::editOut, label = { Text("Out") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(72.dp).testTag("custom_out"))
                SmallAction("+", "custom_out_plus", pace.outCount < 7) { setPace(pace.copy(outCount = BreathingRules.increase(pace.outCount))) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pace_error")) }
            Body("Selected pace: ${pace.label} (about ${"%.1f".format(pace.breathsPerMinute)} breaths/min)", "selected_pace")
            Body("Length:")
            Row {
                BreathingRules.LENGTH_OPTIONS.forEach { opt ->
                    FilterChip(selected = length == opt, onClick = { length = opt },
                        label = { Text(opt?.let { "$it s" } ?: "Until I stop") },
                        modifier = Modifier.testTag("len_${opt ?: "until"}"))
                }
            }
            val canStart = BreathingRules.canStart(inText, outText)
            Primary("Start", "breathing_start", enabled = canStart) { clock = StepClock().start(mono()) }
        } else {
            val ph = BreathingRules.phaseAt(elapsed, pace)
            Text("${ph.name} … ${ph.count}", style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("breath_phase"))
            Body("Pace ${pace.label}" + (length?.let { " · ${clock.remainingSeconds(now, it)} s left" } ?: " · ${elapsed} s (until you stop)"), "breathing_remaining")
            PauseResume(clock, "breathing") { clock = it }
            Secondary("End early", "step_end_early") { finish(false) }
        }
        SmallAction("Skip", "step_skip") { finish(true) }
    }
}

// ---------------------------------------------------------------- Step 3: Rehearsal
@Composable
fun RehearsalStep(
    initialSeconds: Int, sensorText: String,
    /** The process-wide camera source: used only to make sure the camera is off, never to start it. */
    cameraSource: HeartRateSource = NoOpHeartRateSource,
    draft: PrepDraft? = null,
    onDone: (StepResult) -> Unit,
) {
    var total by remember { mutableIntStateOf(initialSeconds.coerceIn(RehearsalRules.DEFAULT_SECONDS, RehearsalRules.CEILING_SECONDS)) }
    var clock by remember { mutableStateOf(StepClock()) }
    var finished by remember { mutableStateOf(false) }
    val running = clock.started && !finished
    val now = rememberNow(clock.isRunning)
    // 0.2.0-prep2 camera-off rehearsal: no LiveSignalPanel here, and an explicit stop on entry (idempotent)
    // so the SDK/camera is released before rehearsal can start. Start waits until the source reports off.
    LaunchedEffect(cameraSource) { cameraSource.stop() }
    val cameraActive by cameraSource.cameraActive.collectAsState()
    var rateDistress by remember { mutableStateOf(false) }
    var distress by remember { mutableIntStateOf(0) }
    var smallerPart by remember { mutableStateOf(false) }

    fun finish(skipped: Boolean, toBreathing: Boolean = false) {
        if (finished) return
        val ran = if (skipped) 0 else clock.elapsedSeconds(mono()).coerceAtMost(total)
        finished = true; clock = clock.pause(mono())
        // 0.2.0: the optional 0–10 self-rating is session-only, never saved or exported
        onDone(StepResult.Rehearsal(TimelineEntry(StepType.REHEARSAL, skipped, ran, ""), total, toBreathing))
    }
    LaunchedEffect(now, clock, total) { if (running && clock.isDone(now, total)) finish(false) }
    PauseOnBackground { clock = clock.pause(mono()) }

    Section {
        Title("Rehearsal")
        Why("Briefly picturing the conversation can help the real moment feel more familiar.")
        Text(RehearsalGate.cameraText(cameraActive), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(if (cameraActive) "rehearsal_camera_stopping" else "rehearsal_camera_off"))
        draft?.let {
            Body("Your opening:", "rehearsal_opening_label")
            Text(it.opening, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("rehearsal_opening"))
            Body("Your request: ${it.request}", "rehearsal_request")
            Body("Say it out loud or in your head. Nothing is recorded.")
        }
        RehearsalRules.PROMPTS.forEachIndexed { i, p -> Body("${i + 1}. $p") }
        if (smallerPart) Body("Pick just one small part of the situation to picture this time.", "smaller_part_note")
        Body(if (running) "${clock.remainingSeconds(now, total)} s left of ${formatDuration(total)}" else "Length: ${formatDuration(total)}", "rehearsal_length")
        SmallAction("Extend (+30 s)", "rehearsal_extend", RehearsalRules.canExtend(total)) { total = RehearsalRules.extend(total) }
        if (!RehearsalRules.canExtend(total)) Body("10 minutes is the suggested maximum.", "rehearsal_ceiling")
        if (!running) {
            Primary("Start", "rehearsal_start", enabled = RehearsalGate.canStart(cameraActive)) { clock = StepClock().start(mono()) }
        } else {
            PauseResume(clock, "rehearsal") { clock = it }
            Secondary("End early", "step_end_early") { finish(false) }
        }
        SmallAction("Skip", "step_skip") { finish(true) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rateDistress, { rateDistress = it }, modifier = Modifier.testTag("distress_toggle"))
            Body("Rate how distressed you feel (optional, 0–10; only used on this screen, not saved)")
        }
        if (rateDistress) {
            Body("Distress: $distress / 10", "distress_value")
            ButtonRow {
                SmallAction("−", "distress_minus", distress > RehearsalRules.DISTRESS_MIN) { distress-- }
                Slider(value = distress.toFloat(), onValueChange = { distress = it.toInt() },
                    valueRange = RehearsalRules.DISTRESS_MIN.toFloat()..RehearsalRules.DISTRESS_MAX.toFloat(), steps = 9,
                    modifier = Modifier.width(200.dp).testTag("distress_slider"))
                SmallAction("+", "distress_plus", distress < RehearsalRules.DISTRESS_MAX) { distress++ }
            }
            val s = RehearsalRules.suggestionsFor(distress)
            if (s.isNotEmpty()) {
                Body("That sounds hard. You could:", "distress_suggestions")
                SmallAction(s[0], "suggest_end") { finish(false) }
                SmallAction(s[1], "suggest_breathing") { finish(false, toBreathing = true) }
                SmallAction(s[2], "suggest_smaller") { smallerPart = true; if (clock.started) clock = StepClock().start(mono()) }
            }
        }
    }
}
