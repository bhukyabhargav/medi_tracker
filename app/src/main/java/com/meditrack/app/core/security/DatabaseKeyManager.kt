package com.meditrack.app.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 12 / Stability Fix A4:
 * Exception thrown when database passphrase exists on disk but cannot be decrypted.
 */
class DatabaseKeyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Stage 12 Database Key Manager
 *
 * Generates and stores a random 32-byte (256-bit) SQLCipher encryption passphrase,
 * wrapped securely by KeystoreSecurityManager using AES-GCM.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreSecurityManager: KeystoreSecurityManager
) {
    companion object {
        const val KEY_FILE_NAME = "meditrack_db_key.enc"
        const val PASSPHRASE_LENGTH_BYTES = 32

        /**
         * Stage 12 / Stability Fix A2:
         * Derives a consistent ASCII passphrase string from the raw 32-byte key.
         * This ensures ATTACH DATABASE ... KEY '<passphrase>' and SupportOpenHelperFactory(passphraseBytes)
         * evaluate the exact same PBKDF2 key derivation.
         */
        fun derivePassphraseString(rawKey: ByteArray): String {
            return rawKey.joinToString("") { "%02x".format(it) }
        }

        fun derivePassphraseBytes(rawKey: ByteArray): ByteArray {
            return derivePassphraseString(rawKey).toByteArray(Charsets.US_ASCII)
        }
    }

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists() && keyFile.length() > 0) {
            val encrypted = keyFile.readBytes()
            return try {
                keystoreSecurityManager.decrypt(encrypted)
            } catch (e: Throwable) {
                // Never silently generate a new key for an existing database!
                throw DatabaseKeyException(
                    "Existing database encryption key could not be decrypted. Hardware KeyStore may be temporarily unavailable or credentials changed.",
                    e
                )
            }
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
