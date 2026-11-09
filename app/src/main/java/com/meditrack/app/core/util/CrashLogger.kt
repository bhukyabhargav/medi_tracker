package com.meditrack.app.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 12 / Stability Fix E1:
 * Uncaught exception handler that logs crash stack traces to a rotating local file
 * (retains last 5 crashes) without logging personal identifiable health data.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    const val CRASH_DIR = "crashes"
    const val MAX_CRASH_LOGS = 5

    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(context, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to record uncaught crash: ${e.message}", e)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR).apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val crashFile = File(dir, "crash_${timeStamp}.log")

        val content = buildString {
            appendLine("=== MediTrack Diagnostic Crash Report ===")
            appendLine("Timestamp: $timeStamp")
            appendLine("Thread: ${thread.name} (ID: ${thread.id})")
            appendLine("Exception Class: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message ?: "No message"}")
            appendLine()
            appendLine("Stack Trace:")
            appendLine(throwable.stackTraceToString())
        }

        crashFile.writeText(content)

        // Rotate to keep only newest MAX_CRASH_LOGS
        val logs = dir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified()} ?: emptyList()
        if (logs.size > MAX_CRASH_LOGS) {
            logs.drop(MAX_CRASH_LOGS).forEach { it.delete() }
        }
    }

    fun exportDiagnosticsText(context: Context): String {
        val dir = File(context.filesDir, CRASH_DIR)
        val logs = dir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (logs.isEmpty()) {
            return "MediTrack Diagnostic Log: No local crashes recorded. System is healthy."
        }

        return buildString {
            appendLine("MediTrack Crash Diagnostics Export (${logs.size} recorded incidents):")
            appendLine("------------------------------------------------------------------")
            logs.forEachIndexed { idx, file ->
                appendLine("Incident #${idx + 1} (${file.name}):")
                appendLine(file.readText())
                appendLine("------------------------------------------------------------------")
            }
        }
    }
}
