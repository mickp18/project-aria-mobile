package com.example.projectariamobile

import android.graphics.Bitmap
import android.graphics.RectF

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




