package com.example.projectariamobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.core.graphics.scale

class TextRecognitionProcessor(private val context: Context) {

    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Paint for the bounding box
    private val rectPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
    }

    /**
     * 1. Crops the bitmap.
     * 2. Runs OCR on the crop.
     * 3. Draws bounding boxes ON THE CROP.
     * 4. Saves the annotated CROP.
     */
    suspend fun recognizeTextInBoundingBox(
        originalBitmap: Bitmap,
        boundingBox: Rect,
        detectionClass: String? = null

    ): String? {
        return try {
            // Crop the original image
            var scaledBitmap: Bitmap? = null
            var binarizedBitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null


            val validCropRect = validateAndClampBoundingBox(originalBitmap, boundingBox)
            croppedBitmap = originalBitmap.crop( validCropRect)
            // store cropped bitmap
            saveBitmapToGallery(context, croppedBitmap, fileName = "CROP_${System.currentTimeMillis()}.jpg", folderName = "OCR_Test_Crops")
            // scale bitmap if too small
            val targetMinSize = 100
            val scaleNeeded = maxOf(
                if (croppedBitmap.width  < targetMinSize) targetMinSize.toFloat() / croppedBitmap.width  else 1f,
                if (croppedBitmap.height < targetMinSize) targetMinSize.toFloat() / croppedBitmap.height else 1f
            )
            scaledBitmap = if (scaleNeeded > 1f) croppedBitmap.scaleBitmap(scaleNeeded) else croppedBitmap
            // In recognizeTextInBoundingBox, after cropping:
            if (croppedBitmap.width < 10 || croppedBitmap.height < 10) {
                Log.w("TextRecognizer", "Crop too small to be a real sign: ${croppedBitmap.width}x${croppedBitmap.height}, skipping")
                return null
            }
            // binarizedBitmap = scaledBitmap.binarizeBitmap()

            // Run OCR directly on the crop
//            val mlKitTextResult = performOCR(binarizedBitmap)
            val mlKitTextResult = performOCR(croppedBitmap)

            if (mlKitTextResult == null || mlKitTextResult.text.isBlank()) {
                Log.d("TextRecognizer", "No text found in crop.")
                // Recycle if created
                if (croppedBitmap != originalBitmap) croppedBitmap.recycle()
                return null
            }

            // Draw results directly onto the cropped bitmap
            // No offset math needed because ML Kit coordinates match the crop exactly
            val annotatedCrop = drawDetectionResults(croppedBitmap, mlKitTextResult.textBlocks)

            // Save the ANNOTATED CROP
            val className = detectionClass ?: "unknown"
            // Ensure saveBitmapToGallery is defined in your project
            val saved = saveBitmapToGallery(
                context,
                annotatedCrop, // Saving the drawn-over crop
                fileName = "CROP_ANNOTATED_${className}_${System.currentTimeMillis()}.jpg",
                folderName = "OCR_Test_Crops"
            )

            if (saved) {
                Log.i("TextRecognizer", "Annotated crop saved: $className")
            }

            // Clean up
            if (croppedBitmap != originalBitmap) croppedBitmap?.recycle()
            if (scaledBitmap != croppedBitmap) scaledBitmap?.recycle()
            if (binarizedBitmap != scaledBitmap) binarizedBitmap?.recycle()

            mlKitTextResult.text

        } catch (e: Exception) {
            Log.e("TextRecognizer", "Error recognizing text: ${e.message}", e)
            null
        }
    }

    private suspend fun performOCR(bitmap: Bitmap): Text? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image).await()
        } catch (e: Exception) {
            Log.e("TextRecognizer", "OCR failed: ${e.message}", e)
            null
        }
    }

    /**
     * Draws bounding boxes onto the provided bitmap.
     */
    private fun drawDetectionResults(
        bitmap: Bitmap,
        textBlocks: List<Text.TextBlock>
    ): Bitmap {
        // Create a mutable copy so we can draw on it
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        for (block in textBlocks) {
            val box = block.boundingBox
            if (box != null) {
                // Direct drawing: The box coordinates from ML Kit match the bitmap exactly
                canvas.drawRect(box, rectPaint)
            }
        }
        return mutableBitmap
    }

    // --- Helper Utils --

    private fun validateAndClampBoundingBox(bitmap: Bitmap, box: Rect): Rect {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)

        if (left >= right || top >= bottom) {
            return Rect(0,0, bitmap.width, bitmap.height)
        }
        return Rect(left, top, right, bottom)
    }

    fun stop() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e("TextRecognizer", "Error closing: ${e.message}")
        }
    }
}