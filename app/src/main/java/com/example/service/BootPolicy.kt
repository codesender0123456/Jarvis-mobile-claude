package com.example.service

enum class BootAction { NONE, START_WAKE_SERVICE, POST_TAP_TO_START }

/**
 * What to do after a reboot. Android 14+ forbids starting a microphone foreground service from a
 * BOOT_COMPLETED receiver, so there the user gets a notification and one tap starts it.
 */
object BootPolicy {
    fun decide(autoStartOnBoot: Boolean, hasMicPermission: Boolean, sdkInt: Int): BootAction = when {
        !autoStartOnBoot || !hasMicPermission -> BootAction.NONE
        sdkInt >= 34 -> BootAction.POST_TAP_TO_START
        else -> BootAction.START_WAKE_SERVICE
    }
}
