package com.example.actions.impl

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.service.ReminderWorker
import java.util.concurrent.TimeUnit

class SetAlarmAction(private val context: Context) : Action {

    override val name: String = "set_alarm"
    override val description: String = "Sets a system clock alarm for specified hour, minute, and optional label."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "hour" to ParamDefinition("integer", "Hour of alarm in 24-hour format (0-23)", required = true),
        "minute" to ParamDefinition("integer", "Minute of alarm (0-59)", required = true),
        "message" to ParamDefinition("string", "Label or title for the alarm", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val hour = (args["hour"] as? Number)?.toInt() ?: return ActionResult("Hour is required.", isError = true)
        val minute = (args["minute"] as? Number)?.toInt() ?: 0
        val message = (args["message"] as? String) ?: "Alarm"

        if (hour !in 0..23 || minute !in 0..59) {
            return ActionResult(
                "Alarm time is out of range: hour must be 0-23 and minute 0-59.",
                isError = true
            )
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            // EXTRA_SKIP_UI is only honoured when JARVIS is the user's default alarm app;
            // otherwise the clock app shows its own confirmation screen.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ActionResult("Alarm requested for %02d:%02d: '%s', Sir.".format(hour, minute, message))
        } catch (e: Exception) {
            ActionResult("Could not set system alarm: ${e.message}", isError = true)
        }
    }
}

class SetTimerAction(private val context: Context) : Action {

    override val name: String = "set_timer"
    override val description: String = "Sets a countdown timer for a specified duration in seconds or minutes."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "seconds" to ParamDefinition("integer", "Duration of timer in seconds", required = true),
        "message" to ParamDefinition("string", "Timer description", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val seconds = (args["seconds"] as? Number)?.toInt() ?: return ActionResult("Timer duration required.", isError = true)
        val message = (args["message"] as? String) ?: "Timer"

        if (seconds <= 0) {
            return ActionResult("Timer duration must be a positive number of seconds.", isError = true)
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val minutes = seconds / 60
            val remSec = seconds % 60
            val durDesc = if (minutes > 0) "$minutes minute(s) $remSec second(s)" else "$seconds seconds"
            ActionResult("Timer requested for $durDesc: '$message', Sir.")
        } catch (e: Exception) {
            ActionResult("Failed to set timer: ${e.message}", isError = true)
        }
    }
}

class CreateReminderAction(
    private val context: Context,
    private val undoStack: com.example.actions.UndoStack
) : Action {

    override val name: String = "create_reminder"
    override val description: String = "Schedules a persistent reminder that notifies the user after a given delay in minutes."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "message" to ParamDefinition("string", "Reminder message content", required = true),
        "delay_minutes" to ParamDefinition("integer", "Delay in minutes from now", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val message = (args["message"] as? String)?.trim() ?: return ActionResult("Reminder message required.", isError = true)
        val delayMinutes = (args["delay_minutes"] as? Number)?.toLong()
            ?: return ActionResult("Reminder delay in minutes is required.", isError = true)

        // setInitialDelay throws IllegalArgumentException on non-positive durations.
        if (delayMinutes <= 0) {
            return ActionResult("Reminder delay must be at least 1 minute.", isError = true)
        }

        val workData = Data.Builder()
            .putString(ReminderWorker.KEY_REMINDER_TEXT, message)
            .build()

        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workData)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        val notificationsOff = !com.example.service.NotificationGate.canPost(context)
        undoStack.push(com.example.actions.UndoableAction("Cancel reminder '$message'") {
            WorkManager.getInstance(context).cancelWorkById(request.id)
            "Reminder cancelled."
        })

        return ActionResult(
            spokenResult = "Reminder scheduled in $delayMinutes minute(s): '$message'." +
                if (notificationsOff) " Warning: notifications are turned off, so I won't be able to alert you. Enable them in settings." else "",
            cardData = mapOf("reminder" to message, "delay" to delayMinutes)
        )
    }
}
