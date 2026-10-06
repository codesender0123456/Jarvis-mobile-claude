package com.example.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jarvis_memories",
    indices = [
        Index(value = ["key"]),
        Index(value = ["category"]),
        Index(value = ["timestamp"])
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val fact: String,
    val category: String = "general",
    val timestamp: Long = System.currentTimeMillis(),
    val isCore: Boolean = false,
    val isConsumed: Boolean = false
)
