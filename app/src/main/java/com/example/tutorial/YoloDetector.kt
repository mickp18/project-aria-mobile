/*
package com.example.tutorial

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.channels.FileChannel

class YoloDetector(
    private val context: Context,
    private val modelPath: String = "yolov11n.tflite",
    private val labelPath: String = "labels.txt"
) {
    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()
    private val tensorWidth = 640
    private val tensorHeight = 640
    private val numChannel = 84 // 4 coordinates + 80 classes
    private val numElements = 8400 // Number of anchors

    init {
        val options = Interpreter.Options()
        // Use GPU if available (falls back to CPU if not)
        // options.addDelegate(GpuDelegate())

        val modelFile = loadModelFile(context, modelPath)
        interpreter = Interpreter(modelFile, options)
        labels = loadLabels(context, labelPath)
    }

    fun detect(bitmap: Bitmap): List<BoundingBox> {
        if (interpreter == null) return emptyList()

        // 1. Preprocess the image
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(tensorHeight, tensorWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f)) // Normalize to [0, 1]
            .add(CastOp(DataType.FLOAT32))
            .build()

        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Run Inference
        // Output buffer: [1, 84, 8400]
        val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), DataType.FLOAT32)
        interpreter?.run(tensorImage.buffer, outputBuffer.buffer.rewind())

        // 3. Post-Process (Extract Boxes)
        val bestBoxes = extractBoundingBoxes(outputBuffer.floatArray)

        // 4. Scale boxes back to original image size
        return bestBoxes.map { box ->
            box.apply {
                x1 *= bitmap.width
                x2 *= bitmap.width
                y1 *= bitmap.height
                y2 *= bitmap.height
            }
        }
    }

    private fun extractBoundingBoxes(array: FloatArray): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()

        // The output is flattened: [rows * cols].
        // In TFLite 'rows' are usually channels (84) and 'cols' are anchors (8400) for YOLO.

        for (c in 0 until numElements) { // Iterate through 8400 anchors
            var maxConf = 0f
            var maxClass = -1

            // Iterate through classes (starting from index 4 to 84)
            for (j in 4 until numChannel) {
                val conf = array[j * numElements + c] // Access column-wise
                if (conf > maxConf) {
                    maxConf = conf
                    maxClass = j - 4
                }
            }

            if (maxConf > 0.5f) { // Confidence Threshold
                val cx = array[0 * numElements + c]
                val cy = array[1 * numElements + c]
                val w  = array[2 * numElements + c]
                val h  = array[3 * numElements + c]

                val x1 = cx - (w / 2F)
                val y1 = cy - (h / 2F)
                val x2 = cx + (w / 2F)
                val y2 = cy + (h / 2F)

                boxes.add(BoundingBox(x1, y1, x2, y2, maxConf, maxClass, labels.getOrElse(maxClass) { "Unknown" }))
            }
        }
        return nms(boxes) // Apply Non-Maximum Suppression
    }

    private fun nms(boxes: List<BoundingBox>): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.conf }
        val selected = mutableListOf<BoundingBox>()

        for (box in sorted) {
            var overlap = false
            for (existing in selected) {
                if (calculateIoU(box, existing) > 0.5f) { // IoU Threshold
                    overlap = true
                    break
                }
            }
            if (!overlap) selected.add(box)
        }
        return selected
    }

    private fun calculateIoU(b1: BoundingBox, b2: BoundingBox): Float {
        val xA = maxOf(b1.x1, b2.x1)
        val yA = maxOf(b1.y1, b2.y1)
        val xB = minOf(b1.x2, b2.x2)
        val yB = minOf(b1.y2, b2.y2)

        val interArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val boxAArea = (b1.x2 - b1.x1) * (b1.y2 - b1.y1)
        val boxBArea = (b2.x2 - b2.x1) * (b2.y2 - b2.y1)

        return interArea / (boxAArea + boxBArea - interArea)
    }

    private fun loadModelFile(context: Context, modelName: String): java.nio.MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun loadLabels(context: Context, path: String): MutableList<String> {
        return context.assets.open(path).bufferedReader().readLines().toMutableList()
    }
}

data class BoundingBox(
    var x1: Float, var y1: Float, var x2: Float, var y2: Float,
    val conf: Float, val cls: Int, val label: String
)
*/