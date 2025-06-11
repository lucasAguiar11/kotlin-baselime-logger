package com.github.lucasaguiar11.kotlin_baselime_logger

import android.util.Log
import com.github.lucasaguiar11.kotlin_baselime_logger.LoggerUtil.toMap
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger as OtelLogger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.TimeUnit

object Logger {

    private var otelLogger: OtelLogger? = null
    private var isInitialized = false

    fun initialize() {
        try {
            val loggerProvider = OpenTelemetryConfig.getLoggerProvider()
            otelLogger = loggerProvider?.get("kotlin-otel-logger")
            isInitialized = true

            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("OtelLogger initialized successfully")
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
        if (!isInitialized) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("OtelLogger not initialized, skipping log")
            }
            return
        }

        try {
            otelLogger?.let { logger ->

                var innerData = data?.let { OpenTelemetryConfig.getDefaultData()?.plus(it) }
                    ?: OpenTelemetryConfig.getDefaultData()

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
                    // Adiciona informações da exception como atributos adicionais
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
                        } else {

                        }
                    }
                }

                logRecordBuilder.emit()

                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("OpenTelemetry log sent: $level - $tag - $message")
                }
            }
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error sending log to OpenTelemetry: ${e.message}")
            }
        }
    }

    private fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis()
    }

    private fun getErrorString(throwable: Throwable): String {
        return "${throwable.javaClass.simpleName}: ${throwable.message}"
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