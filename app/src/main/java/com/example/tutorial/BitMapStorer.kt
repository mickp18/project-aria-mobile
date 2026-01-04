package com.example.tutorial

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva un bitmap nella galleria del telefono
 * @param context Contesto dell'applicazione
 * @param bitmap Immagine da salvare
 * @param fileName Nome del file (opzionale, genera automaticamente se null)
 * @return true se salvato con successo, false altrimenti
 */
fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    fileName: String? = null,
    folderName: String = "OCR_Crops"
): Boolean {
    return try {
        // Genera nome file se non fornito
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val finalFileName = fileName ?: "CROP_$timeStamp.jpg"

        // Prepara i metadati per MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")

            // Android 10+ usa RELATIVE_PATH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$folderName")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // File non visibile finché non finito
            }
        }

        // Crea il file in MediaStore
        val contentResolver = context.contentResolver
        val imageUri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        if (imageUri == null) {
            Log.e("SaveBitmap", "Failed to create MediaStore entry")
            return false
        }

        // Scrivi il bitmap nel file
        contentResolver.openOutputStream(imageUri)?.use { outputStream ->
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)

            if (!success) {
                Log.e("SaveBitmap", "Failed to compress bitmap")
                return false
            }
        } ?: run {
            Log.e("SaveBitmap", "Failed to open output stream")
            return false
        }

        // Android 10+: marca il file come completato
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(imageUri, contentValues, null, null)
        }

        Log.i("SaveBitmap", "Image saved successfully: $imageUri")
        true

    } catch (e: Exception) {
        Log.e("SaveBitmap", "Error saving bitmap: ${e.message}", e)
        false
    }
}

/**
 * Salva bitmap con informazioni aggiuntive nel nome
 */
fun saveCroppedBitmapWithInfo(
    context: Context,
    bitmap: Bitmap,
    detectionClass: String,
    confidence: Float
): Boolean {
    val timeStamp = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
    val confidenceStr = String.format("%.2f", confidence)
    val fileName = "CROP_${detectionClass}_${confidenceStr}_$timeStamp.jpg"

    return saveBitmapToGallery(context, bitmap, fileName)
}