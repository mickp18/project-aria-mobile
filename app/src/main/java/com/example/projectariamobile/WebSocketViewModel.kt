package com.example.projectariamobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean



sealed class ConnectionStatus {
    object DISCONNECTED : ConnectionStatus()
    object CONNECTING : ConnectionStatus()
    object CONNECTED : ConnectionStatus()
    data class FAILED(val error: String) : ConnectionStatus()
}

class WebSocketViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketClient = WebSocketClient.getInstance()

    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages

    private val _ocrResults = MutableStateFlow<String?>(null)
    val ocrResults: StateFlow<String?> = _ocrResults.asStateFlow()

    // Frame statistics
    private val _frameStats = MutableStateFlow("")
    val frameStats: StateFlow<String> = _frameStats.asStateFlow()

    private var frameCount = 0
    private var lastFrameTime = 0L
    private var firstFrameTime = 0L
    private var totalFrames = 0
    private var lastStatsTime = SystemClock.uptimeMillis()

    private var destination : String = ""


    init {
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
                            Log.d("socketCheck", "Status: $status, reason: $reason")
                            if (status == "stopped") {
                                _isSocketConnected.value = false
                                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                                _messages.value = "Server stopped: ${payload.optString("reason")}"
                                webSocketClient.disconnect()
                                Log.i("socketCheck", "Received stopped signal")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onBinaryMessage(bytes: ByteArray) {
                val receiveTime = SystemClock.uptimeMillis()
                frameCount++
                totalFrames++

                // Record first frame time for average FPS calculation
                if (firstFrameTime == 0L) {
                    firstFrameTime = receiveTime
                }

                val frameSize = bytes.size / 1024.0
                val timeSinceLastFrame = if (lastFrameTime > 0) {
                    receiveTime - lastFrameTime
                } else {
                    0L
                }

                Log.i("FRAME_TIMING",
                    "Frame ${totalFrames}: ${String.format("%.1f", frameSize)}KB | " +
                            "Interval: ${timeSinceLastFrame}ms"
                )

                lastFrameTime = receiveTime
                viewModelScope.launch(Dispatchers.Default) {
                    processFrame()
//                    updateStats()

                }
            }
            private suspend fun processFrame() {
                // simulate processing with sleeping
                Thread.sleep(150)

            }

            override fun onOpen() {
                Log.i("socketCheck", "✓ Connection opened")
                _messages.value = "Socket Opened"
                _isSocketConnected.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED  // NEW: Update status

                // Reset counters
                frameCount = 0
                totalFrames = 0
                lastFrameTime = 0L
                firstFrameTime = 0L
                lastStatsTime = SystemClock.uptimeMillis()
            }

            override fun onError(error: String) {
                _isSocketConnected.value = false
                _connectionStatus.value = ConnectionStatus.FAILED(error)  // NEW: Update status with error
                _messages.value = "Connection Failed: $error"
                Log.e("socketCheck", "✗ Error: $error")
            }
        })
    }

    private fun updateStats() {
        val currentTime = SystemClock.uptimeMillis()
        val elapsedSeconds = (currentTime - lastStatsTime) / 1000.0

        // Update stats every 5 seconds
        if (elapsedSeconds >= 5.0) {
            val fps = frameCount / elapsedSeconds
            val totalElapsed = (currentTime - firstFrameTime) / 1000.0
            val avgFps = if (totalElapsed > 0) totalFrames / totalElapsed else 0.0

            _frameStats.value = String.format(
                "Current FPS: %.1f | Avg FPS: %.1f | Total: %d frames",
                fps, avgFps, totalFrames
            )

            Log.i("STATS", _frameStats.value)

            // Reset interval counter
            frameCount = 0
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
                Log.w("PERFORMANCE", "Processing took > 500ms - client falling behind!")
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
       // Set status to CONNECTING before attempting connection
        _connectionStatus.value = ConnectionStatus.CONNECTING
        Log.d("socketCheck", "Attempting to connect...")

        webSocketClient.setSocketUrl("ws://10.42.0.1:8080")
        webSocketClient.connect()


        webSocketClient.sendMessage("start")

        // Note: _isSocketConnected and _connectionStatus will be updated in onOpen() or onError()
        // Don't set _isSocketConnected to true here - wait for actual connection
    }

    fun disconnect() {
        Log.d("socketCheck", "Disconnecting...")
        webSocketClient.sendMessage("stop")
        webSocketClient.disconnect()
        _isSocketConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED  // NEW: Update status

        // Print final stats
        val totalElapsed = (SystemClock.uptimeMillis() - firstFrameTime) / 1000.0
        val avgFps = if (totalElapsed > 0) totalFrames / totalElapsed else 0.0
        Log.i("FINAL_STATS", "Session ended: ${totalFrames} frames in ${String.format("%.1f", totalElapsed)}s (avg ${String.format("%.1f", avgFps)} FPS)")
    }

    fun setDestination(command : String){
        destination = command
        Log.d("socketCheck", "Destination set to: $command")
    }

    // Helper function to check if currently connecting
    fun isConnecting(): Boolean {
        return _connectionStatus.value is ConnectionStatus.CONNECTING
    }

    // Helper function to get error message if failed
    fun getConnectionError(): String? {
        return when (val status = _connectionStatus.value) {
            is ConnectionStatus.FAILED -> status.error
            else -> null
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        _isSocketConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED  // NEW: Update status
        destination = ""
    }
}