package com.example.memory

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    val allMemoriesFlow: Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun rememberFact(
        key: String,
        fact: String,
        category: String = "preference",
        isCore: Boolean = false
    ): Long {
        val memory = MemoryEntity(
            key = key.trim(),
            fact = fact.trim(),
            category = category.trim().lowercase(),
            isCore = isCore
        )
        return memoryDao.insertMemory(memory)
    }

    suspend fun recallMemory(query: String): List<MemoryEntity> {
        return memoryDao.searchMemories(query)
    }

    suspend fun getPromptIndex(): String {
        val active = memoryDao.getActiveMemories()
        if (active.isEmpty()) return ""
        return active.joinToString("\n") { "- [${it.category}] ${it.key}: ${it.fact}" }
    }

    suspend fun deleteMemory(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun recordSessionRecap(recap: String): Long {
        val memory = MemoryEntity(
            key = "session_recap_${System.currentTimeMillis()}",
            fact = recap.trim(),
            category = "daily_recap",
            isConsumed = false
        )
        return memoryDao.insertMemory(memory)
    }

    suspend fun consumeMorningRecapIfAvailable(): String? {
        val recap = memoryDao.getPendingDailyRecap() ?: return null
        memoryDao.markConsumed(recap.id)
        return recap.fact
    }
}
