package com.meditrack.app.core.database

import android.content.Context
import android.util.Log
import com.meditrack.app.core.security.DatabaseKeyManager
import java.io.File

/**
 * Stage 12 / Stability Fix A1 & A4:
 * Global state holder for database open, encryption migration, and KeyStore health.
 *
 * When an existing database cannot be opened:
 * 1. The database file is PRESERVED on disk (never silently deleted).
 * 2. An error is recorded here with diagnostic details.
 * 3. The UI presents a recoverable error screen (retry, export diagnostics, or confirmed reset).
 */
object DatabaseRecoveryState {
    private const val TAG = "DatabaseRecoveryState"

    data class DatabaseError(
        val title: String,
        val message: String,
        val details: String? = null,
        val isRecoverable: Boolean = true,
        val cause: Throwable? = null
    )

    @Volatile
    var currentError: DatabaseError? = null
        private set

    fun setError(error: DatabaseError) {
        Log.e(TAG, "Database recovery error set: ${error.title} - ${error.message}", error.cause)
        currentError = error
    }

    fun clearError() {
        currentError = null
    }

    /**
     * Explicit, user-confirmed reset of local database and key files.
     * Can only be called after explicit user confirmation on the error screen.
     */
    fun resetLocalData(context: Context, databaseName: String) {
        Log.w(TAG, "Explicit user-confirmed local data reset initiated.")
        try {
            context.deleteDatabase(databaseName)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete database: ${e.message}", e)
        }

        try {
            val keyFile = File(context.filesDir, DatabaseKeyManager.KEY_FILE_NAME)
            if (keyFile.exists()) {
                keyFile.delete()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete key file: ${e.message}", e)
        }

        clearError()
    }
}
