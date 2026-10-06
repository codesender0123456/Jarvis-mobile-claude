package com.example.actions

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ActionRegistry(
    private val undoStack: UndoStack,
    private val safetyGuard: SafetyGuard
) {
    private val actions = ConcurrentHashMap<String, Action>()

    fun register(action: Action) {
        val previous = actions.put(action.name, action)
        if (previous != null) {
            Log.w("ActionRegistry", "Duplicate tool name '${action.name}' replaced ${previous.javaClass.simpleName}")
        }
    }

    fun getAction(name: String): Action? = actions[name]

    fun getAllActions(): List<Action> = actions.values.toList()

    /**
     * Build tools array for Gemini API call
     */
    fun buildGeminiToolsJson(): JSONArray {
        val toolsArray = JSONArray()
        val toolObj = JSONObject()
        val functionDeclarations = JSONArray()

        for (action in actions.values) {
            functionDeclarations.put(action.toGeminiSchema())
        }

        toolObj.put("functionDeclarations", functionDeclarations)
        toolsArray.put(toolObj)
        return toolsArray
    }

    fun getCapabilitiesSummary(): String {
        return actions.values.joinToString("\n") { action ->
            "- ${action.name}: ${action.description}"
        }
    }

    suspend fun executeAction(name: String, args: Map<String, Any?>): ActionResult {
        // Arguments can contain phone numbers and message bodies: log the tool name only.
        Log.d("ActionRegistry", "Executing tool: $name")

        // Intercept the global undo command. This is reachable now because UndoAction is
        // registered as a tool, so saying "undo" works.
        if (name.equals(UNDO_ACTION_NAME, ignoreCase = true)) {
            val result = undoStack.undoLast()
            return ActionResult(spokenResult = result)
        }

        val action = actions[name]
        if (action == null) {
            return ActionResult(
                spokenResult = "Tool '$name' is not recognized in this subsystem, Sir.",
                isError = true
            )
        }

        // Global gate: a sensitive operation is never executed here. The registry raises the
        // confirmation (token issued by SafetyGuard, approvable only from the UI) and the action
        // body runs only inside onApproved, after the user's tap.
        val spec = action.confirmationFor(args)
        if (spec != null) {
            val message = safetyGuard.createConfirmationRequest(
                actionName = action.name,
                title = spec.title,
                details = spec.details,
                onApproved = { runAction(action, args).spokenResult }
            )
            return ActionResult(
                spokenResult = "$message Nothing has been done yet.",
                cardData = mapOf("pending_confirmation" to spec.title)
            )
        }

        val result = runAction(action, args)
        return result
    }

    private suspend fun runAction(action: Action, args: Map<String, Any?>): ActionResult = try {
        action.execute(args)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("ActionRegistry", "Unhandled error executing ${action.name}", e)
        ActionResult(
            spokenResult = "Encountered a system fault executing ${action.name}: ${e.message}",
            isError = true
        )
    }

    companion object {
        const val UNDO_ACTION_NAME = "undo"
    }
}

/**
 * Exposes the undo stack to the model so "undo that" works by voice, not only via the HUD
 * button. Registered automatically by [ActionRegistry.registerBundledActions].
 */
class UndoAction(private val undoStack: UndoStack) : Action {
    override val name: String = ActionRegistry.UNDO_ACTION_NAME
    override val description: String =
        "Reverses the most recent reversible action (volume change, flashlight toggle, file create/rename)."
    override val parameters: Map<String, ParamDefinition> = emptyMap()

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        return ActionResult(spokenResult = undoStack.undoLast())
    }
}