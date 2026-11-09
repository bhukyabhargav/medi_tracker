package com.meditrack.app.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.data.db.DatabaseEncryptionMigrator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DatabaseEncryptionTest {

    private lateinit var context: Context
    private lateinit var keystoreSecurityManager: KeystoreSecurityManager
    private lateinit var databaseKeyManager: DatabaseKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keystoreSecurityManager = KeystoreSecurityManager(TestKeyProvider())
        databaseKeyManager = DatabaseKeyManager(context, keystoreSecurityManager)
    }

    @Test
    fun getOrCreatePassphrase_generates32Bytes_andPersistsWrapped() {
        databaseKeyManager.deletePassphrase()

        val passphrase1 = databaseKeyManager.getOrCreatePassphrase()
        assertNotNull(passphrase1)
        assertEquals(32, passphrase1.size)

        // Key file must exist on disk wrapped
        val keyFile = File(context.filesDir, DatabaseKeyManager.KEY_FILE_NAME)
        assertTrue(keyFile.exists())
        assertTrue(keyFile.length() > 32) // AES-GCM adds IV (12) and Tag (16)

        // Subsequent call must retrieve the exact same passphrase
        val passphrase2 = databaseKeyManager.getOrCreatePassphrase()
        assertTrue(passphrase1.contentEquals(passphrase2))
    }

    @Test
    fun isDatabasePlaintext_detectsSqliteHeader() {
        val testDbFile = File(context.filesDir, "test_plain.db")
        testDbFile.writeBytes("SQLite format 3\u0000SomeDataInsideDatabase".toByteArray(Charsets.US_ASCII))

        assertTrue(DatabaseEncryptionMigrator.isDatabasePlaintext(testDbFile))

        val encryptedDbFile = File(context.filesDir, "test_enc.db")
        encryptedDbFile.writeBytes(ByteArray(100) { 0x7F.toByte() })

        assertFalse(DatabaseEncryptionMigrator.isDatabasePlaintext(encryptedDbFile))

        testDbFile.delete()
        encryptedDbFile.delete()
    }

    @Test
    fun derivePassphrase_producesConsistentAsciiHexOnBothSides() {
        val rawKey = ByteArray(32) { (it + 1).toByte() }
        val passphraseStr = DatabaseKeyManager.derivePassphraseString(rawKey)
        val openerBytes = DatabaseKeyManager.derivePassphraseBytes(rawKey)

        assertEquals(64, passphraseStr.length)
        assertEquals(64, openerBytes.size)
        assertEquals(passphraseStr, String(openerBytes, Charsets.US_ASCII))
    }

    @Test
    fun getOrCreatePassphrase_throwsDatabaseKeyException_whenDecryptFails() {
        val keyFile = File(context.filesDir, DatabaseKeyManager.KEY_FILE_NAME)
        keyFile.writeBytes(ByteArray(50) { 0xFF.toByte() }) // Corrupt key file

        val failingSecurityManager = mockk<KeystoreSecurityManager>()
        every { failingSecurityManager.decrypt(any()) } throws RuntimeException("Corrupted tag")

        val manager = DatabaseKeyManager(context, failingSecurityManager)
        assertThrows(DatabaseKeyException::class.java) {
            manager.getOrCreatePassphrase()
        }

        keyFile.delete()
    }
}
