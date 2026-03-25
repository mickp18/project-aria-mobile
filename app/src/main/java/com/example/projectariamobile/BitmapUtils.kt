package com.example.projectariamobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.scale
import kotlin.math.pow

/**
 * Shared extension function to crop a Bitmap safely.
 */
fun Bitmap.crop(boundingBox: Rect): Bitmap {
    return try {
        Bitmap.createBitmap(
            this,
            boundingBox.left,
            boundingBox.top,
            boundingBox.width(),
            boundingBox.height()
        )
    } catch (e: Exception) {
        Log.e("BitmapUtils", "Crop failed: ${e.message}")
        this // Return original if crop fails
    }
}

/**
 * Scales the bitmap by a factor (e.g., 2.0 = 200% size).
 * Uses bilinear filtering which is "good enough" for OCR upscaling.
 */
fun Bitmap.scaleBitmap(factor: Float): Bitmap {
    var bitmap = this
    val width = (bitmap.width * factor).toInt()
    val height = (bitmap.height * factor).toInt()
    // filter = true enables bilinear filtering for smoother edges
    val scaled = bitmap.scale(width, height)
    Log.d("TextRecognizer", "Scaled to-(Height: ${scaled.height})")
    return scaled
}

fun Bitmap.correctExposure(): Bitmap {
    // Sample a grid of pixels to estimate global brightness
    val sampleSize = 200
    val stepX = width  / 10
    val stepY = height / 10
    var sum = 0f
    var count = 0
    for (x in 0 until width  step stepX) {
        for (y in 0 until height step stepY) {
            val p = getPixel(x, y)
            sum += Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
            count++
        }
    }
    val meanBrightness = sum / count
    Log.d("Exposure", "Mean brightness: $meanBrightness")

    // Only correct if genuinely overexposed — don't touch normal frames
    if (meanBrightness < 160f) return this

    // Gamma < 1.0 compresses highlights, recovers arrow contrast on bright signs
    val gamma = when {
        meanBrightness > 210f -> 0.35f   // severely overexposed
        meanBrightness > 185f -> 0.45f   // moderately overexposed
        else                  -> 0.60f   // mildly overexposed
    }

    return applyGammaLut(gamma)
}

fun Bitmap.applyGammaLut(gamma: Float): Bitmap {
    val lut = IntArray(256) { i ->
        (255f * (i / 255f).pow(gamma)).toInt().coerceIn(0, 255)
    }
    val result = copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val a = Color.alpha(pixels[i])
        val r = lut[Color.red(pixels[i])]
        val g = lut[Color.green(pixels[i])]
        val b = lut[Color.blue(pixels[i])]
        pixels[i] = Color.argb(a, r, g, b)
    }
    result.setPixels(pixels, 0, width, 0, 0, width, height)
    return result
}

/**
 * Converts a bitmap to a high-contrast Black and White image.
 * This removes colored noise and shadows.
 */
fun Bitmap.binarizeBitmap(): Bitmap {
    var src = this
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

fun Bitmap.drawYoloBbox(det: ObjectDetection): Bitmap {
    val source = this
    val out    = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)

    val boxPaint = android.graphics.Paint().apply {
        color       = android.graphics.Color.RED
        style       = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
    }
    val textPaint = android.graphics.Paint().apply {
        color    = android.graphics.Color.RED
        textSize = 36f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style    = android.graphics.Paint.Style.FILL
    }
    val bgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(160, 0, 0, 0)
        style = android.graphics.Paint.Style.FILL
    }

    val box   = det.boundingBox
    val label = "${det.category.label} ${"%.2f".format(det.category.confidence)}"

    canvas.drawRect(box, boxPaint)

    // Draw label background then text
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(label, 0, label.length, textBounds)
    val labelY = if (box.top > textBounds.height() + 8f) box.top else box.bottom + textBounds.height() + 8f
    canvas.drawRect(
        box.left, labelY - textBounds.height() - 8f,
        box.left + textBounds.width() + 8f, labelY + 4f,
        bgPaint
    )
    canvas.drawText(label, box.left + 4f, labelY, textPaint)

    return out
}

