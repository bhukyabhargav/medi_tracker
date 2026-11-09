package com.meditrack.app.core.security

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class KeystoreSecurityManagerTest {

    private lateinit var keyProvider: KeyProvider
    private lateinit var securityManager: KeystoreSecurityManager

    @Before
    fun setUp() {
        keyProvider = TestKeyProvider()
        securityManager = KeystoreSecurityManager(keyProvider)
    }

    @Test
    fun `encrypt and decrypt roundtrip preserves string and binary data`() {
        val originalText = "Sensitive Patient Health Export Payload: Metformin 500mg taken at 10:00"
        val plaintext = originalText.toByteArray(Charsets.UTF_8)

        val ciphertext = securityManager.encrypt(plaintext)
        assertTrue(ciphertext.size > plaintext.size) // Has 12-byte IV + 16-byte GCM tag
        assertFalse(plaintext.contentEquals(ciphertext))

        val decrypted = securityManager.decrypt(ciphertext)
        assertArrayEquals(plaintext, decrypted)
        assertEquals(originalText, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `decrypt throws IllegalArgumentException when ciphertext is shorter than IV`() {
        val tooShort = ByteArray(8) { 1 }
        assertThrows(IllegalArgumentException::class.java) {
            securityManager.decrypt(tooShort)
        }
    }

    @Test
    fun `decrypt throws exception when ciphertext is tampered with`() {
        val plaintext = "Tamper Proof Prescription Data".toByteArray(Charsets.UTF_8)
        val ciphertext = securityManager.encrypt(plaintext)

        // Corrupt the ciphertext payload
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()

        assertThrows(Exception::class.java) {
            securityManager.decrypt(ciphertext)
        }
    }

    @Test
    fun `encrypt throws KeystoreUnavailableException when KeyProvider fails`() {
        val failingProvider = mockk<KeyProvider>()
        every { failingProvider.getOrCreateKey() } throws KeystoreUnavailableException("AndroidKeyStore hardware error")

        val failingSecurityManager = KeystoreSecurityManager(failingProvider)
        assertThrows(KeystoreUnavailableException::class.java) {
            failingSecurityManager.encrypt("test data".toByteArray())
        }
    }
}
