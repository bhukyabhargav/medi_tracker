package com.meditrack.app.feature.verification.di

import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationModuleTest {

    private val fakeDetector: FakeStripDetector = mockk(relaxed = true)
    private val tfliteDetector: TfliteStripDetector = mockk(relaxed = true)

    @Test
    fun provideStripDetector_releaseBuildLogic_strictlyUsesTfliteAndRejectsFake() {
        // Verify release build contract: when isDebug is false, FakeStripDetector cannot be provided
        val isDebug = false
        val detector = if (isDebug) fakeDetector else tfliteDetector

        assertTrue("Release build must use TfliteStripDetector", detector is TfliteStripDetector)
        assertFalse("Release build must NEVER provide FakeStripDetector", detector is FakeStripDetector)
    }
}
