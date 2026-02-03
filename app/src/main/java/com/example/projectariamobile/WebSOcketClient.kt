package com.example.projectariamobile

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketClient {
    @Volatile private var webSocket: WebSocket? = null
    private var socketListener: SocketListener? = null
    private var socketUrl = ""

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)      // No timeout for streaming
        .connectTimeout(10, TimeUnit.SECONDS)       // Initial connection timeout
        .pingInterval(20, TimeUnit.SECONDS)         // Keep-alive ping every 20s
        .retryOnConnectionFailure(true)    // Auto-retry on network issues
        .callTimeout(0, TimeUnit.MILLISECONDS)      // No call timeout
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
        Log.d("WebSocketClient", "Listener set")
    }

    fun setSocketUrl(socketUrl: String) {
        this.socketUrl = socketUrl
        Log.d("WebSocketClient", "Socket URL set to: $socketUrl")
    }

    fun connect() {
        Log.i("WebSocketClient", "Connecting to: $socketUrl")

        // Cancel old connections before starting new one
        client.dispatcher.cancelAll()

        val request = Request.Builder().url(url = socketUrl).build()
        webSocket = client.newWebSocket(request, webSocketListener)
    }

    fun sendMessage(message: String) {
        val sent = webSocket?.send(message) ?: false
        if (sent) {
            Log.d("WebSocketClient", "Sent: $message")
        } else {
            Log.e("WebSocketClient", "Failed to send: $message (not connected)")
        }
    }

    fun disconnect() {
        Log.i("WebSocketClient", "Disconnecting...")

        // Send stop command if connected
        webSocket?.send("stop")

        // Close gracefully with normal closure code
        val closed = webSocket?.close(1000, "User disconnected")

        if (closed != true) {
            // Force close if graceful close failed
            webSocket?.cancel()
        }

        webSocket = null
    }

    fun isConnected(): Boolean {
        return webSocket != null
    }

    interface SocketListener {
        fun onMessage(message: String)
        fun onBinaryMessage(bytes: ByteArray)
        fun onOpen()
        fun onError(error: String)
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i("WebSocketClient", "✓ Connection opened on thread: ${Thread.currentThread().name}")
            socketListener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("WebSocketClient", "Text message received: $text")
            socketListener?.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val msgSize = bytes.size / 1024.0
            Log.d("WebSocketClient", "Binary message: ${String.format("%.1f", msgSize)}KB")

            // Pass to listener immediately - should take < 1ms
            socketListener?.onBinaryMessage(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("WebSocketClient", "✗ Connection failed: ${t.message}", t)
            socketListener?.onError(t.message ?: "Connection Error")

            // Clean up the failed connection
            webSocket.cancel()
            if (this@WebSocketClient.webSocket == webSocket) {
                this@WebSocketClient.webSocket = null
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i("WebSocketClient", "Connection closed: $code - $reason")
            if (this@WebSocketClient.webSocket == webSocket) {
                this@WebSocketClient.webSocket = null
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i("WebSocketClient", "Connection closing: $code - $reason")
            webSocket.close(1000, null)
        }
    }
}