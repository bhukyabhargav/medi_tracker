package com.meditrack.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.NotificationOutboxDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.VerificationAttempt

@Database(
    entities = [
        User::class,
        Medicine::class,
        DoseEvent::class,
        VerificationAttempt::class,
        CaregiverLink::class,
        DoctorLink::class,
        MedicineProposal::class,
        NotificationOutbox::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun medicineDao(): MedicineDao
    abstract fun doseEventDao(): DoseEventDao
    abstract fun verificationAttemptDao(): VerificationAttemptDao
    abstract fun caregiverLinkDao(): CaregiverLinkDao
    abstract fun doctorLinkDao(): DoctorLinkDao
    abstract fun medicineProposalDao(): MedicineProposalDao
    abstract fun notificationOutboxDao(): NotificationOutboxDao

    companion object {
        const val DATABASE_NAME = "meditrack_db"

        /**
         * v1 -> v2: soft delete + low-stock flag on medicines (matches schemas/2.json).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE medicines ADD COLUMN lowStockNotified INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 -> v3: ownership (patientId), refill marker, snooze persistence, consent-based
         * Doctor Mode fields, removal of users.doctorRegistrationNo and enum renames.
         * Existing user data is preserved.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // medicines
                db.execSQL("ALTER TABLE medicines ADD COLUMN patientId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medicines ADD COLUMN lastRefillAt INTEGER")
                db.execSQL("ALTER TABLE medicines ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE medicines SET updatedAt = createdAt")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_medicines_patientId ON medicines(patientId)")

                // dose_events
                db.execSQL("ALTER TABLE dose_events ADD COLUMN patientId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dose_events ADD COLUMN nextReminderAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dose_events_patientId ON dose_events(patientId)")

                // Backfill ownership when the device has exactly one local user (the normal case).
                db.execSQL(
                    "UPDATE medicines SET patientId = (SELECT id FROM users LIMIT 1) " +
                        "WHERE patientId = '' AND (SELECT COUNT(*) FROM users) = 1"
                )
                db.execSQL(
                    "UPDATE dose_events SET patientId = " +
                        "COALESCE((SELECT m.patientId FROM medicines m WHERE m.id = dose_events.medicineId), '') " +
                        "WHERE patientId = ''"
                )

                // caregiver_links
                db.execSQL("CREATE INDEX IF NOT EXISTS index_caregiver_links_caregiverUid ON caregiver_links(caregiverUid)")

                // doctor_links
                db.execSQL("ALTER TABLE doctor_links ADD COLUMN invitationCode TEXT")
                db.execSQL("ALTER TABLE doctor_links ADD COLUMN expiresAt INTEGER")
                db.execSQL("ALTER TABLE doctor_links ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE doctor_links SET updatedAt = createdAt")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_doctor_links_doctorUid ON doctor_links(doctorUid)")

                // medicine_proposals
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN clarificationNote TEXT")
                db.execSQL("UPDATE medicine_proposals SET status = 'ACCEPTED' WHERE status = 'APPLIED'")

                // notification_outbox: old doctor-mode names -> new names
                db.execSQL("UPDATE notification_outbox SET type = 'DOCTOR_INVITE_RECEIVED' WHERE type = 'DOCTOR_INVITE'")
                db.execSQL("UPDATE notification_outbox SET type = 'PRESCRIPTION_PROPOSAL_RECEIVED' WHERE type = 'MEDICINE_PRESCRIBED'")
                db.execSQL(
                    "UPDATE notification_outbox SET type = 'PRESCRIPTION_REVISED' " +
                        "WHERE type IN ('MEDICINE_CHANGED_BY_DOCTOR', 'MEDICINE_STOPPED_BY_DOCTOR')"
                )

                // users: drop doctorRegistrationNo by recreating the table (minSdk 26 has no DROP COLUMN)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS users_new (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT, `phone` TEXT, " +
                        "`role` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO users_new (id, name, email, phone, role, createdAt) " +
                        "SELECT id, name, email, phone, role, createdAt FROM users"
                )
                db.execSQL("DROP TABLE users")
                db.execSQL("ALTER TABLE users_new RENAME TO users")
            }
        }

        /**
         * v3 -> v4: adds supersedesProposalId to medicine_proposals for tracking
         * proposal revision lineages in Doctor Mode.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN supersedesProposalId TEXT")
            }
        }

        /**
         * v4 -> v5: adds baseline geometry signature fields to medicines
         * for rotation-invariant blister verification and brand/layout mismatch detection.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineCellCount INTEGER")
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineCentersJson TEXT")
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineCellSizeRatio REAL")
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineRows INTEGER")
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineCols INTEGER")
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineAspectRatio REAL")
                db.execSQL("ALTER TABLE medicines ADD COLUMN baselineVersion INTEGER NOT NULL DEFAULT 1")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
