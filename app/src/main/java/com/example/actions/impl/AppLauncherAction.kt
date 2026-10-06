package com.example.actions.impl

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition

class AppLauncherAction(private val context: Context) : Action {

    override val name: String = "open_app"
    override val description: String = "Opens an installed Android application by name using fuzzy matching."

    override val parameters: Map<String, ParamDefinition> = mapOf(
        "app_name" to ParamDefinition(
            type = "string",
            description = "The spoken or common name of the application to open (e.g. YouTube, Spotify, Chrome, Camera).",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val targetName = (args["app_name"] as? String)?.trim()
            ?: return ActionResult("No application name specified, Sir.", isError = true)

        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        var bestMatchPkg: String? = null
        var bestMatchLabel: String? = null
        var highestScore = -1

        for (app in installedApps) {
            // Check if application has a launch intent
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue

            val label = pm.getApplicationLabel(app).toString()
            val score = fuzzyMatchScore(targetName.lowercase(), label.lowercase())
            if (score > highestScore) {
                highestScore = score
                bestMatchPkg = app.packageName
                bestMatchLabel = label
            }
        }

        if (bestMatchPkg != null && highestScore >= 40) {
            val launchIntent = pm.getLaunchIntentForPackage(bestMatchPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ActionResult(
                    spokenResult = "Opening $bestMatchLabel, Sir.",
                    cardData = mapOf("app" to bestMatchLabel, "package" to bestMatchPkg)
                )
            }
        }

        return ActionResult(
            spokenResult = "I could not find an installed application matching '$targetName', Sir.",
            isError = true
        )
    }

    private fun fuzzyMatchScore(query: String, target: String): Int {
        if (query == target) return 100
        if (target.startsWith(query)) return 90
        if (target.contains(query)) return 80
        val queryWords = query.split(" ")
        val targetWords = target.split(" ")
        for (qw in queryWords) {
            for (tw in targetWords) {
                if (tw.startsWith(qw) && qw.length >= 3) return 60
            }
        }
        return 0
    }
}
