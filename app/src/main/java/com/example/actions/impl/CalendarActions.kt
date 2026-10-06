package com.example.actions.impl

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import java.util.Calendar
import java.util.TimeZone

class CalendarActions(private val context: Context) : Action {

    override val name: String = "calendar_event"
    override val description: String = "Creates a new calendar event or reads upcoming events from the user's primary calendar."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "action_type" to ParamDefinition("string", "'create' or 'read'", required = true, enumValues = listOf("create", "read")),
        "title" to ParamDefinition("string", "Title of the event", required = false),
        "start_minutes_from_now" to ParamDefinition("integer", "Start time offset in minutes from now", required = false),
        "duration_minutes" to ParamDefinition("integer", "Duration in minutes (default 60)", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val actionType = (args["action_type"] as? String)?.lowercase() ?: "read"

        if (actionType == "create") {
            val title = (args["title"] as? String)?.trim() ?: "Scheduled by JARVIS"
            val offsetMin = (args["start_minutes_from_now"] as? Number)?.toInt() ?: 30
            val durationMin = (args["duration_minutes"] as? Number)?.toInt() ?: 60

            val startMillis = Calendar.getInstance().apply {
                add(Calendar.MINUTE, offsetMin)
            }.timeInMillis
            val endMillis = startMillis + (durationMin * 60 * 1000L)

            // Open calendar insertion intent
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                putExtra(CalendarContract.Events.TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ActionResult("Opened calendar to schedule '$title', Sir.")
            } catch (e: Exception) {
                ActionResult("Could not open calendar: ${e.message}", isError = true)
            }
        } else {
            // Read upcoming events
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
            val now = System.currentTimeMillis()
            val later = now + (24 * 60 * 60 * 1000L) // 24 hours
            val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
            val selectionArgs = arrayOf(now.toString(), later.toString())

            val events = mutableListOf<String>()
            try {
                val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Events.DTSTART} ASC")
                cursor?.use {
                    val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
                    while (it.moveToNext() && events.size < 5) {
                        val t = it.getString(titleIdx) ?: "Untitled Event"
                        events.add(t)
                    }
                }
            } catch (e: Exception) {
                return ActionResult("Calendar read error: ${e.message}", isError = true)
            }

            return if (events.isNotEmpty()) {
                ActionResult(
                    spokenResult = "Upcoming in next 24 hours: ${events.joinToString(", ")}.",
                    cardData = mapOf("events" to events)
                )
            } else {
                ActionResult("No events scheduled for the next 24 hours, Sir.")
            }
        }
    }
}

class NavigationAction(private val context: Context) : Action {

    override val name: String = "navigate_to"
    override val description: String = "Launches map directions or searches for a destination using Google Maps or default navigation."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "destination" to ParamDefinition("string", "Destination address, place name, or landmark", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val dest = (args["destination"] as? String)?.trim() ?: return ActionResult("Destination missing, Sir.", isError = true)
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(dest))
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            `package` = "com.google.android.apps.maps"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(mapIntent)
            ActionResult("Plotting route to $dest, Sir.")
        } catch (e: Exception) {
            // Fallback to generic geo intent
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(geoIntent)
            ActionResult("Opening navigation for $dest, Sir.")
        }
    }
}
