package com.github.lucasaguiar11.kotlin_baselime_logger

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Database(
    entities = [LogEntry::class, TraceEntry::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LogRoomDatabase : RoomDatabase() {

    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var INSTANCE: LogRoomDatabase? = null

        fun getDatabase(context: Context): LogRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LogRoomDatabase::class.java,
                    "baselime_logs_db"
                )
                    .addCallback(LogDatabaseCallback())
                    .fallbackToDestructiveMigration() // Para desenvolvimento - remover em produção
                    .build()
                    
                INSTANCE = instance
                instance
            }
        }

        private class LogDatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                
                // Cria índices para otimização de performance - LOGS
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_status ON logs(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_created_at ON logs(created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_request_id ON logs(request_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_retry_count ON logs(retry_count)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_last_retry_at ON logs(last_retry_at)")

                // Cria índices para otimização de performance - TRACES
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_status ON traces(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_created_at ON traces(created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_trace_id ON traces(trace_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_span_id ON traces(span_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_parent_span_id ON traces(parent_span_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_start_time ON traces(start_time)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_retry_count ON traces(retry_count)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_traces_last_retry_at ON traces(last_retry_at)")

                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Room Database created with optimized indexes for logs and traces")
                }
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromLogStatus(value: LogStatus): String {
        return value.name
    }

    @TypeConverter
    fun toLogStatus(value: String): LogStatus {
        return try {
            LogStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            LogStatus.PENDING // Fallback seguro
        }
    }

    @TypeConverter
    fun fromTraceStatus(value: TraceStatus): String {
        return value.name
    }

    @TypeConverter
    fun toTraceStatus(value: String): TraceStatus {
        return try {
            TraceStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            TraceStatus.PENDING // Fallback seguro
        }
    }
}