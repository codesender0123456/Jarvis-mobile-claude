package com.example.actions.impl

import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.service.JarvisAccessibilityService

class AccessibilityControlAction : Action {

    override val name: String = "accessibility_control"
    override val description: String = "Automates device screen gestures: 'tap', 'scroll_down', 'scroll_up', 'back', 'home', 'recents', or 'read_screen'."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "action" to ParamDefinition(
            "string",
            "'tap', 'scroll_down', 'scroll_up', 'back', 'home', 'recents', or 'read_screen'",
            required = true,
            enumValues = listOf("tap", "scroll_down", "scroll_up", "back", "home", "recents", "read_screen")
        ),
        "x" to ParamDefinition("number", "X coordinate for tap", required = false),
        "y" to ParamDefinition("number", "Y coordinate for tap", required = false),
        "text_to_find" to ParamDefinition("string", "Button or element text to find and click", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult("JARVIS Accessibility Service is not enabled in Android Settings, Sir.", isError = true)

        val action = (args["action"] as? String)?.lowercase() ?: "read_screen"

        return when (action) {
            "back" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ActionResult("Pressed Back button, Sir.")
            }
            "home" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                ActionResult("Returned to Home screen, Sir.")
            }
            "recents" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                ActionResult("Opened Recent apps, Sir.")
            }
            "scroll_down" -> {
                service.scroll(down = true)
                ActionResult("Scrolled down, Sir.")
            }
            "scroll_up" -> {
                service.scroll(down = false)
                ActionResult("Scrolled up, Sir.")
            }
            "tap" -> {
                val x = (args["x"] as? Number)?.toFloat() ?: 500f
                val y = (args["y"] as? Number)?.toFloat() ?: 500f
                service.tap(x, y)
                ActionResult("Tapped screen at ($x, $y), Sir.")
            }
            "read_screen" -> {
                val screenText = service.readScreenContent()
                if (screenText.isNotEmpty()) {
                    ActionResult(
                        spokenResult = "Screen contains: ${screenText.take(150)}...",
                        cardData = mapOf("screen_text" to screenText)
                    )
                } else {
                    ActionResult("No readable text found on current screen, Sir.")
                }
            }
            else -> ActionResult("Unrecognized accessibility action: $action", isError = true)
        }
    }
}
