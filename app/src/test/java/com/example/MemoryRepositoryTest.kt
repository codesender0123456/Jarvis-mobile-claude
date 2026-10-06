package com.example

import com.example.memory.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {

    @Test
    fun testMemoryEntityCreation() {
        val memory = MemoryEntity(
            id = 1L,
            key = "favorite_tea",
            fact = "Earl Grey, black, two sugars",
            category = "preference",
            isCore = true
        )

        assertEquals("favorite_tea", memory.key)
        assertEquals("Earl Grey, black, two sugars", memory.fact)
        assertEquals("preference", memory.category)
        assertTrue(memory.isCore)
        assertFalse(memory.isConsumed)
    }

    @Test
    fun testMemoryPromptIndexFormatting() {
        val memories = listOf(
            MemoryEntity(id = 1L, key = "assistant_role", fact = "Executive AI", category = "identity"),
            MemoryEntity(id = 2L, key = "vehicle", fact = "Audi e-tron GT", category = "preference")
        )

        val formatted = memories.joinToString("\n") { "- [${it.category}] ${it.key}: ${it.fact}" }
        assertTrue(formatted.contains("- [identity] assistant_role: Executive AI"))
        assertTrue(formatted.contains("- [preference] vehicle: Audi e-tron GT"))
    }
}
