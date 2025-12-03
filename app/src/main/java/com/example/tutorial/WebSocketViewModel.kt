package com.example.tutorial

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject


class WebSocketViewModel : ViewModel() {
    private val webSocketClient = WebSocketClient.getInstance()

    // StateFlow for socket connection status
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected : StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    // message to show on screen
    private val _messages = MutableStateFlow("")
    val messages: StateFlow<String> = _messages

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

                    when(type){
                        "STATUS_UPDATE" -> {
                            val payload = jsonObject.getJSONObject("payload")
                            val status = payload.getString("status")
                            val reason = payload.getString("reason")
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


            }

            override fun onOpen() {
                Log.i("socketCheck", "onOpen()")
                // Update the flow so the Activity sees it
                _messages.value = "Socket Opened"

            }
        })
    }

    fun connect(){
        webSocketClient.setSocketUrl("ws://192.168.1.106:8080")
        // webSocketClient.setSocketUrl("ws://172.20.10.3:8080")
        webSocketClient.connect()
        webSocketClient.sendMessage("start")

        // Update state
        _isSocketConnected.value = true
    }

    fun disconnect(){
        webSocketClient.sendMessage("stop")
        webSocketClient.disconnect()
        _isSocketConnected.value = false
    }
}