package com.example.tutorial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.ultralytics.yolo.models.LocalYoloModel
import com.ultralytics.yolo.predict.detect.DetectedObject
import com.ultralytics.yolo.predict.detect.TfliteDetector

// Simple data classes for results
data class Category(val label: String, val confidence: Float)
data class ObjectDetection(val boundingBox: RectF, val category: Category)
data class DetectionResult(val detections: List<ObjectDetection>, val inferenceTime: Long)

class YoloDetector(
    val context: Context,
    val modelFilename: String = "best_int8.tflite",
    val metadataFilename: String = "metadata.yaml",
    val confidenceThreshold: Float = 0.5f,
    val iouThreshold: Float = 0.3f
) {

    private var yolo: TfliteDetector? = null

    init {
        try {
            yolo = TfliteDetector(context)
            yolo?.setIouThreshold(iouThreshold)
            yolo?.setConfidenceThreshold(confidenceThreshold)

            val config = LocalYoloModel(
                "detect",
                "tflite",
                modelFilename,
                metadataFilename
            )

            // Try to use GPU (true), fallback to CPU if needed
            yolo?.loadModel(config, true)
        } catch (e: Exception) {
            e.printStackTrace()
            yolo = null
        }
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        if (yolo == null) return DetectionResult(emptyList(), 0)

        val startTime = System.currentTimeMillis()

        // The preprocess step is handled inside TfliteDetector via the 'preprocess' call
        // or implicitly if you used the original flow, but here we call predict directly
        // which expects a bitmap. TfliteDetector.predict handles the resizing/pre-proc.

        val results: ArrayList<DetectedObject> = yolo!!.predict(bitmap)

        val detections = ArrayList<ObjectDetection>()

        // The result coordinates are normalized (0..1) or relative to input size depending on model.
        // TfliteDetector usually returns boxes relative to the INPUT_SIZE (e.g., 320x320).
        // We need to map them back to the original bitmap size.

        // Note: The original repo handles rotation/aspect ratio in a complex way in the UI layer.
        // Since we are passing the bitmap directly, we map based on the bitmap dims.
        val imgW = bitmap.width
        val imgH = bitmap.height

        // The internal YOLO logic normally returns normalized coords (0-1) OR input-sized coords.
        // Looking at PostProcessUtils in the repo, it returns 0-1 normalized coords.

        for (result in results) {
            val category = Category(
                result.label ?: "Unknown",
                result.confidence
            )

            val box = result.boundingBox // These are 0..1 normalized
            val rect = RectF(
                box.left * imgW,
                box.top * imgH,
                box.right * imgW,
                box.bottom * imgH
            )

            detections.add(ObjectDetection(rect, category))
        }

        val time = System.currentTimeMillis() - startTime
        return DetectionResult(detections, time)
    }

    fun close() {
        // Add cleanup if TfliteDetector exposes a close method (Interpreter)
        // usage: yolo?.close()
    }
}