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

    private val rectPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
    }

    /**
     * Crops the bitmap, runs OCR, draws bounding boxes on the crop, and saves
     * annotated images.
     *
     * Returns a [Pair] of:
     *   - first  : the recognised text (null if nothing found or error)
     *   - second : average ML Kit element confidence across all recognised tokens
     *              (0.0–1.0), or -1f when no text was found
     *
     * All saved filenames are prefixed with [frameTag] (e.g. "F00042") so every
     * image can be matched to its corresponding report entry by searching the
     * report for that tag.
     *
     * @param frameTag  Zero-padded frame identifier from reportManager.frameTag()
     */
    suspend fun recognizeTextInBoundingBox(
        originalBitmap : Bitmap,
        boundingBox    : Rect,
        detectionClass : String? = null,
        sessionFolder  : String  = "OCR_Crops",
        frameTag       : String  = "F00000"   // ← NEW param; default safe for call-sites not yet updated
    ): Pair<String?, Float> {            // ← was String?; now (text, avgConfidence)
        return try {
            var scaledBitmap    : Bitmap? = null
            var binarizedBitmap : Bitmap? = null
            var croppedBitmap   : Bitmap? = null

            val validCropRect = validateAndClampBoundingBox(originalBitmap, boundingBox)
            croppedBitmap = originalBitmap.crop(validCropRect)

            // Save raw crop — filename carries frameTag for cross-reference
            saveBitmapToGallery(
                context, croppedBitmap,
                fileName   = "${frameTag}_OCR_raw_${detectionClass}_${System.currentTimeMillis()}.jpg",
                folderName = sessionFolder
            )

            if (croppedBitmap.width < 10 || croppedBitmap.height < 10) {
                Log.w("TextRecognizer", "Crop too small: ${croppedBitmap.width}x${croppedBitmap.height}, skipping")
                return Pair(null, -1f)
            }

            val targetMinSize = 100
            val scaleNeeded = maxOf(
                if (croppedBitmap.width  < targetMinSize) targetMinSize.toFloat() / croppedBitmap.width  else 1f,
                if (croppedBitmap.height < targetMinSize) targetMinSize.toFloat() / croppedBitmap.height else 1f
            )
            scaledBitmap = if (scaleNeeded > 1f) croppedBitmap.scaleBitmap(scaleNeeded) else croppedBitmap

            binarizedBitmap = scaledBitmap.binarizeBitmap()
            saveBitmapToGallery(
                context, binarizedBitmap,
                fileName   = "${frameTag}_OCR_binarized_${detectionClass}_${System.currentTimeMillis()}.jpg",
                folderName = sessionFolder
            )

            val mlKitTextResult = performOCR(croppedBitmap)

            if (mlKitTextResult == null || mlKitTextResult.text.isBlank()) {
                Log.d("TextRecognizer", "[$frameTag] No text found in crop.")
                if (croppedBitmap != originalBitmap) croppedBitmap.recycle()
                return Pair(null, -1f)
            }

            // ── Compute average element confidence ────────────────────────────
            // ML Kit exposes confidence at the Element (word/token) level.
            // We collect every element across all blocks and average them.
            val allElements = mlKitTextResult.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
            val avgConfidence = if (allElements.isNotEmpty())
                allElements.map { it.confidence }.average().toFloat()
            else -1f

            Log.d("TextRecognizer", "[$frameTag] OCR confidence: " +
                    if (avgConfidence >= 0f) String.format("%.2f", avgConfidence) else "n/a")

            val annotatedCrop = drawDetectionResults(croppedBitmap, mlKitTextResult.textBlocks)

            val className = detectionClass ?: "unknown"
            val saved = saveBitmapToGallery(
                context, annotatedCrop,
                fileName   = "${frameTag}_OCR_annotated_${className}_${System.currentTimeMillis()}.jpg",
                folderName = sessionFolder
            )
            if (saved) Log.i("TextRecognizer", "[$frameTag] Annotated crop saved: $className")

            if (croppedBitmap != originalBitmap) croppedBitmap?.recycle()
            if (scaledBitmap  != croppedBitmap)  scaledBitmap?.recycle()
            if (binarizedBitmap != scaledBitmap)  binarizedBitmap?.recycle()

            Pair(mlKitTextResult.text, avgConfidence)

        } catch (e: Exception) {
            Log.e("TextRecognizer", "Error recognizing text: ${e.message}", e)
            Pair(null, -1f)
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

    private fun drawDetectionResults(bitmap: Bitmap, textBlocks: List<Text.TextBlock>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        for (block in textBlocks) {
            block.boundingBox?.let { canvas.drawRect(it, rectPaint) }
        }
        return mutableBitmap
    }

    private fun validateAndClampBoundingBox(bitmap: Bitmap, box: Rect): Rect {
        val left   = box.left.coerceIn(0, bitmap.width - 1)
        val top    = box.top.coerceIn(0, bitmap.height - 1)
        val right  = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        if (left >= right || top >= bottom) return Rect(0, 0, bitmap.width, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    fun stop() {
        try { textRecognizer.close() }
        catch (e: Exception) { Log.e("TextRecognizer", "Error closing: ${e.message}") }
    }
}