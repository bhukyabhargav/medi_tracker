package com.meditrack.app.feature.verification.di

import com.meditrack.app.BuildConfig
import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.MedicineNameReader
import com.meditrack.app.feature.verification.pipeline.MlKitNameReader
import com.meditrack.app.feature.verification.pipeline.StripDetector
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import dagger.Binds
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
        return if (BuildConfig.DEBUG) {
            fakeStripDetector
        } else {
            tfliteStripDetector
        }
    }

    @Provides
    @Singleton
    fun provideMedicineNameReader(
        impl: MlKitNameReader
    ): MedicineNameReader {
        return impl
    }
}
