package com.meditrack.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MediTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "MediTrack application initialized - Stage 0 bootstrap")
        }
    }

    companion object {
        const val TAG = "MediTrack"
    }
}
