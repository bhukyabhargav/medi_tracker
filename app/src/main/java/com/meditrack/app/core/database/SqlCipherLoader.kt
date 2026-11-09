package com.meditrack.app.core.database

import android.util.Log

/**
 * Stage 12 / Stability Fix A6:
 * Single centralized loader for the SQLCipher native C++ library.
 *
 * Prevents multiple uncoordinated calls to System.loadLibrary across MediTrackApp,
 * DatabaseModule, and DatabaseEncryptionMigrator, and records any loading failure
 * so it can be surfaced cleanly via the DatabaseRecoveryState error screen rather
 * than causing late undefined native crashes.
 */
object SqlCipherLoader {
    private const val TAG = "SqlCipherLoader"
    private const val LIBRARY_NAME = "sqlcipher"

    @Volatile
    private var isLoaded: Boolean = false

    @Volatile
    private var loadFailure: Throwable? = null

    /**
     * Loads the SQLCipher native library if not already loaded.
     * Thread-safe and returns a Result indicating success or failure.
     */
    @Synchronized
    fun load(): Result<Unit> {
        if (isLoaded) return Result.success(Unit)
        loadFailure?.let { return Result.failure(it) }

        return try {
            System.loadLibrary(LIBRARY_NAME)
            isLoaded = true
            Log.i(TAG, "SQLCipher native library loaded successfully.")
            Result.success(Unit)
        } catch (t: Throwable) {
            loadFailure = t
            Log.e(TAG, "Failed to load SQLCipher native library: ${t.message}", t)
            Result.failure(t)
        }
    }

    val isAvailable: Boolean
        get() = isLoaded

    val failure: Throwable?
        get() = loadFailure

    /**
     * Resets load state for testing purposes.
     */
    @Synchronized
    internal fun resetForTesting(loaded: Boolean = false, failure: Throwable? = null) {
        isLoaded = loaded
        loadFailure = failure
    }
}
