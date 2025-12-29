package com.example.tutorial

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector


class YoloDetector(
    private val context: Context,
    private val modelPath: String = "yolov11n.tflite",
    private val labelPath: String = "labels.txt"
) {
    // Move the detector to a class property
    private var detector: ObjectDetector? = null

    init {
        setupDetector()
    }

    private fun setupDetector() {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()

        try {
            detector = ObjectDetector.createFromFileAndOptions(
                context,
                modelPath,
                options
            )
        } catch (e: Exception) {
            Log.e("YoloDetector", "TFLite failed to load model", e)
        }
    }

    fun runObjectDetection(bitmap: Bitmap) {
        val image = TensorImage.fromBitmap(bitmap)

        // Use the already initialized detector
        val results = detector?.detect(image)

        if (results != null) {
            debugPrint(results)
        }
    }

    private fun debugPrint(results : List<Detection>) {
        for ((i, obj) in results.withIndex()) {
            val box = obj.boundingBox
            val TAG = "YoloDetector"
            Log.d(TAG, "Detected object: ${i} ")
            Log.d(TAG, "  boundingBox: (${box.left}, ${box.top}) - (${box.right},${box.bottom})")

            for ((j, category) in obj.categories.withIndex()) {
                Log.d(TAG, "    Label $j: ${category.label}")
                val confidence: Int = category.score.times(100).toInt()
                Log.d(TAG, "    Confidence: ${confidence}%")
            }
        }
    }


}

