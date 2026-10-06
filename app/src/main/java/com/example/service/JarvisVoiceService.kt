package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.JarvisApp
import com.example.MainActivity
import com.example.R
import com.example.SessionOrigin
import com.example.core.config.AppPreferences
import com.example.core.wake.SpeechWakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one foreground (microphone) service. It is the legal host that keeps the microphone usable
 * while the app is in the background, and it runs in one of three modes:
 *
 *  WAKE    listens for "Hey Jarvis" (real phrase recognition); on a match it starts a full session.
 *  SESSION hosts a live Gemini session. The wake detector is stopped first, so there is only ever
 *          ONE microphone user (the session's AudioEngine). Ends after a few idle minutes.
 *  ASLEEP  after [SLEEP_AFTER_MS] with no speech the microphone is fully released. Nothing is
 *          listening, so "Hey Jarvis" cannot wake it: tap the notification, the tile or the
 *          bubble. (A real low-power wake word needs a dedicated on-device model.)
 */
class JarvisVoiceService : Service() {

    private enum class Mode { WAKE, SESSION, ASLEEP }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detector: SpeechWakeWordDetector? = null
    private var sessionWatch: Job? = null
    private var mode = Mode.WAKE
    @Volatile private var lastSpeechAt = SystemClock.elapsedRealtime()

    private val app get() = application as JarvisApp

    private val sleepCheck = object : Runnable {
        override fun run() {
            if (mode == Mode.WAKE && SystemClock.elapsedRealtime() - lastSpeechAt > SLEEP_AFTER_MS) {
                enterAsleep()
            } else if (mode == Mode.WAKE) {
                main.postDelayed(this, 10_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_WAKE
        // startForegroundService() obliges us to call startForeground() quickly, whatever happens.
        if (!enterForeground(notificationFor(if (action == ACTION_HOST_SESSION) Mode.SESSION else Mode.WAKE))) {
            stopSelf(); return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> { app.stopAssistantSession(); stopSelf() }
            ACTION_HOST_SESSION -> enterSession()
            ACTION_WAKE_NOW -> {
                // From the "Asleep" notification: the user tapped, so start a conversation directly.
                app.startAssistantSession(SessionOrigin.WAKE)
            }
            else -> scope.launch {
                if (AppPreferences(applicationContext).settingsFlowOnce().wakeWordEnabled) enterWake() else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---- Modes --------------------------------------------------------------------------

    private fun enterWake() {
        mode = Mode.WAKE
        sessionWatch?.cancel()
        notify(notificationFor(Mode.WAKE))
        lastSpeechAt = SystemClock.elapsedRealtime()
        detector?.stop()
        detector = SpeechWakeWordDetector(
            context = this,
            assistantName = { currentAssistantName },
            onWake = { onWakeWord() },
            onSpeechHeard = { lastSpeechAt = SystemClock.elapsedRealtime() },
            onUnavailable = { reason ->
                Log.w(TAG, reason)
                app.liveSession.addLog("error", reason)
                stopSelf()
            }
        ).also { it.start() }
        main.removeCallbacks(sleepCheck)
        main.postDelayed(sleepCheck, 10_000L)
        scope.launch { currentAssistantName = AppPreferences(applicationContext).settingsFlowOnce().assistantName }
    }

    @Volatile private var currentAssistantName = "Jarvis"

    private fun onWakeWord() {
        Log.d(TAG, "Wake phrase heard; starting a session")
        detector?.stop(); detector = null // release the microphone before the session opens it
        main.postDelayed({ app.startAssistantSession(SessionOrigin.WAKE) }, 250L)
    }

    private fun enterSession() {
        mode = Mode.SESSION
        main.removeCallbacks(sleepCheck)
        detector?.stop(); detector = null
        notify(notificationFor(Mode.SESSION))

        sessionWatch?.cancel()
        sessionWatch = scope.launch {
            val log = app.liveSession.activityLog
            var lastSize = log.value.size
            var lastChangeAt = SystemClock.elapsedRealtime()
            // Poll cheaply; the session itself is event driven.
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                if (!app.liveSession.isWanted) break
                if (log.value.size != lastSize) { lastSize = log.value.size; lastChangeAt = SystemClock.elapsedRealtime() }
                if (SystemClock.elapsedRealtime() - lastChangeAt > SESSION_IDLE_END_MS) {
                    app.liveSession.addLog("system", "Ending the session after a few quiet minutes.")
                    app.stopAssistantSession()
                    break
                }
            }
            sessionEnded()
        }
    }

    private fun sessionEnded() {
        scope.launch {
            if (AppPreferences(applicationContext).settingsFlowOnce().wakeWordEnabled) enterWake() else stopSelf()
        }
    }

    private fun enterAsleep() {
        mode = Mode.ASLEEP
        detector?.stop(); detector = null // microphone fully released
        main.removeCallbacks(sleepCheck)
        notify(notificationFor(Mode.ASLEEP))
        Log.d(TAG, "Asleep: microphone released")
    }

    // ---- Notification -------------------------------------------------------------------

    private fun notificationFor(m: Mode): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS background listener", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, JarvisVoiceService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
        when (m) {
            Mode.WAKE -> b.setContentTitle("JARVIS is listening for the wake word")
                .setContentText("Say \"Hey Jarvis\". Sleeps after 2 quiet minutes.")
                .addAction(0, "Stop", action(ACTION_STOP, 11))
            Mode.SESSION -> b.setContentTitle("JARVIS is in a conversation")
                .setContentText("The microphone is open. Tap Stop to end it.")
                .addAction(0, "Stop", action(ACTION_STOP, 11))
            Mode.ASLEEP -> b.setContentTitle("JARVIS is asleep")
                .setContentText("The microphone is off. Tap Wake to talk.")
                .addAction(0, "Wake", action(ACTION_WAKE_NOW, 12))
                .addAction(0, "Stop", action(ACTION_STOP, 11))
        }
        return b.build()
    }

    private fun notify(n: Notification) {
        if (NotificationGate.canPost(this)) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
        }
    }

    private fun enterForeground(n: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, n)
            true
        } catch (e: Exception) {
            // SecurityException (no RECORD_AUDIO) or ForegroundServiceStartNotAllowedException.
            Log.e(TAG, "Could not enter foreground", e)
            false
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        detector?.stop(); detector = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisVoiceService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "jarvis_voice_service_channel"
        const val EXTRA_START_LISTENING = "EXTRA_START_LISTENING"

        const val ACTION_START_WAKE = "com.example.action.START_WAKE"
        const val ACTION_HOST_SESSION = "com.example.action.HOST_SESSION"
        const val ACTION_WAKE_NOW = "com.example.action.WAKE_NOW"
        const val ACTION_STOP = "com.example.action.STOP_SERVICE"

        const val SLEEP_AFTER_MS = 120_000L
        const val SESSION_IDLE_END_MS = 180_000L

        private fun hasMic(context: Context) = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        private fun launch(context: Context, action: String): Boolean {
            // Checked before startForegroundService: once started this way the service MUST call
            // startForeground() within seconds or Android kills the process.
            if (!hasMic(context)) { Log.w(TAG, "RECORD_AUDIO not granted; not starting"); return false }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, JarvisVoiceService::class.java).setAction(action))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Foreground start refused", e); false
            }
        }

        /** Starts the wake-word listener. */
        fun start(context: Context): Boolean = launch(context, ACTION_START_WAKE)

        /** Starts (or switches to) hosting a live session; called before the session opens the mic. */
        fun hostSession(context: Context): Boolean = launch(context, ACTION_HOST_SESSION)

        fun stop(context: Context) { context.stopService(Intent(context, JarvisVoiceService::class.java)) }
    }
}
