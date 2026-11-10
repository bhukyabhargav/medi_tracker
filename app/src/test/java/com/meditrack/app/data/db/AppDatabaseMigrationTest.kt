package com.meditrack.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.domain.model.BaselineGeometrySignature
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun migration_4_to_5_addsBaselineGeometryColumns_andPreservesExistingData() {
        val testDbFile = File(context.filesDir, "test_mig_4_5.db")
        if (testDbFile.exists()) testDbFile.delete()

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(sqLiteDb: SupportSQLiteDatabase) {
                    // Create v4 schema of medicines table
                    sqLiteDb.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `medicines` (
                            `id` TEXT NOT NULL,
                            `patientId` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `dosageText` TEXT NOT NULL,
                            `pillsPerDose` INTEGER NOT NULL,
                            `frequencyType` TEXT NOT NULL,
                            `timesOfDay` TEXT NOT NULL,
                            `intervalHours` INTEGER,
                            `daysOfWeek` TEXT,
                            `startDate` INTEGER NOT NULL,
                            `endDate` INTEGER,
                            `foodRelation` TEXT NOT NULL,
                            `instructions` TEXT NOT NULL,
                            `reminderEnabled` INTEGER NOT NULL,
                            `initialStock` INTEGER NOT NULL,
                            `currentStock` INTEGER NOT NULL,
                            `baselineEmptyCells` INTEGER NOT NULL,
                            `baselinePhotoPath` TEXT,
                            `lastRefillAt` INTEGER,
                            `prescribedByDoctorUid` TEXT,
                            `prescribedByDoctorName` TEXT,
                            `stoppedByDoctor` INTEGER NOT NULL,
                            `isDeleted` INTEGER NOT NULL,
                            `lowStockNotified` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    // Insert existing medicine in v4
                    sqLiteDb.execSQL(
                        """
                        INSERT INTO medicines (
                            id, patientId, name, dosageText, pillsPerDose, frequencyType,
                            timesOfDay, startDate, foodRelation, instructions, reminderEnabled,
                            initialStock, currentStock, baselineEmptyCells, baselinePhotoPath,
                            stoppedByDoctor, isDeleted, lowStockNotified, createdAt, updatedAt
                        ) VALUES (
                            'med_mig_1', 'patient_1', 'Metformin', '500mg', 1, 'DAILY',
                            '08:00', 1000, 'AFTER', 'Take with water', 1,
                            60, 50, 0, 'photo.jpg',
                            0, 0, 0, 1000, 1000
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(sqLiteDb: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val writableDb = openHelper.writableDatabase

        // Execute MIGRATION_4_5
        AppDatabase.MIGRATION_4_5.migrate(writableDb)

        // Verify existing row is preserved and has expected default/null values for new columns
        val cursor = writableDb.query("SELECT id, name, baselineCellCount, baselineCentersJson, baselineCellSizeRatio, baselineRows, baselineCols, baselineAspectRatio, baselineVersion FROM medicines WHERE id = 'med_mig_1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("med_mig_1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Metformin", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("baselineCellCount")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("baselineCentersJson")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("baselineCellSizeRatio")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("baselineRows")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("baselineCols")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("baselineAspectRatio")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("baselineVersion")))
        cursor.close()
        writableDb.close()
        testDbFile.delete()
    }

    @Test
    fun migration_5_to_6_addsDetectorSourceColumns_andPreservesExistingData() {
        val testDbFile = File(context.filesDir, "test_mig_5_6.db")
        if (testDbFile.exists()) testDbFile.delete()

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(sqLiteDb: SupportSQLiteDatabase) {
                    // Create v5 schema of medicines table
                    sqLiteDb.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `medicines` (
                            `id` TEXT NOT NULL,
                            `patientId` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `dosageText` TEXT NOT NULL,
                            `pillsPerDose` INTEGER NOT NULL,
                            `frequencyType` TEXT NOT NULL,
                            `timesOfDay` TEXT NOT NULL,
                            `intervalHours` INTEGER,
                            `daysOfWeek` TEXT,
                            `startDate` INTEGER NOT NULL,
                            `endDate` INTEGER,
                            `foodRelation` TEXT NOT NULL,
                            `instructions` TEXT NOT NULL,
                            `reminderEnabled` INTEGER NOT NULL,
                            `initialStock` INTEGER NOT NULL,
                            `currentStock` INTEGER NOT NULL,
                            `baselineEmptyCells` INTEGER NOT NULL,
                            `baselinePhotoPath` TEXT,
                            `lastRefillAt` INTEGER,
                            `prescribedByDoctorUid` TEXT,
                            `prescribedByDoctorName` TEXT,
                            `stoppedByDoctor` INTEGER NOT NULL,
                            `isDeleted` INTEGER NOT NULL,
                            `lowStockNotified` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `baselineCellCount` INTEGER,
                            `baselineCentersJson` TEXT,
                            `baselineCellSizeRatio` REAL,
                            `baselineRows` INTEGER,
                            `baselineCols` INTEGER,
                            `baselineAspectRatio` REAL,
                            `baselineVersion` INTEGER NOT NULL DEFAULT 1,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    // Create v5 schema of dose_events table
                    sqLiteDb.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `dose_events` (
                            `id` TEXT NOT NULL,
                            `patientId` TEXT NOT NULL,
                            `medicineId` TEXT NOT NULL,
                            `scheduledAt` INTEGER NOT NULL,
                            `windowEndsAt` INTEGER NOT NULL,
                            `status` TEXT NOT NULL,
                            `takenAt` INTEGER,
                            `verifiedPhotoPath` TEXT,
                            `verificationAttempts` INTEGER NOT NULL,
                            `emptyCellsAfter` INTEGER,
                            `snoozeCount` INTEGER NOT NULL,
                            `lateEntry` INTEGER NOT NULL,
                            `manualUnverified` INTEGER NOT NULL,
                            `nextReminderAt` INTEGER,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )

                    sqLiteDb.execSQL(
                        """
                        INSERT INTO medicines (
                            id, patientId, name, dosageText, pillsPerDose, frequencyType,
                            timesOfDay, startDate, foodRelation, instructions, reminderEnabled,
                            initialStock, currentStock, baselineEmptyCells, baselinePhotoPath,
                            stoppedByDoctor, isDeleted, lowStockNotified, createdAt, updatedAt
                        ) VALUES (
                            'med_mig_5_6', 'patient_1', 'Paracetamol', '650mg', 1, 'DAILY',
                            '09:00', 1000, 'AFTER', 'With water', 1,
                            10, 9, 2, 'baseline.jpg',
                            0, 0, 0, 1000, 1000
                        )
                        """.trimIndent()
                    )

                    sqLiteDb.execSQL(
                        """
                        INSERT INTO dose_events (
                            id, patientId, medicineId, scheduledAt, windowEndsAt, status,
                            takenAt, verifiedPhotoPath, verificationAttempts, emptyCellsAfter,
                            snoozeCount, lateEntry, manualUnverified, updatedAt
                        ) VALUES (
                            'dose_mig_5_6', 'patient_1', 'med_mig_5_6', 1000, 2000, 'VERIFIED',
                            1050, 'dose.jpg', 1, 3,
                            0, 0, 0, 1050
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(sqLiteDb: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val writableDb = openHelper.writableDatabase

        // Execute MIGRATION_5_6
        AppDatabase.MIGRATION_5_6.migrate(writableDb)

        // Verify medicines table has baselineDetectorSource column and data preserved
        val medCursor = writableDb.query("SELECT id, name, baselineDetectorSource FROM medicines WHERE id = 'med_mig_5_6'")
        assertTrue(medCursor.moveToFirst())
        assertEquals("med_mig_5_6", medCursor.getString(medCursor.getColumnIndexOrThrow("id")))
        assertEquals("Paracetamol", medCursor.getString(medCursor.getColumnIndexOrThrow("name")))
        assertTrue(medCursor.isNull(medCursor.getColumnIndexOrThrow("baselineDetectorSource")))
        medCursor.close()

        // Verify dose_events table has detectorSource column and data preserved
        val doseCursor = writableDb.query("SELECT id, emptyCellsAfter, detectorSource FROM dose_events WHERE id = 'dose_mig_5_6'")
        assertTrue(doseCursor.moveToFirst())
        assertEquals("dose_mig_5_6", doseCursor.getString(doseCursor.getColumnIndexOrThrow("id")))
        assertEquals(3, doseCursor.getInt(doseCursor.getColumnIndexOrThrow("emptyCellsAfter")))
        assertTrue(doseCursor.isNull(doseCursor.getColumnIndexOrThrow("detectorSource")))
        doseCursor.close()

        writableDb.close()
        testDbFile.delete()
    }

    @Test
    fun medicineWithBaselineSignature_savesAndRestoresCorrectly() = runTest {
        val sig = BaselineGeometrySignature(
            totalCellCount = 10,
            normalizedCenters = listOf(Pair(0.1f, 0.2f), Pair(0.3f, 0.4f), Pair(0.5f, 0.6f), Pair(0.7f, 0.8f)),
            relativeCellSize = 0.08f,
            estimatedRows = 2,
            estimatedColumns = 5,
            stripAspectRatio = 2.4f,
            baselineVersion = 1
        )

        val medicine = Medicine(
            id = "med_sig_1",
            patientId = "patient_1",
            name = "Cipla 10",
            dosageText = "10mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(9, 0)),
            startDate = 5000L,
            foodRelation = FoodRelation.AFTER,
            initialStock = 10,
            currentStock = 10,
            baselinePhotoPath = "baseline_cipla.jpg",
            baselineEmptyCells = 3
        ).withBaselineSignature(sig)

        db.medicineDao().insert(medicine)

        val retrieved = db.medicineDao().getById("med_sig_1")
        assertNotNull(retrieved)
        assertEquals(10, retrieved!!.baselineCellCount)
        assertEquals(2, retrieved.baselineRows)
        assertEquals(5, retrieved.baselineCols)
        assertEquals(2.4f, retrieved.baselineAspectRatio!!, 0.01f)
        assertEquals(0.08f, retrieved.baselineCellSizeRatio!!, 0.01f)

        val retrievedSig = retrieved.getBaselineSignature()
        assertNotNull(retrievedSig)
        assertEquals(10, retrievedSig!!.totalCellCount)
        assertEquals(2, retrievedSig.estimatedRows)
        assertEquals(5, retrievedSig.estimatedColumns)
        assertEquals(4, retrievedSig.normalizedCenters.size)
        assertEquals(0.1f, retrievedSig.normalizedCenters[0].first, 0.01f)
        assertEquals(0.2f, retrievedSig.normalizedCenters[0].second, 0.01f)
    }
}
