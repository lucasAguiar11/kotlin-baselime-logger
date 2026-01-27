package com.github.lucasaguiar11.kotlin_baselime_logger

class RetryPolicy(
    private val maxAttempts: Int = 3,
    private val delayMillis: Long = 1000L
) {

    suspend fun <T> execute(
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e

                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(delayMillis)
                }
            }
        }

        throw lastError ?: IllegalStateException("Erro desconhecido no retry")
    }
}
