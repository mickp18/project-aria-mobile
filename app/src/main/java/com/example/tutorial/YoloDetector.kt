package com.example.tutorial

// from https://gemini.google.com/app/fa36e49d354295d1?hl=it
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock

import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.image.ImageProcessor


class YoloDetector(
    private val context: Context,
    private val modelPath: String = "best_float32.tflite",
    private val labelPath: String = "labels_gigi",
    private var threshold: Float = 0.5f,
    private var numThreads: Int = 2,
    private var maxResults: Int = 3,
    private var currentDelegate: Int = 0,
    // val objectDetectorListener: DetectorListener?

) {
    // Move the detector to a class property
    private var detector: ObjectDetector? = null

    // Delegate constants
    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DELEGATE_NNAPI = 2
    }

    init {
        setupDetector()
    }

    fun clearObjectDetector() {
        detector = null
    }

    private fun setupDetector() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setNumThreads(numThreads) // Enable hardware acceleration

        // Implement the hardware check logic
        when (currentDelegate) {
            DELEGATE_CPU -> {
                // Default CPU - no extra options needed
            }
            DELEGATE_GPU -> {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    baseOptionsBuilder.useGpu()
                } else {
                    Log.w("YoloDetector", "GPU not supported, falling back to CPU")
                    // No need to call error listener, just proceed to create with CPU
                }
            }
            DELEGATE_NNAPI -> {
                baseOptionsBuilder.useNnapi()
            }
        }

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setMaxResults(maxResults)
            .setScoreThreshold(threshold)
            .build()

        try {
            detector = ObjectDetector.createFromFileAndOptions(context, modelPath, options)
        } catch (e: Exception) {
            Log.e("YoloDetector", "TFLite failed to load model", e)
        }
    }

    fun runObjectDetection(bitmap: Bitmap, imageRotation : Int) {
        if (detector == null) {
            setupDetector()
        }

        var inferenceTime = SystemClock.uptimeMillis()

        // Create preprocessor for the image.
        val imageProcessor =
            ImageProcessor.Builder()
                .add(Rot90Op(-imageRotation / 90))
                .build()

        val image = TensorImage.fromBitmap(bitmap)

        val results = detector?.detect(image)
        // inferenceTime = SystemClock.uptimeMillis() - inferenceTime
//        objectDetectorListener?.onResults(
//            results,
//            inferenceTime,
//            image.height,
//            image.width)

        if (results != null) {
            debugPrint(results)
        }
    }

//    interface DetectorListener {
//        fun onError(error: String)
//        fun onResults(
//            results: MutableList<Detection>?,
//            inferenceTime: Long,
//            imageHeight: Int,
//            imageWidth: Int
//        )
//    }
    private fun debugPrint(results: List<Detection>) {
        for ((i, obj) in results.withIndex()) {
            val label = obj.categories.firstOrNull()?.label ?: "Unknown"
            val confidence = (obj.categories.firstOrNull()?.score?.times(100))?.toInt() ?: 0
            Log.d("YoloDetector", "Detected #$i: $label ($confidence%)")
        }
    }


}

