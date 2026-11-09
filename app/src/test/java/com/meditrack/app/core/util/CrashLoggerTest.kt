package com.meditrack.app.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLoggerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashLogger.clearCrashLogs(context)
    }

    @After
    fun tearDown() {
        CrashLogger.clearCrashLogs(context)
    }

    @Test
    fun testRecordCrash_writesFileToCrashesDirWithSanitizedStackTrace() {
        val testThread = Thread.currentThread()
        val exception = RuntimeException("Test simulated crash for diagnosability")

        CrashLogger.recordCrash(context, testThread, exception)

        val crashDir = File(context.filesDir, CrashLogger.CRASH_DIR)
        val files = crashDir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }
        assertTrue("Crash log file should be created", files != null && files.isNotEmpty())

        val logContent = files!!.first().readText()
        assertTrue("Log should contain crash header", logContent.contains("=== MediTrack Diagnostic Crash Report ==="))
        assertTrue("Log should contain thread name", logContent.contains(testThread.name))
        assertTrue("Log should contain exception class", logContent.contains("java.lang.RuntimeException"))
        assertTrue("Log should contain exception message", logContent.contains("Test simulated crash for diagnosability"))
        assertFalse("Log should NOT contain personal medicine details", logContent.contains("Metformin") || logContent.contains("patientId"))
    }

    @Test
    fun testCrashRotation_keepsOnlyLatest5Files() {
        val testThread = Thread.currentThread()

        // Write 8 crashes with slight delay to ensure different timestamps
        for (i in 1..8) {
            val exception = IllegalStateException("Crash iteration #$i")
            CrashLogger.recordCrash(context, testThread, exception)
            Thread.sleep(5)
        }

        val crashDir = File(context.filesDir, CrashLogger.CRASH_DIR)
        val files = crashDir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }

        assertEquals("Rotating log should retain at most 5 files", CrashLogger.MAX_CRASH_LOGS, files?.size)
    }

    @Test
    fun testExportDiagnosticsText_whenNoCrashes_reportsSystemHealthy() {
        val export = CrashLogger.exportDiagnosticsText(context)
        assertTrue(export.contains("No local crashes recorded. System is healthy."))
    }

    @Test
    fun testExportDiagnosticsText_formatsReportCorrectly() {
        val testThread = Thread.currentThread()
        CrashLogger.recordCrash(context, testThread, IllegalArgumentException("Diagnostics check 1"))
        Thread.sleep(5)
        CrashLogger.recordCrash(context, testThread, NullPointerException("Diagnostics check 2"))

        val export = CrashLogger.exportDiagnosticsText(context)
        assertTrue("Report must include incident count", export.contains("2 recorded incidents"))
        assertTrue("Report must include class names", export.contains("IllegalArgumentException") && export.contains("NullPointerException"))
    }
}
