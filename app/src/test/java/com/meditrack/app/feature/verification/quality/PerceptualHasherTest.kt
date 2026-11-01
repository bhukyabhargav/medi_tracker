package com.meditrack.app.feature.verification.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.meditrack.app.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerceptualHasherTest {

    @Test
    fun identical_images_produce_zero_hamming_distance() {
        val bitmap = createSampleBitmap(100, 100, isHorizontalGradient = true)

        val hash1 = PerceptualHasher.computeHash(bitmap)
        val hash2 = PerceptualHasher.computeHash(bitmap)

        assertEquals(0, PerceptualHasher.hammingDistance(hash1, hash2))
        assertTrue(PerceptualHasher.isDuplicate(hash1, hash2))
    }

    @Test
    fun recompressed_image_has_distance_under_threshold() {
        val original = createSampleBitmap(120, 120, isHorizontalGradient = true)
        val originalHash = PerceptualHasher.computeHash(original)

        // Compress to JPEG 80% and re-decode
        val stream = ByteArrayOutputStream()
        original.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val recompressed = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())

        val recompressedHash = PerceptualHasher.computeHash(recompressed)
        val distance = PerceptualHasher.hammingDistance(originalHash, recompressedHash)

        assertTrue(
            "Expected distance <= ${AppConfig.PHASH_MAX_HAMMING_DISTANCE}, but got $distance",
            distance <= AppConfig.PHASH_MAX_HAMMING_DISTANCE
        )
        assertTrue(PerceptualHasher.isDuplicate(originalHash, recompressedHash))
    }

    @Test
    fun different_images_have_large_hamming_distance() {
        // Image 1: Left to right decreasing (white on left -> black on right)
        val image1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val value = 255 - ((x.toFloat() / 100) * 255).toInt().coerceIn(0, 255)
                image1.setPixel(x, y, Color.rgb(value, value, value))
            }
        }

        // Image 2: Left to right increasing (black on left -> white on right)
        val image2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val value = ((x.toFloat() / 100) * 255).toInt().coerceIn(0, 255)
                image2.setPixel(x, y, Color.rgb(value, value, value))
            }
        }

        val hash1 = PerceptualHasher.computeHash(image1)
        val hash2 = PerceptualHasher.computeHash(image2)

        val distance = PerceptualHasher.hammingDistance(hash1, hash2)

        assertTrue(
            "Expected distance > ${AppConfig.PHASH_MAX_HAMMING_DISTANCE}, but got $distance",
            distance > AppConfig.PHASH_MAX_HAMMING_DISTANCE
        )
        assertFalse(PerceptualHasher.isDuplicate(hash1, hash2))
    }

    private fun createSampleBitmap(width: Int, height: Int, isHorizontalGradient: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Alternating high and low frequencies
                val isEven = ((x / 8) + (y / 8)) % 2 == 0
                val value = if (isEven) 40 else 220
                bitmap.setPixel(x, y, Color.rgb(value, value, value))
            }
        }
        return bitmap
    }
}
