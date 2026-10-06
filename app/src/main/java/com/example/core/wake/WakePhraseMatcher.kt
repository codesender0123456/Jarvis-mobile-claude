package com.example.core.wake

/**
 * Decides whether recognised speech contains the wake phrase ("hey jarvis" or "hey <assistant
 * name>"). Pure string logic so it can be unit-tested. A bare name is NOT enough: requiring a
 * greeting directly before it keeps ordinary conversation about Jarvis from waking the phone.
 */
object WakePhraseMatcher {
    private val GREETINGS = setOf("hey", "hay", "hi", "ok", "okay", "yo", "hello", "ei")

    private fun tokens(text: String): List<String> =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    fun matches(heard: String, assistantName: String = "jarvis"): Boolean {
        val t = tokens(heard)
        if (t.isEmpty()) return false
        val nameTokens = tokens(assistantName)
        val names = HashSet<String>().apply {
            add("jarvis")
            if (nameTokens.size == 1) add(nameTokens[0])
        }
        // "heyjarvis" run together by the recogniser
        if (t.any { token -> names.any { token == "hey$it" || token == "ok$it" } }) return true
        for (i in 1 until t.size) {
            if (t[i] in names && t[i - 1] in GREETINGS) return true
        }
        // multi-word assistant names: "hey" followed by the full name
        if (nameTokens.size > 1) {
            for (i in 1..t.size - nameTokens.size) {
                if (t[i - 1] in GREETINGS && t.subList(i, i + nameTokens.size) == nameTokens) return true
            }
        }
        return false
    }
}
