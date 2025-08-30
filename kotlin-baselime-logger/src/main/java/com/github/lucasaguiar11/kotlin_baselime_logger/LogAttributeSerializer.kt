package com.github.lucasaguiar11.kotlin_baselime_logger

import com.github.lucasaguiar11.kotlin_baselime_logger.LoggerUtil.toMap
import com.google.gson.Gson

object LogAttributeSerializer {
    private val gson = Gson()
    
    fun serializeAttributes(
        tag: String?,
        data: Map<String, Any>?,
        obj: Any?,
        duration: Long?,
        requestId: String?,
        throwable: Throwable?
    ): String {
        val attributeMap = mutableMapOf<String, Any>()
        
        try {
            // Logger name
            tag?.let { attributeMap["logger.name"] = it }
            
            // Duration
            duration?.let { attributeMap["duration_ms"] = it }
            
            // Request ID
            requestId?.let { attributeMap["request_id"] = it }
            
            // Exception info
            throwable?.let {
                attributeMap["exception.message"] = "${it.javaClass.simpleName}: ${it.message}"
                attributeMap["exception.type"] = it.javaClass.simpleName
                attributeMap["exception.stacktrace"] = it.stackTraceToString()
            }
            
            // Merge default data from OpenTelemetry config
            OpenTelemetryConfig.getDefaultData()?.let { defaultData ->
                attributeMap.putAll(defaultData)
            }
            
            // Custom data
            data?.let { attributeMap.putAll(it) }
            
            // Object serialization
            obj?.let {
                val objectMap = it.toMap()
                attributeMap.putAll(objectMap)
            }
            
            return gson.toJson(attributeMap)
            
        } catch (e: Exception) {
            if (OpenTelemetryConfig.isDebugEnabled()) {
                println("Error serializing attributes: ${e.message}")
            }
            // Fallback: pelo menos tenta salvar o básico
            return gson.toJson(mapOf(
                "logger.name" to (tag ?: "unknown"),
                "error" to "serialization_failed",
                "error_message" to e.message
            ))
        }
    }
}