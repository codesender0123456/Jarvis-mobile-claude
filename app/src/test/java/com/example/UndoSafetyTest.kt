package com.example

import com.example.actions.SafetyGuard
import com.example.actions.UndoStack
import com.example.actions.UndoableAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoSafetyTest {

    @Test
    fun testUndoStackPushAndPop() = runBlocking {
        val undoStack = UndoStack()
        assertFalse(undoStack.canUndo.value)

        var counter = 10
        undoStack.push(
            UndoableAction("Incremented counter") {
                counter -= 1
                "Reverted counter to $counter"
            }
        )

        assertTrue(undoStack.canUndo.value)
        assertEquals("Incremented counter", undoStack.lastActionDesc.value)

        val result = undoStack.undoLast()
        assertEquals("Reverted counter to 9", result)
        assertEquals(9, counter)
        assertFalse(undoStack.canUndo.value)
    }

    @Test
    fun testSafetyGuardTokenValidation() = runBlocking {
        val safetyGuard = SafetyGuard()
        var actionExecuted = false

        val prompt = safetyGuard.createConfirmationRequest(
            actionName = "delete_file",
            title = "Delete File",
            details = "Delete important.txt?",
            onApproved = {
                actionExecuted = true
                "File deleted"
            }
        )

        assertTrue(prompt.contains("Confirmation required"))
        val pending = safetyGuard.pendingRequest.value
        assertNotNull(pending)

        // Invalid token should fail
        val rejectResult = safetyGuard.approve("invalid_token")
        assertEquals("Security token mismatch. Action denied.", rejectResult)
        assertFalse(actionExecuted)

        // Valid token executes action
        val successResult = safetyGuard.approve(pending!!.token)
        assertEquals("File deleted", successResult)
        assertTrue(actionExecuted)
    }
}
