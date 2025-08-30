package com.github.lucasaguiar11.kotlin_baselime_logger

import androidx.room.*

@Dao
interface LogDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(logEntry: LogEntry): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<LogEntry>): List<Long>
    
    @Query("""
        SELECT * FROM logs 
        WHERE status IN ('PENDING', 'FAILED') 
        ORDER BY 
            CASE level 
                WHEN 'ERROR' THEN 1 
                WHEN 'WARN' THEN 2 
                WHEN 'INFO' THEN 3 
                WHEN 'DEBUG' THEN 4 
                WHEN 'TRACE' THEN 5 
                ELSE 6 
            END,
            created_at ASC 
        LIMIT :limit
    """)
    suspend fun getPendingLogs(limit: Int = 100): List<LogEntry>
    
    @Query("""
        SELECT * FROM logs 
        WHERE status = 'FAILED' 
        AND retry_count < 5 
        AND (last_retry_at IS NULL OR last_retry_at < :currentTime) 
        ORDER BY created_at ASC 
        LIMIT 50
    """)
    suspend fun getLogsForRetry(currentTime: Long): List<LogEntry>
    
    @Query("UPDATE logs SET status = :status, error_message = :errorMessage, last_retry_at = :currentTime WHERE id = :id")
    suspend fun updateLogStatus(id: Long, status: LogStatus, errorMessage: String? = null, currentTime: Long = System.currentTimeMillis())
    
    @Query("UPDATE logs SET retry_count = :retryCount, last_retry_at = :currentTime, status = 'FAILED' WHERE id = :id")
    suspend fun updateRetryCount(id: Long, retryCount: Int, currentTime: Long = System.currentTimeMillis())
    
    @Query("UPDATE logs SET status = 'SENT' WHERE id IN (:ids)")
    suspend fun markAsSent(ids: List<Long>)
    
    @Query("DELETE FROM logs WHERE status = 'SENT' AND created_at < :cutoffTime")
    suspend fun deleteSentLogsBefore(cutoffTime: Long): Int
    
    @Query("""
        DELETE FROM logs 
        WHERE id NOT IN (
            SELECT id FROM logs 
            WHERE status = 'FAILED' 
            ORDER BY created_at DESC 
            LIMIT :keepCount
        ) AND status = 'FAILED'
    """)
    suspend fun deleteOldestFailedLogs(keepCount: Int): Int
    
    @Query("""
        SELECT 
            status,
            COUNT(*) as count
        FROM logs 
        GROUP BY status
    """)
    suspend fun getLogStatsCounts(): List<LogStatusCount>
    
    @Query("SELECT COUNT(*) FROM logs WHERE status = :status")
    suspend fun getLogCountByStatus(status: LogStatus): Int
    
    @Query("SELECT COUNT(*) FROM logs")
    suspend fun getTotalLogCount(): Int
    
    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun deleteLog(id: Long)
    
    @Query("DELETE FROM logs WHERE status = 'DELETED'")
    suspend fun deleteMarkedLogs(): Int
    
    @Update
    suspend fun updateLog(logEntry: LogEntry)
    
    @Query("SELECT * FROM logs WHERE request_id = :requestId ORDER BY created_at ASC")
    suspend fun getLogsByRequestId(requestId: String): List<LogEntry>
    
    @Query("SELECT * FROM logs WHERE level = :level AND created_at >= :since ORDER BY created_at DESC LIMIT :limit")
    suspend fun getLogsByLevelSince(level: String, since: Long, limit: Int = 100): List<LogEntry>
}

data class LogStatusCount(
    val status: LogStatus,
    val count: Int
)