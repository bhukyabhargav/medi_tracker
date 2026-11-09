package com.meditrack.app.core.security

/**
 * Stage 12 / Stability Fix A3:
 * Typed exception thrown when hardware-backed AndroidKeyStore is unavailable,
 * locked, or failed to initialize on a real device.
 * Fails closed rather than using an insecure fallback key.
 */
class KeystoreUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
