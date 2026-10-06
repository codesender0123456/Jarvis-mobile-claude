package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.R

class ReminderWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val reminderText = inputData.getString(KEY_REMINDER_TEXT) ?: DEFAULT_REMINDER_TEXT
        if (!NotificationGate.canPost(appContext)) {
            // Notifications are off: nothing can be shown, and retrying would only loop.
            Log.w("ReminderWorker", "Notifications disabled; reminder not shown")
            return Result.success()
        }
        return try {
            showNotification(reminderText)
            Result.success()
        } catch (e: Exception) {
            Log.e("ReminderWorker", "Failed to post reminder notification", e)
            Result.retry()
        }
    }

    private fun showNotification(text: String) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = CHANNEL_ID

        val channel = NotificationChannel(
            channelId,
            "JARVIS Reminders",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS Alert")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Stable per-reminder id derived from the text so retries replace the same
        // notification instead of stacking duplicates. Avoids the 32-bit truncation
        // of System.currentTimeMillis() which wraps roughly every 49 days.
        notificationManager.notify(text.hashCode(), notification)
    }

    companion object {
        const val KEY_REMINDER_TEXT = "reminder_text"
        const val CHANNEL_ID = "jarvis_reminders_channel"
        private const val DEFAULT_REMINDER_TEXT = "JARVIS Reminder"
    }
}