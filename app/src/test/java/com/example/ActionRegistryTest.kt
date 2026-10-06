package com.example

import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ActionRegistry
import com.example.actions.ParamDefinition
import com.example.actions.SafetyGuard
import com.example.actions.UndoStack
import com.example.actions.UndoableAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionRegistryTest {

    private lateinit var undoStack: UndoStack
    private lateinit var safetyGuard: SafetyGuard
    private lateinit var registry: ActionRegistry

    @Before
    fun setup() {
        undoStack = UndoStack()
        safetyGuard = SafetyGuard()
        registry = ActionRegistry(undoStack, safetyGuard)
    }

    @Test
    fun testToolRegistrationAndExecution() = runBlocking {
        val testAction = object : Action {
            override val name: String = "test_ping"
            override val description: String = "Returns pong"
            override val parameters: Map<String, ParamDefinition> = mapOf(
                "echo" to ParamDefinition("string", "String to echo", required = true)
            )

            override suspend fun execute(args: Map<String, Any?>): ActionResult {
                val echo = args["echo"] as? String ?: ""
                return ActionResult("Pong: $echo")
            }
        }

        registry.register(testAction)
        assertNotNull(registry.getAction("test_ping"))

        val result = registry.executeAction("test_ping", mapOf("echo" to "JARVIS"))
        assertEquals("Pong: JARVIS", result.spokenResult)
    }

    @Test
    fun testGeminiToolsSchemaGeneration() {
        val testAction = object : Action {
            override val name: String = "test_schema"
            override val description: String = "Schema test"
            override val parameters: Map<String, ParamDefinition> = mapOf(
                "query" to ParamDefinition("string", "Query parameter", required = true)
            )

            override suspend fun execute(args: Map<String, Any?>): ActionResult {
                return ActionResult("OK")
            }
        }

        registry.register(testAction)
        val toolsJson = registry.buildGeminiToolsJson()
        assertTrue(toolsJson.length() > 0)
        val firstObj = toolsJson.getJSONObject(0)
        assertTrue(firstObj.has("functionDeclarations"))
    }

    @Test
    fun testGlobalUndoDispatching() = runBlocking {
        var actionState = "modified"
        undoStack.push(
            UndoableAction("Revert state") {
                actionState = "initial"
                "Reverted successfully"
            }
        )

        val result = registry.executeAction("undo", emptyMap())
        assertEquals("Reverted successfully", result.spokenResult)
        assertEquals("initial", actionState)
    }
}
