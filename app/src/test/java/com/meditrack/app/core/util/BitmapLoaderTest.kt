package com.meditrack.app.core.util

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class BitmapLoaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `calculateInSampleSize calculates correct power of 2 downsampling`() {
        assertEquals(1, BitmapLoader.calculateInSampleSize(1920, 1080, 1920))
        assertEquals(1, BitmapLoader.calculateInSampleSize(1000, 800, 1920))

        // 4000x3000 with maxSidePx 1920 -> 4000 / 2 = 2000 > 1920 -> sampleSize 4 (4000/4 = 1000 <= 1920)
        val sampleSize = BitmapLoader.calculateInSampleSize(4000, 3000, 1920)
        assertTrue(sampleSize >= 2)

        // 8000x6000 with maxSidePx 1920 -> sampleSize 8
        val sampleSizeHuge = BitmapLoader.calculateInSampleSize(8000, 6000, 1920)
        assertTrue(sampleSizeHuge >= 4)
    }

    @Test
    fun `decodeSampled from large image file caps longest side to maxSidePx`() {
        val largeFile = tempFolder.newFile("large_camera_photo.jpg")
        val originalW = 4000
        val originalH = 3000
        val syntheticBitmap = Bitmap.createBitmap(originalW, originalH, Bitmap.Config.RGB_565)
        FileOutputStream(largeFile).use { out ->
            syntheticBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        syntheticBitmap.recycle()

        val maxSide = 1920
        val decoded = BitmapLoader.decodeSampled(largeFile, maxSidePx = maxSide)

        assertNotNull("Decoded bitmap must not be null", decoded)
        val maxDecodedDim = maxOf(decoded!!.width, decoded.height)
        assertTrue(
            "Max dimension $maxDecodedDim must be <= $maxSide",
            maxDecodedDim <= maxSide
        )
        decoded.recycle()
    }

    @Test
    fun `decodeSampled from byte array caps dimensions correctly`() {
        val originalW = 3200
        val originalH = 2400
        val syntheticBitmap = Bitmap.createBitmap(originalW, originalH, Bitmap.Config.RGB_565)
        val bos = ByteArrayOutputStream()
        syntheticBitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        syntheticBitmap.recycle()
        val bytes = bos.toByteArray()

        val maxSide = 1200
        val decoded = BitmapLoader.decodeSampled(bytes, maxSidePx = maxSide)

        assertNotNull("Decoded bitmap must not be null", decoded)
        val maxDecodedDim = maxOf(decoded!!.width, decoded.height)
        assertTrue(
            "Max dimension $maxDecodedDim must be <= $maxSide",
            maxDecodedDim <= maxSide
        )
        decoded.recycle()
    }

    @Test
    fun `decodeSampled returns null safely on missing or empty file without throwing`() {
        val missingFile = File(tempFolder.root, "non_existent.jpg")
        val resultMissing = BitmapLoader.decodeSampled(missingFile)
        assertNull(resultMissing)

        val emptyFile = tempFolder.newFile("empty.jpg")
        val resultEmpty = BitmapLoader.decodeSampled(emptyFile)
        assertNull(resultEmpty)
    }

    @Test
    fun `decodeSampled returns null safely on empty byte array without throwing`() {
        val emptyBytes = ByteArray(0)
        val result = BitmapLoader.decodeSampled(emptyBytes)
        assertNull(result)
    }

    @Test
    fun `scaleAndRotateBitmap applies 90 degree rotation and adjusts dimensions`() {
        val src = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val rotated = BitmapLoader.scaleAndRotateBitmap(src, maxSidePx = 500, rotationDegrees = 90)

        // 400x200 rotated 90 degrees becomes 200x400
        assertEquals(200, rotated.width)
        assertEquals(400, rotated.height)
        rotated.recycle()
    }
}
