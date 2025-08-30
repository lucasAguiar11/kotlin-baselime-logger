package com.github.lucasaguiar11.kotlin_baselime_logger

import android.os.Build
import android.os.DeadObjectException
import android.os.DeadSystemException
import android.util.Log
import java.lang.StringBuilder
import java.net.UnknownHostException

object LoggerUtil {
    fun Any.toMap(): Map<String, Any> {
        return this::class.java.declaredFields.associate { field ->
            field.isAccessible = true
            field.name to (field.get(this) ?: "null")
        }
    }
}