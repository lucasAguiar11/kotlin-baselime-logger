package com.github.lucasaguiar11.kotlin_baselime_logger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logs")
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    
    @ColumnInfo(name = "level")
    val level: String,
    
    @ColumnInfo(name = "tag")
    val tag: String,
    
    @ColumnInfo(name = "message")
    val message: String,
    
    @ColumnInfo(name = "attributes")
    val attributes: String, // JSON serializado dos atributos
    
    @ColumnInfo(name = "request_id")
    val requestId: String?,
    
    @ColumnInfo(name = "status")
    val status: LogStatus = LogStatus.PENDING,
    
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_retry_at")
    val lastRetryAt: Long? = null,
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)

enum class LogStatus {
    PENDING,    // Aguardando envio
    SENDING,    // Sendo enviado no momento
    SENT,       // Enviado com sucesso  
    FAILED,     // Falhou após max tentativas
    DELETED     // Marcado para exclusão
}

