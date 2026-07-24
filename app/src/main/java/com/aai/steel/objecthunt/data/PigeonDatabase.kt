package com.aai.steel.objecthunt.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PigeonEntity::class, QueuedDetectionEntity::class], version = 3, exportSchema = false)
abstract class PigeonDatabase : RoomDatabase() {

    abstract fun pigeonDao(): PigeonDao
    abstract fun queuedDetectionDao(): QueuedDetectionDao

    companion object {
        @Volatile
        private var INSTANCE: PigeonDatabase? = null

        fun getInstance(context: Context): PigeonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PigeonDatabase::class.java,
                    "pigeon_hunter.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // For tests: in-memory DB with synchronous executors so runTest's advanceUntilIdle can control Room work
        // Room by default uses its own background queryExecutor which is not seen by TestDispatcher -> timeout + Main looper unexecuted runnables
        fun getInMemoryInstance(context: Context): PigeonDatabase {
            val directExecutor = java.util.concurrent.Executor { it.run() } // runs on calling thread (testDispatcher)
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PigeonDatabase::class.java
            )
                .allowMainThreadQueries()
                .setQueryExecutor(directExecutor)
                .setTransactionExecutor(directExecutor)
                .build()
        }
    }
}
