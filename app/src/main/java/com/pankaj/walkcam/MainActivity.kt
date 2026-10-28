package com.pankaj.walkcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {

    private enum class Stab { PREVIEW, VIDEO, NONE }

    /** One camera mode to try. The list is ordered best-first; we fall back if the phone refuses one. */
    private data class Config(val quality: Quality, val fps: Int, val stab: Stab) {
        val resLabel: String
            get() = when (quality) { Quality.UHD -> "4K"; Quality.FHD -> "1080p"; else -> "Best" }
        val key: String get() = "$resLabel-$fps-${stab.name}"
    }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var recText: TextView
    private lateinit var modeText: TextView
    private lateinit var btnRecord: TextView
    private lateinit var btnEnd: TextView
    private lateinit var btnTorch: TextView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView

    // Camera state
    private lateinit var store: SessionStore
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraInfo: CameraInfo? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var stopping = false
    private var configs: List<Config> = emptyList()
    private var configIndex = 0
    private var activeConfig: Config? = null
    private var hasOis = false
    private var hasFlash = true
    private var bindGeneration = 0
    private var currentSegMs = 0L
    private var pendingEnd = false
    private var merging = false
    private var blink = false

    private val main = Handler(Looper.getMainLooper())
    private val fpsMeter = FpsMeter()
    private val torch = LowLightTorch { on ->
        main.post {
            if (store.torchMode == TorchMode.AUTO) camera?.cameraControl?.enableTorch(on)
            updateUi()
        }
    }

    /** Called by the camera for every frame: feeds the live fps meter and the auto-torch. */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            fpsMeter.onFrame(result.get(CaptureResult.SENSOR_TIMESTAMP))
            torch.onFrame(result.get(CaptureResult.SENSOR_SENSITIVITY))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            blink = !blink
            updateHud()
            main.postDelayed(this, 500)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true || hasPerm(Manifest.permission.CAMERA)) startCamera()
            else showStatus("Camera permission is needed. Allow it in Settings › Apps › Walk Cam.")
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        store = SessionStore(this)
        previewView = findViewById(R.id.preview)
        recText = findViewById(R.id.recText)
        modeText = findViewById(R.id.modeText)
        btnRecord = findViewById(R.id.btnRecord)
        btnEnd = findViewById(R.id.btnEnd)
        btnTorch = findViewById(R.id.btnTorch)
        overlay = findViewById(R.id.overlay)
        overlayText = findViewById(R.id.overlayText)

        btnRecord.setOnClickListener { toggleRecord() }
        btnEnd.setOnClickListener {
            if (store.active) Toast.makeText(this, "Hold the button to end & save", Toast.LENGTH_SHORT).show()
        }
        btnEnd.setOnLongClickListener { endSession(); true }
        btnTorch.setOnClickListener { cycleTorch() }
        torch.autoEnabled = store.torchMode == TorchMode.AUTO

        if (hasPerm(Manifest.permission.CAMERA)) startCamera()
        else permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        updateUi()
    }

    override fun onStart() {
        super.onStart()
        main.post(ticker)
        // Came back to the app: continue the session exactly where it stopped.
        if (videoCapture != null && store.active && store.wantRecording && recording == null && !merging) {
            startSegment()
        }
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        // Leaving the app: close the current clip cleanly. wantRecording stays true,
        // so recording continues automatically next time the app opens.
        if (recording != null && !stopping) stopSegment()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Either volume key = Start / Pause, handy while walking. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) toggleRecord()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- camera setup

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            val provider = try { future.get() } catch (e: Exception) {
                showStatus("Camera unavailable: ${e.message}"); return@addListener
            }
            cameraProvider = provider
            val info = try {
                CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
            } catch (e: Exception) { null }
            if (info == null) { showStatus("No back camera found"); return@addListener }
            cameraInfo = info
            hasFlash = info.hasFlashUnit()
            configs = buildConfigs(info)
            configIndex = configs.indexOfFirst { it.key == store.configKey }.coerceAtLeast(0)
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Best-first list: 4K60 stabilized → 4K30 stabilized → 1080p60 … → unstabilized. */
    private fun buildConfigs(info: CameraInfo): List<Config> {
        val caps = Recorder.getVideoCapabilities(info)
        val qualities = caps.getSupportedQualities(DynamicRange.SDR)
        val c2 = Camera2CameraInfo.from(info)

        val fpsRanges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
        val has60 = fpsRanges.any { it.upper >= 60 }

        val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val uhd60Possible = try {
            val minFrameNs = map?.getOutputMinFrameDuration(ImageFormat.PRIVATE, Size(3840, 2160)) ?: 0L
            minFrameNs == 0L || minFrameNs <= 16_700_000L
        } catch (e: Exception) { true } // unknown: just try it

        hasOis = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        val tiers = buildList {
            if (Quality.UHD in qualities) {
                if (has60 && uhd60Possible) add(Quality.UHD to 60)
                add(Quality.UHD to 30)
            }
            if (Quality.FHD in qualities) {
                if (has60) add(Quality.FHD to 60)
                add(Quality.FHD to 30)
            }
            if (isEmpty()) add(Quality.HIGHEST to 30)
        }
        val stabs = buildList {
            if (Preview.getPreviewCapabilities(info).isStabilizationSupported) add(Stab.PREVIEW)
            if (caps.isStabilizationSupported) add(Stab.VIDEO)
        }
        val stabilized = tiers.flatMap { (q, f) -> stabs.map { Config(q, f, it) } }
        val plain = tiers.map { (q, f) -> Config(q, f, Stab.NONE) }
        Log.i(TAG, "4K60 possible=$uhd60Possible has60=$has60 OIS=$hasOis stabs=$stabs qualities=$qualities")
        return stabilized + plain
    }

    private fun bitrateFor(c: Config): Int = when {
        c.quality == Quality.UHD && c.fps >= 60 -> 100_000_000
        c.quality == Quality.UHD -> 60_000_000
        c.fps >= 60 -> 32_000_000
        else -> 20_000_000
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (configIndex >= configs.size) {
            showStatus("This phone refused every camera mode. Try restarting the phone.")
            return
        }
        val cfg = configs[configIndex]
        val gen = ++bindGeneration
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        provider.unbindAll()
        videoCapture = null
        activeConfig = null
        fpsMeter.reset()

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_90
        val fpsRange = Range(cfg.fps, cfg.fps) // fixed fps: less motion blur when walking

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .setTargetRotation(rotation)
        Camera2Interop.Extender(previewBuilder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            if (hasOis) setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            setSessionCaptureCallback(captureCallback)
        }
        if (cfg.stab == Stab.PREVIEW) previewBuilder.setPreviewStabilizationEnabled(true)
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(cfg.quality))
            .setTargetVideoEncodingBitRate(bitrateFor(cfg))
            .build()
        val vcBuilder = VideoCapture.Builder(recorder)
            .setTargetFrameRate(fpsRange)
            .setTargetRotation(rotation)
        if (cfg.stab == Stab.VIDEO) vcBuilder.setVideoStabilizationEnabled(true)
        Camera2Interop.Extender(vcBuilder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            if (hasOis) setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }
        val vc = vcBuilder.build()

        val cam = try {
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
        } catch (e: Exception) {
            Log.w(TAG, "Mode ${cfg.key} refused: ${e.message}")
            tryNextConfig()
            return
        }
        camera = cam
        videoCapture = vc
        activeConfig = cfg
        Log.i(TAG, "Camera running in ${cfg.key}")

        cam.cameraInfo.cameraState.observe(this) { st ->
            if (gen != bindGeneration) return@observe
            if (st.error?.code == CameraState.ERROR_STREAM_CONFIG) {
                Log.w(TAG, "Mode ${cfg.key} failed at runtime, falling back")
                tryNextConfig()
            }
        }

        torch.reset()
        applyTorch()
        updateUi()

        if (store.active && store.wantRecording && recording == null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) startSegment()
    }

    private fun tryNextConfig() {
        configIndex++
        main.post { bindCamera() }
    }

    // ---------------------------------------------------------------- recording

    private fun toggleRecord() {
        if (merging) return
        if (recording != null && !stopping) {
            store.wantRecording = false   // Pause
            stopSegment()
        } else {
            if (!store.active) store.begin()
            store.wantRecording = true    // Start / Resume
            if (recording == null) startSegment() // else: previous clip is closing, it restarts itself
        }
        updateUi()
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        val vc = videoCapture ?: return
        if (recording != null || merging) return
        val file = store.nextSegmentFile()
        try {
            var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
            stopping = false
            recording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(ev, file) }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            recording = null
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
        updateUi()
    }

    private fun stopSegment() {
        val r = recording ?: return
        stopping = true
        r.stop()
    }

    private fun onRecordEvent(ev: VideoRecordEvent, file: File) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                activeConfig?.let { store.configKey = it.key }
                updateUi()
            }
            is VideoRecordEvent.Status -> {
                currentSegMs = ev.recordingStats.recordedDurationNanos / 1_000_000
            }
            is VideoRecordEvent.Finalize -> {
                val durMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                if (ev.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA || durMs == 0L) {
                    file.delete()
                } else {
                    store.totalMs = store.totalMs + durMs
                }
                if (ev.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
                    store.wantRecording = false
                    Toast.makeText(this, "Phone storage is full — recording paused", Toast.LENGTH_LONG).show()
                }
                currentSegMs = 0L
                recording = null
                stopping = false
                when {
                    pendingEnd -> { pendingEnd = false; finishSession() }
                    store.wantRecording && !merging &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> startSegment()
                }
                updateUi()
            }
            else -> Unit
        }
    }

    private fun endSession() {
        if (merging || !store.active) return
        store.wantRecording = false
        if (recording != null) {
            pendingEnd = true
            stopSegment()
        } else finishSession()
        updateUi()
    }

    /** Joins all clips of the session into one video in the gallery. */
    private fun finishSession() {
        val segments = store.segments()
        if (segments.isEmpty()) {
            store.clear(); updateUi()
            Toast.makeText(this, "Nothing was recorded", Toast.LENGTH_SHORT).show()
            return
        }
        merging = true
        overlay.visibility = View.VISIBLE
        overlayText.text = "Saving video (${segments.size} clip${if (segments.size > 1) "s" else ""})…"
        updateUi()
        val started = store.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val name = "WalkCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(started))
        val appCtx = applicationContext
        val st = store

        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val r = SegmentMerger.merge(appCtx, segments, name)
                    st.clear()
                    "Saved to Gallery › WalkCam\n${r.summary()}"
                } catch (e: Exception) {
                    Log.e(TAG, "merge failed", e)
                    try {
                        val n = SegmentMerger.exportSeparately(appCtx, segments, name)
                        st.clear()
                        "Saved as $n separate clips in Gallery › WalkCam (${e.message})"
                    } catch (e2: Exception) {
                        "Could not save yet (${e2.message}). Your clips are kept — try End again."
                    }
                }
            }
            merging = false
            overlay.visibility = View.GONE
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            updateUi()
        }
    }

    // ---------------------------------------------------------------- torch

    private fun cycleTorch() {
        store.torchMode = when (store.torchMode) {
            TorchMode.AUTO -> TorchMode.ON
            TorchMode.ON -> TorchMode.OFF
            TorchMode.OFF -> TorchMode.AUTO
        }
        torch.autoEnabled = store.torchMode == TorchMode.AUTO
        if (store.torchMode == TorchMode.AUTO) torch.reset()
        applyTorch()
        updateUi()
    }

    private fun applyTorch() {
        if (!hasFlash) return
        val on = when (store.torchMode) {
            TorchMode.ON -> true
            TorchMode.OFF -> false
            TorchMode.AUTO -> torch.isOn
        }
        camera?.cameraControl?.enableTorch(on)
    }

    // ---------------------------------------------------------------- UI

    private fun updateUi() {
        val rec = recording != null && !stopping
        btnRecord.text = when {
            rec -> "PAUSE"
            store.active -> "RESUME"
            else -> "START"
        }
        btnRecord.setBackgroundResource(if (rec) R.drawable.bg_record_active else R.drawable.bg_record)
        btnRecord.isEnabled = !merging && videoCapture != null
        btnRecord.alpha = if (btnRecord.isEnabled) 1f else 0.5f

        btnEnd.isEnabled = store.active && !merging
        btnEnd.alpha = if (btnEnd.isEnabled) 1f else 0.35f

        btnTorch.visibility = if (hasFlash) View.VISIBLE else View.GONE
        btnTorch.text = when (store.torchMode) {
            TorchMode.AUTO -> if (torch.isOn) "LIGHT AUTO • ON" else "LIGHT AUTO"
            TorchMode.ON -> "LIGHT ON"
            TorchMode.OFF -> "LIGHT OFF"
        }
        updateHud()
    }

    private fun updateHud() {
        val rec = recording != null && !stopping
        val ms = store.totalMs + currentSegMs
        recText.text = when {
            merging -> "SAVING…"
            rec -> (if (blink) "●  REC  " else "○  REC  ") + fmt(ms)
            store.active && store.wantRecording -> "●  RESUMING  " + fmt(ms)
            store.active -> "❚❚  PAUSED  " + fmt(ms)
            else -> "READY"
        }
        recText.setTextColor(if (rec) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())

        val cfg = activeConfig
        modeText.text = if (cfg == null) "Starting camera…" else {
            val stab = when (cfg.stab) {
                Stab.PREVIEW -> "Steady EIS"
                Stab.VIDEO -> "EIS"
                Stab.NONE -> "No EIS"
            } + if (hasOis) " + OIS" else ""
            val live = fpsMeter.fps
            val liveTxt = if (live > 1) String.format(Locale.US, " (live %.0f)", live) else ""
            "${cfg.resLabel} · ${cfg.fps} fps$liveTxt · $stab"
        }
    }

    private fun showStatus(msg: String) {
        modeText.text = msg
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object { private const val TAG = "WalkCam" }
}
