package com.example.tutorial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await


/** Processor for the text detector. */
class TextRecognitionProcessor(private val context: Context){

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
    * Crop bitmap based on bounding box and run OCR
    * @param bitmap Original image
    * @param boundingBox Rectangle defining the crop area
    * @return Recognized text or null if failed
    */
    suspend fun recognizeTextInBoundingBox(
        bitmap: Bitmap,
        boundingBox: Rect,
        detectionClass: String? = null
    ): String? {
        return try {
            // Validate bounding box is within bitmap bounds
            val validBox = validateAndClampBoundingBox(bitmap, boundingBox)

            // Crop the bitmap to the bounding box
            val croppedBitmap = cropBitmap(bitmap, validBox)
            val className = detectionClass ?: "unknown"
            val saved = saveBitmapToGallery(
                context,
                croppedBitmap,
                fileName = "CROP_${className}_${System.currentTimeMillis()}.jpg",
                folderName = "OCR_Test_Crops"
            )
            if (saved) {
                Log.i("TextRecognizer", "Cropped image saved for class: $className")
            } else {
                Log.e("TextRecognizer", "Failed to save cropped image")
            }

            // Run OCR on cropped region
            val text = null
            // val text = recognizeText(croppedBitmap)

            // Clean up
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle()
            }
            text
        } catch (e: Exception) {
            Log.e("TextRecognizer", "Error recognizing text: ${e.message}", e)
            null
        }
    }
    fun getImagefromBitmap(bitmap : Bitmap) : InputImage{
        return InputImage.fromBitmap(bitmap, 0)
    }

    suspend fun getTextfromImage(image : InputImage) : Text  {
        val result = textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Task completed successfully
                // ...

            }
            .addOnFailureListener { e ->
                // Task failed with an exception
                // ...
            }
        return result.result
    }

//    fun getText(bitmap : Bitmap){
//        val image = getImagefromBitmap(bitmap)
//        val result = getTextfromImage(image)
//        val resultText = result.text
//
//
//
//    }

    /**
     * Crop bitmap to bounding box
     */
    private fun cropBitmap(bitmap: Bitmap, boundingBox: Rect): Bitmap {
        return try {
            Bitmap.createBitmap(
                bitmap,
                boundingBox.left,
                boundingBox.top,
                boundingBox.width(),
                boundingBox.height()
            )
        } catch (e: Exception) {
            Log.e("TextRecognizer", "Crop failed, using original: ${e.message}")
            bitmap
        }
    }
    /**
     * Ensure bounding box is within bitmap bounds
     */
    private fun validateAndClampBoundingBox(bitmap: Bitmap, box: Rect): Rect {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)

        return Rect(left, top, right, bottom)
    }

    /**
     * Clean up resources
     */
    fun stop() {
        try {
            textRecognizer.close()
            Log.d("TextRecognizer", "TextRecognizer closed")
        } catch (e: Exception) {
            Log.e("TextRecognizer", "Error closing recognizer: ${e.message}")
        }
    }


}
