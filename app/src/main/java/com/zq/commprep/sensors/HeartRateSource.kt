package com.zq.commprep.sensors

import android.content.Context
import android.os.SystemClock
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import com.presagetech.smartspectra.CustomInput
import com.presagetech.smartspectra.FrameSubmissionResult
import com.presagetech.smartspectra.FrameTransform
import com.presagetech.smartspectra.ProcessingStatus
import com.presagetech.smartspectra.SmartSpectraConfig
import com.presagetech.smartspectra.SmartSpectraError
import com.presagetech.smartspectra.SmartSpectraException
import com.presagetech.smartspectra.SmartSpectraSdk
import com.presagetech.smartspectra.ValidationStatus
import com.presagetech.smartspectra.proto.MetricsProto
import com.zq.commprep.BuildConfig
import com.zq.commprep.domain.LiveSignal
import com.zq.commprep.domain.LiveSignalState
import com.zq.commprep.domain.MetricsUpdate
import com.zq.commprep.domain.RateSample
import com.zq.commprep.domain.SdkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Logcat tag for SDK status transitions (status and counts only; never configuration values). */
const val SDK_LOG_TAG = "SETTLE_SDK"

/**
 * Physiological measurement boundary for SETTLE.
 * Camera-off / declined consent / no key uses [NoOpHeartRateSource] (timer only).
 * The challenge build uses [SmartSpectraHeartRateSource] when a Presage API key is present.
 */
interface HeartRateSource {
    /** True only when a real measurement source is present and configured. */
    val isAvailable: Boolean
    val name: String
    /** Live-signal proof for the session screens (status, SDK update count, SDK confidence). */
    val liveSignal: StateFlow<LiveSignalState>
    /**
     * Start measuring for the current session step. [preview] is an on-screen preview surface
     * (display only; nothing is recorded or saved). Must only be called once CameraGate allows.
     */
    fun start(preview: Preview.SurfaceProvider?)
    fun stop()
    /**
     * 0.2.0-prep2: true from a start() request until the matching stop() has completed (camera released
     * as far as the SDK reports). Rehearsal waits for false before it can begin.
     */
    val cameraActive: StateFlow<Boolean>
}

/** Default implementation: measures nothing. Used for the decline path and builds without a key. */
object NoOpHeartRateSource : HeartRateSource {
    override val isAvailable = false
    override val name = "No sensor (timer-only)"
    override val liveSignal: StateFlow<LiveSignalState> = MutableStateFlow(LiveSignalState())
    override fun start(preview: Preview.SurfaceProvider?) {}
    override fun stop() {}
    override val cameraActive: StateFlow<Boolean> = MutableStateFlow(false)
}

/**
 * Presage SmartSpectra Android SDK v3.4.0 wrapper (SDK-owned front camera, headless pattern from
 * Presage's docs: configure SmartSpectraSdk.shared → useCamera(Front) → suspend start() / stop()).
 * Telemetry off. Pulse + breathing metrics requested; SETTLE requests no LLM Insights and supplies no
 * prompt text (whether SDK 3.4.0 generates Insights anyway is not verified – see the consent notice).
 * No expressions. imageOutput off. Obtain it via [heartRateSourceFor] (one instance per process).
 * API key comes from BuildConfig (gitignored local.properties or the PRESAGE_API_KEY env var at build time).
 * Nothing here runs at construction: the SDK is initialised lazily on the first start().
 */
