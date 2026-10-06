package com.example.actions.impl

import com.example.actions.Action
import com.example.actions.ActionRegistry
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.core.gemini.GeminiCallResult
import org.json.JSONArray
import org.json.JSONObject

/** One planned tool call. */
data class AgentStep(val tool: String, val args: Map<String, Any?>, val why: String)

/**
 * Autonomous multi-step mode. Asks Gemini for a JSON plan restricted to tools that really exist,
 * then runs each step through the [ActionRegistry]. Because every step goes through the registry,
 * sensitive steps still need the user's tap: the agent stops at the first one and reports what is
 * waiting, instead of racing ahead.
 */
class AgentModeAction(
    /** Produces the plan text for a prompt (Gemini in the app, a fake in tests). */
    private val planner: suspend (String) -> GeminiCallResult,
    private val registry: () -> ActionRegistry
) : Action {

    override val name: String = "plan_and_execute"
    override val description: String =
        "Autonomous mode for goals that need several tools in sequence (for example: search, then set a " +
        "reminder, then message someone). Plans up to $MAX_STEPS steps and runs them one after another, " +
        "stopping at the first failure or at any step that needs the user's confirmation."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "goal" to ParamDefinition("string", "The multi-step objective, in the user's words", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val goal = (args["goal"] as? String)?.trim().orEmpty()
        if (goal.isEmpty()) return ActionResult("Goal required.", isError = true)

        val reg = registry()
        val tools = reg.getAllActions().filter { it.name != name && it.name != ActionRegistry.UNDO_ACTION_NAME }
        val toolList = tools.joinToString("\n") { t ->
            "- ${t.name}(${t.parameters.keys.joinToString(", ")}): ${t.description.take(140)}"
        }
        val plan = planner(planPrompt(goal, toolList))
        if (plan.isError) return ActionResult("I couldn't build a plan: ${plan.text}", isError = true)

        val steps = parsePlan(plan.text, tools.map { it.name }.toSet())
        if (steps.isEmpty()) {
            return ActionResult(
                "I couldn't turn that into steps I can run with my tools. Tell me the first thing to do.",
                isError = true
            )
        }

        val log = StringBuilder()
        var completed = 0
        var haltedBy: String? = null
        for ((i, step) in steps.withIndex()) {
            val r = reg.executeAction(step.tool, step.args)
            log.append("${i + 1}. ${step.tool}: ${r.spokenResult.take(160)}\n")
            if (r.isError) { haltedBy = "step ${i + 1} failed"; break }
            completed++
            if (r.cardData?.containsKey("pending_confirmation") == true) {
                haltedBy = "step ${i + 1} is waiting for your confirmation on the screen"
                break
            }
        }

        val spoken = when {
            haltedBy == null -> "Done. I completed all ${steps.size} steps."
            else -> "I stopped: $haltedBy. $completed of ${steps.size} steps are done."
        }
        return ActionResult(
            spokenResult = spoken,
            cardData = mapOf(
                "goal" to goal,
                "plan" to log.toString().trim(),
                "executed" to (haltedBy == null).toString()
            ),
            isError = haltedBy != null && haltedBy.contains("failed")
        )
    }

    companion object {
        const val MAX_STEPS = 6

        fun planPrompt(goal: String, toolList: String) = """You are a planner for a phone assistant.
Goal: $goal

Available tools:
$toolList

Reply with ONLY a JSON array (no markdown) of at most $MAX_STEPS steps in execution order, each
{"tool": "<tool name from the list>", "args": {<arguments>}, "why": "<short reason>"}.
Use only listed tools and only their listed argument names. If the goal cannot be done with these tools, reply []."""

        /** Extracts and validates the plan; unknown tools are dropped, never invented. */
        fun parsePlan(raw: String, knownTools: Set<String>): List<AgentStep> {
            val start = raw.indexOf('['); val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr = try { JSONArray(raw.substring(start, end + 1)) } catch (e: Exception) { return emptyList() }
            val steps = ArrayList<AgentStep>()
            for (i in 0 until arr.length()) {
                if (steps.size >= MAX_STEPS) break
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                val tool = o.optString("tool", "")
                if (tool !in knownTools) continue
                val a = HashMap<String, Any?>()
                o.optJSONObject("args")?.let { j -> j.keys().forEach { k -> a[k] = j.opt(k).takeIf { v -> v != JSONObject.NULL } } }
                steps.add(AgentStep(tool, a, o.optString("why", "")))
            }
            return steps
        }
    }
}
