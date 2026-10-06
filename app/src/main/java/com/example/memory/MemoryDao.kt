package com.example.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM jarvis_memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM jarvis_memories WHERE isCore = 1 OR isConsumed = 0 ORDER BY timestamp DESC LIMIT 30")
    suspend fun getActiveMemories(): List<MemoryEntity>

    @Query("SELECT * FROM jarvis_memories WHERE fact LIKE '%' || :query || '%' OR key LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM jarvis_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("UPDATE jarvis_memories SET isConsumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long)

    @Query("SELECT * FROM jarvis_memories WHERE category = 'daily_recap' AND isConsumed = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getPendingDailyRecap(): MemoryEntity?
}
