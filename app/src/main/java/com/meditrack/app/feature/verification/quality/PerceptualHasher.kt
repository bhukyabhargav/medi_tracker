package com.meditrack.app.feature.verification.quality

import android.graphics.Bitmap
import com.meditrack.app.core.config.AppConfig

object PerceptualHasher {

    /**
     * Computes a 64-bit difference hash (dHash) for an image:
     * 1. Rescales bitmap to 9x8 (9 columns, 8 rows).
     * 2. Converts each pixel to grayscale luminance.
     * 3. Compares adjacent horizontal pixels: (x > x+1) sets the corresponding bit.
     * 4. Returns the resulting 64-bit Long.
     */
    fun computeHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(9 * 8)
        scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)

        val luminance = FloatArray(9 * 8)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luminance[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        var hash = 0L
        var bitIndex = 0

        for (row in 0 until 8) {
            val rowOffset = row * 9
            for (col in 0 until 8) {
                val left = luminance[rowOffset + col]
                val right = luminance[rowOffset + col + 1]

                if (left > right) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }

        return hash
    }

    /**
     * Calculates the Hamming distance (number of differing bits) between two 64-bit hashes.
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * Returns true if two hashes represent likely duplicate images
     * (Hamming distance <= PHASH_MAX_HAMMING_DISTANCE).
     */
    fun isDuplicate(hash1: Long, hash2: Long): Boolean {
        return hammingDistance(hash1, hash2) <= AppConfig.PHASH_MAX_HAMMING_DISTANCE
    }
}
