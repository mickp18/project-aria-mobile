package com.example.tutorial

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketClient {
    // @Volatile ensures checking 'webSocket' from a background thread
    // always sees the latest assigned instance.
    @Volatile private var webSocket: WebSocket? = null

    private var socketListener: SocketListener? = null
    private var socketUrl = ""
    private var shouldReconnect = false

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS) // if timeout reached without any data received, disconnect
        .build()

    companion object {
        private lateinit var instance: WebSocketClient
        @JvmStatic
        @Synchronized
        fun getInstance(): WebSocketClient {
            synchronized(WebSocketClient::class) {
                if (!::instance.isInitialized) {
                    instance = WebSocketClient()
                }
            }
            return instance
        }
    }

    fun setListener(listener: SocketListener) {
        this.socketListener = listener
    }

    fun setSocketUrl(socketUrl: String) {
        this.socketUrl = socketUrl
    }

    private fun initWebSocket() {
        Log.e("socketCheck", "initWebSocket() socketurl = $socketUrl")
        val request = Request.Builder().url(url = socketUrl).build()
        webSocket = client.newWebSocket(request, webSocketListener)
    }

    fun connect() {
        Log.e("socketCheck", "connect()")
        shouldReconnect = true
        initWebSocket()
    }

    fun reconnect() {
        Log.e("socketCheck", "reconnect()")
        initWebSocket()
    }

    fun sendMessage(message: String) {
        Log.e("socketCheck", "sendMessage($message)")
        webSocket?.send(message)
    }

    fun disconnect() {
        Log.e("socketCheck", "disconnect()")
        shouldReconnect = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    interface SocketListener {
        fun onMessage(message: String)
        fun onBinaryMessage(bytes: ByteArray) // Added to handle bytes
        fun onOpen()
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Log.e("socketCheck", "onOpen()")
            socketListener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            socketListener?.onMessage(text)
        }

        // Handle incoming binary messages
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            socketListener?.onBinaryMessage(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.e("socketCheck", "onClosing()")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.e("socketCheck", "onClosed()")

            if (webSocket != this@WebSocketClient.webSocket) {
                Log.e("socketCheck", "Ignoring onClosed from old socket")
                return
            }

            if (shouldReconnect) {
                waitAndReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("socketCheck", "onFailure() : ${t.localizedMessage}")

            if (webSocket != this@WebSocketClient.webSocket) {
                Log.e("socketCheck", "Ignoring onFailure from old socket")
                return
            }

            if (shouldReconnect) {
                waitAndReconnect()
            }
        }
    }

    private fun waitAndReconnect() {
        try {
            Thread.sleep(3000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        reconnect()
    }
}