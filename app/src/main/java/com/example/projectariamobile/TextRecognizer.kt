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
            croppedBitmap = cropBitmap(originalBitmap, validCropRect)

            // scale bitmap if too small
            scaledBitmap = if (croppedBitmap.height < 100) {
                Log.d("TextRecognizer", "Upscaling image (Height: ${croppedBitmap.height})")
                scaleBitmap(croppedBitmap, 2.0f)
            } else {
                // No scaling needed, just use the cropped one
                croppedBitmap
            }
//            binarizedBitmap = binarizeBitmap(scaledBitmap)

            // Run OCR directly on the crop
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

    // --- Helper Utils ---

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
            Log.e("TextRecognizer", "Crop failed: ${e.message}")
            bitmap
        }
    }

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
    /**
     * Scales the bitmap by a factor (e.g., 2.0 = 200% size).
     * Uses bilinear filtering which is "good enough" for OCR upscaling.
     */
    private fun scaleBitmap(bitmap: Bitmap, factor: Float): Bitmap {
        val width = (bitmap.width * factor).toInt()
        val height = (bitmap.height * factor).toInt()
        // filter = true enables bilinear filtering for smoother edges
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
    /**
     * Converts a bitmap to a high-contrast Black and White image.
     * This removes colored noise and shadows.
     */
    private fun binarizeBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        // Create a bitmap to draw on
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()

        // Create a ColorMatrix that converts to Grayscale AND increases Contrast
        // The standard grayscale matrix:
        // [ 0.33  0.59  0.11  0  0 ]
        // To make it "Binarized" (Threshold), we multiply these by a large factor (contrast)
        // and subtract a large offset (brightness) to push grays to 0 or 255.
        // Formula: Pixel = (Color * Contrast) + Offset
        val contrast = 2.0f // Scale factor > 1 increases contrast
        val offset = -100.0f // Shift darks to black

        val colorMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,  // Red
            0f, contrast, 0f, 0f, offset,  // Green
            0f, 0f, contrast, 0f, offset,  // Blue
            0f, 0f, 0f, 1f, 0f             // Alpha
        ))

        // Apply the filter (Grayscale is implicit if we use R=G=B inputs,
        // but explicit Grayscale + High Contrast usually works best)
        val grayscaleMatrix = ColorMatrix()
        grayscaleMatrix.setSaturation(0f) // First turn to gray

        // Combine: Gray -> Contrast
        grayscaleMatrix.postConcat(colorMatrix)

        paint.colorFilter = ColorMatrixColorFilter(grayscaleMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return dest
    }
    fun stop() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e("TextRecognizer", "Error closing: ${e.message}")
        }
    }
}