package com.meditrack.app.core.security

import javax.crypto.SecretKey

/**
 * Stage 12 / Stability Fix A3:
 * Abstraction for master key generation and retrieval.
 * Enables deterministic test keys without hardcoded fallback secrets in production code.
 */
interface KeyProvider {
    fun getOrCreateKey(): SecretKey
}
