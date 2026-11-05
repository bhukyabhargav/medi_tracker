package com.meditrack.app.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object ImageDecoder {
    fun decodeSampledBitmap(photoPath: String, reqWidth: Int = 1024, reqHeight: Int = 1024): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(photoPath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeFile(photoPath, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }
}
