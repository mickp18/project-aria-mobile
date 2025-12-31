package com.example.tutorial

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.provider.MediaStore
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

class WebSocketViewModel(application: Application) : AndroidViewModel(application) {
    private val webSocketClient = WebSocketClient.getInstance()

    // StateFlow for socket connection status
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    // message to show on screen
    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages

    private val isProcessing = AtomicBoolean(false)

    val yoloDetector : YoloDetector = YoloDetector(application)



    init {
        // Initialize the listener ONCE when ViewModel is created
        webSocketClient.setListener(object : WebSocketClient.SocketListener {
            override fun onMessage(message: String) {
                // Update the flow so the Activity sees it
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
                                // Handle the stop logic
                                _isSocketConnected.value = false
                                _messages.value = "Server stopped: ${payload.optString("reason")}"

                                webSocketClient.disconnect()

                            }
                        }

                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }


            override fun onBinaryMessage(bytes: ByteArray) {
                Log.i("socketCheck", "onBinaryMessage()")
                // Handle binary messages if needed
                if (isProcessing.get()) {
                    Log.d("socketCheck", "Ignoring binary message while processing previous one")
                    return
                }

                // Process the frame in a background thread
                viewModelScope.launch(Dispatchers.Default) {
                    if (isProcessing.compareAndSet(false, true)) {
                        try {
                            // Decode bitmap
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                            if (bitmap != null) {
                                val results = yoloDetector.detect(bitmap)

                                if (results.detections.isEmpty()) {
                                    Log.i("YOLO", "No detections")
                                }
                                else {
                                    for (det in results.detections) {
                                        Log.i(
                                            ",YOLO",
                                            "Found ${det.category.label} at ${det.boundingBox}"
                                        )

                                    }
                                }
                                Log.i(",YOLO" , "Executed YOLO in ${results.inferenceTime} ms")


                            } else {
                                Log.e("socketCheck", "Failed to decode bitmap")
                            }
                        } catch (e: Throwable) {
                            // CRITICAL: Catch 'Throwable' to handle OutOfMemoryError
                            Log.e("socketCheck", "Error processing frame: ${e.localizedMessage}", e)
                        } finally {
                            isProcessing.set(false)
                        }
                    }
                }
            }

            override fun onOpen() {
                Log.i("socketCheck", "onOpen()")
                // Update the flow so the Activity sees it
                _messages.value = "Socket Opened"
                _isSocketConnected.value = true

            }
            override fun onError(error: String) {
                // Reset UI state so Start button becomes enabled again
                _isSocketConnected.value = false
                _messages.value = "Connection Failed: $error"
                Log.e("socketCheck", "UI notified of error: $error")
            }
        })
    }

    fun connect() {
        // webSocketClient.setSocketUrl("ws://192.168.1.106:8080") // casa To
        // webSocketClient.setSocketUrl("ws://172.20.10.3:8080")
        // webSocketClient.setSocketUrl("ws://10.42.0.1:8080") // hotspot vado
        webSocketClient.setSocketUrl("ws://192.168.1.3:8080") // casa vado modem
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

        // Use MediaStore to save to the public Pictures directory
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // Save to the Pictures directory
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TutorialAppFrames")
            }
        }

        // Get the URI of the new image file
        val imageUri =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (imageUri == null) {
            Log.e("socketCheck", "Failed to create new MediaStore record.")
            return
        }

        try {
            // Open an output stream to the new URI and save the bitmap
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