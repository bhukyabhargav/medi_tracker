package com.meditrack.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Stage 12 / Stability Fix B1:
 * Centralized, memory-safe bitmap decoding utility.
 *
 * Prevents OutOfMemoryError crashes by:
 * 1. Reading image bounds first without allocating pixel memory.
 * 2. Calculating optimal inSampleSize to downsample high-res camera captures (e.g. 12-50 MP)
 *    to safe operational dimensions (default max side: 1920 px).
 * 3. Handling EXIF rotation and recycling intermediate unrotated bitmaps.
 * 4. Catching Throwable (including OutOfMemoryError) at all decoding sites.
 */
object BitmapLoader {
    private const val TAG = "BitmapLoader"
    const val DEFAULT_MAX_SIDE_PX = 1920

    /**
     * Decodes an image file from disk with sampling and EXIF orientation applied.
     */
    fun decodeSampled(
        path: String,
        maxSidePx: Int = DEFAULT_MAX_SIDE_PX
    ): Bitmap? = decodeSampled(File(path), maxSidePx)

    /**
     * Decodes an image file from disk with sampling and EXIF orientation applied.
     */
    fun decodeSampled(
        file: File,
        maxSidePx: Int = DEFAULT_MAX_SIDE_PX
    ): Bitmap? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "File does not exist or is empty: ${file.absolutePath}")
            return null
        }

        return try {
            val rotationDegrees = getExifRotation(file)

            // Step 1: Decode bounds only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.w(TAG, "Could not determine image bounds for: ${file.absolutePath}")
                return null
            }

            // Step 2: Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxSidePx)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            // Step 3: Decode sampled bitmap
            val decoded = FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            // Step 4: Rotate and scale down if still exceeding maxSidePx
            scaleAndRotateBitmap(decoded, maxSidePx, rotationDegrees)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decodeSampled from file ${file.absolutePath}: ${t.message}", t)
            null
        }
    }

    /**
     * Decodes an image from raw byte array with sampling.
     */
    fun decodeSampled(
        bytes: ByteArray,
        maxSidePx: Int = DEFAULT_MAX_SIDE_PX,
        rotationOverride: Int? = null
    ): Bitmap? {
        if (bytes.isEmpty()) return null

        return try {
            val rotationDegrees = rotationOverride ?: getExifRotation(ByteArrayInputStream(bytes))

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxSidePx)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            scaleAndRotateBitmap(decoded, maxSidePx, rotationDegrees)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decodeSampled from byte array: ${t.message}", t)
            null
        }
    }

    /**
     * Decodes an image from a ContentResolver Uri with sampling.
     */
    fun decodeSampled(
        context: Context,
        uri: Uri,
        maxSidePx: Int = DEFAULT_MAX_SIDE_PX
    ): Bitmap? {
        return try {
            val rotationDegrees = context.contentResolver.openInputStream(uri)?.use {
                getExifRotation(it)
            } ?: 0

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxSidePx)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            scaleAndRotateBitmap(decoded, maxSidePx, rotationDegrees)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decodeSampled from Uri $uri: ${t.message}", t)
            null
        }
    }

    /**
     * Safely converts an ImageProxy to a sampled Bitmap and closes the proxy.
     */
    fun decodeSampled(
        imageProxy: ImageProxy,
        maxSidePx: Int = DEFAULT_MAX_SIDE_PX
    ): Bitmap? {
        return try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (imageProxy.format == android.graphics.ImageFormat.JPEG) {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                decodeSampled(bytes, maxSidePx, rotationOverride = rotation)
            } else {
                val raw = imageProxy.toBitmap()
                scaleAndRotateBitmap(raw, maxSidePx, rotation)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decodeSampled from ImageProxy: ${t.message}", t)
            null
        }
    }

    /**
     * Calculates the largest inSampleSize value that is a power of 2 and keeps both
     * width and height within reasonable bounds.
     */
    fun calculateInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        var inSampleSize = 1
        val maxDim = maxOf(width, height)
        if (maxDim > maxSidePx) {
            while ((maxDim / inSampleSize) > maxSidePx) {
                inSampleSize *= 2
            }
        }
        return maxOf(1, inSampleSize)
    }

    /**
     * Applies rotation and scales down the bitmap if dimensions still exceed maxSidePx.
     * Recycles intermediate bitmaps to conserve native memory.
     */
    fun scaleAndRotateBitmap(
        src: Bitmap,
        maxSidePx: Int,
        rotationDegrees: Int
    ): Bitmap {
        var current = src

        // 1. Apply rotation if necessary
        if (rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (rotated != current) {
                current.recycle()
                current = rotated
            }
        }

        // 2. Downscale if max dimension exceeds maxSidePx
        val maxDim = maxOf(current.width, current.height)
        if (maxDim > maxSidePx) {
            val scale = maxSidePx.toFloat() / maxDim
            val targetW = maxOf(1, (current.width * scale).roundToInt())
            val targetH = maxOf(1, (current.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(current, targetW, targetH, true)
            if (scaled != current) {
                current.recycle()
                current = scaled
            }
        }

        return current
    }

    private fun getExifRotation(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            exifToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } catch (_: Throwable) {
            0
        }
    }

    private fun getExifRotation(inputStream: InputStream): Int {
        return try {
            val exif = ExifInterface(inputStream)
            exifToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } catch (_: Throwable) {
            0
        }
    }

    private fun exifToDegrees(orientation: Int): Int {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}
