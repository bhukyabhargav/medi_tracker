package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.core.config.VerificationConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TfliteStripDetectorTest {

    @Test
    fun detect_withoutModelAsset_throwsIllegalStateException() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        val exception = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                detector.detect(bitmap)
            }
        }

        assertTrue(
            "Exception message must mention missing model file: ${exception.message}",
            exception.message?.contains("not installed") == true
        )
    }

    @Test
    fun verificationConfig_containsAuditableModelVersionAndLatencyTargets() {
        assertNotNull(VerificationConfig.modelVersion)
        assertTrue(VerificationConfig.modelVersion.startsWith("v1"))
        assertEquals(400L, VerificationConfig.maxInferenceLatencyMs)
        assertEquals(0.05f, VerificationConfig.edgeMarginFraction, 0.001f)
    }

    @Test
    fun benchmarkLatency_returnsZeroWhenModelNotLoaded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        val latency = detector.benchmarkLatency(bitmap, iterations = 2)
        assertEquals(0L, latency)
    }
}
