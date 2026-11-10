package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.meditrack.app.core.config.VerificationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Cloud-based StripDetector powered by Roboflow Serverless Workflows.
 *
 * Integrates with the "Blister Pack Missing Tablet Counter" workflow:
 * - Detects blister packs using foundation vision models.
 * - Detects and counts empty pill cavities.
 * - Provides verified missing tablet counts directly.
 * - Gracefully falls back to local [TfliteStripDetector] if offline or when API limits are reached.
 */
@Singleton
class RoboflowWorkflowStripDetector @Inject constructor(
    private val fallbackDetector: TfliteStripDetector
) : StripDetector {

    companion object {
        private const val TAG = "RoboflowWorkflow"
        private const val MAX_IMAGE_DIMENSION = 640
        private const val JPEG_QUALITY = 80
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 25000
    }

    override suspend fun detect(bitmap: Bitmap): Detection = withContext(Dispatchers.IO) {
        if (!VerificationConfig.isRoboflowWorkflowEnabled) {
            return@withContext fallbackDetector.detect(bitmap)
        }

        try {
            val endpoint = VerificationConfig.roboflowWorkflowEndpoint
            val apiKey = VerificationConfig.roboflowApiKey

            if (endpoint.isBlank() || apiKey.isBlank()) {
                Log.w(TAG, "Roboflow endpoint or API key not configured; falling back to TFLite")
                return@withContext fallbackDetector.detect(bitmap)
            }

            // Encode scaled bitmap to Base64 JPEG
            val base64Image = prepareBase64Image(bitmap)

            val requestUrl = if (endpoint.contains("?")) {
                "$endpoint&api_key=$apiKey"
            } else {
                "$endpoint?api_key=$apiKey"
            }

            val payload = JSONObject().apply {
                put("api_key", apiKey)
                put("inputs", JSONObject().apply {
                    put("image", JSONObject().apply {
                        put("type", "base64")
                        put("value", base64Image)
                    })
                })
            }

            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            connection.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                }
                parseWorkflowResponse(responseText)
            } else {
                val errorText = try {
                    connection.errorStream?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    } ?: "HTTP $responseCode"
                } catch (_: Throwable) {
                    "HTTP $responseCode"
                }
                Log.w(TAG, "Roboflow workflow returned HTTP $responseCode: $errorText. Falling back to local detector.")
                fallbackDetector.detect(bitmap)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Roboflow inference failed: ${e.message}. Falling back to local detector.", e)
            fallbackDetector.detect(bitmap)
        }
    }

    private fun prepareBase64Image(source: Bitmap): String {
        val width = source.width
        val height = source.height
        val maxDim = max(width, height)
        val scaled = if (maxDim > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxDim
            Bitmap.createScaledBitmap(source, (width * scale).toInt(), (height * scale).toInt(), true)
        } else {
            source
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        if (scaled !== source && !scaled.isRecycled) {
            scaled.recycle()
        }
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun parseWorkflowResponse(jsonString: String): Detection {
        val root = if (jsonString.trim().startsWith("[")) {
            val array = JSONArray(jsonString)
            if (array.length() > 0) array.getJSONObject(0) else JSONObject()
        } else {
            val obj = JSONObject(jsonString)
            if (obj.has("outputs")) {
                val outputs = obj.getJSONArray("outputs")
                if (outputs.length() > 0) outputs.getJSONObject(0) else obj
            } else {
                obj
            }
        }

        val packStatus = root.optString("pack_status", "accepted")
        val isPackAccepted = packStatus.equals("accepted", ignoreCase = true)

        val missingCountRaw = root.opt("missing_count")
        val missingCount = when (missingCountRaw) {
            is Number -> missingCountRaw.toInt()
            is String -> missingCountRaw.toIntOrNull()
            else -> null
        }

        // Parse blister pack bounding box
        var stripBox: RectF? = null
        if (root.has("pack_predictions")) {
            val packPredObj = root.optJSONObject("pack_predictions")
            if (packPredObj != null) {
                val imgObj = packPredObj.optJSONObject("image")
                val imgW = imgObj?.optDouble("width", 1.0)?.toFloat() ?: 1.0f
                val imgH = imgObj?.optDouble("height", 1.0)?.toFloat() ?: 1.0f

                val preds = packPredObj.optJSONArray("predictions")
                if (preds != null && preds.length() > 0) {
                    val firstPack = preds.getJSONObject(0)
                    val px = firstPack.optDouble("x", 0.0).toFloat()
                    val py = firstPack.optDouble("y", 0.0).toFloat()
                    val pw = firstPack.optDouble("width", 0.0).toFloat()
                    val ph = firstPack.optDouble("height", 0.0).toFloat()

                    val normXc = px / imgW
                    val normYc = py / imgH
                    val normW = pw / imgW
                    val normH = ph / imgH

                    stripBox = RectF(
                        max(0f, normXc - normW / 2f),
                        max(0f, normYc - normH / 2f),
                        min(1f, normXc + normW / 2f),
                        min(1f, normYc + normH / 2f)
                    )
                }
            }
        }

        // Parse empty cavities
        val cells = mutableListOf<Cell>()
        if (root.has("empty_cavity_predictions")) {
            val emptyPredObj = root.optJSONObject("empty_cavity_predictions")
            if (emptyPredObj != null) {
                val imgObj = emptyPredObj.optJSONObject("image")
                val imgW = imgObj?.optDouble("width", 1.0)?.toFloat() ?: 1.0f
                val imgH = imgObj?.optDouble("height", 1.0)?.toFloat() ?: 1.0f

                val preds = emptyPredObj.optJSONArray("predictions")
                if (preds != null) {
                    for (i in 0 until preds.length()) {
                        val pred = preds.getJSONObject(i)
                        val cx = pred.optDouble("x", 0.0).toFloat()
                        val cy = pred.optDouble("y", 0.0).toFloat()
                        val cw = pred.optDouble("width", 0.0).toFloat()
                        val ch = pred.optDouble("height", 0.0).toFloat()
                        val conf = pred.optDouble("confidence", 1.0).toFloat()

                        val normXc = cx / imgW
                        val normYc = cy / imgH
                        val normW = cw / imgW
                        val normH = ch / imgH

                        val box = RectF(
                            max(0f, normXc - normW / 2f),
                            max(0f, normYc - normH / 2f),
                            min(1f, normXc + normW / 2f),
                            min(1f, normYc + normH / 2f)
                        )
                        cells.add(Cell(box = box, state = CellState.EMPTY, confidence = conf))
                    }
                }
            }
        }

        // Check if strip is fully in frame geometrically
        val fullyInFrame = if (!isPackAccepted) {
            false
        } else if (stripBox != null) {
            val tol = VerificationConfig.borderToleranceFraction
            stripBox.left > tol && stripBox.top > tol && stripBox.right < (1.0f - tol) && stripBox.bottom < (1.0f - tol)
        } else {
            cells.isNotEmpty()
        }

        return Detection(
            cells = cells,
            stripFullyInFrame = fullyInFrame,
            stripConfidence = if (isPackAccepted) 1.0f else 0.0f,
            stripBox = stripBox,
            isRoboflowWorkflow = true,
            roboflowMissingCount = missingCount ?: cells.size,
            roboflowPackStatus = packStatus
        )
    }
}
