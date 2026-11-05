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
    version = 3,
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Medicines table additions
                db.execSQL("ALTER TABLE medicines ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE medicines ADD COLUMN lowStockNotified INTEGER NOT NULL DEFAULT 0")

                // 2. Recreate dose_events with ON DELETE RESTRICT and add indices
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dose_events_new` (
                        `id` TEXT NOT NULL,
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
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`medicineId`) REFERENCES `medicines`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO dose_events_new (
                        id, medicineId, scheduledAt, windowEndsAt, status, takenAt,
                        verifiedPhotoPath, verificationAttempts, emptyCellsAfter, snoozeCount,
                        lateEntry, manualUnverified, updatedAt
                    )
                    SELECT id, medicineId, scheduledAt, windowEndsAt, status, takenAt,
                           verifiedPhotoPath, verificationAttempts, emptyCellsAfter, snoozeCount,
                           lateEntry, manualUnverified, updatedAt
                    FROM dose_events
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE dose_events")
                db.execSQL("ALTER TABLE dose_events_new RENAME TO dose_events")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_medicineId` ON `dose_events` (`medicineId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_scheduledAt` ON `dose_events` (`scheduledAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_status` ON `dose_events` (`status`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Medicines table additions
                db.execSQL("ALTER TABLE medicines ADD COLUMN patientId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medicines ADD COLUMN lastRefillAt INTEGER")
                db.execSQL("ALTER TABLE medicines ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_medicines_patientId ON medicines(patientId)")

                // 2. DoseEvents table additions
                db.execSQL("ALTER TABLE dose_events ADD COLUMN patientId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dose_events ADD COLUMN nextReminderAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dose_events_patientId ON dose_events(patientId)")

                // 3. CaregiverLinks index additions
                db.execSQL("CREATE INDEX IF NOT EXISTS index_caregiver_links_caregiverUid ON caregiver_links(caregiverUid)")

                // 4. DoctorLinks table additions
                db.execSQL("ALTER TABLE doctor_links ADD COLUMN invitationCode TEXT")
                db.execSQL("ALTER TABLE doctor_links ADD COLUMN expiresAt INTEGER")
                db.execSQL("ALTER TABLE doctor_links ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_doctor_links_doctorUid ON doctor_links(doctorUid)")

                // 5. MedicineProposals table additions and status migration
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN clarificationNote TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_medicine_proposals_doctorUid ON medicine_proposals(doctorUid)")
                db.execSQL("UPDATE medicine_proposals SET status = 'ACCEPTED' WHERE status = 'APPLIED'")

                // 6. NotificationOutbox type migration
                db.execSQL("UPDATE notification_outbox SET type = 'DOCTOR_INVITE_RECEIVED' WHERE type = 'DOCTOR_INVITE'")
                db.execSQL("UPDATE notification_outbox SET type = 'PRESCRIPTION_PROPOSAL_RECEIVED' WHERE type = 'DOCTOR_PROPOSAL_NEW'")
                db.execSQL("UPDATE notification_outbox SET type = 'PRESCRIPTION_ACCEPTED' WHERE type = 'DOCTOR_PROPOSAL_ACCEPTED'")
                db.execSQL("UPDATE notification_outbox SET type = 'PRESCRIPTION_DECLINED' WHERE type = 'DOCTOR_PROPOSAL_REJECTED'")
                db.execSQL("UPDATE notification_outbox SET type = 'PRESCRIPTION_CLARIFICATION_REQUESTED' WHERE type = 'DOCTOR_PROPOSAL_CLARIFICATION'")

                // 7. Users table recreation without doctorRegistrationNo (SQLite minSdk 26 table copy)
                db.execSQL("CREATE TABLE IF NOT EXISTS `users_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT, `phone` TEXT, `role` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `users_new` (`id`, `name`, `email`, `phone`, `role`, `createdAt`) SELECT `id`, `name`, `email`, `phone`, `role`, `createdAt` FROM `users`")
                db.execSQL("DROP TABLE `users`")
                db.execSQL("ALTER TABLE `users_new` RENAME TO `users`")

                // 8. Backfill patientId from users table for existing medicines & dose_events
                val cursor = db.query("SELECT id FROM users LIMIT 1")
                val defaultPatientId = if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    "default_patient"
                }
                cursor.close()

                db.execSQL("UPDATE medicines SET patientId = '$defaultPatientId' WHERE patientId = '' OR patientId IS NULL")
                db.execSQL("UPDATE medicines SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("UPDATE dose_events SET patientId = '$defaultPatientId' WHERE patientId = '' OR patientId IS NULL")
                db.execSQL("UPDATE dose_events SET updatedAt = scheduledAt WHERE updatedAt = 0")
            }
        }
    }
}
