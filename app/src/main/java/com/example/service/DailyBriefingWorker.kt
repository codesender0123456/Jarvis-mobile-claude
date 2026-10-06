package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.memory.MemoryDatabase
import com.example.memory.MemoryRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyBriefingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val memoryDb = MemoryDatabase.getInstance(appContext)
            val memoryRepo = MemoryRepository(memoryDb.memoryDao())

            val recap = memoryRepo.consumeMorningRecapIfAvailable()
            val briefingText = if (recap != null) {
                "Good morning, Sir. Reviewing yesterday's focus: $recap. Atmospheric conditions and neural links are nominal."
            } else {
                "Good morning, Sir. All systems initialized and standing by for instructions."
            }

            if (NotificationGate.canPost(appContext)) showNotification(briefingText)
            Result.success()
        } catch (e: Exception) {
            Log.e("DailyBriefingWorker", "Failed to build morning briefing", e)
            Result.retry()
        }
    }

    private fun showNotification(briefing: String) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = CHANNEL_ID

        val channel = NotificationChannel(
            channelId,
            "JARVIS Morning Briefing",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS Morning Briefing")
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefing))
            .setContentText(briefing)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_briefing_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UNIQUE_WORK_NAME = "jarvis_daily_briefing"

        /**
         * Schedules the daily briefing as *unique* periodic work so that repeated calls from
         * Application.onCreate and BootReceiver replace rather than stack.
         */
        fun scheduleDaily(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<DailyBriefingWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(millisUntilNextMorning(), TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                Log.e("DailyBriefingWorker", "Unable to schedule daily briefing", e)
            }
        }

        /**
         * PeriodicWorkRequest has a 15 minute floor and drifts across cycles, so the first run is
         * aligned to the next morning. Falls back to a short delay if the clock is unusable.
         */
        private fun millisUntilNextMorning(): Long {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Today at 07:30 if that is still ahead, otherwise tomorrow.
            if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
            return (next.timeInMillis - now.timeInMillis).coerceAtLeast(TimeUnit.MINUTES.toMillis(5))
        }
    }
}
