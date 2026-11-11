package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.meditrack.app.core.config.VerificationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Typed exception thrown when Roboflow workflow execution fails.
 */
class RoboflowInferenceException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val isRetryable: Boolean = false
) : Exception(message, cause)

/**
 * Cloud-based StripDetector powered by Roboflow Serverless Workflows.
 *
 * Integrates the "Android Blister Presence and Empty Count" workflow:
 * - Workspace: varshiths-workspace-y38an
 * - Workflow ID: android-blister-presence-and-empty-count-1794338462016
 * - Endpoint: https://serverless.roboflow.com/varshiths-workspace-y38an/workflows/android-blister-presence-and-empty-count-1794338462016
 *
 * Implements:
 * - Authorization: Bearer <key> header authentication (inference v1.5.0+)
 * - Exponential backoff retry with timeout
 * - Defensive JSON parsing supporting multiple output key variants
 * - Disk writing for image/visualization blobs (prevents memory leaks/excessive logging)
 * - Seamless fallback to on-device [TfliteStripDetector] if offline or when quota/API fails
 */
@Singleton
class RoboflowWorkflowStripDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fallbackDetector: TfliteStripDetector
) : StripDetector {

    companion object {
        private const val TAG = "RoboflowWorkflow"
        private const val MAX_IMAGE_DIMENSION = 640
        private const val JPEG_QUALITY = 80
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 25000
        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 500L
    }

    override suspend fun detect(bitmap: Bitmap): Detection = withContext(Dispatchers.IO) {
        if (!VerificationConfig.isRoboflowWorkflowEnabled) {
            return@withContext fallbackDetector.detect(bitmap)
        }

        try {
            val responseJson = runWorkflowInference(bitmap)
            parseWorkflowResponse(responseJson)
        } catch (e: Throwable) {
            Log.w(TAG, "Roboflow inference failed: ${e.message}. Cloud-only verification reporting rejected.", e)
            Detection(
                cells = emptyList(),
                stripFullyInFrame = false,
                stripConfidence = 0.0f,
                isRoboflowWorkflow = true,
                roboflowPackStatus = "rejected",
                roboflowMissingCount = null
            )
        }
    }

    /**
     * Executes Roboflow inference with request timeouts and exponential backoff retry.
     */
    suspend fun runWorkflowInference(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val endpoint = VerificationConfig.roboflowWorkflowEndpoint
        val apiKey = System.getenv("ROBOFLOW_API_KEY") ?: VerificationConfig.roboflowApiKey

        if (endpoint.isBlank() || apiKey.isBlank()) {
            throw RoboflowInferenceException("Roboflow endpoint or API key not configured", isRetryable = false)
        }

        val base64Image = prepareBase64Image(bitmap)
        val payload = JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("image", JSONObject().apply {
                    put("type", "base64")
                    put("value", base64Image)
                })
            })
        }

        var lastException: RoboflowInferenceException? = null

        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val backoff = INITIAL_BACKOFF_MS * (1L shl (attempt - 1))
                Log.d(TAG, "Retrying Roboflow inference (attempt $attempt/$MAX_RETRIES) after ${backoff}ms...")
                delay(backoff)
            }

            try {
                return@withContext executeHttpRequest(endpoint, apiKey, payload.toString())
            } catch (e: RoboflowInferenceException) {
                lastException = e
                if (!e.isRetryable || attempt == MAX_RETRIES) {
                    throw e
                }
            }
        }

        throw lastException ?: RoboflowInferenceException("Workflow inference failed after $MAX_RETRIES retries", isRetryable = false)
    }

    private fun executeHttpRequest(endpointUrl: String, apiKey: String, jsonPayload: String): String {
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            // Strict Bearer header authentication (never in URL or body)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                }
            }

            val errorText = try {
                connection.errorStream?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                } ?: "HTTP $responseCode"
            } catch (_: Throwable) {
                "HTTP $responseCode"
            }

            val isRetryable = responseCode >= 500 || responseCode == 429
            throw RoboflowInferenceException(
                message = "Roboflow returned HTTP $responseCode: $errorText",
                statusCode = responseCode,
                isRetryable = isRetryable
            )
        } catch (e: SocketTimeoutException) {
            throw RoboflowInferenceException("Connection timed out communicating with Roboflow", cause = e, isRetryable = true)
        } catch (e: IOException) {
            throw RoboflowInferenceException("I/O error communicating with Roboflow: ${e.message}", cause = e, isRetryable = true)
        } finally {
            connection.disconnect()
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

    /**
     * Defensively parses workflow outputs into standard [Detection] model.
     * Supports various naming conventions from "Android Blister Presence and Empty Count" workflow.
     */
    fun parseWorkflowResponse(jsonString: String): Detection {
        val root = extractRootOutput(jsonString)

        // 1. Offload any heavy image/visualization outputs to disk immediately
        persistImageOutputsIfPresent(root)

        // 2. Determine presence / pack acceptance
        val isPackAccepted = parseBlisterPresence(root)
        val packStatus = if (isPackAccepted) "accepted" else "rejected"

        // 3. Extract missing / empty tablet count
        val missingCount = parseMissingCount(root)

        // 4. Extract blister pack bounding box
        val stripBox = parseStripBox(root)

        // 5. Extract empty cavity detections
        val cells = parseEmptyCells(root)

        // 6. Geometric frame containment check
        val fullyInFrame = if (!isPackAccepted) {
            false
        } else if (stripBox != null) {
            val tol = VerificationConfig.borderToleranceFraction
            stripBox.left > tol && stripBox.top > tol && stripBox.right < (1.0f - tol) && stripBox.bottom < (1.0f - tol)
        } else {
            isPackAccepted
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

    private fun extractRootOutput(jsonString: String): JSONObject {
        val trimmed = jsonString.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            return if (array.length() > 0) array.getJSONObject(0) else JSONObject()
        }
        val obj = JSONObject(trimmed)
        if (obj.has("outputs")) {
            val outputs = obj.optJSONArray("outputs")
            if (outputs != null && outputs.length() > 0) {
                return outputs.getJSONObject(0)
            }
            val outputObj = obj.optJSONObject("outputs")
            if (outputObj != null) return outputObj
        }
        return obj
    }

    private fun parseBlisterPresence(root: JSONObject): Boolean {
        // Boolean indicators (e.g. blister_present from android-blister-presence-and-empty-count workflow)
        val boolKeys = listOf("blister_present", "is_blister_present", "presence", "blister_detected")
        for (k in boolKeys) {
            if (root.has(k)) {
                return root.optBoolean(k, false)
            }
        }

        // Direct status strings
        val status = root.optString("pack_status", "").ifBlank {
            root.optString("status", "")
        }
        if (status.isNotBlank()) {
            return status.equals("accepted", ignoreCase = true) || status.equals("present", ignoreCase = true)
        }

        // Check predictions for blister pack
        val packObj = root.optJSONObject("pack_predictions")
            ?: root.optJSONObject("blister_presence")
            ?: root.optJSONObject("blister_pack")
        if (packObj != null) {
            val preds = packObj.optJSONArray("predictions")
            if (preds != null) {
                return preds.length() > 0
            }
        }

        return false
    }

    private fun parseMissingCount(root: JSONObject): Int? {
        val keys = listOf("empty_blister_count", "missing_count", "empty_count", "count", "missing_tablets", "empty_cavities_count")
        for (key in keys) {
            if (root.has(key)) {
                val raw = root.opt(key)
                when (raw) {
                    is Number -> return raw.toInt()
                    is String -> raw.toIntOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseStripBox(root: JSONObject): RectF? {
        val packPredObj = root.optJSONObject("pack_predictions")
            ?: root.optJSONObject("blister_pack")
            ?: root.optJSONObject("strip_predictions")
            ?: return null

        val imgObj = packPredObj.optJSONObject("image")
        val imgW = imgObj?.optDouble("width", 1.0)?.toFloat() ?: 1.0f
        val imgH = imgObj?.optDouble("height", 1.0)?.toFloat() ?: 1.0f

        val preds = packPredObj.optJSONArray("predictions") ?: return null
        if (preds.length() == 0) return null

        val firstPack = preds.getJSONObject(0)
        val px = firstPack.optDouble("x", 0.0).toFloat()
        val py = firstPack.optDouble("y", 0.0).toFloat()
        val pw = firstPack.optDouble("width", 0.0).toFloat()
        val ph = firstPack.optDouble("height", 0.0).toFloat()

        val normXc = px / imgW
        val normYc = py / imgH
        val normW = pw / imgW
        val normH = ph / imgH

        return RectF(
            max(0f, normXc - normW / 2f),
            max(0f, normYc - normH / 2f),
            min(1f, normXc + normW / 2f),
            min(1f, normYc + normH / 2f)
        )
    }

    private fun parseEmptyCells(root: JSONObject): List<Cell> {
        val cells = mutableListOf<Cell>()
        val emptyPredObj = root.optJSONObject("empty_cavity_predictions")
            ?: root.optJSONObject("empty_cavities")
            ?: root.optJSONObject("predictions")
            ?: return cells

        val imgObj = emptyPredObj.optJSONObject("image")
        val imgW = imgObj?.optDouble("width", 1.0)?.toFloat() ?: 1.0f
        val imgH = imgObj?.optDouble("height", 1.0)?.toFloat() ?: 1.0f

        val preds = emptyPredObj.optJSONArray("predictions") ?: return cells
        for (i in 0 until preds.length()) {
            val pred = preds.getJSONObject(i)
            // Discard heavy polygon "points" to keep memory small
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
        return cells
    }

    /**
     * Decodes and writes any base64 image-shaped outputs to disk, preventing
     * memory accumulation and log pollution.
     */
    private fun persistImageOutputsIfPresent(root: JSONObject) {
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.contains("image", ignoreCase = true) || key.contains("visualization", ignoreCase = true)) {
                val value = root.opt(key)
                val base64Str = when (value) {
                    is String -> value
                    is JSONObject -> value.optString("value", "")
                    else -> ""
                }
                if (base64Str.startsWith("/9j/") || base64Str.startsWith("iVBORw0") || base64Str.startsWith("data:image")) {
                    try {
                        val cleanBase64 = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
                        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        val outputFile = File(context.cacheDir, "roboflow_${key}_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(outputFile).use { fos ->
                            fos.write(bytes)
                            fos.flush()
                        }
                        Log.d(TAG, "Cached workflow image output ($key) to disk: ${outputFile.name}")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to persist image output $key to disk", e)
                    }
                }
            }
        }
    }
}
