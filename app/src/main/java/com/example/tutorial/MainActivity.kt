package com.example.tutorial

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch




class MainActivity : AppCompatActivity() {
    private lateinit var startButton : Button
    private lateinit var stopButton : Button
    private val webSocketViewModel: WebSocketViewModel by viewModels()

    // val detector = YoloDetector(this, "assets/yolov11n_int8.tflite", "labels.txt")



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
            webSocketViewModel.connect()
        }

        stopButton.setOnClickListener {
            webSocketViewModel.disconnect()
        }

        lifecycleScope.launch {
            // This runs every time _isSocketConnected changes
            // AND immediately when the app starts/rotates
            webSocketViewModel.isSocketConnected.collect { isConnected: Boolean ->
                startButton.isEnabled = !isConnected
                stopButton.isEnabled = isConnected
            }
        }

    }
}