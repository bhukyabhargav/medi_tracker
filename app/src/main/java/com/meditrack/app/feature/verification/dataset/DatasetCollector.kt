package com.meditrack.app.feature.verification.dataset

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meditrack.app.BuildConfig
import com.meditrack.app.MediTrackApp
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DatasetSample(
    val id: String,
    val timestamp: Long,
    val stripType: String,
    val fullCells: Int,
    val emptyCells: Int
)

object DatasetCollector {

    var isDataCollectionEnabled: Boolean = false

    fun saveSample(
        context: Context,
        bitmap: Bitmap,
        stripType: String,
        fullCells: Int,
        emptyCells: Int
    ): Boolean {
        if (!BuildConfig.DEBUG) return false

        return try {
            val baseDir = File(context.filesDir, "dataset")
            val imagesDir = File(baseDir, "images").apply { mkdirs() }
            val labelsDir = File(baseDir, "labels").apply { mkdirs() }

            val sampleId = "sample_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val imageFile = File(imagesDir, "$sampleId.jpg")
            val labelFile = File(labelsDir, "$sampleId.json")

            FileOutputStream(imageFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }

            val jsonContent = """
                {
                  "id": "$sampleId",
                  "timestamp": ${System.currentTimeMillis()},
                  "stripType": "$stripType",
                  "fullCells": $fullCells,
                  "emptyCells": $emptyCells
                }
            """.trimIndent()
            labelFile.writeText(jsonContent)

            Log.d(MediTrackApp.TAG, "Saved dataset sample: $sampleId (full: $fullCells, empty: $emptyCells)")
            true
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "Failed to save dataset sample: ${e.message}", e)
            false
        }
    }

    fun getSampleCount(context: Context): Int {
        val imagesDir = File(context.filesDir, "dataset/images")
        return imagesDir.listFiles()?.size ?: 0
    }

    fun exportDatasetZip(context: Context): File? {
        if (!BuildConfig.DEBUG) return null

        val datasetDir = File(context.filesDir, "dataset")
        if (!datasetDir.exists() || datasetDir.listFiles().isNullOrEmpty()) {
            return null
        }

        val zipFile = File(context.filesDir, "dataset_export_${System.currentTimeMillis()}.zip")
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                datasetDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relativePath = file.relativeTo(datasetDir).path
                    val zipEntry = ZipEntry(relativePath)
                    zos.putNextEntry(zipEntry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
            Log.d(MediTrackApp.TAG, "Exported dataset zip: ${zipFile.absolutePath} (${zipFile.length() / 1024} KB)")
            zipFile
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "Failed to zip dataset: ${e.message}", e)
            null
        }
    }
}
