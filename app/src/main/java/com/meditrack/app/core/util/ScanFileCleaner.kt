package com.meditrack.app.core.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Stage 12 / Stability Fix B3:
 * Manages temporary prescription and blister scan files in filesDir/scan_tmp.
 *
 * Ensures:
 * 1. Single scan files are cleaned up immediately after OCR decoding.
 * 2. Stale scan files older than 1 hour are automatically purged at application start.
 * 3. All scan files are removed during account deletion.
 */
object ScanFileCleaner {
    private const val TAG = "ScanFileCleaner"
    const val SCAN_TMP_DIR = "scan_tmp"
    const val MAX_AGE_MS = 60 * 60 * 1000L // 1 hour

    fun getScanTmpDir(context: Context): File {
        return File(context.filesDir, SCAN_TMP_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Purges temporary scan files older than maxAgeMs (default 1 hour).
     */
    fun purgeStaleScanFiles(context: Context, maxAgeMs: Long = MAX_AGE_MS) {
        try {
            val dir = File(context.filesDir, SCAN_TMP_DIR)
            if (!dir.exists() || !dir.isDirectory) return

            val now = System.currentTimeMillis()
            var deletedCount = 0
            dir.listFiles()?.forEach { file ->
                if (file.isFile && (now - file.lastModified()) > maxAgeMs) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "Purged $deletedCount stale prescription scan files from $SCAN_TMP_DIR.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to purge stale scan files: ${e.message}")
        }
    }

    /**
     * Safely deletes an individual temporary scan file.
     */
    fun deleteTempFile(file: File?) {
        try {
            if (file != null && file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {}
    }
}
