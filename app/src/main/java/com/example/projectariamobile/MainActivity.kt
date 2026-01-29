package com.example.projectariamobile

import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

class MainActivity : AppCompatActivity(), RecognitionListener {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var tts: TextToSpeech

    // Vosk Engines
    private var speechService: SpeechService? = null
    private var voskModelEn: Model? = null
    private var consecutiveVoskFailures = 0
    private val MAX_VOSK_RETRIES = 3

    // State Tracking
    private var confirmationDialog: AlertDialog? = null
    private var pendingGoal: String = ""
    private var isWaitingForConfirmation = false
    private var isVoiceFlowActive = false

    private val webSocketViewModel: WebSocketViewModel by viewModels()

    // Track if we are in "wake mode" or in navigation
    private var isAwaitingWakeWord = true

    // Permission Handler
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startVoiceCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        setupUI()
        setupTTS()
        initVoskModels()
        observeViewModel()
    }

    private fun setupUI() {
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startButton.setOnClickListener { checkPermissionAndStart() }
        stopButton.setOnClickListener { webSocketViewModel.disconnect() }
    }

    private fun initVoskModels() {
        startButton.isEnabled = false

        // Unpack English
        StorageService.unpack(this, "vosk-model-small-en-us-0.15", "model-en",
            { enModel ->
                this.voskModelEn = enModel
                Log.d("VOSK", "English model loaded.")

                // Automatically start listening for "Start" command
                runOnUiThread {
                    startButton.isEnabled = true
                    startBackgroundListening()
                }
            },
            { e ->
                Log.e("VOSK", "English fail: ${e.message}")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Initialization Error")
                        .setMessage("Speech recognition failed to load. Please restart the app.")
                        .setPositiveButton("Retry") { _, _ -> initVoskModels() }
                        .setNegativeButton("Exit") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
        )
    }

    // Put app in "wake mode"
    private fun startBackgroundListening() {
        stopVosk() // Ensure clean state
        isAwaitingWakeWord = true
        isVoiceFlowActive = false

        val model = voskModelEn
        if (model == null) {
            Log.e("VOSK", "Model not loaded, cannot start listening")
            return
        }

        try {
            // Limited grammar for high accuracy wake-word detection
            val grammar = "[\"start\", \"stop\"]"
            val rec = Recognizer(voskModelEn, 16000.0f, grammar)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            Log.d("VOSK", "Listening for wake word...")
        } catch (e: Exception) {
            consecutiveVoskFailures++
            Log.e("VOSK", "Background listen failed (attempt $consecutiveVoskFailures): ${e.message}")

            if (consecutiveVoskFailures >= MAX_VOSK_RETRIES) {
                showVoskErrorDialog()
            }
        }
    }

    private fun showVoskErrorDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Voice Recognition Error")
                .setMessage("Voice recognition is not working. You can still use manual buttons.")
                .setPositiveButton("OK") { _, _ ->
                    consecutiveVoskFailures = 0
                }
                .show()
        }
    }

    private fun setupTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(p0: String?) {}
                    override fun onError(p0: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            when (utteranceId) {
                                "GOAL_PROMPT", "CONFIRM_PROMPT" -> {
                                    // Post a delay of 100ms before starting the mic
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        if (!tts.isSpeaking) { // Extra safety check
                                            startVoskListening()
                                        }
                                    }, 100)
                                }
                            }
                        }
                    }
                })
            }
            else {
                Log.e("TTS", "TTS initialization failed")
            }
        }
    }

    private fun startVoskListening() {
        stopVosk()
        isAwaitingWakeWord = false // Switch to navigation mode
        val modelToUse = voskModelEn

        if (modelToUse == null) {
            Log.e("VOSK", "Model not ready")
            return
        }

        try {
            val grammar = "[\"yes\", \"no\", \"r one\", \"r two\",\"r three\", \"r four\", \"r one b\", \"r two b\", \"r three b\", \"one\",\"two\",\"three\",\"four\",\"b\", \"r four b\", \"study room\", \"study\", \"room\", \"exit\"]"
            val rec = Recognizer(modelToUse, 16000.0f, grammar)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
        } catch (e: Exception) {
            resetVoiceFlow()
        }
    }

    // --- Vosk Callbacks ---
    override fun onResult(hypothesis: String) {
        val text = JSONObject(hypothesis).optString("text", "")
        if (text.isEmpty()) {
            // If we heard nothing, we wait
            return
        }
        if (text == "stop" && webSocketViewModel.isSocketConnected.value == true){
            stopVosk()
            webSocketViewModel.disconnect()
            tts.speak("Disconnected, stopping...", TextToSpeech.QUEUE_FLUSH, null, null)
            startBackgroundListening()
            return
        }

        if (isAwaitingWakeWord){
            if (text == "start") {
                stopVosk()
                runOnUiThread { startVoiceCapture() }
            }
            // Else keep listening waiting for start
        }
        else {
            stopVosk()
            runOnUiThread {
                if (isWaitingForConfirmation) {
                    handleYesNoResponse(text)
                } else {
                    handleGoalSelection(text)
                }
            }
        }
    }

    override fun onPartialResult(p0: String?) {}
    override fun onFinalResult(p0: String?) {}
    override fun onError(e: Exception?) { resetVoiceFlow() }
    override fun onTimeout() { resetVoiceFlow() }
    override fun onPause() {
        super.onPause()
        if (isAwaitingWakeWord && !isVoiceFlowActive) {
            stopVosk() // Stop wake word listening to save battery
        }
    }
    override fun onResume() {
        super.onResume()
        if (isAwaitingWakeWord && !isVoiceFlowActive && speechService == null) {
            startBackgroundListening()
        }
    }

    // --- Voice Logic Flow ---

    private fun handleGoalSelection(goal: String) {
        // Dismiss any existing dialog first
        confirmationDialog?.dismiss()

        pendingGoal = goal
        isWaitingForConfirmation = true

        confirmationDialog = AlertDialog.Builder(this)
            .setTitle("Confirm Goal")
            .setMessage("Go to: $goal?")
            .setPositiveButton("Yes") { _, _ -> finalizeGoal() }
            .setNegativeButton("No") { _, _ -> startVoiceCapture() }
            .setOnCancelListener { resetVoiceFlow() }
            .create()

        confirmationDialog?.show()
        tts.speak("You said $goal. Is this correct?", TextToSpeech.QUEUE_FLUSH, null, "CONFIRM_PROMPT")
    }

    private fun handleYesNoResponse(response: String) {
        val clean = response.lowercase()
        when {
            clean.contains("yes") -> {
                confirmationDialog?.dismiss()
                finalizeGoal()
            }
            clean.contains("no") -> {
                confirmationDialog?.dismiss()
                startVoiceCapture()
            }
            clean.contains("cancel") || clean.contains("stop") -> {
                confirmationDialog?.dismiss()
                resetVoiceFlow()
            }
            else -> {
                tts.speak("Please say yes, no, or cancel.", TextToSpeech.QUEUE_FLUSH, null, "CONFIRM_PROMPT")
            }
        }
    }

    private fun finalizeGoal() {
        // stopVosk()
        Log.d("VOICE_FLOW", "Finalizing goal: $pendingGoal")
        isVoiceFlowActive = false
        isWaitingForConfirmation = false

        val dataCommand = mapSpeechToCommand(pendingGoal)
        tts.speak("Confirmed, going to $dataCommand. Connecting.", TextToSpeech.QUEUE_FLUSH, null, null)

        webSocketViewModel.setDestination(dataCommand)

        lifecycleScope.launch {
            webSocketViewModel.connect()

            val status = webSocketViewModel.connectionStatus.first { it is ConnectionStatus.CONNECTED || it is ConnectionStatus.FAILED }

            when (status) {
                ConnectionStatus.CONNECTED -> {
                    Log.d("WEBSOCKET", "Connected successfully")
                    runOnUiThread {
                        tts.speak("Connected. Navigation started.", TextToSpeech.QUEUE_FLUSH, null, null)
                        startBackgroundListening()
                    }
                }
                is ConnectionStatus.FAILED -> {
                    Log.e("WEBSOCKET", "Connection failed")
                    runOnUiThread {
                        tts.speak("Connection failed.", TextToSpeech.QUEUE_FLUSH, null, null)
                        showConnectionFailureDialog()
                    }
                }
                else -> { /* Ignore other states like CONNECTING */ }
            }
        }
    }

    private fun showConnectionFailureDialog() {
        AlertDialog.Builder(this)
            .setTitle("Connection Failed")
            .setMessage("Could not connect to navigation service. Try again?")
            .setPositiveButton("Retry") { _, _ ->
                finalizeGoal()  // Retry with same destination
            }
            .setNegativeButton("Choose Different Destination") { _, _ ->
                startVoiceCapture()  // Start over
            }
            .setOnCancelListener {
                resetVoiceFlow()  // Cancel and go to wake word mode
            }
            .show()
    }
    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startVoiceCapture()
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceCapture() {
        if (webSocketViewModel.isSocketConnected.value || webSocketViewModel.isConnecting()) {
            Log.d("VOICE_FLOW", "Ignoring start command: already connected.")
            return
        }

        isVoiceFlowActive = true
        isAwaitingWakeWord = false
        startButton.isEnabled = false
        stopButton.isEnabled = false
        isWaitingForConfirmation = false
        tts.speak("Where do you want to go?", TextToSpeech.QUEUE_FLUSH, null, "GOAL_PROMPT")
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            webSocketViewModel.isSocketConnected.collect { isConnected ->
                if (!isVoiceFlowActive) {
                    startButton.isEnabled = !isConnected
                    stopButton.isEnabled = isConnected
                }
            }
        }
    }

    private fun stopVosk() {
        speechService?.stop()
        speechService = null
    }

    private fun resetVoiceFlow() {
        runOnUiThread {
            tts.stop()
            stopVosk()
            isVoiceFlowActive = false
            isWaitingForConfirmation = false
            confirmationDialog?.dismiss()
            val isConnected = webSocketViewModel.isSocketConnected.value ?: false
            startButton.isEnabled = !isConnected
            stopButton.isEnabled = isConnected

            // Go back to listening for "Start"
            startBackgroundListening()
        }
    }

    private fun mapSpeechToCommand(text: String): String {
        return when (text.lowercase().trim()) {
            "r one" -> "r1"
            "r two" -> "r2"
            "r three" -> "r3"
            "r four" -> "r4"
            "r one b" -> "r1b"
            "r two b" -> "r2b"
            "r three b" -> "r3b"
            "r four b" -> "r4b"
            else -> text.replace(" ", "") // Fallback: remove spaces
        }
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}