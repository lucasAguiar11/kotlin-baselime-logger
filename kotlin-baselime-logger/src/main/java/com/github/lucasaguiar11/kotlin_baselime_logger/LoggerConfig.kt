package com.github.lucasaguiar11.kotlin_baselime_logger

data class LoggerConfig(
    val batchSize: Int = 100,
    val maxPerRun: Int = 1000,
    val flushTimeoutSeconds: Long = 30
)
