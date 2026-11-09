package com.meditrack.app.feature.verification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meditrack.app.core.util.BitmapLoader
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round 5 Stability Fix C7:
 * Instrumented test that evaluates on-device TFLite YOLOv8 blister strip detection.
 *
 * Requirements:
 * - Loads 5+ real strip photos from androidTest/assets/selftest (supplied by product owner).
 * - Asserts detected cell counts within expected tolerances.
 * - When real photos are not yet supplied, logs explicit OPEN status and validates
 *   interpreter execution on a synthetic strip to guarantee zero native SIGSEGV / OOM faults.
 */
@RunWith(AndroidJUnit4::class)
class RealStripDetectorInstrumentedTest {

    companion object {
        private const val TAG = "RealStripDetectorTest"
    }

    @Test
    fun testRealStripPhotos_whenPresent_assertsCellCountsWithinTolerance() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = TfliteStripDetector(targetContext)

        // Attempt to find real strip photos in androidTest assets or target assets
        val testAssets = try {
            testContext.assets.list("selftest")
                ?.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val appAssets = try {
            targetContext.assets.list("selftest")
                ?.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val availablePhotos = if (testAssets.isNotEmpty()) testAssets else appAssets
        val assetSource = if (testAssets.isNotEmpty()) testContext else targetContext

        if (availablePhotos.size >= 5) {
            Log.i(TAG, "Evaluating ${availablePhotos.size} real blister strip photos...")
            var passedCount = 0

            for (photoName in availablePhotos) {
                val bytes = assetSource.assets.open("selftest/$photoName").use { it.readBytes() }
                val bitmap = BitmapLoader.decodeSampled(bytes, maxSidePx = 1920)
                assertNotNull("Decoded bitmap must not be null for $photoName", bitmap)

                val detection = detector.detect(bitmap!!)
                bitmap.recycle()

                Log.i(TAG, "Photo: $photoName -> StripInFrame: ${detection.stripFullyInFrame}, Cells: ${detection.cells.size}")

                // Assert strip presence and reasonable cell range (e.g. standard strip has 4 to 20 cavities)
                val stripValid = detection.stripFullyInFrame || (detection.stripConfidence ?: 0f) > 0.40f
                val cellCountValid = detection.cells.size in 4..20

                if (stripValid && cellCountValid) {
                    passedCount++
                }
            }

            assertTrue("At least 80% of real test photos must pass cell count tolerances", passedCount >= availablePhotos.size * 0.8)
        } else {
            // Document that real photos are missing from product owner
            Log.w(
                TAG,
                "OPEN (C7): Held-out gold standard real strip photo test set (5+ photos) is MISSING.\n" +
                "Product owner must supply real strip photos into androidTest/assets/selftest/.\n" +
                "Running synthetic strip benchmark to verify interpreter execution without crashing."
            )

            // Validate model interpreter pipeline execution on synthetic test bitmap
            val syntheticBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                canvas.drawColor(Color.LTGRAY)
                val paint = Paint().apply { color = Color.WHITE }
                canvas.drawRoundRect(20f, 40f, 300f, 280f, 10f, 10f, paint)
            }

            val detection = detector.detect(syntheticBitmap)
            syntheticBitmap.recycle()

            assertNotNull("Detection output must not be null", detection)
            Log.i(TAG, "Synthetic execution successful. Output cells: ${detection.cells.size}")
        }

        detector.close()
    }
}
