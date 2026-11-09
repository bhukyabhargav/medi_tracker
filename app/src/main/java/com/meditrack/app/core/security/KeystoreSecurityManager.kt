package com.meditrack.app.core.security

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 12 Keystore Security Manager
 *
 * Provides AES-256 GCM encryption. Master key management is delegated to KeyProvider.
 * Fails closed on device via KeystoreUnavailableException if the master key is not accessible.
 */
@Singleton
class KeystoreSecurityManager @Inject constructor(
    private val keyProvider: KeyProvider
) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        // Bundle: [IV (12 bytes)][Ciphertext + GCM Tag]
        val result = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(encrypted, 0, result, iv.size, encrypted.size)
        return result
    }

    fun decrypt(ciphertextWithIv: ByteArray): ByteArray {
        if (ciphertextWithIv.size < IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Ciphertext too short to contain IV.")
        }
        val key = keyProvider.getOrCreateKey()
        val iv = ByteArray(IV_LENGTH_BYTES)
        System.arraycopy(ciphertextWithIv, 0, iv, 0, IV_LENGTH_BYTES)

        val encryptedSize = ciphertextWithIv.size - IV_LENGTH_BYTES
        val encrypted = ByteArray(encryptedSize)
        System.arraycopy(ciphertextWithIv, IV_LENGTH_BYTES, encrypted, 0, encryptedSize)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(encrypted)
    }
}
