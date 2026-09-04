package com.example.data

import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val dao: ActivityLogDao) {
    val allLogs: Flow<List<ActivityLog>> = dao.getAllLogs()

    suspend fun insertLog(log: ActivityLog) {
        dao.insertLog(log)
    }
}
