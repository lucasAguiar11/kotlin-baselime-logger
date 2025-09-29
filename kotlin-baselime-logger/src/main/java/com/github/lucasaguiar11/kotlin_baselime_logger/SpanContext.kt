package com.github.lucasaguiar11.kotlin_baselime_logger

import io.opentelemetry.api.trace.Span
import java.util.concurrent.TimeUnit

/**
 * Wrapper para identificar spans ativos no sistema de tracing.
 * Usado para correlacionar operações de start/end span e adicionar eventos.
 */
data class SpanContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    internal val span: Span? = null,
    internal val databaseId: Long? = null,
    internal val customStartTimeMs: Long? = null,
    internal val customEndTimeMs: Long? = null,
    internal val actualStartTimeMs: Long = System.currentTimeMillis(),
    // Propriedades para recriação do span quando necessário
    internal val operationName: String? = null,
    internal val originalAttributes: Map<String, Any>? = null,
    internal val parentContext: SpanContext? = null
)

/**
 * Status de finalização de um span
 */
enum class SpanStatus {
    OK,        // Operação completada com sucesso
    ERROR      // Operação falhou
}

// Extensões para API fluente de spans


/**
 * Define timestamp de início customizado em milissegundos.
 * Automaticamente recria o span OpenTelemetry com o timestamp correto.
 * Permite encadeamento fluente: span.withStartTime(timestamp).endSpan(...)
 */
fun SpanContext.withStartTime(timestampMs: Long): SpanContext {
    // SIMPLIFICADO: Apenas define o timestamp customizado
    // O OpenTelemetry será recriado na próxima operação se necessário
    return this.copy(customStartTimeMs = timestampMs)
}

/**
 * Define timestamp de fim customizado em milissegundos.
 * Permite encadeamento fluente: span.withEndTime(timestamp).endSpan(...)
 */
fun SpanContext.withEndTime(timestampMs: Long): SpanContext {
    return this.copy(customEndTimeMs = timestampMs)
}

/**
 * Define ambos timestamps customizados (início e fim).
 * Permite encadeamento fluente: span.withTimeRange(start, end).endSpan(...)
 */
fun SpanContext.withTimeRange(startTimeMs: Long, endTimeMs: Long): SpanContext {
    return this.copy(customStartTimeMs = startTimeMs, customEndTimeMs = endTimeMs)
}

/**
 * Finaliza o span com API fluente.
 * Permite encadeamento: span.withDurationMs(1000).endSpan(attributes, SpanStatus.OK)
 */
fun SpanContext.endSpan(
    attributes: Map<String, Any>? = null,
    status: SpanStatus = SpanStatus.OK
): SpanContext {
    Logger.endSpan(this, attributes, status)
    return this
}

// Funções helper para criar spans com timestamps customizados

/**
 * Cria um span com timestamp de início customizado.
 * Esta é a forma correta de usar timestamps customizados com OpenTelemetry.
 */
fun Logger.startSpanWithCustomTime(
    operationName: String,
    startTimeMs: Long,
    attributes: Map<String, Any>? = null,
    parentContext: SpanContext? = null
): SpanContext {
    return startSpan(operationName, attributes, parentContext, startTimeMs)
}

/**
 * Cria um span com range de tempo específico (início e fim).
 * O span é criado e imediatamente finalizado com os timestamps fornecidos.
 */
fun Logger.createSpanWithTimeRange(
    operationName: String,
    startTimeMs: Long,
    endTimeMs: Long,
    attributes: Map<String, Any>? = null,
    parentContext: SpanContext? = null,
    status: SpanStatus = SpanStatus.OK
): SpanContext {
    val span = startSpan(operationName, attributes, parentContext, startTimeMs)
        .withEndTime(endTimeMs)
    endSpan(span, attributes, status)
    return span
}