package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.example.JarvisApp
import com.example.MainActivity
import com.example.R
import com.example.core.vision.VisionController
import com.example.core.vision.VisionSource

/**
 * Foreground service (type mediaProjection) that turns the screen into ~1 JPEG frame per second
 * for the live session. Android 14+ requires the service to be foreground BEFORE the projection
 * is created, and the consent result can be used once, so the order below matters.
 *
 * Always visible to the user (ongoing notification with a Stop button) and self-limiting:
 * it stops itself after [MAX_DURATION_MS].
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        promoteToForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopCapture()
            return
        }
        instance = this
        stopped = false
        val vision = (application as JarvisApp).visionController

        try {
            val ht = HandlerThread("jarvis-screen-capture").also { it.start() }
            thread = ht
            val h = Handler(ht.looper)
            handler = h

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj
            // Required on Android 14+ before createVirtualDisplay; also fires if the user stops
            // sharing from the system status-bar chip.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, h)

            val metrics = resources.displayMetrics
            val scale = minOf(1f, MAX_SIDE / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
            val hgt = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and 1.inv()

            val imageReader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ r ->
                val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameAt < VisionController.FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastFrameAt = now
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * image.width
                    val padded = Bitmap.createBitmap(
                        image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val frame = if (rowPadding == 0) padded
                    else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
                    vision.publishFrame(vision.encodeJpeg(frame, 0), VisionSource.SCREEN)
                    frame.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "Dropping screen frame", t)
                } finally {
                    image.close()
                }
            }, h)

            display = proj.createVirtualDisplay(
                "jarvis-screen", w, hgt, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, h
            )
            h.postDelayed({ stopCapture() }, MAX_DURATION_MS)
            vision.onScreenCaptureStarted()
        } catch (t: Throwable) {
            Log.e(TAG, "Screen capture failed to start", t)
            stopCapture()
        }
    }

    private fun promoteToForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS can see your screen")
            .setContentText("Screen sharing stops automatically after 10 minutes.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun stopCapture() {
        if (stopped) return
        stopped = true
        runCatching { display?.release() }
        runCatching { reader?.setOnImageAvailableListener(null, null); reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
        thread?.quitSafely(); thread = null; handler = null
        (application as? JarvisApp)?.visionController?.onScreenCaptureStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "jarvis_screen_capture"
        private const val ACTION_START = "com.example.action.START_SCREEN_CAPTURE"
        private const val ACTION_STOP = "com.example.action.STOP_SCREEN_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val MAX_SIDE = 768f
        private const val MAX_DURATION_MS = 10 * 60 * 1000L

        @Volatile private var instance: ScreenCaptureService? = null

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        /** Stops an active capture; a no-op when none is running. */
        fun requestStop() { instance?.stopCapture() }
    }
}
