package com.meditrack.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 11 / D2: Room Migration Test (1 -> 2 -> 3) using MigrationTestHelper and schema JSONs.
 * Asserts data survival across versions and backfills of patientId ownership and timestamps.
 * Placed in androidTest per requirement D2 when schemas cannot be bundled into local JVM unit tests.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_addsIsDeletedAndLowStockNotified() {
        var db = helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO medicines (
                    id, name, dosageText, pillsPerDose, frequencyType, timesOfDay,
                    intervalHours, daysOfWeek, startDate, endDate, foodRelation,
                    instructions, reminderEnabled, initialStock, currentStock,
                    baselineEmptyCells, baselinePhotoPath, prescribedByDoctorUid,
                    prescribedByDoctorName, stoppedByDoctor, createdAt
                ) VALUES (
                    'med_1', 'Metformin', '500mg', 1, 'DAILY', '["08:00"]',
                    NULL, NULL, 1000, 5000, 'AFTER',
                    '', 1, 20, 20,
                    0, NULL, NULL,
                    NULL, 0, 1000
                )
                """.trimIndent()
            )
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        val cursor = db.query("SELECT id, name, isDeleted, lowStockNotified FROM medicines WHERE id = 'med_1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Metformin", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isDeleted")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("lowStockNotified")))
        cursor.close()
    }

    @Test
    fun migrateAll_1To2To3_preservesDataAndBackfillsPatientOwnership() {
        var db = helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO users (
                    id, name, email, phone, role, doctorRegistrationNo, createdAt
                ) VALUES (
                    'user_alice', 'Alice Smith', 'alice@example.com', '1234567890', 'PATIENT', 'DOC-1234', 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO medicines (
                    id, name, dosageText, pillsPerDose, frequencyType, timesOfDay,
                    intervalHours, daysOfWeek, startDate, endDate, foodRelation,
                    instructions, reminderEnabled, initialStock, currentStock,
                    baselineEmptyCells, baselinePhotoPath, prescribedByDoctorUid,
                    prescribedByDoctorName, stoppedByDoctor, createdAt
                ) VALUES (
                    'med_1', 'Paracetamol', '500mg', 1, 'DAILY', '["08:00"]',
                    NULL, NULL, 1000, 5000, 'AFTER',
                    '', 1, 20, 20,
                    0, NULL, NULL,
                    NULL, 0, 1000
                )
                """.trimIndent()
            )
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *AppDatabase.ALL_MIGRATIONS)

        // Verify medicines: patientId backfilled from single local user, updatedAt copied from createdAt
        val medCursor = db.query("SELECT id, name, patientId, updatedAt, isDeleted FROM medicines WHERE id = 'med_1'")
        assertTrue(medCursor.moveToFirst())
        assertEquals("Paracetamol", medCursor.getString(medCursor.getColumnIndexOrThrow("name")))
        assertEquals("user_alice", medCursor.getString(medCursor.getColumnIndexOrThrow("patientId")))
        assertEquals(1000L, medCursor.getLong(medCursor.getColumnIndexOrThrow("updatedAt")))
        assertEquals(0, medCursor.getInt(medCursor.getColumnIndexOrThrow("isDeleted")))
        medCursor.close()

        // Verify users: doctorRegistrationNo column was removed, user preserved
        val userCursor = db.query("SELECT id, name, role FROM users WHERE id = 'user_alice'")
        assertTrue(userCursor.moveToFirst())
        assertEquals("Alice Smith", userCursor.getString(userCursor.getColumnIndexOrThrow("name")))
        assertEquals("PATIENT", userCursor.getString(userCursor.getColumnIndexOrThrow("role")))
        userCursor.close()
    }
}
