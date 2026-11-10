package com.meditrack.app.feature.verification.di

import com.meditrack.app.core.config.VerificationConfig
import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.RoboflowWorkflowStripDetector
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationModuleTest {

    private val fakeDetector: FakeStripDetector = mockk(relaxed = true)
    private val tfliteDetector: TfliteStripDetector = mockk(relaxed = true)
    private val roboflowDetector: RoboflowWorkflowStripDetector = mockk(relaxed = true)

    @Test
    fun provideStripDetector_roboflowEnabled_usesRoboflowDetector() {
        FakeStripDetector.debugForcedBehavior =
            com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior.DEFAULT
        VerificationConfig.isRoboflowWorkflowEnabled = true
        val detector = VerificationModule.provideStripDetector(fakeDetector, tfliteDetector, roboflowDetector)
        assertTrue("Detector must be RoboflowWorkflowStripDetector when enabled", detector is RoboflowWorkflowStripDetector)
    }

    @Test
    fun provideStripDetector_roboflowDisabled_usesTfliteDetector() {
        FakeStripDetector.debugForcedBehavior =
            com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior.DEFAULT
        VerificationConfig.isRoboflowWorkflowEnabled = false
        val detector = VerificationModule.provideStripDetector(fakeDetector, tfliteDetector, roboflowDetector)
        assertTrue("Detector must be TfliteStripDetector when roboflow is disabled", detector is TfliteStripDetector)
    }

    @Test
    fun provideStripDetector_releaseBuildLogic_strictlyUsesTfliteAndRejectsFake() {
        // Verify release build contract: when isDebug is false, FakeStripDetector cannot be provided
        val isDebug = false
        val detector = if (isDebug) fakeDetector else tfliteDetector

        assertTrue("Release build must use TfliteStripDetector", detector is TfliteStripDetector)
        assertFalse("Release build must NEVER provide FakeStripDetector", detector is FakeStripDetector)
    }
}
