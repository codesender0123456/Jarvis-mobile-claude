package com.example.core.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.service.ScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

enum class VisionSource { NONE, CAMERA, SCREEN }

/**
 * Owns the live visual input. One source streams at a time (about 1 JPEG frame per second) so
 * the model is never unsure what a frame shows: the tool result names the source, and the
 * source labels are fixed:
 *   camera -> "shows the user and their room"
 *   screen -> "shows the user's phone screen"
 *
 * Camera frames come from CameraX (only while the app is on screen, which Android requires for
 * camera access). Screen frames come from [ScreenCaptureService] after the user approves the
 * system MediaProjection prompt.
 */
class VisionController(private val context: Context) {

    private val _active = MutableStateFlow(VisionSource.NONE)
    val active: StateFlow<VisionSource> = _active.asStateFlow()

    /** Receives JPEG frames; wired to the live session by JarvisApp. */
    @Volatile var frameSink: ((ByteArray) -> Unit)? = null
    /** Receives short bracketed status notes for the model (screen start/decline). */
    @Volatile var noteSink: ((String) -> Unit)? = null
    /** Set by MainActivity while it is alive; CameraX needs a lifecycle to bind to. */
    @Volatile var lifecycleOwner: LifecycleOwner? = null
    /** Set by MainActivity; shows the system screen-capture prompt. Returns false if it cannot. */
    @Volatile var screenCaptureRequester: (() -> Boolean)? = null

    private val analysisExecutor by lazy { Executors.newSingleThreadExecutor() }
    private val mainExecutor get() = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var lastCameraFrameAt = 0L

    // ---- Camera -------------------------------------------------------------------------

    fun startCamera(front: Boolean): String {
        val owner = lifecycleOwner
            ?: return "The JARVIS app has to be on screen before I can use the camera."
        stopAll()
        _active.value = VisionSource.CAMERA
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> onCameraImage(image) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    owner,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Camera failed to start", t)
                if (_active.value == VisionSource.CAMERA) _active.value = VisionSource.NONE
                noteSink?.invoke("[The camera failed to start, so no camera frames are arriving.]")
            }
        }, mainExecutor)
        val lens = if (front) "front" else "rear"
        return "Camera is on ($lens). Frames from the camera show the user and their room, not their phone screen."
    }

    fun stopCamera() {
        if (_active.value == VisionSource.CAMERA) _active.value = VisionSource.NONE
        mainExecutor.execute { runCatching { cameraProvider?.unbindAll() } }
    }

    private fun onCameraImage(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (_active.value != VisionSource.CAMERA || now - lastCameraFrameAt < FRAME_INTERVAL_MS) return
            lastCameraFrameAt = now
            publishFrame(encodeJpeg(image.toBitmap(), image.imageInfo.rotationDegrees), VisionSource.CAMERA)
        } catch (t: Throwable) {
            Log.w(TAG, "Dropping camera frame", t)
        } finally {
            image.close()
        }
    }

    // ---- Screen -------------------------------------------------------------------------

    /** Asks the user (system prompt) for screen capture; nothing is captured until they accept. */
    fun requestScreen(): String {
        if (_active.value == VisionSource.SCREEN) return "Screen sharing is already on."
        val shown = screenCaptureRequester?.invoke() ?: false
        return if (shown) {
            "I've asked for screen-capture permission on your display. Tap Start there to allow it; " +
                "until then I can't see the screen."
        } else {
            "Open the JARVIS app first, then ask again, so Android can show its screen-capture prompt."
        }
    }

    fun onScreenCaptureGranted(resultCode: Int, data: Intent) {
        stopCamera()
        ContextCompat.startForegroundService(context, ScreenCaptureService.startIntent(context, resultCode, data))
    }

    fun onScreenCaptureDenied() {
        noteSink?.invoke("[The user declined screen sharing. You cannot see their screen.]")
    }

    /** Called by [ScreenCaptureService] once frames are flowing. */
    fun onScreenCaptureStarted() {
        _active.value = VisionSource.SCREEN
        noteSink?.invoke("[Screen sharing started. Frames arriving now show the user's phone screen, not the camera.]")
    }

    fun onScreenCaptureStopped() {
        if (_active.value == VisionSource.SCREEN) _active.value = VisionSource.NONE
    }

    // ---- Shared -------------------------------------------------------------------------

    fun stopAll() {
        stopCamera()
        ScreenCaptureService.requestStop()
        _active.value = VisionSource.NONE
    }

    /** Frames from a source that is no longer the active one are discarded. */
    fun publishFrame(jpeg: ByteArray, source: VisionSource) {
        if (_active.value == source) frameSink?.invoke(jpeg)
    }

    /** Downscales to [MAX_SIDE], applies [rotationDegrees], returns a JPEG. */
    fun encodeJpeg(src: Bitmap, rotationDegrees: Int): ByteArray {
        val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        val bytes = ByteArrayOutputStream().use {
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            it.toByteArray()
        }
        if (out !== src) out.recycle()
        return bytes
    }

    companion object {
        private const val TAG = "VisionController"
        const val FRAME_INTERVAL_MS = 1_000L
        private const val MAX_SIDE = 768
        private const val JPEG_QUALITY = 60
    }
}
