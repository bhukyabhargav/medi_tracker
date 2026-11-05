package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TfliteStripDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : StripDetector {

    private var interpreter: Interpreter? = null

    val isModelAvailable: Boolean
        get() = interpreter != null

    init {
        try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd("strip_detector.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            interpreter = Interpreter(modelBuffer)
        } catch (_: Exception) {
            // As specified in Stage 6 Task 2: interpreter remains null until model added in Stage 13
            interpreter = null
        }
    }

    override suspend fun detect(bitmap: Bitmap): Detection {
        val activeInterpreter = interpreter
            ?: throw IllegalStateException(
                "Model assets/strip_detector.tflite not installed. Real model arrives in Stage 13."
            )

        // Skeleton inference logic for Stage 13
        return Detection(cells = emptyList(), stripFullyInFrame = false)
    }
}
