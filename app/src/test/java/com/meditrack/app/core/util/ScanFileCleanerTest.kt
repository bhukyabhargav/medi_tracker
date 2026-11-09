package com.meditrack.app.core.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScanFileCleanerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `purgeStaleScanFiles purges files older than maxAge and keeps recent files`() {
        val filesDir = tempFolder.newFolder("files")
        val context: Context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        val scanDir = File(filesDir, ScanFileCleaner.SCAN_TMP_DIR).apply { mkdirs() }
        val oldFile = File(scanDir, "scan_old.jpg").apply {
            writeText("old scan")
            // Set timestamp to 2 hours ago
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        }
        val recentFile = File(scanDir, "scan_recent.jpg").apply {
            writeText("recent scan")
            setLastModified(System.currentTimeMillis() - 5 * 60 * 1000L) // 5 minutes ago
        }

        assertTrue(oldFile.exists())
        assertTrue(recentFile.exists())

        ScanFileCleaner.purgeStaleScanFiles(context, maxAgeMs = 60 * 60 * 1000L)

        assertFalse("Stale scan file older than 1 hour must be deleted", oldFile.exists())
        assertTrue("Recent scan file must be preserved", recentFile.exists())
    }

    @Test
    fun `deleteTempFile deletes existing file cleanly`() {
        val testFile = tempFolder.newFile("temp.jpg")
        assertTrue(testFile.exists())

        ScanFileCleaner.deleteTempFile(testFile)
        assertFalse(testFile.exists())

        // Should not throw on null or non-existent file
        ScanFileCleaner.deleteTempFile(null)
        ScanFileCleaner.deleteTempFile(testFile)
    }
}
