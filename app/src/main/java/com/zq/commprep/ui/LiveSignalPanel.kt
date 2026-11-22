package com.zq.commprep.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.zq.commprep.domain.LiveSignal
import com.zq.commprep.domain.SdkStatus
import com.zq.commprep.sensors.HeartRateSource
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Session measurement panel (0.1.3). [sensor] is already gated by CameraGate in CommPrepApp
 * (NoOp unless consent accepted + CAMERA granted + key present).
 * While [running] and the app is in the foreground: shows the live front-camera preview (display only,
 * never recorded or saved) and starts the SDK; stops it when the step ends, is skipped, or the app
 * goes to the background. Timer-only: no preview, a clear "Timer only - no live signal" line.
 *
 * 0.2.0: by default only a plain-language status and, when fresh and valid, a pulse estimate are
 * shown (no breathing value, no confidence, no score). SDK counters, confidence, codes and timing
 * are behind a collapsed "Signal details" control.
 */
@Composable
fun LiveSignalPanel(sensor: HeartRateSource, running: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val camera = sensor.isAvailable && running
    val ctx = LocalContext.current
    val previewView = remember(sensor) {
        if (sensor.isAvailable) PreviewView(ctx).apply {
            // 0.1.4: SurfaceView-backed preview (cheaper than TextureView on low-end GPUs) and
            // FIT_CENTER so the preview never draws outside its box (0.1.3 overlapped the text).
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        } else null
    }
    // Preview can be hidden (diagnostic A/B for frame-rate problems); changing it restarts the SDK.
    var showPreview by remember { mutableStateOf(true) }
    // User-initiated retry after an SDK error (Presage: do not restart automatically).
    var attempt by remember { mutableIntStateOf(0) }
    val measure = camera && foreground
    DisposableEffect(sensor, measure, showPreview, attempt) {
        if (measure) sensor.start(if (showPreview) previewView?.surfaceProvider else null)
        onDispose { if (measure) sensor.stop() }
    }

    if (!running) return
    val s by sensor.liveSignal.collectAsState()
    // Re-evaluate freshness every 500 ms so a value disappears once it is older than the window,
    // even if the SDK sends nothing more. Same clock as the SDK wrapper (elapsedRealtime).
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(measure) {
        while (measure) { nowMs = SystemClock.elapsedRealtime(); delay(500) }
    }
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (camera && previewView != null && showPreview) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(260.dp).clipToBounds().testTag("camera_preview"))
            Small("Camera preview (shown only, not recorded or saved)", "camera_preview_note")
        }
        if (camera && previewView != null) {
            SmallAction(if (showPreview) "Hide camera view" else "Show camera view", "camera_preview_toggle") { showPreview = !showPreview }
        }
        if (!camera) {
            Text(LiveSignal.TIMER_ONLY, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("live_signal_none"))
            return@Column
        }
        Text(LiveSignal.plainStatus(s, nowMs), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("sdk_status"))
        LiveSignal.vitalsText(s, nowMs)?.let { Small(it, "sdk_vitals") }
        if (s.status == SdkStatus.ERROR) SmallAction("Try the camera check-in again", "sdk_retry") { attempt++ }
        SmallAction(if (details) "Hide signal details" else "Signal details", "signal_details_toggle") { details = !details }
        if (details) {
            Small(LiveSignal.statusText(s), "sdk_status_detail")
            Small(LiveSignal.samplesText(s), "sdk_samples")
            Small(LiveSignal.confidenceText(s), "sdk_confidence")
            LiveSignal.hintText(s)?.let { Small("SDK check: $it", "sdk_hint") }
            LiveSignal.errorText(s)?.let { Small(it, "sdk_error") }
            Small(LiveSignal.diagnosticsText(s), "sdk_diagnostics")
            if (!s.isLive) Small(LiveSignal.TIMER_ONLY, "live_signal_none")
        }
    }
}

@Composable
private fun Small(text: String, tag: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
