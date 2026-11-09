package com.meditrack.app.data.db

import android.content.Context
import android.util.Log
import com.meditrack.app.core.database.SqlCipherLoader
import com.meditrack.app.core.security.DatabaseKeyManager
import java.io.File
import java.io.FileInputStream

/**
 * Stage 12: Database Plaintext-to-Encrypted Migration Utility
 *
 * Detects whether an existing on-disk Room database is unencrypted SQLite (magic header "SQLite format 3\0")
 * and migrates it to an AES-256 SQLCipher encrypted database in-place via sqlcipher_export.
 *
 * Stability Fix A2:
 * 1. Derives an ASCII passphrase string via DatabaseKeyManager.derivePassphraseString(passphrase)
 *    and uses it consistently in ATTACH DATABASE ... KEY '<passphrase>' and SupportOpenHelperFactory.
 * 2. Preserves plaintext database as <name>.bak until the encrypted database is verified readable.
 *    If verification fails, rolls back to plaintext database without data loss.
 */
object DatabaseEncryptionMigrator {

    private const val TAG = "DatabaseEncryptionMigrator"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Checks if the target database file exists and is plaintext SQLite format.
     */
    fun isDatabasePlaintext(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() < SQLITE_HEADER.size) {
            return false
        }
        val header = ByteArray(SQLITE_HEADER.size)
        return try {
            FileInputStream(dbFile).use { it.read(header) }
            header.contentEquals(SQLITE_HEADER)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Migrates a plaintext SQLite database to an encrypted SQLCipher database using sqlcipher_export.
     * Retains <name>.bak until encrypted DB is opened and confirmed readable.
     */
    fun migratePlaintextToEncrypted(
        context: Context,
        dbFile: File,
        passphrase: ByteArray
    ): Boolean {
        if (!isDatabasePlaintext(dbFile)) return false

        val loadResult = SqlCipherLoader.load()
        if (loadResult.isFailure) {
            Log.w(TAG, "SQLCipher native library unavailable for migration: ${loadResult.exceptionOrNull()?.message}")
            return false
        }

        val parentDir = dbFile.parentFile ?: context.filesDir
        val tempEncryptedFile = File(parentDir, "${dbFile.name}.encrypted_tmp")
        if (tempEncryptedFile.exists()) {
            tempEncryptedFile.delete()
        }

        // A2: Derive consistent ASCII passphrase string
        val passphraseStr = DatabaseKeyManager.derivePassphraseString(passphrase)
        val openerPassphrase = DatabaseKeyManager.derivePassphraseBytes(passphrase)

        var exportSuccess = false
        try {
            val plaintextDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                "",
                null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                null
            )

            try {
                plaintextDb.rawExecSQL(
                    "ATTACH DATABASE '${tempEncryptedFile.absolutePath}' AS encrypted KEY '$passphraseStr';"
                )
                plaintextDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                plaintextDb.rawExecSQL("DETACH DATABASE encrypted;")
                exportSuccess = true
            } finally {
                plaintextDb.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Plaintext export to encrypted threw: ${e.message}", e)
            if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
            return false
        }

        if (!exportSuccess || !tempEncryptedFile.exists() || tempEncryptedFile.length() == 0L) {
            if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
            return false
        }

        // Clean up temporary WAL/SHM artifacts if present
        val walFile = File(parentDir, "${dbFile.name}-wal")
        val shmFile = File(parentDir, "${dbFile.name}-shm")
        if (walFile.exists()) walFile.delete()
        if (shmFile.exists()) shmFile.delete()

        // Atomically swap in the encrypted database, keeping .bak
        val backupPlaintext = File(parentDir, "${dbFile.name}.bak")
        if (backupPlaintext.exists()) backupPlaintext.delete()

        val renamedBackup = dbFile.renameTo(backupPlaintext)
        if (!renamedBackup) {
            if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
            return false
        }

        val renamedEncrypted = tempEncryptedFile.renameTo(dbFile)
        if (!renamedEncrypted) {
            // Restore original plaintext database
            backupPlaintext.renameTo(dbFile)
            return false
        }

        // Verify that the encrypted database can be read with openerPassphrase
        var isVerified = false
        try {
            val verifyDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                openerPassphrase,
                null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
                null
            )
            verifyDb.rawExecSQL("SELECT count(*) FROM sqlite_master;")
            verifyDb.close()
            isVerified = true
        } catch (e: Throwable) {
            Log.e(TAG, "Verification of newly migrated encrypted database failed: ${e.message}", e)
            isVerified = false
        }

        return if (isVerified) {
            // Success: safe to delete .bak now
            backupPlaintext.delete()
            Log.i(TAG, "Plaintext database successfully encrypted and verified.")
            true
        } else {
            // Verification failed: roll back to .bak to guarantee zero patient data loss
            Log.e(TAG, "Encrypted database verification failed. Rolling back to plaintext backup.")
            if (dbFile.exists()) dbFile.delete()
            backupPlaintext.renameTo(dbFile)
            false
        }
    }
}
