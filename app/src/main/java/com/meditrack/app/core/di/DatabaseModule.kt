package com.meditrack.app.core.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.meditrack.app.core.database.DatabaseRecoveryState
import com.meditrack.app.core.database.SqlCipherLoader
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
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val TAG = "DatabaseModule"

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        databaseKeyManager: DatabaseKeyManager
    ): AppDatabase {
        // A6: Centralized native library load
        val nativeLoadResult = SqlCipherLoader.load()
        if (nativeLoadResult.isFailure) {
            val err = nativeLoadResult.exceptionOrNull()
            DatabaseRecoveryState.setError(
                DatabaseRecoveryState.DatabaseError(
                    title = "Database Engine Error",
                    message = "The secure database library (SQLCipher) failed to load on this device.",
                    details = err?.stackTraceToString(),
                    isRecoverable = false,
                    cause = err
                )
            )
            return createFallbackDatabase(context)
        }

        // A3 & A4: Explicitly handle passphrase retrieval failure
        val rawPassphrase = try {
            databaseKeyManager.getOrCreatePassphrase()
        } catch (e: Throwable) {
            DatabaseRecoveryState.setError(
                DatabaseRecoveryState.DatabaseError(
                    title = "KeyStore Security Error",
                    message = "Could not access or decrypt the database encryption key. Your local data file is preserved.",
                    details = e.stackTraceToString(),
                    isRecoverable = true,
                    cause = e
                )
            )
            return createFallbackDatabase(context)
        }

        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)

        // A2: Handle one-time plaintext to encrypted migration if needed
        try {
            if (DatabaseEncryptionMigrator.isDatabasePlaintext(dbFile)) {
                DatabaseEncryptionMigrator.migratePlaintextToEncrypted(context, dbFile, rawPassphrase)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Plaintext to encrypted migration attempt threw: ${e.message}", e)
        }

        val openerPassphrase = DatabaseKeyManager.derivePassphraseBytes(rawPassphrase)

        // A1: Verify database file readability without deleting the file on failure
        if (dbFile.exists() && dbFile.length() > 0) {
            var isReadable = false
            var openError: Throwable? = null
            try {
                // Use OPEN_READWRITE so SQLite WAL mode can recover un-checkpointed frames if previous session ended abruptly
                val testDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    openerPassphrase,
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                    null
                )
                testDb.rawExecSQL("SELECT count(*) FROM sqlite_master;")
                testDb.close()
                isReadable = true
            } catch (t: Throwable) {
                isReadable = false
                openError = t
                Log.e(TAG, "Test database open check failed: ${t.message}", t)
            }

            if (!isReadable) {
                // A1: DO NOT DELETE USER DATABASE. Keep file and surface recoverable error screen.
                DatabaseRecoveryState.setError(
                    DatabaseRecoveryState.DatabaseError(
                        title = "Database Decryption Error",
                        message = "The database exists but cannot be decrypted with the current credentials. Your local file has been preserved.",
                        details = openError?.stackTraceToString(),
                        isRecoverable = true,
                        cause = openError
                    )
                )
                return createFallbackDatabase(context)
            }
        }

        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(openerPassphrase)

        return try {
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .build()
        } catch (t: Throwable) {
            DatabaseRecoveryState.setError(
                DatabaseRecoveryState.DatabaseError(
                    title = "Database Initialization Failed",
                    message = "Could not initialize database: ${t.message}",
                    details = t.stackTraceToString(),
                    isRecoverable = true,
                    cause = t
                )
            )
            createFallbackDatabase(context)
        }
    }

    private fun createFallbackDatabase(context: Context): AppDatabase {
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
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
