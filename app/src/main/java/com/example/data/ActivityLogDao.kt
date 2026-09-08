package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<ActivityLog>>

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ActivityLog>>

    @Query("SELECT COUNT(*) FROM activity_logs")
    suspend fun getLogCount(): Int

    @Insert
    suspend fun insertLog(log: ActivityLog)

    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM activity_logs ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneOldLogs(keepCount: Int = 500)

    @Query("DELETE FROM activity_logs WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneLogsOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM activity_logs")
    suspend fun clearAllLogs()
}
