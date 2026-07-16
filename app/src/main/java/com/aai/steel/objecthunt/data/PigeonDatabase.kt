package com.aai.steel.objecthunt.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PigeonEntity::class], version = 1, exportSchema = false)
abstract class PigeonDatabase : RoomDatabase() {

    abstract fun pigeonDao(): PigeonDao

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

        // For tests: in-memory DB
        fun getInMemoryInstance(context: Context): PigeonDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PigeonDatabase::class.java
            )
                .allowMainThreadQueries() // for tests only
                .build()
        }
    }
}
