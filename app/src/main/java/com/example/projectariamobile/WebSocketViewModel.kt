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

    // NEW: Connection status flow
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