package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.JarvisApp
import com.example.MainActivity
import com.example.R
import com.example.core.config.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // WorkManager restores reminders itself; the briefing is armed from code.
                (appContext as? JarvisApp)?.rescheduleDailyBriefing() ?: DailyBriefingWorker.scheduleDaily(appContext)

                val settings = AppPreferences(appContext).settingsFlowOnce()
                val hasMic = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

                when (BootPolicy.decide(settings.autoStartOnBoot, hasMic, Build.VERSION.SDK_INT)) {
                    BootAction.START_WAKE_SERVICE ->
                        runCatching { JarvisVoiceService.start(appContext) }
                            .onFailure { Log.w(TAG, "Could not start listener after boot", it) }
                    BootAction.POST_TAP_TO_START -> postTapToStart(appContext)
                    BootAction.NONE -> Unit
                }

                if (settings.overlayBubbleEnabled) {
                    runCatching { JarvisOverlayService.start(appContext) }
                        .onFailure { Log.w(TAG, "Could not restore overlay after boot", it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Post-boot re-arming failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Android 14+: a user tap on this notification is what allows the mic service to start. */
    private fun postTapToStart(context: Context) {
        if (!NotificationGate.canPost(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS startup", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_WAKE_SERVICE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            3001,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("JARVIS is ready")
                .setContentText("Tap to start listening for your wake word.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val CHANNEL = "jarvis_boot_channel"
    }
}
