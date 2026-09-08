package com.example.data

import com.example.util.PrivacyUtils
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val dao: ActivityLogDao) {

    val recentLogs: Flow<List<ActivityLog>> = dao.getRecentLogs(100)
    val allLogs: Flow<List<ActivityLog>> = dao.getRecentLogs(200)

    suspend fun insertLog(log: ActivityLog) {
        val sanitizedCommand = PrivacyUtils.sanitizeForLog(log.command)
        val sanitizedResult = log.result?.let { PrivacyUtils.redactSensitiveText(it) }
        val sanitizedLog = log.copy(
            command = sanitizedCommand,
            result = sanitizedResult
        )
        dao.insertLog(sanitizedLog)
        
        // Auto-prune logs to keep max 500 rows bounded retention
        dao.pruneOldLogs(500)
    }

    suspend fun pruneOldLogs(keepCount: Int = 500) {
        dao.pruneOldLogs(keepCount)
    }

    suspend fun pruneLogsOlderThan(cutoffTimestamp: Long) {
        dao.pruneLogsOlderThan(cutoffTimestamp)
    }

    suspend fun clearAllLogs() {
        dao.clearAllLogs()
    }
}
