package com.example.somsecurity.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // "FAILED_UNLOCK", "MANUAL_PHOTO", "MANUAL_VIDEO"
    val failedAttemptsCount: Int = 0,
    val captureStatus: String, // "SUCCESS", "FAILED", "RESTRICTED", "INTERRUPTED", "PERMISSION_DENIED"
    val mediaPath: String? = null,
    val mediaType: String? = null, // "PHOTO", "VIDEO"
    val errorMessage: String? = null
)

