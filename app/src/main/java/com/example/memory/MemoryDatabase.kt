package com.example.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MemoryEntity::class], version = 1, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            // Double-checked locking requires re-reading INSTANCE inside the lock; without it
            // two threads racing at startup each build a separate database handle.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "jarvis_memory_db"
                )
                    // No destructive fallback: persistent facts must never be wiped silently.
                    // When the schema changes, bump the version AND add an explicit Migration here.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
