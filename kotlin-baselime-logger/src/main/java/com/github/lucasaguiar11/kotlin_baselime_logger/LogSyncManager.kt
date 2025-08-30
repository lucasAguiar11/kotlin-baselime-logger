package com.github.lucasaguiar11.kotlin_baselime_logger

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class LogSyncManager private constructor(private val context: Context) {

    companion object {
        private const val WORK_NAME_PERIODIC = "log_sync_periodic"
        private const val WORK_NAME_IMMEDIATE = "log_sync_immediate"
        private const val WORK_NAME_RETRY = "log_sync_retry"

        @Volatile
        private var INSTANCE: LogSyncManager? = null

        fun getInstance(context: Context): LogSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogSyncManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val workManager = WorkManager.getInstance(context)

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<LogSyncWorker>(
            repeatInterval = 15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )

        if (OpenTelemetryConfig.isDebugEnabled()) {
            println("Scheduled periodic log sync every 15 minutes")
        }
    }

    fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<LogSyncWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )

        if (OpenTelemetryConfig.isDebugEnabled()) {
            println("Triggered immediate log sync")
        }
    }

    fun cancelAllSync() {
        workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
        workManager.cancelUniqueWork(WORK_NAME_IMMEDIATE)
        workManager.cancelUniqueWork(WORK_NAME_RETRY)

        if (OpenTelemetryConfig.isDebugEnabled()) {
            println("Cancelled all log sync work")
        }
    }

    suspend fun getSyncStatus(): LogSyncStatus {
        val database = LogDatabase.getInstance(context)
        val stats = database.getLogStats()

        return LogSyncStatus(
            pendingLogs = stats.pending,
            failedLogs = stats.failed,
            sentLogs = stats.sent,
            totalLogs = stats.total,
        )
    }
}

data class LogSyncStatus(
    val pendingLogs: Int,
    val failedLogs: Int,
    val sentLogs: Int,
    val totalLogs: Int,
    val lastSyncTime: Long = 0L
)