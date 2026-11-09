package com.meditrack.app.feature.verification.di

import com.meditrack.app.BuildConfig
import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior
import com.meditrack.app.feature.verification.pipeline.StripDetector
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VerificationModule {

    @Provides
    @Singleton
    fun provideStripDetector(
        fakeStripDetector: FakeStripDetector,
        tfliteStripDetector: TfliteStripDetector
    ): StripDetector {
        // In both debug and release, use real TfliteStripDetector so ML model evaluation works on device.
        // Allow FakeStripDetector in debug only if a non-default forced behavior was chosen in debug tools.
        return if (BuildConfig.DEBUG && FakeStripDetector.debugForcedBehavior != ForceDetectorBehavior.DEFAULT) {
            fakeStripDetector
        } else {
            tfliteStripDetector
        }
    }
}
