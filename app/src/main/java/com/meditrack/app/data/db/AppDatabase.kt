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
    version = 2,
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

                // 5. MedicineProposals table additions
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE medicine_proposals ADD COLUMN clarificationNote TEXT")
            }
        }
    }
}
