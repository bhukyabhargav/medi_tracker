package com.meditrack.app.core.security

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Stage 12 / Stability Fix A3:
 * In-memory KeyProvider for unit testing.
 * Provides a deterministic AES-256 key without requiring AndroidKeyStore.
 */
class TestKeyProvider(
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { 42 }, "AES")
) : KeyProvider {
    override fun getOrCreateKey(): SecretKey = key
}
