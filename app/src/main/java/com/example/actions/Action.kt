package com.example.actions

import org.json.JSONObject

/**
 * Result returned by an Action execution.
 * @param spokenResult Concise, natural response intended to be spoken by JARVIS.
 * @param cardData Optional structured data to display in the HUD dynamic card panel.
 * @param isError Indicates if the execution encountered a soft error.
 */
data class ActionResult(
    val spokenResult: String,
    val cardData: Map<String, Any?>? = null,
    val isError: Boolean = false
)

/**
 * Parameter definition for JSON Schema generation.
 */
data class ParamDefinition(
    val type: String, // "string", "number", "boolean", "integer"
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String>? = null
)

/** What the user is asked to approve before a sensitive action may run. */
data class ConfirmationSpec(val title: String, val details: String)

/**
 * Base contract for all JARVIS actions.
 *
 * HOW TO ADD A NEW SKILL / TOOL:
 * 1. Create a single Kotlin class implementing [Action].
 * 2. Define [name], [description], and [parameters].
 * 3. Implement [execute], ensuring it returns [ActionResult] and never throws unhandled exceptions.
 * 4. Register it with one line in JarvisApp.registerBundledActions().
 *
 * SENSITIVE ACTIONS (calls, sends, deletes): override [confirmationFor]. The registry then NEVER
 * calls [execute] directly: it raises a confirmation that only the user's tap can approve, and
 * [execute] runs only after that. [execute] must therefore not ask for confirmation itself.
 */
interface Action {
    val name: String
    val description: String
    val parameters: Map<String, ParamDefinition>
    val isSensitive: Boolean get() = false

    /**
     * Returns what to show the user before running with [args], or null when no confirmation is
     * needed for these arguments. The default makes every [isSensitive] action confirm.
     */
    fun confirmationFor(args: Map<String, Any?>): ConfirmationSpec? =
        if (isSensitive) ConfirmationSpec(name, "Run $name?") else null

    /**
     * Executes the action asynchronously.
     * Guaranteed to never throw; wrap operations in try/catch and return an [ActionResult].
     */
    suspend fun execute(args: Map<String, Any?>): ActionResult

    /**
     * Generates Gemini-compliant FunctionDeclaration schema as a JSONObject.
     */
    fun toGeminiSchema(): JSONObject {
        val root = JSONObject()
        root.put("name", name)
        root.put("description", description)

        val paramsObj = JSONObject()
        paramsObj.put("type", "OBJECT")

        val propertiesObj = JSONObject()
        val requiredArray = org.json.JSONArray()

        for ((paramName, paramDef) in parameters) {
            val p = JSONObject()
            p.put("type", paramDef.type.uppercase())
            p.put("description", paramDef.description)
            if (!paramDef.enumValues.isNullOrEmpty()) {
                val enumArr = org.json.JSONArray()
                paramDef.enumValues.forEach { enumArr.put(it) }
                p.put("enum", enumArr)
            }
            propertiesObj.put(paramName, p)
            if (paramDef.required) {
                requiredArray.put(paramName)
            }
        }

        paramsObj.put("properties", propertiesObj)
        if (requiredArray.length() > 0) {
            paramsObj.put("required", requiredArray)
        }
        root.put("parameters", paramsObj)
        return root
    }
}
