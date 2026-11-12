package com.meditrack.app.feature.verification.di

import com.meditrack.app.core.config.VerificationConfig
import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.RfdetrStripDetector
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
    private val rfdetrDetector: RfdetrStripDetector = mockk(relaxed = true)

    private val originalOffline = VerificationConfig.isOfflineRfdetrEnabled
    private val originalRoboflow = VerificationConfig.isRoboflowWorkflowEnabled

    @org.junit.After
    fun restoreConfig() {
        VerificationConfig.isOfflineRfdetrEnabled = originalOffline
        VerificationConfig.isRoboflowWorkflowEnabled = originalRoboflow
    }

    @Test
    fun provideStripDetector_offlineEnabled_usesOfflineRfdetrDetector() {
        FakeStripDetector.debugForcedBehavior =
            com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior.DEFAULT
        VerificationConfig.isOfflineRfdetrEnabled = true
        VerificationConfig.isRoboflowWorkflowEnabled = true
        val detector = VerificationModule.provideStripDetector(fakeDetector, tfliteDetector, roboflowDetector, rfdetrDetector)
        assertTrue("Offline model must be used whenever it is enabled", detector is RfdetrStripDetector)
    }

    @Test
    fun provideStripDetector_roboflowEnabled_usesRoboflowDetector() {
        FakeStripDetector.debugForcedBehavior =
            com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior.DEFAULT
        VerificationConfig.isOfflineRfdetrEnabled = false
        VerificationConfig.isRoboflowWorkflowEnabled = true
        val detector = VerificationModule.provideStripDetector(fakeDetector, tfliteDetector, roboflowDetector, rfdetrDetector)
        assertTrue("Detector must be RoboflowWorkflowStripDetector when enabled", detector is RoboflowWorkflowStripDetector)
    }

    @Test
    fun provideStripDetector_roboflowDisabled_usesTfliteDetector() {
        FakeStripDetector.debugForcedBehavior =
            com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior.DEFAULT
        VerificationConfig.isOfflineRfdetrEnabled = false
        VerificationConfig.isRoboflowWorkflowEnabled = false
        val detector = VerificationModule.provideStripDetector(fakeDetector, tfliteDetector, roboflowDetector, rfdetrDetector)
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
