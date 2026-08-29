package com.boom.anydown.util

import android.content.Context
import android.util.Log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "ANYDOWN_CRASH"
    private val lock = Any()
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            logFile = File(context.applicationContext.filesDir, "anydown_log.txt")
        }
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            log("FATAL: ${throwable.stackTraceToString()}")
        }
    }

    fun log(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            val file = logFile
            if (file == null) {
                Log.e(TAG, msg)
                return
            }
            runCatching { file.appendText("[$timestamp] $msg\n") }
                .onFailure { Log.e(TAG, "Could not write crash log", it) }
        }
    }

    /** Returns the file contents newest first for the in-app debug viewer. */
    fun readLogs(): String = synchronized(lock) {
        val file = logFile
        if (file == null || !file.exists()) return@synchronized "No logs yet."
        val lines = runCatching { file.readLines() }.getOrElse {
            return@synchronized "Unable to read logs."
        }
        lines.asReversed().joinToString("\n").ifBlank { "No logs yet." }
    }

    fun clear() {
        synchronized(lock) {
            logFile?.let { file ->
                runCatching { file.writeText("") }
                    .onFailure { Log.e(TAG, "Could not clear crash log", it) }
            }
        }
    }
}
