package com.example.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** Notifications can be off (Android 13+ runtime permission, or disabled in settings). */
object NotificationGate {
    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
