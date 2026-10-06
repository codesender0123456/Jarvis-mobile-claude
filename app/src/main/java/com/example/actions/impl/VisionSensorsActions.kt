package com.example.actions.impl

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.core.vision.VisionController
import com.example.core.vision.VisionSource

class DeviceHealthAction(private val context: Context) : Action {

    override val name: String = "check_device_health"
    override val description: String = "Monitors device health metrics: battery level and temperature, RAM usage, storage availability, and network speed."
    override val parameters: Map<String, ParamDefinition> = emptyMap()

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        // Battery and Temperature
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempTenths / 10.0

        // RAM
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val usedRamGb = totalRamGb - availRamGb

        // Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val availStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)

        // Network
        val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connManager.activeNetwork
        val caps = activeNetwork?.let { connManager.getNetworkCapabilities(it) }
        // linkDownstreamBandwidthKbps is a link estimate, not a measured throughput.
        val downstreamMbps = ((caps?.linkDownstreamBandwidthKbps ?: 0) / 1000).coerceAtLeast(0)
        val netType = when {
            caps == null -> "No active connection"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular / Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }

        val spoken = "All systems operational, Sir. Battery is at $batteryPct% at %.1f°C. RAM is %.1f of %.1f GB. %.1f GB storage free. Connected via $netType with an estimated %d Mbps link."
            .format(tempCelsius, usedRamGb, totalRamGb, availStorageGb, downstreamMbps)

        val cardData = mapOf(
            "battery" to "$batteryPct%",
            "temperature" to "%.1f°C".format(tempCelsius),
            "ram" to "%.1f / %.1f GB".format(usedRamGb, totalRamGb),
            "storage" to "%.1f GB free".format(availStorageGb),
            "network" to "$netType ($downstreamMbps Mbps est.)"
        )

        return ActionResult(spokenResult = spoken, cardData = cardData)
    }
}

class VisionContextAction(
    private val context: Context,
    private val vision: VisionController
) : Action {

    override val name: String = "inspect_visual_input"
    override val description: String =
        "Turns live visual input on or off. source='camera' streams the camera: those frames show the " +
        "user and their room. source='screen' streams the screen: those frames show the user's phone " +
        "screen (the user must approve an Android prompt). One source at a time. While a source is " +
        "on, you can see it and answer questions about it directly."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "source" to ParamDefinition(
            "string", "Visual source: 'camera' or 'screen'", required = true,
            enumValues = listOf("camera", "screen")
        ),
        "action" to ParamDefinition(
            "string", "'start' (default), 'stop' or 'status'",
            enumValues = listOf("start", "stop", "status")
        ),
        "lens" to ParamDefinition(
            "string", "Camera lens: 'front' (default, shows the user) or 'back'",
            enumValues = listOf("front", "back")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val source = (args["source"] as? String)?.lowercase() ?: "camera"
        val action = (args["action"] as? String)?.lowercase() ?: "start"
        val front = (args["lens"] as? String)?.lowercase() != "back"

        fun card(status: String) = mapOf<String, Any?>("source" to source, "status" to status)

        return when (action) {
            "stop" -> {
                vision.stopAll()
                ActionResult("Visual input is off.", card("stopped"))
            }
            "status" -> {
                val now = vision.active.value
                val say = when (now) {
                    VisionSource.NONE -> "No visual source is active."
                    VisionSource.CAMERA -> "The camera is on; frames show the user and their room."
                    VisionSource.SCREEN -> "Screen sharing is on; frames show the user's phone screen."
                }
                ActionResult(say, card(now.name.lowercase()))
            }
            else -> if (source == "camera") {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    ActionResult(
                        "I don't have camera permission yet. Grant it from the permissions screen, then ask again.",
                        card("permission_needed"), isError = true
                    )
                } else {
                    val say = vision.startCamera(front)
                    ActionResult(say, card("starting"), isError = say.startsWith("The JARVIS app"))
                }
            } else {
                ActionResult(vision.requestScreen(), card("awaiting_user_approval"))
            }
        }
    }
}
