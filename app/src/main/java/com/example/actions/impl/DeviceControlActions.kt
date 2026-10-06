package com.example.actions.impl

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.actions.UndoFactory
import com.example.actions.UndoStack
import com.example.actions.UndoableAction

class VolumeControlAction(
    private val context: Context,
    private val undoStack: UndoStack
) : Action {

    override val name: String = "set_volume"
    override val description: String = "Adjusts device audio volume (media, ring, notification, or alarm) from 0 to 100%."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "level" to ParamDefinition("integer", "Volume percentage from 0 to 100", required = true),
        "stream" to ParamDefinition(
            "string",
            "Audio stream type: 'media', 'ring', 'alarm', or 'notification'",
            enumValues = listOf("media", "ring", "alarm", "notification")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val levelPercent = (args["level"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
        val streamTypeStr = (args["stream"] as? String)?.lowercase() ?: "media"

        val streamType = when (streamTypeStr) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }

        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val oldVolume = audioManager.getStreamVolume(streamType)
        val newVolume = (levelPercent * maxVolume) / 100

        audioManager.setStreamVolume(streamType, newVolume, AudioManager.FLAG_SHOW_UI)

        undoStack.push(UndoFactory(context).volume(streamType, oldVolume, streamTypeStr))

        return ActionResult(
            spokenResult = "Volume set to $levelPercent% for $streamTypeStr, Sir.",
            cardData = mapOf("stream" to streamTypeStr, "level" to levelPercent)
        )
    }
}

class FlashlightControlAction(
    private val context: Context,
    private val undoStack: UndoStack
) : Action {

    override val name: String = "toggle_flashlight"
    override val description: String = "Turns the device flashlight on or off."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "enabled" to ParamDefinition("boolean", "True to turn on, false to turn off", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val enabled = args["enabled"] as? Boolean ?: true
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ActionResult("Camera hardware unavailable for flashlight, Sir.", isError = true)

        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                val c = cameraManager.getCameraCharacteristics(it)
                c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return ActionResult("This device has no flashlight.", isError = true)

            cameraManager.setTorchMode(cameraId, enabled)
            undoStack.push(UndoFactory(context).flashlight(cameraId, !enabled))

            ActionResult(
                spokenResult = "Flashlight turned ${if (enabled) "on" else "off"}, Sir.",
                cardData = mapOf("flashlight" to enabled)
            )
        } catch (e: Exception) {
            ActionResult("Could not toggle flashlight: ${e.message}", isError = true)
        }
    }
}

class NetworkPanelsAction(private val context: Context) : Action {

    override val name: String = "manage_connectivity"
    override val description: String = "Opens system network or Bluetooth settings panels. Note: Android 10+ prohibits silent WiFi toggling."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "type" to ParamDefinition(
            "string",
            "Type of panel: 'wifi', 'internet', or 'bluetooth'",
            required = true,
            enumValues = listOf("wifi", "internet", "bluetooth")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val type = (args["type"] as? String)?.lowercase() ?: "internet"

        return when (type) {
            "wifi", "internet" -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult(
                    spokenResult = "Opening the connectivity panel. Android 10 and above prohibits silent WiFi switching, Sir.",
                    cardData = mapOf("panel" to "internet")
                )
            }
            "bluetooth" -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult(
                    spokenResult = "Opening Bluetooth settings, Sir.",
                    cardData = mapOf("panel" to "bluetooth")
                )
            }
            else -> ActionResult("Unrecognized connectivity panel, Sir.", isError = true)
        }
    }
}

class MediaPlaybackControlAction(private val context: Context) : Action {

    override val name: String = "control_media"
    override val description: String = "Controls media playback on the device (play, pause, next, previous)."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "command" to ParamDefinition(
            "string",
            "Playback command: 'play', 'pause', 'toggle', 'next', 'previous'",
            required = true,
            enumValues = listOf("play", "pause", "toggle", "next", "previous")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val command = (args["command"] as? String)?.lowercase() ?: "toggle"
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val keyCode = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        return ActionResult("Executed media $command, Sir.")
    }
}

class DoNotDisturbAction(
    private val context: Context,
    private val undoStack: UndoStack
) : Action {

    override val name: String = "set_do_not_disturb"
    override val description: String = "Controls Do Not Disturb mode or opens notification policy settings."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "enabled" to ParamDefinition("boolean", "True for DND on, false for normal mode", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val enabled = args["enabled"] as? Boolean ?: true
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ActionResult("Do Not Disturb permission is required. Opening settings, Sir.")
        }

        val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        val previous = notificationManager.currentInterruptionFilter
        notificationManager.setInterruptionFilter(filter)
        undoStack.push(UndoFactory(context).doNotDisturb(previous))

        return ActionResult("Do Not Disturb mode ${if (enabled) "engaged" else "disengaged"}, Sir.")
    }
}

/** Screen brightness, reversible. Needs the special "Modify system settings" access. */
class BrightnessControlAction(
    private val context: Context,
    private val undoStack: UndoStack
) : Action {

    override val name: String = "set_brightness"
    override val description: String =
        "Sets screen brightness from 1 to 100 percent. Needs the 'Modify system settings' permission."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "level" to ParamDefinition("integer", "Brightness percentage from 1 to 100", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val percent = (args["level"] as? Number)?.toInt()?.coerceIn(1, 100)
            ?: return ActionResult("What brightness level, Sir?", isError = true)

        if (!Settings.System.canWrite(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return ActionResult(
                "I need permission to change system settings. I've opened the page; allow it, then ask again.",
                isError = true
            )
        }

        val cr = context.contentResolver
        val previousMode = Settings.System.getInt(
            cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val previousLevel = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)

        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, percent * 255 / 100)

        undoStack.push(UndoFactory(context).brightness(previousMode, previousLevel))
        return ActionResult("Brightness set to $percent percent.")
    }
}
