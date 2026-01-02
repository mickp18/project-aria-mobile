package com.example.tutorial

import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage

class Category (
    val label: String,
    val confidence: Float
)

class ObjectDetection(
    val boundingBox: RectF,
    val category: Category
)

class DetectionResult(
    val image: Bitmap,
    val detections: List<ObjectDetection>,
    var info: Any?=null
)

interface ObjectDetector {
    fun detect(image: Bitmap, imageRotation: Int): DetectionResult
}




