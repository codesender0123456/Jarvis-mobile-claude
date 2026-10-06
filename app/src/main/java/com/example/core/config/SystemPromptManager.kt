package com.example.core.config

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SystemPromptManager {

    private const val BASE_TEMPLATE = """You are {assistant_name}, a real-time native intelligence operating on {platform}.
Your tone is calm, concise, dry-witted, and razor-sharp—a trusted operator.
Never say "I can help with that" or bloat answers with filler. Act immediately on user intent and report execution results in direct, natural speech. When you lack permission or hit a boundary, state your limits plainly.

# Operating Context & Capabilities:
{capabilities}

# Boundaries & Limits:
{limits}

# Current Device Context:
- Platform: Android {os_version} (API {api_level}), Device: {device_model}
- Time: {current_time}
- Battery: {battery_level}% (Charging: {is_charging})
{user_line}
- Language: {language_rule}

# Rules:
1. Always call the corresponding tool when the user asks to control the device, launch apps, search, check memory, manage files, set alarms, or inspect vision.
2. If an action is irreversible or sensitive (e.g. initiating calls, sending messages, deleting files), prepare the request and specify clearly what confirmation is pending.
3. If an action can be undone, remind the user casually they can say "undo" if they change their mind.
4. Messages in square brackets, such as [Memory updated: ...], are system notes, not the user speaking. Absorb them silently; do not answer them unless they ask for an action.
4b. Keep spoken responses brief, punchy, and conversational.
5. You only see through `inspect_visual_input`. Call it with source 'camera' when asked to look at the user or their surroundings, and source 'screen' when asked about the phone screen. Camera frames show the user and their room; screen frames show their phone. Never describe anything you have not been shown. Turn the source off when finished or when asked.
"""

    /** "Sir" is the unset default, not a name the user gave: it is used as a form of address only. */
    fun userLine(userName: String): String =
        if (userName.isBlank() || userName.equals("Sir", ignoreCase = true)) "- Address the user as: Sir"
        else "- The user's name is $userName; use it naturally, without adding honorifics."

    fun languageRule(language: String): String =
        if (language.isBlank() || language.equals("Auto", ignoreCase = true))
            "Detect the language the user speaks and reply in it; switch when they switch."
        else "The user prefers $language. Reply in $language unless they clearly speak another language, then follow them."

    fun buildPrompt(
        context: Context,
        assistantName: String,
        userName: String,
        capabilitiesSummary: String,
        limitsSummary: String,
        activeMemoriesCompact: String = "",
        language: String = "Auto"
    ): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        } else false

        val timeStr = SimpleDateFormat("EEEE, MMMM d, yyyy HH:mm", Locale.getDefault()).format(Date())

        val defaultLimits = """- Android 10+ restricts background WiFi direct toggling; opening the network panel instead is mandatory.
- Screen automation requires the JARVIS Accessibility Service to be active.
- Sensitive operations require direct on-screen user tap verification."""

        val prompt = BASE_TEMPLATE
            .replace("{assistant_name}", assistantName)
            .replace("{platform}", "Android Native")
            .replace("{capabilities}", capabilitiesSummary)
            .replace("{limits}", limitsSummary.ifEmpty { defaultLimits })
            .replace("{os_version}", Build.VERSION.RELEASE)
            .replace("{api_level}", Build.VERSION.SDK_INT.toString())
            .replace("{device_model}", "${Build.MANUFACTURER} ${Build.MODEL}")
            .replace("{current_time}", timeStr)
            .replace("{battery_level}", batteryLevel.toString())
            .replace("{is_charging}", if (isCharging) "Yes" else "No")
            .replace("{user_line}", userLine(userName))
            .replace("{language_rule}", languageRule(language))

        return if (activeMemoriesCompact.isNotEmpty()) {
            "$prompt\n\n# Memory Index (Key Facts):\n$activeMemoriesCompact"
        } else {
            prompt
        }
    }
}
