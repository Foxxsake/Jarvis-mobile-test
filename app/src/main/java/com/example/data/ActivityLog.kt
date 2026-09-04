package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_logs")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val command: String,
    val classification: String,
    val proposedTool: String?,
    val status: String,
    val timestamp: Long = System.currentTimeMillis(),
    val result: String?,
    val approvalRequired: Boolean
)
