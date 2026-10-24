package com.example.somsecurity.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityEventDao {

    @Query("SELECT * FROM security_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<SecurityEventEntity>>

    @Query("SELECT * FROM security_events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: Long): SecurityEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SecurityEventEntity): Long

    @Update
    suspend fun updateEvent(event: SecurityEventEntity)

    @Delete
    suspend fun deleteEvent(event: SecurityEventEntity)

    @Query("DELETE FROM security_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM security_events")
    suspend fun deleteAll()
}

