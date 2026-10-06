package com.example.actions

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds undo entries for device-setting changes together with a small JSON snapshot, so the
 * entry can be rebuilt after the process dies ([UndoStack.restore]). Entries backed only by
 * in-memory closures (file rename, memory edits, reminders) are session-scoped.
 */
class UndoFactory(private val context: Context) {

    fun volume(stream: Int, old: Int, label: String): UndoableAction = UndoableAction(
        description = "Revert $label volume",
        snapshot = JSONObject().put("kind", "volume").put("stream", stream).put("old", old).put("label", label)
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(stream, old, AudioManager.FLAG_SHOW_UI)
        "Reverted $label volume."
    }

    fun brightness(mode: Int, level: Int): UndoableAction = UndoableAction(
        description = "Restore previous brightness",
        snapshot = JSONObject().put("kind", "brightness").put("mode", mode).put("level", level)
    ) {
        if (!Settings.System.canWrite(context)) "Cannot restore brightness: settings access was revoked."
        else {
            val cr = context.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            "Brightness restored."
        }
    }

    fun doNotDisturb(previousFilter: Int): UndoableAction = UndoableAction(
        description = "Restore previous Do Not Disturb setting",
        snapshot = JSONObject().put("kind", "dnd").put("filter", previousFilter)
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) "Cannot restore Do Not Disturb: access was revoked."
        else { nm.setInterruptionFilter(previousFilter); "Do Not Disturb restored." }
    }

    fun flashlight(cameraId: String, restoreOn: Boolean): UndoableAction = UndoableAction(
        description = "Turn flashlight ${if (restoreOn) "on" else "off"} again",
        snapshot = JSONObject().put("kind", "flashlight").put("id", cameraId).put("on", restoreOn)
    ) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.setTorchMode(cameraId, restoreOn)
        "Flashlight turned ${if (restoreOn) "on" else "off"}."
    }

    /** Rebuilds an entry from its saved snapshot, or null if it is unknown or malformed. */
    fun rebuild(s: JSONObject): UndoableAction? = try {
        when (s.optString("kind")) {
            "volume" -> volume(s.getInt("stream"), s.getInt("old"), s.optString("label", "media"))
            "brightness" -> brightness(s.getInt("mode"), s.getInt("level"))
            "dnd" -> doNotDisturb(s.getInt("filter"))
            "flashlight" -> flashlight(s.getString("id"), s.getBoolean("on"))
            else -> null
        }
    } catch (e: Exception) { null }
}

/** Where undo snapshots survive process death. */
interface UndoPersistence {
    fun save(entries: List<JSONObject>)
    fun load(): List<JSONObject>
}

class PrefsUndoPersistence(context: Context) : UndoPersistence {
    private val prefs = context.getSharedPreferences("jarvis_undo", Context.MODE_PRIVATE)

    override fun save(entries: List<JSONObject>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    override fun load(): List<JSONObject> = try {
        val arr = JSONArray(prefs.getString("entries", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    } catch (e: Exception) { emptyList() }
}
