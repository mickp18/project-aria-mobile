package com.example.tutorial

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
class MainActivity : AppCompatActivity() {
    private lateinit var startButton : Button
    private lateinit var stopButton : Button
    private lateinit var webSocketClient: WebSocketClient


    private val socketListener = object : WebSocketClient.SocketListener {
        override fun onMessage(message: String) {
            Log.e("socketCheck onMessage", message)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        stopButton.isEnabled = false


        startButton.setOnClickListener {
            startButton.isEnabled = false
            stopButton.isEnabled = true
            webSocketClient = WebSocketClient.getInstance()
            // TODO: REPLACE WITH ACTUAL IP ADDRESS OF YOUR DEVICE
            //  webSocketClient.setSocketUrl("ws://10.0.2.2:8080")
            //  webSocketClient.setSocketUrl("ws://10.42.0.1:8080") //10.42.0.1
            webSocketClient.setSocketUrl("wss://192.168.1.68:8080") //192.168.1.68
            webSocketClient.setListener(socketListener)
            webSocketClient.connect()
        }

        stopButton.setOnClickListener{
            startButton.isEnabled = true
            stopButton.isEnabled = false
            webSocketClient.disconnect()

        }

    }
}