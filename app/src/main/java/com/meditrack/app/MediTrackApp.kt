package com.meditrack.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.meditrack.app.feature.medicine.worker.DailyDoseExtensionWorker
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
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "MediTrack application initialized - Stage 3 Medicine Management")
        }

        try {
            DailyDoseExtensionWorker.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule DailyDoseExtensionWorker: ${e.message}")
        }
    }

    companion object {
        const val TAG = "MediTrack"
    }
}
