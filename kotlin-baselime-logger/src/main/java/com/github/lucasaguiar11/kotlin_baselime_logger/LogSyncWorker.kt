package com.github.lucasaguiar11.kotlin_baselime_logger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger as OtelLogger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Tracer as OtelTracer
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.*
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class LogSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val database = LogDatabase.getInstance(context)
    private val gson = Gson()
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("LogSyncWorker: Starting logs and traces sync...")
            }

            // Verifica se o OpenTelemetry está inicializado
            val loggerProvider = OpenTelemetryConfig.getLoggerProvider()
            val tracerProvider = OpenTelemetryConfig.getTracerProvider()
            if (loggerProvider == null && tracerProvider == null) {
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("LogSyncWorker: OpenTelemetry not initialized, skipping sync")
                }
                return@withContext Result.success()
            }

            val otelLogger = loggerProvider?.get("kotlin-otel-logger")
            val otelTracer = tracerProvider?.get("kotlin-otel-tracer")
            var totalProcessed = 0
            var successCount = 0
            var failureCount = 0

            // Processa logs pendentes em lotes
            otelLogger?.let { logger ->
                do {
                    val pendingLogs = database.getPendingLogs(limit = 50)
                    if (pendingLogs.isEmpty()) break

                    for (logEntry in pendingLogs) {
                        try {
                            database.updateLogStatus(logEntry.id, LogStatus.SENDING)
                            sendLogToOtel(logger, logEntry)
                            database.updateLogStatus(logEntry.id, LogStatus.SENT)
                            successCount++
                        } catch (e: Exception) {
                            handleLogFailure(logEntry, e)
                            failureCount++
                        }
                        totalProcessed++
                    }

                    // Evita sobrecarga - pausa entre lotes
                    if (pendingLogs.size >= 50) {
                        delay(100)
                    }

                } while (pendingLogs.size >= 50 && totalProcessed < 500) // Limite máximo por execução

                // Processa logs para retry
                processRetryLogs(logger)
            }

            // Processa traces pendentes em lotes
            otelTracer?.let { tracer ->
                do {
                    val pendingTraces = database.getPendingTraces(limit = 50)
                    if (pendingTraces.isEmpty()) break

                    for (traceEntry in pendingTraces) {
                        try {
                            sendTraceToOtel(tracer, traceEntry)
                            successCount++
                        } catch (e: Exception) {
                            handleTraceFailure(traceEntry, e)
                            failureCount++
                        }
                        totalProcessed++
                    }

                    // Evita sobrecarga - pausa entre lotes
                    if (pendingTraces.size >= 50) {
                        delay(100)
                    }

                } while (pendingTraces.size >= 50 && totalProcessed < 500) // Limite máximo por execução
            }

            // Limpeza de logs e traces antigos
            performCleanup()
            
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("LogSyncWorker: Completed. Processed: $totalProcessed, Success: $successCount, Failed: $failureCount")
            }
            
            Result.success()
            
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("LogSyncWorker: Error during sync: ${e.message}")
            }
            Result.retry()
        }
    }
    
    private suspend fun processRetryLogs(otelLogger: OtelLogger) {
        val currentTime = System.currentTimeMillis()
        val retryLogs = database.getLogsForRetry(currentTime)
        
        for (logEntry in retryLogs) {
            val retryDelay = calculateRetryDelay(logEntry.retryCount)
            val nextRetryTime = (logEntry.lastRetryAt ?: logEntry.createdAt) + retryDelay
            
            if (currentTime >= nextRetryTime) {
                try {
                    database.updateLogStatus(logEntry.id, LogStatus.SENDING)
                    sendLogToOtel(otelLogger, logEntry)
                    database.updateLogStatus(logEntry.id, LogStatus.SENT)
                } catch (e: Exception) {
                    handleLogFailure(logEntry, e)
                }
            }
        }
    }
    
    private fun sendLogToOtel(otelLogger: OtelLogger, logEntry: LogEntry) {
        val attributes = deserializeAttributes(logEntry.attributes)
        val severity = mapLevelToSeverity(logEntry.level)
        
        val logRecordBuilder = otelLogger.logRecordBuilder()
            .setTimestamp(logEntry.timestamp, TimeUnit.MILLISECONDS)
            .setSeverity(severity)
            .setBody(logEntry.message)
            .setAllAttributes(attributes)
        
        logRecordBuilder.emit()
    }

    private suspend fun sendTraceToOtel(otelTracer: OtelTracer, traceEntry: TraceEntry) {
        try {
            val attributes = deserializeAttributes(traceEntry.attributes)

            // Cria span no OpenTelemetry
            val spanBuilder = otelTracer.spanBuilder(traceEntry.operationName)

            // Configura timestamps
            spanBuilder.setStartTimestamp(traceEntry.startTime, TimeUnit.MILLISECONDS)

            // Adiciona atributos
            spanBuilder.setAllAttributes(attributes)

            val span = spanBuilder.startSpan()

            // Define status baseado no trace status
            when (traceEntry.status) {
                TraceStatus.ENDED_SUCCESS -> span.setStatus(StatusCode.OK)
                TraceStatus.ENDED_ERROR -> span.setStatus(StatusCode.ERROR, traceEntry.statusMessage ?: "Operation failed")
                else -> span.setStatus(StatusCode.OK)
            }

            // Define end timestamp se disponível
            traceEntry.endTime?.let { endTime ->
                span.end(endTime, TimeUnit.MILLISECONDS)
            } ?: span.end()

            // Marca como enviado
            database.updateTraceStatus(traceEntry.id, TraceStatus.SENT)

        } catch (e: Exception) {
            throw e // Re-lança para ser tratado pelo handler de falha
        }
    }

    private suspend fun handleTraceFailure(traceEntry: TraceEntry, exception: Exception) {
        val newRetryCount = traceEntry.retryCount + 1
        val maxRetries = 5
        val errorMessage = "${exception.javaClass.simpleName}: ${exception.message}"

        when {
            newRetryCount >= maxRetries -> {
                database.updateTraceStatus(traceEntry.id, TraceStatus.FAILED, errorMessage)
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Trace ${traceEntry.id} failed permanently after $maxRetries retries: $errorMessage")
                }
            }
            isTemporaryError(exception) -> {
                database.updateTraceRetryCount(traceEntry.id, newRetryCount)
                val retryDelay = calculateRetryDelay(newRetryCount)
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Trace ${traceEntry.id} failed (retry $newRetryCount), will retry in ${retryDelay}ms: $errorMessage")
                }
            }
            else -> {
                database.updateTraceStatus(traceEntry.id, TraceStatus.FAILED, errorMessage)
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Trace ${traceEntry.id} failed permanently (non-retryable error): $errorMessage")
                }
            }
        }
    }
    
    private fun deserializeAttributes(attributesJson: String): Attributes {
        if (attributesJson.isBlank()) {
            return Attributes.empty()
        }
        
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val attributeMap: Map<String, Any> = gson.fromJson(attributesJson, type)
            
            val attributesBuilder = Attributes.builder()
            
            attributeMap.forEach { (key, value) ->
                when (value) {
                    is String -> attributesBuilder.put(AttributeKey.stringKey(key), value)
                    is Number -> {
                        when {
                            value is Double || value is Float -> 
                                attributesBuilder.put(AttributeKey.doubleKey(key), value.toDouble())
                            else -> 
                                attributesBuilder.put(AttributeKey.longKey(key), value.toLong())
                        }
                    }
                    is Boolean -> attributesBuilder.put(AttributeKey.booleanKey(key), value)
                    else -> attributesBuilder.put(AttributeKey.stringKey(key), value.toString())
                }
            }
            
            attributesBuilder.build()
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error deserializing attributes: ${e.message}")
            }
            Attributes.empty()
        }
    }
    
    private fun mapLevelToSeverity(level: String): Severity {
        return when (level.uppercase()) {
            "TRACE" -> Severity.TRACE
            "DEBUG" -> Severity.DEBUG
            "INFO" -> Severity.INFO
            "WARN" -> Severity.WARN
            "ERROR" -> Severity.ERROR
            else -> Severity.INFO
        }
    }
    
    private suspend fun handleLogFailure(logEntry: LogEntry, exception: Exception) {
        val newRetryCount = logEntry.retryCount + 1
        val maxRetries = 5
        val errorMessage = "${exception.javaClass.simpleName}: ${exception.message}"
        
        when {
            newRetryCount >= maxRetries -> {
                database.updateLogStatus(logEntry.id, LogStatus.FAILED, errorMessage)
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Log ${logEntry.id} failed permanently after $maxRetries retries: $errorMessage")
                }
            }
            isTemporaryError(exception) -> {
                database.updateRetryCount(logEntry.id, newRetryCount)
                val retryDelay = calculateRetryDelay(newRetryCount)
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Log ${logEntry.id} failed (retry $newRetryCount), will retry in ${retryDelay}ms: $errorMessage")
                }
            }
            else -> {
                database.updateLogStatus(logEntry.id, LogStatus.FAILED, errorMessage)
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Log ${logEntry.id} failed permanently (non-retryable error): $errorMessage")
                }
            }
        }
    }
    
    private fun isTemporaryError(exception: Exception): Boolean {
        return when (exception) {
            is UnknownHostException -> true
            is SocketTimeoutException -> true
            is ConnectException -> true
            else -> {
                // Se a mensagem contém indicadores de erro temporário
                val message = exception.message?.lowercase() ?: ""
                message.contains("timeout") || 
                message.contains("connection") || 
                message.contains("network") ||
                message.contains("unavailable") ||
                message.contains("server error") ||
                message.contains("503") ||
                message.contains("502") ||
                message.contains("504") ||
                message.contains("429") // Rate limit
            }
        }
    }
    
    private fun calculateRetryDelay(retryCount: Int): Long {
        val baseDelay = 30_000L // 30 segundos
        val maxDelay = 24 * 60 * 60 * 1000L // 24 horas
        val exponentialDelay = baseDelay * (2.0.pow(retryCount).toLong())
        
        return minOf(exponentialDelay, maxDelay)
    }
    
    private suspend fun performCleanup() {
        try {
            val maxRetentionDays = 30
            val cutoffTime = System.currentTimeMillis() - (maxRetentionDays * 24 * 60 * 60 * 1000L)
            
            // Remove logs enviados há mais de X dias
            val deletedSent = database.deleteSentLogsBefore(cutoffTime)
            
            // Limita logs falhos para evitar crescimento infinito
            val maxFailedLogs = 10000
            val deletedFailed = database.deleteOldestFailedLogs(maxFailedLogs)
            
            if (OpenTelemetryConfig.isDebugEnabled() && (deletedSent > 0 || deletedFailed > 0)) {
                println("Cleanup: Deleted $deletedSent sent logs and $deletedFailed old failed logs")
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error during cleanup: ${e.message}")
            }
        }
    }
}