package com.meditrack.app.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 12 Database Key Manager
 *
 * Generates and stores a random 32-byte (256-bit) SQLCipher encryption passphrase,
 * wrapped securely by KeystoreSecurityManager using AndroidKeyStore AES-GCM.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreSecurityManager: KeystoreSecurityManager
) {
    companion object {
        const val KEY_FILE_NAME = "meditrack_db_key.enc"
        const val PASSPHRASE_LENGTH_BYTES = 32
    }

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists() && keyFile.length() > 0) {
            val encrypted = keyFile.readBytes()
            return keystoreSecurityManager.decrypt(encrypted)
        }

        val rawKey = ByteArray(PASSPHRASE_LENGTH_BYTES)
        SecureRandom().nextBytes(rawKey)
        val encrypted = keystoreSecurityManager.encrypt(rawKey)
        keyFile.writeBytes(encrypted)
        return rawKey
    }

    @Synchronized
    fun deletePassphrase() {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists()) {
            keyFile.delete()
        }
    }
}
