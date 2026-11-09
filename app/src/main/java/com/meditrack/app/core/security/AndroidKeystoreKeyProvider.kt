package com.meditrack.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 12 / Stability Fix A3:
 * Production KeyProvider backed by the hardware-isolated AndroidKeyStore.
 * Throws KeystoreUnavailableException if the provider cannot generate or load the key.
 */
@Singleton
class AndroidKeystoreKeyProvider @Inject constructor() : KeyProvider {

    companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "MediTrackMasterKey"
    }

    override fun getOrCreateKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey ?: generateKeystoreKey()
            } else {
                generateKeystoreKey()
            }
        } catch (e: Throwable) {
            throw KeystoreUnavailableException(
                "AndroidKeyStore is unavailable or master key could not be accessed: ${e.message}",
                e
            )
        }
    }

    private fun generateKeystoreKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
