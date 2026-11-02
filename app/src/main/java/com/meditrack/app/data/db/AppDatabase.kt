package com.meditrack.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    }
}
