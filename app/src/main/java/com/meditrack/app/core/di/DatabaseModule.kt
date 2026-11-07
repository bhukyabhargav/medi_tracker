package com.meditrack.app.core.di

import android.content.Context
import androidx.room.Room
import com.meditrack.app.core.security.DatabaseKeyManager
import com.meditrack.app.data.db.AppDatabase
import com.meditrack.app.data.db.DatabaseEncryptionMigrator
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.NotificationOutboxDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        databaseKeyManager: DatabaseKeyManager
    ): AppDatabase {
        val passphrase = databaseKeyManager.getOrCreatePassphrase()
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)

        // Handle one-time plaintext to encrypted migration if needed
        try {
            if (DatabaseEncryptionMigrator.isDatabasePlaintext(dbFile)) {
                DatabaseEncryptionMigrator.migratePlaintextToEncrypted(context, dbFile, passphrase)
            }
        } catch (_: Throwable) {
            // Best effort migration
        }

        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .openHelperFactory(factory)
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .build()
    }

    @Provides
    fun provideUserDao(database: AppDatabase): UserDao = database.userDao()

    @Provides
    fun provideMedicineDao(database: AppDatabase): MedicineDao = database.medicineDao()

    @Provides
    fun provideDoseEventDao(database: AppDatabase): DoseEventDao = database.doseEventDao()

    @Provides
    fun provideVerificationAttemptDao(database: AppDatabase): VerificationAttemptDao =
        database.verificationAttemptDao()

    @Provides
    fun provideCaregiverLinkDao(database: AppDatabase): CaregiverLinkDao =
        database.caregiverLinkDao()

    @Provides
    fun provideDoctorLinkDao(database: AppDatabase): DoctorLinkDao =
        database.doctorLinkDao()

    @Provides
    fun provideMedicineProposalDao(database: AppDatabase): MedicineProposalDao =
        database.medicineProposalDao()

    @Provides
    fun provideNotificationOutboxDao(database: AppDatabase): NotificationOutboxDao =
        database.notificationOutboxDao()
}
