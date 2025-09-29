package com.github.lucasaguiar11.kotlin_baselime_logger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traces")
data class TraceEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "trace_id")
    val traceId: String,

    @ColumnInfo(name = "span_id")
    val spanId: String,

    @ColumnInfo(name = "parent_span_id")
    val parentSpanId: String?,

    @ColumnInfo(name = "operation_name")
    val operationName: String,

    @ColumnInfo(name = "start_time")
    val startTime: Long,

    @ColumnInfo(name = "end_time")
    val endTime: Long?,

    @ColumnInfo(name = "attributes")
    val attributes: String, // JSON serializado dos atributos

    @ColumnInfo(name = "status")
    val status: TraceStatus = TraceStatus.ACTIVE,

    @ColumnInfo(name = "status_message")
    val statusMessage: String? = null,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_retry_at")
    val lastRetryAt: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)

enum class TraceStatus {
    ACTIVE,         // Span ativo (ainda não finalizado)
    ENDED_SUCCESS,  // Span finalizado com sucesso
    ENDED_ERROR,    // Span finalizado com erro
    PENDING,        // Aguardando envio
    SENDING,        // Sendo enviado no momento
    SENT,           // Enviado com sucesso
    FAILED,         // Falhou após max tentativas
    DELETED         // Marcado para exclusão
}