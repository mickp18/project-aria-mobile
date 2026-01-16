package com.example.projectariamobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.application
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlin.text.category

class WebSocketViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketClient = WebSocketClient.getInstance()

    // StateFlow for socket connection status
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    // message to show on screen
    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages

    // OCR results flow
    private val _ocrResults = MutableStateFlow<String?>(null)
    val ocrResults: StateFlow<String?> = _ocrResults.asStateFlow()

    // Frame statistics
    private val _frameStats = MutableStateFlow("")
    val frameStats: StateFlow<String> = _frameStats.asStateFlow()

    private var frameCount = 0
    private var droppedFrames = 0
    private var lastStatsTime = SystemClock.uptimeMillis()

    private val isProcessing = AtomicBoolean(false)
    val threshold = 0.5f
    val numThreads = 2
    val currentDelegate = 0
    val maxResults = 3

    // YOLO detector
    val yoloDetector: YoloDetector = YoloDetector(
        threshold,
        0.3f,
        numThreads,
        maxResults,
        currentDelegate,
        application,
    )

    // OCR text recognizer
    private val textRecognizer = TextRecognitionProcessor(application)

    // Classes that should trigger OCR (configure as needed)
    private val ocrTargetClasses = setOf(
        "room",
        "direction_left",
        "direction_right",
        "stairs",
    )

    init {
        // Initialize the listener ONCE when ViewModel is created
        webSocketClient.setListener(object : WebSocketClient.SocketListener {
            override fun onMessage(message: String) {
                _messages.value = "New Message: $message"

                try {
                    val jsonObject = JSONObject(message)
                    val type = jsonObject.optString("type", "")
                    Log.d("socketCheck", "onMessage() type = $type")

                    when (type) {
                        "STATUS_UPDATE" -> {
                            val payload = jsonObject.getJSONObject("payload")
                            val status = payload.optString("status", "unknown")
                            val reason = payload.optString("reason", "")
                            Log.d("socketCheck", "onMessage() type = $status reason=$reason")
                            if (status == "stopped") {
                                _isSocketConnected.value = false
                                _messages.value = "Server stopped: ${payload.optString("reason")}"
                                webSocketClient.disconnect()
                                Log.i("onMessage()", "received stopped")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onBinaryMessage(bytes: ByteArray) {
                Log.i("socketCheck", "onBinaryMessage()")
                val receiveTime = SystemClock.uptimeMillis()
                frameCount++

                if (isProcessing.get()) {
                    droppedFrames++
                    Log.d("socketCheck", "Dropped frame (still processing previous)")
                    updateStats()
                    return
                }

                // Process the frame in a background thread
                viewModelScope.launch(Dispatchers.Default) {
                    if (isProcessing.compareAndSet(false, true)) {
                        try {
                            val frameSize = bytes.size / 1024.0
                            Log.i("socketCheck", "Processing frame ${frameCount}: ${String.format("%.1f", frameSize)}KB")

                            var start = SystemClock.uptimeMillis()
                            processFrame(bytes)
                            var end = SystemClock.uptimeMillis()
                            Log.i("socketCheck", "Frame processed in ${end - start} ms")
                            updateStats()
                        } catch (e: Throwable) {
                            Log.e("socketCheck", "Error processing frame: ${e.localizedMessage}", e)
                        } finally {
                            isProcessing.set(false)
                        }
                    }

                }
            }

            override fun onOpen() {
                Log.i("socketCheck", "onOpen()")
                _messages.value = "Socket Opened"
                _isSocketConnected.value = true
                frameCount = 0
                droppedFrames = 0
                lastStatsTime = SystemClock.uptimeMillis()
            }

            override fun onError(error: String) {
                _isSocketConnected.value = false
                _messages.value = "Connection Failed: $error"
                Log.e("socketCheck", "UI notified of error: $error")
            }
        })
    }
    private fun updateStats() {
        val currentTime = SystemClock.uptimeMillis()
        val elapsedSeconds = (currentTime - lastStatsTime) / 1000.0

        if (elapsedSeconds >= 5.0) {
            val fps = frameCount / elapsedSeconds
            val dropRate = (droppedFrames.toFloat() / frameCount) * 100

            _frameStats.value = String.format(
                "FPS: %.1f | Received: %d | Dropped: %d (%.1f%%)",
                fps, frameCount, droppedFrames, dropRate
            )

            Log.i("STATS", _frameStats.value)

            // Reset counters
            frameCount = 0
            droppedFrames = 0
            lastStatsTime = currentTime
        }
    }

    /**
     * Process incoming video frame: run YOLO detection and OCR if needed
     */
//    private suspend fun processFrame(bytes: ByteArray) {
//        // Decode bitmap
//        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//
//        if (bitmap == null) {
//            Log.e("socketCheck", "Failed to decode bitmap")
//            return
//        }
//        var count = 0
//        try {
////            val save_orig = saveBitmapToGallery(
////                application,
////                bitmap,
////                fileName = "ORIGINAL_${count}_${System.currentTimeMillis()}.jpg",
////                folderName = "YOLO_DETECTIONS"
////            )
////            if (save_orig) {
////                Log.i("TextRecognizer", "saved frame number: $count ")
////            } else {
////                Log.e("TextRecognizer", "Failed to save cropped image")
////            }
////            count = count +1
//            // Run YOLO detection
//            var startTime = SystemClock.uptimeMillis()
//            val results = yoloDetector.detect(bitmap, 0)
//            val endTime = SystemClock.uptimeMillis()
//
//            val inferanceTime = endTime - startTime
//            Log.i(
//                "YOLO",
//                "Found in $inferanceTime ms"
//            )
//
//            if (results.detections.isEmpty()) {
//                Log.i("YOLO", "No detections on frame; $count")
//                return
//            }
//            count = count + 1
//
//            // Process each detection
//            for (detection in results.detections) {
//                val label = detection.category.label.lowercase()
//                val bbox = detection.boundingBox
//
//
//                Log.i(
//                    "YOLO",
//                    "Found $label at ${bbox} (confidence: ${detection.category.confidence})"
//                )
//
//                val saved = saveBitmapToGallery(
//                    application,
//                    bitmap,
//                    fileName = "ORIGINAL_${label}_${System.currentTimeMillis()}.jpg",
//                    folderName = "YOLO_DETECTIONS"
//                )
////                if (saved) {
////                    Log.i("TextRecognizer", "Cropped image saved for class: $label")
////                } else {
////                    Log.e("TextRecognizer", "Failed to save cropped image")
////                }
//
//                // Check if this detection should trigger OCR
//                if (shouldRunOCR(label)) {
//                    Log.i("OCR", "Running OCR on detected $label")
//
//                    // Convert RectF to Rect for cropping
//                    val cropRect = Rect(
//                        bbox.left.toInt(),
//                        bbox.top.toInt(),
//                        bbox.right.toInt(),
//                        bbox.bottom.toInt()
//                    )
//                    startTime = SystemClock.uptimeMillis()
//                    // Run OCR on the bounding box
//                    val recognizedText = textRecognizer.recognizeTextInBoundingBox(
//                        bitmap,
//                        cropRect,
//                        label
//                    )
//                    val ocrTime = SystemClock.uptimeMillis() - startTime
//                    Log.i("tOCR", "OCR executed in $ocrTime")
//                    if (recognizedText != null) {
//                        Log.i("OCR", "Recognized text in $label: $recognizedText")
//
//                        // Update UI with OCR result
//                        _ocrResults.value = "[$label]: $recognizedText"
//
//                        // Optional: Save results or trigger other actions
////                        handleOCRResult(label, recognizedText, bbox)
//                    } else {
//                        Log.i("OCR", "No text found in $label bounding box")
//                    }
//                }
//            }
//        } finally {
//            // Clean up bitmap to prevent memory leaks
//            bitmap.recycle()
//        }
//    }
    private suspend fun processFrame(bytes: ByteArray) {
        val decodeStart = SystemClock.uptimeMillis()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val decodeTime = SystemClock.uptimeMillis() - decodeStart

        try {
            Log.d("TIMING", "Decode: ${decodeTime}ms")

            val yoloStart = SystemClock.uptimeMillis()
            val results = yoloDetector.detect(bitmap, 0)
            val yoloTime = SystemClock.uptimeMillis() - yoloStart
            Log.i("YOLO", "Detection completed in ${yoloTime}ms, found ${results.detections.size} objects")

            var totalOcrTime = 0L
            var ocrCount = 0

            for (detection in results.detections) {
                val label = detection.category.label.lowercase()
                val confidence = detection.category.confidence
                var bbox= detection.boundingBox

                Log.i(
                    "YOLO",
                    "Detected: $label (${String.format("%.2f", confidence)}) at [${bbox.left.toInt()},${bbox.top.toInt()},${bbox.right.toInt()},${bbox.bottom.toInt()}]"
                )

                if (shouldRunOCR(label)) {
                    ocrCount++
                    val cropRect = Rect(
                        bbox.left.toInt(),
                        bbox.top.toInt(),
                        bbox.right.toInt(),
                        bbox.bottom.toInt()
                    )
                    val ocrStart = SystemClock.uptimeMillis()
                    val recognizedText = textRecognizer.recognizeTextInBoundingBox(bitmap, cropRect, label)
                    val ocrTime = SystemClock.uptimeMillis() - ocrStart
                    totalOcrTime += ocrTime

                    if (recognizedText != null) {
                        Log.i("OCR", "Recognized in ${ocrTime}ms: [$label] = $recognizedText")
                        _ocrResults.value = "[$label]: $recognizedText"
                    } else {
                        Log.i("OCR", "No text found in $label (${ocrTime}ms)")
                    }
                }
            }

            val totalTime = SystemClock.uptimeMillis() - decodeStart

            Log.i(
                "TIMING",
                "Total: ${totalTime}ms (decode: ${decodeTime}ms, YOLO: ${yoloTime}ms, OCR: ${totalOcrTime}ms for ${ocrCount} objects)"
            )

            if (totalTime > 500) {
                Log.w("PERFORMANCE", "⚠️ Processing took > 500ms - client falling behind!")
            }

        } finally {
            bitmap.recycle()
        }
    }
    /**
     * Determine if OCR should run for this detection class
     */
    private fun shouldRunOCR(detectionLabel: String): Boolean {
        return ocrTargetClasses.any { target ->
            detectionLabel.contains(target, ignoreCase = true)
        }
    }

    /**
     * Handle OCR results - customize based on your needs
     */
//    private fun handleOCRResult(
//        objectClass: String,
//        text: String,
//        boundingBox: android.graphics.RectF
//    ) {
//        // Example: Log to analytics
//        Log.i("OCR_RESULT", "Class: $objectClass, Text: $text, BBox: $boundingBox")
//
//        // Example: Send to server
//        // webSocketClient.sendMessage(createOCRResultMessage(objectClass, text))
//
//        // Example: Save to database
//        // saveOCRResultToDatabase(objectClass, text, System.currentTimeMillis())
//
//        // Example: Trigger specific actions based on text content
//        when (objectClass) {
//            "license plate" -> handleLicensePlate(text)
//            "sign" -> handleTrafficSign(text)
//            "card" -> handleCard(text)
//            else -> Log.d("OCR", "No specific handler for $objectClass")
//        }
//    }


    private fun handleTrafficSign(signText: String) {
        // Custom logic for traffic signs
        Log.i("TRAFFIC_SIGN", "Detected sign: $signText")
    }


    /**
     * Add or remove OCR target classes dynamically
     */
    fun addOCRTargetClass(className: String) {
        (ocrTargetClasses as MutableSet).add(className.lowercase())
        Log.d("OCR", "Added OCR target class: $className")
    }

    fun removeOCRTargetClass(className: String) {
        (ocrTargetClasses as MutableSet).remove(className.lowercase())
        Log.d("OCR", "Removed OCR target class: $className")
    }

    fun connect() {
//        webSocketClient.setSocketUrl("ws://192.168.1.3:8080")
        webSocketClient.setSocketUrl("ws://10.42.0.1:8080")
        webSocketClient.connect()
        webSocketClient.sendMessage("start")
        _isSocketConnected.value = true
    }

    fun disconnect() {
        webSocketClient.sendMessage("stop")
        webSocketClient.disconnect()
        _isSocketConnected.value = false
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        val context = getApplication<Application>().applicationContext
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FRAME_$timeStamp.jpg"

        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TutorialAppFrames")
            }
        }

        val imageUri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        if (imageUri == null) {
            Log.e("socketCheck", "Failed to create new MediaStore record.")
            return
        }

        try {
            contentResolver.openOutputStream(imageUri).use { out ->
                if (out == null) {
                    Log.e("socketCheck", "Failed to open output stream for $imageUri")
                    return@use
                }
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                Log.i("socketCheck", "Image saved successfully to gallery: $imageUri")
            }
        } catch (e: Exception) {
            Log.e("socketCheck", "Error saving image to MediaStore", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        textRecognizer.stop() // Clean up OCR resources
        _isSocketConnected.value = false
    }
}

class WebSocketViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebSocketViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WebSocketViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}