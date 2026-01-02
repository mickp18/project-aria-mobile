package com.example.tutorial

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Processor for the text detector. */
class TextRecognitionProcessor(private val context: Context){

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)


    fun getImagefromBitmap(bitmap : Bitmap) : InputImage{
        return InputImage.fromBitmap(bitmap, 0)
    }

    fun getTextfromImage(image : InputImage) : Text  {
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

    fun getText(bitmap : Bitmap){
        val image = getImagefromBitmap(bitmap)
        val result = getTextfromImage(image)
        val resultText = result.text



    }

    fun stop() {
        textRecognizer.close()
    }
}
