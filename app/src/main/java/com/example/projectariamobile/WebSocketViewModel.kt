package com.example.projectariamobile

import android.annotation.SuppressLint
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
import com.google.android.datatransport.runtime.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean


data class Detection(
    var label : String = "",
    var text : String = "",
    var confidence : Float = 0.0f,
    var bbox : android.graphics.RectF = android.graphics.RectF(),
    var timeStamp : Long = System.currentTimeMillis()
)

sealed class NavigationEvent {
    data class Speak(val message: String) : NavigationEvent()
    object StopNavigation : NavigationEvent()
}


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

//    private val _ocrResults = MutableStateFlow<String?>(null)
//    val ocrResults: StateFlow<String?> = _ocrResults.asStateFlow()

    // Frame statistics
    private val _frameStats = MutableStateFlow("")
    val frameStats: StateFlow<String> = _frameStats.asStateFlow()

    // Debugging variables
    private var frameCount = 0
    private var droppedFrames = 0
    private var lastFrameTime = 0L
    private var firstFrameTime = 0L
    private var totalFrames = 0
    private var lastStatsTime = SystemClock.uptimeMillis()

    var destination : String = ""

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
        "room_direction_left",
        "room_direction_right",
        "stairs",
    )

    var detections = mutableListOf<Detection>()
    var lastDetection = Detection()
    var lastInstruction : String = ""
    var lastInstructionTime : Long = 0L
    var matchCounter = 0
    private val SPEECH_COOLDOWN = 3000L // 3 Seconds

    // Sign on the side handling
    var lastSideDetectionTimestamp = 0L
    var SIDE_MOVEMENT_COOLDOWN = 8000L

    // Channel for sending one-time events to MainActivity
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

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

                if (isProcessing.get()) {
                    droppedFrames++
                    Log.d("socketCheck", "Dropped frame (still processing previous)")
                    updateStats()
                    return
                }

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
                    Log.i("OnBinaryMessage", "------------------------")
                }
            }

            override fun onOpen() {
                Log.i("socketCheck", "✓ Connection opened")
                _messages.value = "Socket Opened"
                _isSocketConnected.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED  // NEW: Update status

                // Reset counters
                frameCount = 0
                droppedFrames=0
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

    @SuppressLint("DefaultLocale")
    private fun updateStats() {
        val currentTime = SystemClock.uptimeMillis()
        val elapsedSeconds = (currentTime - lastStatsTime) / 1000.0

        // Update stats every 5 seconds
        if (elapsedSeconds >= 5.0) {
            val fps = frameCount / elapsedSeconds
            val dropRate = (droppedFrames.toFloat() / frameCount) * 100
            val totalElapsed = (currentTime - firstFrameTime) / 1000.0
            val avgFps = if (totalElapsed > 0) totalFrames / totalElapsed else 0.0

            _frameStats.value = String.format(
                "FPS: %.1f | Received: %d | Dropped: %d (%.1f%%)",
                fps, frameCount, droppedFrames, dropRate,
                "Current FPS: %.1f | Avg FPS: %.1f | Total: %d frames",
                fps, avgFps, totalFrames
            )

            Log.i("STATS", _frameStats.value)
            Log.i("STATS", "--------------------------------------------\n")

            // Reset interval counter
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

            for (yoloDetection in results.detections) {
                val label = yoloDetection.category.label.lowercase()
                val confidence = yoloDetection.category.confidence
                var bbox= yoloDetection.boundingBox

                // create detection object to store global detection (yolo + ocr)
                val detection = Detection()
                detection.label = label
                detection.confidence = confidence
                detection.bbox = bbox

                Log.i(
                    "YOLO",
                    "Detected: $label (${String.format("%.2f", confidence)}) at [${bbox.left.toInt()},${bbox.top.toInt()},${bbox.right.toInt()},${bbox.bottom.toInt()}]"
                )

                ocrCount++
                val cropRect = Rect(
                    bbox.left.toInt(),
                    bbox.top.toInt(),
                    bbox.right.toInt(),
                    bbox.bottom.toInt()
                )

                var isDistorted = false
                if (shouldCHeckDistortion(label, confidence, 0.5f)) { // low confidence and possible sign of interest
                    Log.d("processFrame()", "low confidence frame, checking distortion")
                    // check if on the side and distorted
                    if (DistortionChecker.isSignDistorted(bitmap, cropRect)) {
                        isDistorted = true
                        // check on which side the sign is
                        Log.d("processFrame()", "Sign distorted, getting which side is on")
                        var onWhichSide: String = getSignPosition(bitmap, cropRect)
                        lastSideDetectionTimestamp =
                            SystemClock.uptimeMillis() - lastSideDetectionTimestamp

                        if (onWhichSide.isNotEmpty() && lastSideDetectionTimestamp > SIDE_MOVEMENT_COOLDOWN)
                            Log.d("processFrame()", "Sign on $onWhichSide")
                        _navigationEvents.emit(NavigationEvent.Speak("There is a ${label}sign  on the $onWhichSide side, get closer to it and face it to get better readings"))
                    }
                }
                if (shouldRunOCR(label) && !isDistorted) {
                    // try to read text only when enough confidence in yolo detection
                    val ocrStart = SystemClock.uptimeMillis()
                    val recognizedText = textRecognizer.recognizeTextInBoundingBox(bitmap, cropRect, label)
                    val ocrTime = SystemClock.uptimeMillis() - ocrStart
                    totalOcrTime += ocrTime

                    if (recognizedText != null) {
                        Log.i("OCR", "Recognized in ${ocrTime}ms: [$label] = $recognizedText")
                        // add text to detection
                        detection.text = recognizedText.lowercase()
                    } else {
                        Log.i("OCR", "No text found in $label (${ocrTime}ms)")
                    }
                }
                else {
                    Log.d("pocessFrame()", "sign was distorted or without text, not reading text")
                }
                detections.add(detection)
                // decide navigation
                Log.i("processFrame()", "Handling the detection result")
                handleDetection(detection)
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
    private fun shouldCHeckDistortion(label: String, confidence : Float, conf_treshold: Float) : Boolean{
        return confidence < conf_treshold && isSignPoossibleTarget(label)
    }

    private fun handleLowConfidenceDetection(bitmap: Bitmap, bbox : Rect){

    }

    /**
     * Handle YOLO+OCR results
     */
    private fun handleDetection(detection : Detection){
        if (destination.isEmpty())
            return

        val currentTime = System.currentTimeMillis()
        val isSpeaking = (currentTime - lastInstructionTime) < SPEECH_COOLDOWN
        var compareText = false
        var instruction = ""
        var match = false

       // check what sign was found
        when (detection.label) {
            "room" -> {
                instruction = "Room found"
                match = FuzzyLogic.isMatch(detection.text, destination) && destination!="exit"
            }

            "room_direction_left" -> {
                instruction = "Your destination is on the left"
                match = FuzzyLogic.isMatch(detection.text, destination) && destination!="exit"
            }

            "room_direction_right" -> {
                instruction = "your destination is on the right"
                match = FuzzyLogic.isMatch(detection.text, destination) && destination!="exit"
            }

            "exit_left" -> {
                instruction = "The nearest exit is on the left"
                match = destination == "exit"
            }

            "exit_right" -> {
                instruction = "The nearest exit is on the right"
                match = destination == "exit"
            }
            // stairs
            else -> {
                instruction = "The destination is on the next floor above you, find the closest stairs"
                match = FuzzyLogic.isMatch(detection.text, destination) && destination!="exit"
            }
        }
        
        // if room, dir right, dir left -> check text
        // if exit follow its direction
        Log.d("FuzzyLogic()", "Fuzz logic result: $match")
        Log.d("handleDetection()", "match: $match, isSpeaking: $isSpeaking, lastInstruction: $lastInstruction, instruction: $instruction")
        if (match && !isSpeaking && lastInstruction!=instruction) {
            Log.d("FuzzLogic()", "emitting vocal command")
            lastInstruction = instruction
            lastInstructionTime = currentTime
            viewModelScope.launch {
                _navigationEvents.emit(NavigationEvent.Speak(instruction))
            }

            if (instruction.contains("found")) {
                Log.d("FuzzLogic()", "Emitting stop command")
                viewModelScope.launch {
                    lastInstruction = ""
                    lastInstructionTime = 0L
                    _navigationEvents.emit(NavigationEvent.StopNavigation)
                }
            }

        }


    }


    /**
     * Add or remove OCR target classes dynamically
     */
    fun addOCRTargetClass(className: String) {
        (ocrTargetClasses as MutableSet).add(className.lowercase())
        Log.d("OCR", "Added OCR target class: $className")
    }

    /**
     * Returns if a detected sign is of interest based on the destination
     */
    private fun isSignPoossibleTarget(label: String): Boolean{
        // if the desitnation is the exit and the detected sign is a exit direction
        // instead when searching for room all the other signs could be of interest
        val isExitDetected =  label=="exit_left" || label=="exit_right"

        if ( destination=="exit" && !isExitDetected)
            return false
        else if (destination != "exit" && isExitDetected)
            return false
        return true


    }

    /**
     * Gets if the sign is on the left or right of user's POV
     */
    private fun getSignPosition(bitmap: Bitmap, bbox: Rect): String{
        val xcenter = bitmap.width/2
        val ycenter = bitmap.height/2

        if (bbox.left < xcenter && bbox.right < xcenter)
            return "left"
        else if (bbox.left > xcenter && bbox.right > xcenter)
            return "right"

        return ""
    }

    fun connect() {
       // Set status to CONNECTING before attempting connection
        _connectionStatus.value = ConnectionStatus.CONNECTING
        Log.d("socketCheck", "Attempting to connect...")

        // webSocketClient.setSocketUrl("ws://10.42.0.1:8080")
        webSocketClient.setSocketUrl("ws://192.168.1.98:8080")
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


    class WebSocketViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WebSocketViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return WebSocketViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
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