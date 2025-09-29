package com.github.lucasaguiar11.kotlin_baselime_logger

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import com.github.lucasaguiar11.kotlin_baselime_logger.LoggerUtil.toMap
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger as OtelLogger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Tracer as OtelTracer
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import java.util.UUID
import java.util.concurrent.TimeUnit

object Logger {
    private var otelLogger: OtelLogger? = null
    private var otelTracer: OtelTracer? = null
    private var isInitialized = false
    private var database: LogDatabase? = null
    private var syncManager: LogSyncManager? = null
    private var contextRef: WeakReference<Context>? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(context: Context? = null) {
        try {
            val loggerProvider = OpenTelemetryConfig.getLoggerProvider()
            val tracerProvider = OpenTelemetryConfig.getTracerProvider()
            otelLogger = loggerProvider?.get("kotlin-otel-logger")
            otelTracer = tracerProvider?.get("kotlin-otel-tracer")
            isInitialized = true

            // Inicializa sistema de persistência se context foi fornecido
            context?.let { ctx ->
                // Usa applicationContext para evitar vazamentos de memória
                val appContext = ctx.applicationContext
                contextRef = WeakReference(appContext)
                database = LogDatabase.getInstance(appContext)
                syncManager = LogSyncManager.getInstance(appContext)

                // Agenda sincronização periódica
                syncManager?.schedulePeriodicSync()

                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Logger initialized with persistence support")
                }
            }

            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("OtelLogger and OtelTracer initialized successfully")
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Failed to initialize OtelLogger: ${e.message}")
            }
            isInitialized = false
        }
    }

    private fun mapLoggerLevelToSeverity(level: LoggerLevel): Severity {
        return when (level) {
            LoggerLevel.TRACE -> Severity.TRACE
            LoggerLevel.DEBUG -> Severity.DEBUG
            LoggerLevel.INFO -> Severity.INFO
            LoggerLevel.WARN -> Severity.WARN
            LoggerLevel.ERROR -> Severity.ERROR
        }
    }

    private fun createAttributes(
        tag: String?,
        data: Map<String, Any>?,
        duration: Long?,
        requestId: String?,
        error: String?
    ): Attributes {
        val attributesBuilder = Attributes.builder()

        try {
            tag?.let {
                attributesBuilder.put(AttributeKey.stringKey("logger.name"), it)
            }

            duration?.let {
                attributesBuilder.put(AttributeKey.longKey("duration_ms"), it)
            }

            requestId?.let {
                attributesBuilder.put(AttributeKey.stringKey("request_id"), it)
            }

            error?.let {
                attributesBuilder.put(AttributeKey.stringKey("exception.message"), it)
            }

            // Adiciona dados customizados com tratamento de erro
            data?.forEach { (key, value) ->
                try {
                    when (value) {
                        is String -> attributesBuilder.put(AttributeKey.stringKey(key), value)
                        is Long -> attributesBuilder.put(AttributeKey.longKey(key), value)
                        is Double -> attributesBuilder.put(AttributeKey.doubleKey(key), value)
                        is Boolean -> attributesBuilder.put(AttributeKey.booleanKey(key), value)
                        is Int -> attributesBuilder.put(AttributeKey.longKey(key), value.toLong())
                        is Float -> attributesBuilder.put(
                            AttributeKey.doubleKey(key),
                            value.toDouble()
                        )

                        else -> attributesBuilder.put(AttributeKey.stringKey(key), value.toString())
                    }
                } catch (e: Exception) {
                    if (OpenTelemetryConfig.isDebugEnabled()) {
                        println("Warning: Could not add attribute $key=$value: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error creating attributes: ${e.message}")
            }
        }

        return attributesBuilder.build()
    }

    private fun sendLog(
        level: LoggerLevel,
        tag: String,
        message: String,
        data: Map<String, Any>? = null,
        obj: Any? = null,
        duration: Long? = null,
        throwable: Throwable? = null,
        requestId: String? = null
    ) {
        // 1. SEMPRE persiste no banco primeiro (se disponível)
        database?.let { db ->
            coroutineScope.launch {
                try {
                    val attributesJson = LogAttributeSerializer.serializeAttributes(
                        tag, data, obj, duration, requestId, throwable
                    )

                    val logEntry = LogEntry(
                        timestamp = getCurrentTimestamp(),
                        level = level.name,
                        tag = tag,
                        message = message,
                        attributes = attributesJson,
                        requestId = requestId,
                        status = LogStatus.PENDING
                    )

                    db.insertLog(logEntry)

                    if (OpenTelemetryConfig.isDebugEnabled()) {
                        println("Log persisted to database: $level - $tag - $message")
                    }

                    // Tenta envio imediato se possível
                    if (isInitialized && isNetworkAvailable()) {
                        syncManager?.triggerImmediateSync()
                    }

                } catch (e: Exception) {
                    if (OpenTelemetryConfig.isDebugEnabled()) {
                        println("Error persisting log to database: ${e.message}")
                    }
                }
            }
        }

        // 2. Se database está disponível, usa apenas persistence (evita duplicação)
        if (database != null) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Using database persistence, skipping direct send")
            }
            return
        }

        // 3. Fallback: envio direto apenas se database não está disponível
        if (!isInitialized) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("OtelLogger not initialized and no database, log lost")
            }
            return
        }

        try {
            otelLogger?.let { logger ->

                var innerData = if (data != null) {
                    OpenTelemetryConfig.getDefaultData()?.plus(data) ?: data
                } else {
                    OpenTelemetryConfig.getDefaultData()
                }

                val map = obj?.toMap() ?: emptyMap()
                innerData = innerData?.plus(map)

                val error = throwable?.let { getErrorString(it) }
                val attributes = createAttributes(tag, innerData, duration, requestId, error)

                val logRecordBuilder = logger.logRecordBuilder()
                    .setTimestamp(getCurrentTimestamp(), TimeUnit.MILLISECONDS)
                    .setSeverity(mapLoggerLevelToSeverity(level))
                    .setBody(message)
                    .setAllAttributes(attributes)

                throwable?.let {
                    try {
                        val exceptionAttributes = attributes.toBuilder()
                            .put(AttributeKey.stringKey("exception.type"), it.javaClass.simpleName)
                            .put(
                                AttributeKey.stringKey("exception.stacktrace"),
                                it.stackTraceToString()
                            )
                            .build()
                        logRecordBuilder.setAllAttributes(exceptionAttributes)
                    } catch (e: Exception) {
                        if (OpenTelemetryConfig.isDebugEnabled()) {
                            println("Error adding exception attributes: ${e.message}")
                        }
                    }
                    Unit
                }

                logRecordBuilder.emit()

                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("OpenTelemetry log sent immediately: $level - $tag - $message")
                }
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error sending log to OpenTelemetry immediately: ${e.message}")
            }
        }
    }

    private fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis()
    }

    private fun getErrorString(throwable: Throwable): String {
        return "${throwable.javaClass.simpleName}: ${throwable.message}"
    }

    private fun isNetworkAvailable(): Boolean {
        val ctx = contextRef?.get() ?: return false

        return try {
            val connectivityManager =
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities =
                    connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = connectivityManager.activeNetworkInfo
                activeNetwork?.isConnectedOrConnecting == true
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error checking network availability: ${e.message}")
            }
            false
        }
    }

    // Métodos de utilidade pública
    fun getSyncStatus(): LogSyncStatus? {
        // Versão não-suspend para compatibilidade - usa runBlocking internamente
        return try {
            kotlinx.coroutines.runBlocking {
                syncManager?.getSyncStatus()
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error getting sync status: ${e.message}")
            }
            null
        }
    }

    suspend fun getSyncStatusSuspend(): LogSyncStatus? {
        return database?.let { db ->
            try {
                val stats = db.getLogStats()
                LogSyncStatus(
                    pendingLogs = stats.pending,
                    failedLogs = stats.failed,
                    sentLogs = stats.sent,
                    totalLogs = stats.total,
                )
            } catch (e: Exception) {
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Error getting sync status: ${e.message}")
                }
                null
            }
        }
    }

    fun forceSyncNow() {
        syncManager?.triggerImmediateSync()
    }

    fun shutdown() {
        try {
            syncManager?.cancelAllSync()
            OpenTelemetryConfig.shutdown()

            // Limpa referências para evitar vazamentos
            contextRef?.clear()
            contextRef = null
            database = null
            syncManager = null
            otelLogger = null
            otelTracer = null
            isInitialized = false

            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Logger shutdown completed")
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error during logger shutdown: ${e.message}")
            }
        }
    }

    // MÉTODOS DE TRACING

    fun startSpan(
        operationName: String,
        attributes: Map<String, Any>? = null,
        parentContext: SpanContext? = null,
        customStartTimeMs: Long? = null
    ): SpanContext {
        val traceId = UUID.randomUUID().toString()
        val spanId = UUID.randomUUID().toString()
        val startTime = customStartTimeMs ?: System.currentTimeMillis()

        try {
            // 1. SEMPRE persiste no banco primeiro (se disponível)
            var databaseId: Long? = null
            database?.let { db ->
                coroutineScope.launch {
                    try {
                        val attributesJson = LogAttributeSerializer.serializeTraceAttributes(
                            operationName, attributes, parentContext?.spanId
                        )

                        val traceEntry = TraceEntry(
                            traceId = traceId,
                            spanId = spanId,
                            parentSpanId = parentContext?.spanId,
                            operationName = operationName,
                            startTime = startTime,
                            endTime = null,
                            attributes = attributesJson,
                            status = TraceStatus.ACTIVE
                        )

                        val id = db.insertTrace(traceEntry)
                        if (OpenTelemetryConfig.isDebugEnabled()) {
                            println("Trace persisted to database: $operationName (ID: $id)")
                        }
                    } catch (e: Exception) {
                        if (OpenTelemetryConfig.isDebugEnabled()) {
                            println("Error persisting trace to database: ${e.message}")
                        }
                    }
                }
            }

            // 2. Cria span OpenTelemetry se disponível
            var otelSpan: Span? = null
            if (isInitialized) {
                try {
                    otelTracer?.let { tracer ->
                        val spanBuilder = tracer.spanBuilder(operationName)

                        // Define parent se fornecido
                        parentContext?.span?.let { parentSpan ->
                            spanBuilder.setParent(io.opentelemetry.context.Context.current().with(parentSpan))
                        }

                        // Adiciona atributos
                        attributes?.forEach { (key, value) ->
                            when (value) {
                                is String -> spanBuilder.setAttribute(key, value)
                                is Long -> spanBuilder.setAttribute(key, value)
                                is Double -> spanBuilder.setAttribute(key, value)
                                is Boolean -> spanBuilder.setAttribute(key, value)
                                is Int -> spanBuilder.setAttribute(key, value.toLong())
                                is Float -> spanBuilder.setAttribute(key, value.toDouble())
                                else -> spanBuilder.setAttribute(key, value.toString())
                            }
                        }

                        // Define timestamp customizado se fornecido
                        if (customStartTimeMs != null) {
                            val instant = java.time.Instant.ofEpochMilli(customStartTimeMs)
                            otelSpan = spanBuilder.setStartTimestamp(instant).startSpan()
                        } else {
                            otelSpan = spanBuilder.startSpan()
                        }

                        if (OpenTelemetryConfig.isDebugEnabled()) {
                            println("OpenTelemetry span started: $operationName")
                        }
                    }
                } catch (e: Exception) {
                    if (OpenTelemetryConfig.isDebugEnabled()) {
                        println("Error creating OpenTelemetry span: ${e.message}")
                    }
                }
            }

            return SpanContext(
                traceId = traceId,
                spanId = spanId,
                parentSpanId = parentContext?.spanId,
                span = otelSpan,
                databaseId = databaseId,
                customStartTimeMs = customStartTimeMs,
                actualStartTimeMs = startTime,
                operationName = operationName,
                originalAttributes = attributes,
                parentContext = parentContext
            )

        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error starting span: ${e.message}")
            }
            return SpanContext(traceId = traceId, spanId = spanId, parentSpanId = parentContext?.spanId, actualStartTimeMs = startTime, operationName = operationName, originalAttributes = attributes, parentContext = parentContext)
        }
    }

    fun startSpan(
        operationName: String,
        attributes: Map<String, Any>? = null,
        parentContext: SpanContext? = null
    ): SpanContext {
        return startSpan(operationName, attributes, parentContext, null)
    }

    fun endSpan(
        spanContext: SpanContext,
        attributes: Map<String, Any>? = null,
        status: SpanStatus = SpanStatus.OK
    ) {
        // Usa timestamp customizado de fim se fornecido, senão calcula baseado no tempo real
        val endTime = spanContext.customEndTimeMs ?: System.currentTimeMillis()

        // Calcula duração para atributos usando os timestamps corretos
        val startTimeForCalculation = spanContext.customStartTimeMs ?: spanContext.actualStartTimeMs
        val calculatedDurationMs = endTime - startTimeForCalculation

        try {
            // 1. Atualiza no banco se disponível
            database?.let { db ->
                coroutineScope.launch {
                    try {
                        // Adiciona duração calculada aos atributos
                        val enrichedAttributes = attributes?.toMutableMap() ?: mutableMapOf()
                        enrichedAttributes["duration_ms"] = calculatedDurationMs

                        val finalAttributes = LogAttributeSerializer.serializeTraceAttributes(
                            null, enrichedAttributes, null
                        )

                        val traceStatus = if (status == SpanStatus.OK) TraceStatus.ENDED_SUCCESS else TraceStatus.ENDED_ERROR
                        val statusMessage = if (status == SpanStatus.ERROR) "Operation failed" else null

                        // Busca trace pelo spanId e atualiza
                        val trace = db.getTraceBySpanId(spanContext.spanId)
                        trace?.let {
                            db.updateTraceEnd(
                                id = it.id,
                                endTime = endTime,
                                attributes = finalAttributes,
                                status = traceStatus,
                                statusMessage = statusMessage
                            )

                            if (OpenTelemetryConfig.isDebugEnabled()) {
                                println("Trace ended in database: ${it.operationName}")
                            }

                            // Tenta envio imediato se possível
                            if (isInitialized && isNetworkAvailable()) {
                                syncManager?.triggerImmediateSync()
                            }
                        }
                    } catch (e: Exception) {
                        if (OpenTelemetryConfig.isDebugEnabled()) {
                            println("Error ending trace in database: ${e.message}")
                        }
                    }
                }
            }

            // 2. Finaliza span OpenTelemetry se disponível - VERSÃO SIMPLIFICADA
            spanContext.span?.let { span ->
                try {
                    // Adiciona duração calculada aos atributos
                    val enrichedAttributes = attributes?.toMutableMap() ?: mutableMapOf()
                    enrichedAttributes["duration_ms"] = calculatedDurationMs

                    // Adiciona atributos finais
                    enrichedAttributes.forEach { (key, value) ->
                        when (value) {
                            is String -> span.setAttribute(key, value)
                            is Long -> span.setAttribute(key, value)
                            is Double -> span.setAttribute(key, value)
                            is Boolean -> span.setAttribute(key, value)
                            is Int -> span.setAttribute(key, value.toLong())
                            is Float -> span.setAttribute(key, value.toDouble())
                            else -> span.setAttribute(key, value.toString())
                        }
                    }

                    // Define status final
                    when (status) {
                        SpanStatus.OK -> span.setStatus(StatusCode.OK)
                        SpanStatus.ERROR -> span.setStatus(StatusCode.ERROR)
                    }

                    // Finaliza span com timestamp customizado se fornecido, senão usa timestamp atual
                    if (spanContext.customEndTimeMs != null) {
                        val instant = java.time.Instant.ofEpochMilli(spanContext.customEndTimeMs)
                        span.end(instant)
                    } else {
                        span.end()
                    }

                    if (OpenTelemetryConfig.isDebugEnabled()) {
                        println("OpenTelemetry span ended with status: $status, duration: ${calculatedDurationMs}ms")
                    }
                } catch (e: Exception) {
                    if (OpenTelemetryConfig.isDebugEnabled()) {
                        println("Error ending OpenTelemetry span: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error ending span: ${e.message}")
            }
        }
    }

    fun addSpanEvent(
        spanContext: SpanContext,
        eventName: String,
        attributes: Map<String, Any>? = null
    ) {
        try {
            spanContext.span?.let { span ->
                val eventAttributes = io.opentelemetry.api.common.Attributes.builder()

                attributes?.forEach { (key, value) ->
                    when (value) {
                        is String -> eventAttributes.put(AttributeKey.stringKey(key), value)
                        is Long -> eventAttributes.put(AttributeKey.longKey(key), value)
                        is Double -> eventAttributes.put(AttributeKey.doubleKey(key), value)
                        is Boolean -> eventAttributes.put(AttributeKey.booleanKey(key), value)
                        is Int -> eventAttributes.put(AttributeKey.longKey(key), value.toLong())
                        is Float -> eventAttributes.put(AttributeKey.doubleKey(key), value.toDouble())
                        else -> eventAttributes.put(AttributeKey.stringKey(key), value.toString())
                    }
                }

                span.addEvent(eventName, eventAttributes.build())

                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Event added to span: $eventName")
                }
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error adding span event: ${e.message}")
            }
        }
    }

    inline fun <T> traceOperation(
        operationName: String,
        attributes: Map<String, Any>? = null,
        parentContext: SpanContext? = null,
        block: (SpanContext) -> T
    ): T {
        val spanContext = startSpan(operationName, attributes, parentContext)
        return try {
            val result = block(spanContext)
            endSpan(spanContext, null, SpanStatus.OK)
            result
        } catch (e: Exception) {
            endSpan(spanContext, mapOf("error.message" to e.message.orEmpty()), SpanStatus.ERROR)
            throw e
        }
    }

    // Métodos públicos de logging
    fun t(
        tag: String,
        message: String,
        data: Map<String, Any>? = null,
        obj: Any? = null,
        duration: Long? = null,
        requestId: String? = null
    ) {
        sendLog(LoggerLevel.TRACE, tag, message, data, obj, duration, null, requestId)
        Log.v(tag, message)
    }

    fun d(
        tag: String,
        message: String,
        data: Map<String, Any>? = null,
        obj: Any? = null,
        duration: Long? = null,
        requestId: String? = null
    ) {
        sendLog(LoggerLevel.DEBUG, tag, message, data, obj, duration, null, requestId)
        Log.d(tag, message)
    }

    fun i(
        tag: String,
        message: String,
        data: Map<String, Any>? = null,
        obj: Any? = null,
        duration: Long? = null,
        requestId: String? = null
    ) {
        sendLog(LoggerLevel.INFO, tag, message, data, obj, duration, null, requestId)
        Log.i(tag, message)
    }

    fun w(
        tag: String,
        message: String,
        data: Map<String, Any>? = null,
        obj: Any? = null,
        duration: Long? = null,
        throwable: Throwable? = null,
        requestId: String? = null
    ) {
        sendLog(LoggerLevel.WARN, tag, message, data, obj, duration, throwable, requestId)
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(
        tag: String,
        message: String,
        data: Map<String, Any>? = null,
        obj: Any? = null,
        duration: Long? = null,
        throwable: Throwable? = null,
        requestId: String? = null
    ) {
        val mergedData = data?.toMutableMap() ?: mutableMapOf()
        obj?.let {
            mergedData["object"] = it.toMap()
        }
        sendLog(LoggerLevel.ERROR, tag, message, data, obj, duration, throwable, requestId)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}