package com.github.lucasaguiar11.kotlin_baselime_logger

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogDatabase private constructor(context: Context) {

    private val database = LogRoomDatabase.getDatabase(context)
    private val logDao = database.logDao()

    companion object {
        @Volatile
        private var INSTANCE: LogDatabase? = null

        fun getInstance(context: Context): LogDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    suspend fun insertLog(logEntry: LogEntry): Long {
        return try {
            withContext(Dispatchers.IO) {
                logDao.insertLog(logEntry)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error inserting log to database: ${e.message}")
            }
            -1L
        }
    }

    suspend fun getPendingLogs(limit: Int = 100): List<LogEntry> {
        return try {
            withContext(Dispatchers.IO) {
                logDao.getPendingLogs(limit)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error reading pending logs: ${e.message}")
            }
            emptyList()
        }
    }

    suspend fun getLogsForRetry(currentTime: Long): List<LogEntry> {
        return try {
            withContext(Dispatchers.IO) {
                logDao.getLogsForRetry(currentTime)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error reading logs for retry: ${e.message}")
            }
            emptyList()
        }
    }

    suspend fun updateLogStatus(id: Long, status: LogStatus, errorMessage: String? = null) {
        try {
            withContext(Dispatchers.IO) {
                logDao.updateLogStatus(id, status, errorMessage)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error updating log status: ${e.message}")
            }
        }
    }

    suspend fun updateRetryCount(id: Long, retryCount: Int) {
        try {
            withContext(Dispatchers.IO) {
                logDao.updateRetryCount(id, retryCount)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error updating retry count: ${e.message}")
            }
        }
    }

    suspend fun markAsSent(ids: List<Long>) {
        if (ids.isEmpty()) return

        try {
            withContext(Dispatchers.IO) {
                logDao.markAsSent(ids)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error marking logs as sent: ${e.message}")
            }
        }
    }

    suspend fun deleteSentLogsBefore(cutoffTime: Long): Int {
        return try {
            withContext(Dispatchers.IO) {
                logDao.deleteSentLogsBefore(cutoffTime)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error deleting old sent logs: ${e.message}")
            }
            0
        }
    }

    suspend fun deleteOldestFailedLogs(keepCount: Int): Int {
        return try {
            withContext(Dispatchers.IO) {
                logDao.deleteOldestFailedLogs(keepCount)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error deleting oldest failed logs: ${e.message}")
            }
            0
        }
    }

    suspend fun getLogStats(): LogStats {
        return try {
            withContext(Dispatchers.IO) {
                val statusCounts = logDao.getLogStatsCounts()
                val stats = LogStats()

                statusCounts.forEach { statusCount ->
                    when (statusCount.status) {
                        LogStatus.PENDING -> stats.pending = statusCount.count
                        LogStatus.SENDING -> stats.sending = statusCount.count
                        LogStatus.SENT -> stats.sent = statusCount.count
                        LogStatus.FAILED -> stats.failed = statusCount.count
                        LogStatus.DELETED -> { /* ignore */
                        }
                    }
                }
                stats
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error getting log stats: ${e.message}")
            }
            LogStats()
        }
    }

    // Métodos adicionais do Room para funcionalidades extras
    suspend fun getLogsByRequestId(requestId: String): List<LogEntry> {
        return try {
            withContext(Dispatchers.IO) {
                logDao.getLogsByRequestId(requestId)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error getting logs by request ID: ${e.message}")
            }
            emptyList()
        }
    }

    suspend fun getLogsByLevelSince(level: String, since: Long, limit: Int = 100): List<LogEntry> {
        return try {
            withContext(Dispatchers.IO) {
                logDao.getLogsByLevelSince(level, since, limit)
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error getting logs by level: ${e.message}")
            }
            emptyList()
        }
    }
}

data class LogStats(
    var pending: Int = 0,
    var sending: Int = 0,
    var sent: Int = 0,
    var failed: Int = 0
) {
    val total: Int get() = pending + sending + sent + failed
}