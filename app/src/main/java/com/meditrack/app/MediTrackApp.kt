package com.meditrack.app

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.meditrack.app.core.database.SqlCipherLoader
import com.meditrack.app.core.util.CrashLogger
import com.meditrack.app.core.util.ScanFileCleaner
import com.meditrack.app.feature.medicine.worker.DailyDoseExtensionWorker
import com.meditrack.app.feature.reminder.NotificationChannels
import com.meditrack.app.feature.reminder.worker.OverdueDoseSafetyWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MediTrackApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = if (::workerFactory.isInitialized) {
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        } else {
            Configuration.Builder().build()
        }

    override fun onCreate() {
        super.onCreate()

        // Stage 12 / Stability Fix E1: Install uncaught exception handler with rotating crash log
        CrashLogger.install(this)

        // Stage 12 / Stability Fix E1: StrictMode in debug builds
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        // Stage 12 / Stability Fix A6: Centralized SQLCipher native library loading
        val sqlCipherResult = SqlCipherLoader.load()
        if (sqlCipherResult.isFailure) {
            Log.w(TAG, "SQLCipher native library not loaded at app start: ${sqlCipherResult.exceptionOrNull()?.message}")
        }

        // Stage 12 / Stability Fix B3: Purge prescription scan temp files older than 1 hour
        ScanFileCleaner.purgeStaleScanFiles(this)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "MediTrack application initialized - Stage 4 Reminders & Missed-Dose Engine")
        }

        NotificationChannels.createChannels(this)

        try {
            DailyDoseExtensionWorker.schedule(this)
            OverdueDoseSafetyWorker.schedule(this)
            com.meditrack.app.feature.reminder.worker.AlarmMaintenanceWorker.schedule(this)
            com.meditrack.app.feature.reminder.worker.OutboxDeliveryWorker.schedule(this)
            com.meditrack.app.feature.sync.SyncWorker.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule background workers: ${e.message}")
        }
    }

    companion object {
        const val TAG = "MediTrack"
    }
}
