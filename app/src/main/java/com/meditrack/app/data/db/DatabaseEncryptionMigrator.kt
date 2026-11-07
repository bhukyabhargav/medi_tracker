package com.meditrack.app.data.db

import android.content.Context
import java.io.File
import java.io.FileInputStream

/**
 * Stage 12: Database Plaintext-to-Encrypted Migration Utility
 *
 * Detects whether an existing on-disk Room database is unencrypted SQLite (magic header "SQLite format 3\0")
 * and migrates it to an AES-256 SQLCipher encrypted database in-place via sqlcipher_export without data loss.
 */
object DatabaseEncryptionMigrator {

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
     */
    fun migratePlaintextToEncrypted(
        context: Context,
        dbFile: File,
        passphrase: ByteArray
    ): Boolean {
        if (!isDatabasePlaintext(dbFile)) return false

        try {
            System.loadLibrary("sqlcipher")
        } catch (_: Throwable) {
            // Environment without native libs
        }

        val parentDir = dbFile.parentFile ?: context.filesDir
        val tempEncryptedFile = File(parentDir, "${dbFile.name}.encrypted_tmp")
        if (tempEncryptedFile.exists()) {
            tempEncryptedFile.delete()
        }

        val hexKey = passphrase.joinToString("") { "%02x".format(it) }

        return try {
            val plaintextDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                "",
                null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                null
            )

            try {
                plaintextDb.rawExecSQL(
                    "ATTACH DATABASE '${tempEncryptedFile.absolutePath}' AS encrypted KEY \"x'$hexKey'\";"
                )
                plaintextDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                plaintextDb.rawExecSQL("DETACH DATABASE encrypted;")
            } finally {
                plaintextDb.close()
            }

            // Clean up temporary WAL/SHM artifacts if present
            val walFile = File(parentDir, "${dbFile.name}-wal")
            val shmFile = File(parentDir, "${dbFile.name}-shm")
            if (walFile.exists()) walFile.delete()
            if (shmFile.exists()) shmFile.delete()

            // Atomically swap in the encrypted database
            val backupPlaintext = File(parentDir, "${dbFile.name}.bak")
            if (backupPlaintext.exists()) backupPlaintext.delete()
            dbFile.renameTo(backupPlaintext)

            val success = tempEncryptedFile.renameTo(dbFile)
            if (success) {
                backupPlaintext.delete()
                true
            } else {
                backupPlaintext.renameTo(dbFile)
                false
            }
        } catch (e: Exception) {
            if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
            false
        }
    }
}