class SmartSpectraHeartRateSource(
    private val appContext: Context,
    private val apiKey: String = BuildConfig.PRESAGE_API_KEY,
) : HeartRateSource {
    override val isAvailable: Boolean = apiKey.isNotBlank()
    override val name: String =
        if (isAvailable) "SmartSpectra (camera pulse + breathing)"
        else "SmartSpectra not configured (no Presage key in this build)"

    private val state = MutableStateFlow(LiveSignalState())
    private val active = MutableStateFlow(false)

    // 0.2.0-prep2-fpsgate-lowres: the app owns the front camera (CameraX) and feeds frames to the SDK via its
    // public custom-input API (useCustomInput + sendFrame(ImageProxy)), so the stream can be requested at a
    // small size (640x480, 4:3) and a fixed 30 fps AE range. The SDK-owned camera exposes no size/fps setting.
    private var customInput: CustomInput? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val camOwner = CamLifecycleOwner()
    // Log-only fps counter (frames actually delivered to the SDK, from camera timestamps); no frame data kept.
    private var fpsWindowStartNs = 0L
    private var fpsFrames = 0
    private var fpsRejected = 0
    private var fpsLastReject: String? = null
    override val cameraActive: StateFlow<Boolean> = active.asStateFlow()
    override val liveSignal: StateFlow<LiveSignalState> = state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleLock = Mutex()
    private var initialized = false
    /** True between our start() and stop(); SDK callbacks outside a session are ignored. */
    @Volatile private var sessionActive = false

    private fun now() = SystemClock.elapsedRealtime()
    private fun log(event: String) = Log.i(SDK_LOG_TAG, LiveSignal.logLine(event, state.value))
    private fun warn(event: String) = Log.w(SDK_LOG_TAG, LiveSignal.logLine(event, state.value))
    private fun safe(msg: String?) = LiveSignal.sanitize(msg, apiKey)

    private val statusObserver = Observer<ProcessingStatus?> { st ->
        if (st == null || !sessionActive && st != ProcessingStatus.IDLE) return@Observer
        val mapped = when (st) {
            ProcessingStatus.IDLE -> SdkStatus.IDLE
            ProcessingStatus.STARTING -> SdkStatus.STARTING
            ProcessingStatus.RUNNING -> SdkStatus.RUNNING
            ProcessingStatus.STOPPING -> SdkStatus.STOPPING
            ProcessingStatus.ERROR -> SdkStatus.ERROR
        }
        if (mapped != state.value.status) {
            state.value = LiveSignal.onStatus(state.value, mapped, now())
            if (mapped == SdkStatus.ERROR) {
                // The error LiveData may not have published yet; read whatever the SDK holds now.
                val e = runCatching { SmartSpectraSdk.shared.error.value }.getOrNull()
                if (e != null && state.value.errorCode == null)
                    state.value = LiveSignal.onError(state.value, e.code.name, safe(e.message), e.retryable, now())
                warn("status")
            } else log("status")
        }
    }
    private val validationObserver = Observer<ValidationStatus?> { v ->
        if (!sessionActive) return@Observer
        val prev = state.value.validationCode
        state.value = LiveSignal.onValidation(state.value, v?.code?.name, safe(v?.hint))
        if (prev != state.value.validationCode)
            Log.i(SDK_LOG_TAG, "event=validation code=${v?.code?.name} hint=\"${state.value.validationHint ?: ""}\" samples=${state.value.samples}")
    }
    private val errorObserver = Observer<SmartSpectraError?> { e ->
        if (e == null || !sessionActive) return@Observer
        state.value = LiveSignal.onError(state.value, e.code.name, safe(e.message), e.retryable, now())
        warn("error")
    }

    private fun toSample(m: MetricsProto.MeasurementWithConfidence?): RateSample? =
        m?.let { RateSample(it.value, it.confidence, it.stable, it.timestamp) }

    private fun onMetrics(metrics: MetricsProto.Metrics) {
        if (!sessionActive) return
        val pulse = if (metrics.hasCardio()) toSample(metrics.cardio.pulseRateList.lastOrNull { it.timestamp > 0 }) else null
        val breathing = if (metrics.hasBreathing()) toSample(metrics.breathing.rateList.lastOrNull { it.timestamp > 0 }) else null
        state.value = LiveSignal.onMetrics(state.value, MetricsUpdate(pulse, breathing), now())
        val n = state.value.samples
        if (n == 1 || n % 100 == 0) log("metrics")
    }

    private fun ensureInitialized() {
        if (initialized || !isAvailable) return
        // The SDK's own startup initializer is removed from the manifest (no init on app launch);
        // run it here, lazily, only once the consent + permission + key gate has been passed.
        // SmartSpectraInitializer is Kotlin-internal in the SDK, so it is referenced by name.
        @Suppress("UNCHECKED_CAST")
        val sdkInit = Class.forName("com.presagetech.smartspectra.SmartSpectraInitializer") as Class<out Initializer<Any>>
        AppInitializer.getInstance(appContext).initializeComponent(sdkInit)
        val config = SmartSpectraConfig().apply {
            this.apiKey = this@SmartSpectraHeartRateSource.apiKey
            enableTelemetry = false
            imageOutputEnabled = false
            requestedMetrics = SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics
        }
        val sdk = SmartSpectraSdk.initialize(appContext, config)
        customInput = sdk.useCustomInput(FrameTransform.NONE)
        sdk.processingStatus.observeForever(statusObserver)
        sdk.validationStatus.observeForever(validationObserver)
        sdk.error.observeForever(errorObserver)
        // metricsFlow delivers every update (LiveData may drop intermediate emits).
        scope.launch { sdk.metricsFlow.collect { onMetrics(it) } }
        initialized = true
        Log.i(SDK_LOG_TAG, "event=initialized sdkVersion=${runCatching { sdk.version }.getOrDefault("?")} telemetry=${sdk.config.enableTelemetry}")
    }

    override fun start(preview: Preview.SurfaceProvider?) {
        if (!isAvailable) return
        // Marked active immediately (not when the queued start runs) so a screen that waits for the camera
        // to be off can never see a stale "off" while a start is still pending; the matching stop() clears it.
        active.value = true
        scope.launch {
            lifecycleLock.withLock {
                try {
                    ensureInitialized()
                    val sdk = SmartSpectraSdk.shared
                    sessionActive = true
                    state.value = LiveSignal.startRequested(now())
                    log("start_requested preview=${preview != null}")
                    // Display-only preview surface; frames are never recorded or saved by SETTLE.
                    sdk.start()
                    bindCamera(preview)
                    log("start_returned")
                } catch (e: SmartSpectraException) {
                    state.value = LiveSignal.onError(state.value, e.error.code.name, safe(e.error.message), e.error.retryable, now())
                    warn("start_failed")
                } catch (e: Exception) {
                    state.value = LiveSignal.onError(state.value, e.javaClass.simpleName, safe(e.message), null, now())
                    warn("start_failed")
                }
            }
        }
    }

    override fun stop() {
        if (!isAvailable) return
        scope.launch {
            lifecycleLock.withLock {
                if (!initialized) { active.value = false; return@withLock }   // SDK never started: camera never opened
                log("stop_requested")
                runCatching { unbindCamera() }
                    .onFailure { Log.i(SDK_LOG_TAG, "event=camera_unbind_failed type=${it.javaClass.simpleName}") }
                runCatching { SmartSpectraSdk.shared.stop() }
                    .onFailure { Log.i(SDK_LOG_TAG, "event=stop_failed type=${it.javaClass.simpleName}") }
                sessionActive = false
                SmartSpectraSdk.shared.config.previewSurfaceProvider = null
                active.value = false
                // Keep an error visible after stop so the code stays on screen for diagnosis.
                if (state.value.status != SdkStatus.ERROR) state.value = state.value.copy(status = SdkStatus.IDLE)
                log("stopped")
            }
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = cameraProvider
        ?: suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val f = ProcessCameraProvider.getInstance(appContext)
            f.addListener({
                runCatching { f.get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(appContext))
        }.also { cameraProvider = it }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private suspend fun bindCamera(preview: Preview.SurfaceProvider?) {
        val input = customInput ?: return
        val provider = awaitProvider()
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(LOWRES_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val aBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setTargetRotation(Surface.ROTATION_0)
            .setOutputImageRotationEnabled(true)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        Camera2Interop.Extender(aBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, TARGET_FPS)
        val analysis = aBuilder.build()
        fpsWindowStartNs = 0L; fpsFrames = 0; fpsRejected = 0; fpsLastReject = null
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val r = input.sendFrame(image)
                countFrame(image, r)
            } finally {
                image.close()
            }
        }
        val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
        val pv = preview?.let { sp ->
            val pBuilder = Preview.Builder().setResolutionSelector(selector).setTargetRotation(Surface.ROTATION_0)
            Camera2Interop.Extender(pBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, TARGET_FPS)
            pBuilder.build().also { it.setSurfaceProvider(sp) }
        }
        pv?.let { useCases += it }
        camOwner.registry.currentState = Lifecycle.State.RESUMED
        provider.bindToLifecycle(camOwner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases.toTypedArray())
        analysisUseCase = analysis
        previewUseCase = pv
        Log.i(SDK_LOG_TAG, "event=camera_bound source=custom_input requested=${LOWRES_SIZE.width}x${LOWRES_SIZE.height} aeFps=$TARGET_FPS " +
            "analysis=${analysis.resolutionInfo?.resolution} preview=${pv?.resolutionInfo?.resolution}")
    }

    private fun unbindCamera() {
        analysisUseCase?.clearAnalyzer()
        val provider = cameraProvider
        listOfNotNull(analysisUseCase, previewUseCase).forEach { provider?.unbind(it) }
        analysisUseCase = null
        previewUseCase = null
        camOwner.registry.currentState = Lifecycle.State.CREATED
    }

    /** Runs on the analysis thread. Logs delivered-frame rate every ~5 s (counts and sizes only). */
    private fun countFrame(image: ImageProxy, r: FrameSubmissionResult) {
        val t = image.imageInfo.timestamp
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = t
        fpsFrames++
        if (r is FrameSubmissionResult.Rejected) { fpsRejected++; fpsLastReject = r.error.code.name }
        val dt = t - fpsWindowStartNs
        if (dt >= 5_000_000_000L) {
            val fps = (fpsFrames - 1) * 1e9 / dt
            Log.i(SDK_LOG_TAG, "event=fps cameraFps=${String.format(java.util.Locale.ROOT, "%.1f", fps)} frames=$fpsFrames " +
                "windowMs=${dt / 1_000_000} rejected=$fpsRejected" + (fpsLastReject?.let { " lastReject=$it" } ?: "") +
                " size=${image.width}x${image.height}")
            fpsWindowStartNs = t; fpsFrames = 1; fpsRejected = 0; fpsLastReject = null
        }
    }

    private class CamLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        val LOWRES_SIZE = Size(640, 480)
        val TARGET_FPS = Range(30, 30)
    }
}

/**
 * Creates its value at most once per process, however many times it is asked (thread-safe).
 * Used so an activity re-creation can never build a second SDK wrapper (0.2.0).
 */
class ProcessSingleton<T : Any>(private val factory: () -> T) {
    @Volatile private var instance: T? = null
    @Volatile var created = 0
        private set
    fun get(): T = instance ?: synchronized(this) {
        instance ?: factory().also { instance = it; created++ }
    }
}

private var sourceHolder: ProcessSingleton<HeartRateSource>? = null

/**
 * Pick the real source when a key is present; otherwise timer-only. 0.2.0: returns the same instance
 * for the whole process, so the SmartSpectra wrapper (and its SDK observers) exists at most once even if
 * MainActivity is re-created.
 */
@Synchronized
fun heartRateSourceFor(context: Context): HeartRateSource {
    val holder = sourceHolder ?: ProcessSingleton<HeartRateSource> {
        val key = BuildConfig.PRESAGE_API_KEY
        if (key.isNotBlank()) SmartSpectraHeartRateSource(context.applicationContext, key) else NoOpHeartRateSource
    }.also { sourceHolder = it }
    return holder.get()
}
