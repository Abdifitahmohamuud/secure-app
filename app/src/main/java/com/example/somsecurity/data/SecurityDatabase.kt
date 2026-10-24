package com.example.somsecurity.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SecurityEventEntity::class], version = 1, exportSchema = false)
abstract class SecurityDatabase : RoomDatabase() {

    abstract fun securityEventDao(): SecurityEventDao

    companion object {
        @Volatile
        private var INSTANCE: SecurityDatabase? = null

        fun getInstance(context: Context): SecurityDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SecurityDatabase::class.java,
                    "som_security.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

