package com.meditrack.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

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
                INSERT INTO medicines (id, name, dosageText, pillsPerDose, frequencyType, timesOfDay, startDate, foodRelation, instructions, reminderEnabled, initialStock, currentStock, baselineEmptyCells, stoppedByDoctor, createdAt)
                VALUES ('med_1', 'Aspirin', '100mg', 1, 'DAILY', '["08:00"]', 1000000, 'ANY', '', 1, 30, 25, 0, 0, 1000000)
                """.trimIndent()
            )
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        val cursor = db.query("SELECT isDeleted, lowStockNotified FROM medicines WHERE id = 'med_1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        assertEquals(0, cursor.getInt(1))
        cursor.close()
    }

    @Test
    fun migrate2To3_addsColumns_recreatesUsers_andBackfillsPatientId() {
        var db = helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO users (id, name, email, phone, role, doctorRegistrationNo, createdAt)
                VALUES ('user_1', 'Alice', 'alice@test.com', '1234567890', 'PATIENT', 'DOC-99', 1000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO medicines (id, name, dosageText, pillsPerDose, frequencyType, timesOfDay, startDate, foodRelation, instructions, reminderEnabled, initialStock, currentStock, baselineEmptyCells, stoppedByDoctor, isDeleted, lowStockNotified, createdAt)
                VALUES ('med_1', 'Metformin', '500mg', 1, 'DAILY', '["09:00"]', 1000000, 'ANY', '', 1, 60, 50, 0, 0, 0, 0, 1000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO dose_events (id, medicineId, scheduledAt, windowEndsAt, status, verificationAttempts, snoozeCount, lateEntry, manualUnverified, updatedAt)
                VALUES ('dose_1', 'med_1', 1005000, 1008600, 'PENDING', 0, 0, 0, 0, 1005000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO medicine_proposals (id, patientId, doctorUid, type, payloadJson, status, createdAt)
                VALUES ('prop_1', 'user_1', 'doc_1', 'ADD', '{}', 'APPLIED', 1000000)
                """.trimIndent()
            )
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3)

        // 1. Verify user recreated without doctorRegistrationNo
        val userCursor = db.query("SELECT id, name, role FROM users WHERE id = 'user_1'")
        assertTrue(userCursor.moveToFirst())
        assertEquals("user_1", userCursor.getString(0))
        assertEquals("Alice", userCursor.getString(1))
        assertEquals("PATIENT", userCursor.getString(2))
        userCursor.close()

        // 2. Verify backfilled patientId on medicine and dose
        val medCursor = db.query("SELECT patientId, updatedAt FROM medicines WHERE id = 'med_1'")
        assertTrue(medCursor.moveToFirst())
        assertEquals("user_1", medCursor.getString(0))
        medCursor.close()

        val doseCursor = db.query("SELECT patientId, nextReminderAt FROM dose_events WHERE id = 'dose_1'")
        assertTrue(doseCursor.moveToFirst())
        assertEquals("user_1", doseCursor.getString(0))
        doseCursor.close()

        // 3. Verify APPLIED status mapped to ACCEPTED
        val propCursor = db.query("SELECT status, revision FROM medicine_proposals WHERE id = 'prop_1'")
        assertTrue(propCursor.moveToFirst())
        assertEquals("ACCEPTED", propCursor.getString(0))
        assertEquals(1, propCursor.getInt(1))
        propCursor.close()
    }

    @Test
    fun migrate1To3_allMigrationsRunSequentially() {
        var db = helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO users (id, name, email, phone, role, doctorRegistrationNo, createdAt)
                VALUES ('user_1', 'Bob', 'bob@test.com', '1234567890', 'PATIENT', 'DOC-88', 1000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO medicines (id, name, dosageText, pillsPerDose, frequencyType, timesOfDay, startDate, foodRelation, instructions, reminderEnabled, initialStock, currentStock, baselineEmptyCells, stoppedByDoctor, createdAt)
                VALUES ('med_1', 'Paracetamol', '650mg', 1, 'DAILY', '["12:00"]', 1000000, 'ANY', '', 1, 20, 20, 0, 0, 1000000)
                """.trimIndent()
            )
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)

        val medCursor = db.query("SELECT patientId, isDeleted, lowStockNotified FROM medicines WHERE id = 'med_1'")
        assertTrue(medCursor.moveToFirst())
        assertEquals("user_1", medCursor.getString(0))
        assertEquals(0, medCursor.getInt(1))
        assertEquals(0, medCursor.getInt(2))
        medCursor.close()
    }
}
