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
    @Volatile private var webSocket: WebSocket? = null
    private var socketListener: SocketListener? = null
    private var socketUrl = ""

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)   // Give it a bit more breathing room (10s)
        .connectTimeout(10, TimeUnit.SECONDS)
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

    fun connect() {
        Log.e("socketCheck", "connect()")

        // Cancel OLD attempts only when starting a NEW one
        client.dispatcher.cancelAll()

        val request = Request.Builder().url(url = socketUrl).build()
        webSocket = client.newWebSocket(request, webSocketListener)
    }

    fun sendMessage(message: String) {
        Log.e("socketCheck", "sendMessage($message)")
        webSocket?.send(message)
    }

    fun disconnect() {
        Log.e("socketCheck", "disconnect()")

        // 1. Send the stop command
        webSocket?.send("stop")

        // 2. Close gracefully (Wait for server to acknowledge)
        // This flushes the "stop" message before closing.
        val closed = webSocket?.close(1000, "User disconnected")

        if (closed != true) {
            // Only cancel if close() failed to initiate (e.g. already closed)
            webSocket?.cancel()
        }

        webSocket = null
    }

    interface SocketListener {
        fun onMessage(message: String)
        fun onBinaryMessage(bytes: ByteArray)
        fun onOpen()
        fun onError(error: String)
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socketListener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            socketListener?.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            socketListener?.onBinaryMessage(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("socketCheck", "onFailure() : ${t.localizedMessage}")
            socketListener?.onError(t.localizedMessage ?: "Connection Error")

            // It's safe to cancel here because the connection is already broken
            webSocket.cancel()
            if (this@WebSocketClient.webSocket == webSocket) {
                this@WebSocketClient.webSocket = null
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.e("socketCheck", "onClosed() : $reason")
            // Connection closed cleanly
        }
    }
}