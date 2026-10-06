package com.example.actions.impl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.core.gemini.GeminiClient

class ClipboardAssistantAction(
    private val context: Context,
    private val geminiClient: GeminiClient
) : Action {

    override val name: String = "clipboard_assistant"
    override val description: String = "Inspects the system clipboard and performs operations: 'summarise', 'translate', 'explain', or 'fix' grammar/code."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "operation" to ParamDefinition(
            "string",
            "Operation type: 'summarise', 'translate', 'explain', or 'fix'",
            required = true,
            enumValues = listOf("summarise", "translate", "explain", "fix")
        ),
        "target_language" to ParamDefinition("string", "Target language if operation is 'translate'", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()?.trim()

        if (text.isNullOrEmpty()) {
            return ActionResult("The clipboard is currently empty, Sir.", isError = true)
        }

        val operation = (args["operation"] as? String)?.lowercase() ?: "summarise"
        val targetLang = (args["target_language"] as? String)?.trim() ?: "English"

        val prompt = when (operation) {
            "summarise" -> "Provide a concise 1-2 sentence executive summary of this clipboard content:\n\n$text"
            "translate" -> "Translate this clipboard text accurately to $targetLang:\n\n$text"
            "explain" -> "Explain the key concepts or meaning of this clipboard text in plain language:\n\n$text"
            "fix" -> "Fix all grammar, spelling, clarity, or code syntax errors in this clipboard text and return the clean version:\n\n$text"
            else -> "Analyze this text:\n\n$text"
        }

        val analysisResult = geminiClient.callOneShot(prompt)
        if (analysisResult.isError) {
            return ActionResult("Clipboard analysis failed: ${analysisResult.text}", isError = true)
        }

        // If fix or translate, also copy back to clipboard for user convenience
        if (operation == "fix" || operation == "translate") {
            clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS Result", analysisResult.text))
        }

        return ActionResult(
            spokenResult = "Clipboard $operation complete: ${analysisResult.text}",
            cardData = mapOf("operation" to operation, "original" to text.take(150), "result" to analysisResult.text)
        )
    }
}
