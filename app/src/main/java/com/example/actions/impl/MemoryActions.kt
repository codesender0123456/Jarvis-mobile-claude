package com.example.actions.impl

import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.memory.MemoryRepository

class RememberFactAction(
    private val memoryRepository: MemoryRepository,
    private val undoStack: com.example.actions.UndoStack
) : Action {

    override val name: String = "remember_fact"
    override val description: String = "Stores a persistent user preference, project detail, personal note, or identity fact in local memory."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "key" to ParamDefinition("string", "Short descriptive key (e.g. coffee_order, car_model, spouse_name)", required = true),
        "fact" to ParamDefinition("string", "The detailed information or preference to remember", required = true),
        "category" to ParamDefinition("string", "Category: 'preference', 'identity', 'project', 'people', or 'general'", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val key = (args["key"] as? String)?.trim() ?: return ActionResult("Key is required.", isError = true)
        val fact = (args["fact"] as? String)?.trim() ?: return ActionResult("Fact is required.", isError = true)
        val category = (args["category"] as? String)?.trim() ?: "preference"

        val id = memoryRepository.rememberFact(key, fact, category, isCore = true)
        undoStack.push(com.example.actions.UndoableAction("Forget '$key'") {
            memoryRepository.deleteMemory(id)
            "Forgot $key."
        })

        return ActionResult("Saved to memory: $key is '$fact'.")
    }
}

class RecallMemoryAction(private val memoryRepository: MemoryRepository) : Action {

    override val name: String = "recall_memory"
    override val description: String = "Searches the offline local memory database for previously stored facts, notes, or preferences."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "query" to ParamDefinition("string", "Keywords or topic to search for", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val query = (args["query"] as? String)?.trim() ?: return ActionResult("Search query required.", isError = true)
        val matches = memoryRepository.recallMemory(query)

        return if (matches.isNotEmpty()) {
            val spoken = "Recalled ${matches.size} item(s): " + matches.take(3).joinToString("; ") { "${it.key}: ${it.fact}" }
            ActionResult(
                spokenResult = spoken,
                cardData = mapOf("memories" to matches.map { mapOf("key" to it.key, "fact" to it.fact, "category" to it.category) })
            )
        } else {
            ActionResult("No stored memories found for '$query', Sir.")
        }
    }
}
