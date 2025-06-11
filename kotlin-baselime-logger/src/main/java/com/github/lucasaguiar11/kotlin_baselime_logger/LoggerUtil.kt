package com.github.lucasaguiar11.kotlin_baselime_logger

import android.os.Build
import android.os.DeadObjectException
import android.os.DeadSystemException
import android.util.Log
import java.lang.StringBuilder
import java.net.UnknownHostException

object LoggerUtil {

    fun getError(throwable: Throwable?): String? {
        if (throwable == null) {
            return null
        }

        val sb = StringBuilder()
        var t: Throwable? = throwable
        while (t != null) {
            if (t is UnknownHostException) {
                break
            }
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    t is DeadSystemException
                } else {
                    t is DeadObjectException
                }
            ) {
                sb.append("DeadSystemException: The system died; earlier logs will point to the root cause")
                break
            }
            t = t.cause
        }
        if (t == null) {
            sb.append(throwable.message)
        }

        sb.append(throwable.stackTraceToString())

        return sb.toString()
    }

    fun Any.toMap(): Map<String, Any> {
        return this::class.java.declaredFields.associate { field ->
            field.isAccessible = true
            field.name to (field.get(this) ?: "null")
        }
    }
}