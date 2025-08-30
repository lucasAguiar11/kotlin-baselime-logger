package com.github.lucasaguiar11.kotlin_baselime_logger

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Database(
    entities = [LogEntry::class],
    version = 1,
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
                
                // Cria índices para otimização de performance
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_status ON logs(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_created_at ON logs(created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_request_id ON logs(request_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_retry_count ON logs(retry_count)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_last_retry_at ON logs(last_retry_at)")
                
                if (OpenTelemetryConfig.isDebugEnabled()) {
                    println("Room Database created with optimized indexes")
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
}