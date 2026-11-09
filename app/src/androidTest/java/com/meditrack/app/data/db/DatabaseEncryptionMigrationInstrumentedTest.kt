package com.meditrack.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meditrack.app.core.security.DatabaseKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage 12 / Stability Fix A2:
 * Instrumented test verifying plaintext-to-encrypted database migration.
 * Creates a plaintext database with rows, migrates using DatabaseEncryptionMigrator,
 * reopens with the app's SupportOpenHelperFactory and reads rows back.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionMigrationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migratePlaintextToEncrypted_preservesData_andOpensWithAppHelperFactory() {
        val testDbFile = context.getDatabasePath("test_migration_source.db")
        if (testDbFile.exists()) testDbFile.delete()
        val backupFile = File(testDbFile.parentFile, "${testDbFile.name}.bak")
        if (backupFile.exists()) backupFile.delete()

        // 1. Create a plaintext database with schema and rows
        val plaintextDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(testDbFile, null)
        plaintextDb.execSQL(
            """
            CREATE TABLE medicines (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                dosageText TEXT NOT NULL,
                pillsPerDose INTEGER NOT NULL,
                frequencyType TEXT NOT NULL,
                timesOfDay TEXT NOT NULL,
                intervalHours INTEGER,
                daysOfWeek TEXT,
                startDate INTEGER NOT NULL,
                endDate INTEGER,
                foodRelation TEXT NOT NULL,
                instructions TEXT NOT NULL,
                reminderEnabled INTEGER NOT NULL,
                initialStock INTEGER NOT NULL,
                currentStock INTEGER NOT NULL,
                baselineEmptyCells INTEGER NOT NULL,
                baselinePhotoPath TEXT,
                prescribedByDoctorUid TEXT,
                prescribedByDoctorName TEXT,
                stoppedByDoctor INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                patientId TEXT NOT NULL DEFAULT '',
                isDeleted INTEGER NOT NULL DEFAULT 0,
                lowStockNotified INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        plaintextDb.execSQL(
            """
            INSERT INTO medicines (
                id, name, dosageText, pillsPerDose, frequencyType, timesOfDay,
                startDate, foodRelation, instructions, reminderEnabled,
                initialStock, currentStock, baselineEmptyCells, stoppedByDoctor,
                createdAt, patientId
            ) VALUES (
                'med_enc_1', 'Atorvastatin', '20mg', 1, 'DAILY', '["21:00"]',
                1000, 'NO_RELATION', 'Take at night', 1,
                30, 30, 0, 0,
                1000, 'user_test'
            );
            """.trimIndent()
        )
        plaintextDb.close()

        assertTrue(DatabaseEncryptionMigrator.isDatabasePlaintext(testDbFile))

        // 2. Perform migration using 32-byte key
        val rawKey = ByteArray(32) { (it + 5).toByte() }
        val migrationSuccess = DatabaseEncryptionMigrator.migratePlaintextToEncrypted(
            context,
            testDbFile,
            rawKey
        )

        assertTrue("Migration must return true", migrationSuccess)
        assertFalse("Database must no longer be plaintext", DatabaseEncryptionMigrator.isDatabasePlaintext(testDbFile))
        assertFalse("Backup .bak must be cleaned up on success", backupFile.exists())

        // 3. Reopen with the app's SupportOpenHelperFactory using derived passphrase bytes
        val openerPassphrase = DatabaseKeyManager.derivePassphraseBytes(rawKey)
        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(openerPassphrase)

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, testDbFile.name)
            .openHelperFactory(factory)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        val medicine = roomDb.medicineDao().getMedicineByIdDirect("med_enc_1")
        roomDb.close()

        // 4. Verify data survived migration
        assertEquals("med_enc_1", medicine?.id)
        assertEquals("Atorvastatin", medicine?.name)
        assertEquals("20mg", medicine?.dosageText)

        testDbFile.delete()
    }
}
