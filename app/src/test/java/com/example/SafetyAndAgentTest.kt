package com.example

import com.example.actions.Action
import com.example.actions.ActionRegistry
import com.example.actions.ActionResult
import com.example.actions.ConfirmationSpec
import com.example.actions.ParamDefinition
import com.example.actions.SafetyGuard
import com.example.actions.UndoPersistence
import com.example.actions.UndoStack
import com.example.actions.UndoableAction
import com.example.actions.impl.AgentModeAction
import com.example.core.gemini.GeminiCallResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAndAgentTest {

    private class Counter(override val name: String, private val sensitive: Boolean = false, private val fail: Boolean = false) : Action {
        var runs = 0
        override val description = "test"
        override val parameters: Map<String, ParamDefinition> = emptyMap()
        override val isSensitive = sensitive
        override fun confirmationFor(args: Map<String, Any?>) =
            if (sensitive) ConfirmationSpec("Do $name", "really?") else null
        override suspend fun execute(args: Map<String, Any?>): ActionResult {
            runs++
            return ActionResult(if (fail) "boom" else "ok $name", isError = fail)
        }
    }

    private val guard = SafetyGuard()
    private val registry = ActionRegistry(UndoStack(), guard)

    @Test fun sensitiveActionDoesNotRunUntilTheUserApproves() = runBlocking {
        val call = Counter("call", sensitive = true)
        registry.register(call)

        val r = registry.executeAction("call", emptyMap())
        assertEquals("must not execute before approval", 0, call.runs)
        assertTrue(r.spokenResult.contains("Nothing has been done yet"))
        val pending = guard.pendingRequest.value
        assertNotNull(pending)
        assertFalse("token must never be returned to the model", r.spokenResult.contains(pending!!.token))

        assertEquals("Security token mismatch. Action denied.", guard.approve("wrong"))
        assertEquals(0, call.runs)

        guard.approve(pending.token)
        assertEquals(1, call.runs)
        assertEquals("a token works once", "No confirmation request pending.", guard.approve(pending.token))
    }

    @Test fun rejectedConfirmationNeverRuns() = runBlocking {
        val call = Counter("sms", sensitive = true)
        registry.register(call)
        registry.executeAction("sms", emptyMap())
        guard.reject()
        assertEquals(0, call.runs)
        assertNull(guard.pendingRequest.value)
    }

    @Test fun nonSensitiveActionsRunImmediately() = runBlocking {
        val a = Counter("light"); registry.register(a)
        registry.executeAction("light", emptyMap())
        assertEquals(1, a.runs)
    }

    // ---- agent ----

    private fun agent(planJson: String) =
        AgentModeAction({ GeminiCallResult(planJson, "fake") }) { registry }

    @Test fun agentRunsEveryStepInOrder() = runBlocking {
        val a = Counter("a"); val b = Counter("b"); registry.register(a); registry.register(b)
        val plan = """[{"tool":"a","args":{},"why":"x"},{"tool":"b","args":{},"why":"y"}]"""
        val r = agent(plan).execute(mapOf("goal" to "do both"))
        assertEquals(1, a.runs); assertEquals(1, b.runs)
        assertEquals("true", r.cardData!!["executed"])
        assertFalse(r.isError)
    }

    @Test fun agentStopsAtTheFirstFailure() = runBlocking {
        val bad = Counter("bad", fail = true); val after = Counter("after")
        registry.register(bad); registry.register(after)
        val r = agent("""[{"tool":"bad","args":{}},{"tool":"after","args":{}}]""").execute(mapOf("goal" to "g"))
        assertEquals(0, after.runs)
        assertTrue(r.isError)
    }

    @Test fun agentStopsAtASensitiveStepAndDoesNotRunIt() = runBlocking {
        val call = Counter("call", sensitive = true); val after = Counter("after")
        registry.register(call); registry.register(after)
        val r = agent("""[{"tool":"call","args":{}},{"tool":"after","args":{}}]""").execute(mapOf("goal" to "g"))
        assertEquals(0, call.runs); assertEquals(0, after.runs)
        assertTrue(r.spokenResult.contains("confirmation"))
    }

    @Test fun agentNeverRunsInventedTools() = runBlocking {
        val a = Counter("a"); registry.register(a)
        val r = agent("""[{"tool":"format_phone","args":{}},{"tool":"a","args":{}}]""").execute(mapOf("goal" to "g"))
        assertEquals(1, a.runs)
        assertFalse(r.isError)
    }

    @Test fun agentParsesFencedJsonAndCapsSteps() {
        val many = (1..10).joinToString(",") { """{"tool":"a","args":{}}""" }
        val steps = AgentModeAction.parsePlan("```json\n[$many]\n```", setOf("a"))
        assertEquals(AgentModeAction.MAX_STEPS, steps.size)
        assertTrue(AgentModeAction.parsePlan("no plan here", setOf("a")).isEmpty())
    }

    // ---- undo persistence ----

    private class MemPersistence : UndoPersistence {
        var saved: List<JSONObject> = emptyList()
        override fun save(entries: List<JSONObject>) { saved = entries }
        override fun load() = saved
    }

    @Test fun undoSnapshotsAreSavedAndRestored() = runBlocking {
        val p = MemPersistence()
        val s1 = UndoStack().also { it.persistence = p }
        s1.push(UndoableAction("vol", snapshot = JSONObject().put("kind", "volume")) { "undone" })
        s1.push(UndoableAction("closure only") { "x" }) // no snapshot: session-scoped
        assertEquals(1, p.saved.size)

        val s2 = UndoStack()
        s2.restore(p.saved) { UndoableAction("restored ${it.getString("kind")}") { "restored-undo" } }
        assertTrue(s2.canUndo.value)
        assertEquals("restored volume", s2.lastActionDesc.value)
        assertEquals("restored-undo", s2.undoLast())
    }
}
