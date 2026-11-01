package com.meditrack.app.feature.verification.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.meditrack.app.MediTrackApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ProofStorage {

    private const val MAX_FILE_SIZE_BYTES = 1_572_864L // 1.5 MB

    /**
     * Saves a captured Bitmap into app-private filesDir/proofs/
     * - Compresses to JPEG <= ~1.5 MB
     * - Ensures EXIF GPS metadata is stripped
     * Returns the absolute path of the saved file.
     */
    fun saveProof(context: Context, bitmap: Bitmap): String {
        val proofsDir = File(context.filesDir, "proofs")
        if (!proofsDir.exists()) {
            proofsDir.mkdirs()
        }

        val filename = "proof_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        val outputFile = File(proofsDir, filename)

        // Compress to <= 1.5 MB
        var quality = 90
        var stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)

        while (stream.size() > MAX_FILE_SIZE_BYTES && quality > 40) {
            stream.reset()
            quality -= 15
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }

        FileOutputStream(outputFile).use { fos ->
            fos.write(stream.toByteArray())
            fos.flush()
        }

        // Ensure no EXIF location tags exist
        try {
            val exif = ExifInterface(outputFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(MediTrackApp.TAG, "Failed to sanitize EXIF location: ${e.message}")
        }

        Log.d(MediTrackApp.TAG, "Saved verification proof: ${outputFile.absolutePath} (${outputFile.length() / 1024} KB)")
        return outputFile.absolutePath
    }
}
