package com.example.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UndoableAction(
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Serializable description of the change; entries that have one survive process death. */
    val snapshot: org.json.JSONObject? = null,
    val undo: suspend () -> String
)

class UndoStack {

    private val stack = mutableListOf<UndoableAction>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _lastActionDesc = MutableStateFlow<String?>(null)
    val lastActionDesc: StateFlow<String?> = _lastActionDesc.asStateFlow()

    /** Set by the Application; every change to the stack is mirrored there. */
    @Volatile var persistence: UndoPersistence? = null

    /** Re-adds entries saved by a previous process (oldest first). */
    @Synchronized
    fun restore(entries: List<org.json.JSONObject>, rebuild: (org.json.JSONObject) -> UndoableAction?) {
        entries.mapNotNull(rebuild).forEach { stack.add(it) }
        while (stack.size > MAX_DEPTH) stack.removeAt(0)
        _canUndo.value = stack.isNotEmpty()
        _lastActionDesc.value = stack.lastOrNull()?.description
    }

    private fun persistLocked() {
        runCatching { persistence?.save(stack.mapNotNull { it.snapshot }) }
    }

    @Synchronized
    fun push(action: UndoableAction) {
        stack.add(action)
        // The stack lives for the whole process (it is an Application-scoped singleton), so it
        // needs a cap: an always-on voice session would otherwise grow it without bound.
        while (stack.size > MAX_DEPTH) {
            stack.removeAt(0)
        }
        _canUndo.value = stack.isNotEmpty()
        _lastActionDesc.value = stack.lastOrNull()?.description
        persistLocked()
    }

    suspend fun undoLast(): String {
        val action = synchronized(this) {
            if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)
        } ?: return "Nothing to undo."

        return try {
            action.undo()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The entry stays consumed; report the failure rather than pretending it worked.
            "Failed to undo '${action.description}': ${e.message}"
        } finally {
            synchronized(this) {
                _canUndo.value = stack.isNotEmpty()
                _lastActionDesc.value = stack.lastOrNull()?.description
                persistLocked()
            }
        }
    }

    @Synchronized
    fun clear() {
        stack.clear()
        _canUndo.value = false
        _lastActionDesc.value = null
    }

    companion object {
        const val MAX_DEPTH = 50
    }
}