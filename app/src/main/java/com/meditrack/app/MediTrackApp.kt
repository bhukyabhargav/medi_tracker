package com.meditrack.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.meditrack.app.core.database.SqlCipherLoader
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

        // Stage 12 / Stability Fix A6: Centralized SQLCipher native library loading
        val sqlCipherResult = SqlCipherLoader.load()
        if (sqlCipherResult.isFailure) {
            Log.w(TAG, "SQLCipher native library not loaded at app start: ${sqlCipherResult.exceptionOrNull()?.message}")
        }

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
