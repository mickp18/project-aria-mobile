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
    private var voskModelIt: Model? = null

    // State Tracking
    private var confirmationDialog: AlertDialog? = null
    private var pendingGoal: String = ""
    private var isWaitingForConfirmation = false
    private var isVoiceFlowActive = false

    private val webSocketViewModel: WebSocketViewModel by viewModels()

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
        initVoskModels() // Sequential loading of En and It
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
        startButton.isEnabled = false // Wait for models

        // 1. Unpack English
        StorageService.unpack(this, "vosk-model-small-en-us-0.15", "model-en",
            { enModel ->
                this.voskModelEn = enModel
                Log.d("VOSK", "English model loaded.")

                // 2. Unpack Italian
//                StorageService.unpack(this, "vosk-model-small-it-0.22", "model-it",
//                    { itModel ->
//                        this.voskModelIt = itModel
//                        Log.d("VOSK", "Both models ready.")
//                        runOnUiThread { startButton.isEnabled = true }
//                    },
//                    { e -> Log.e("VOSK", "Italian fail: ${e.message}") }
//                )
            },
            { e -> Log.e("VOSK", "English fail: ${e.message}") }
        )
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
                                    // Post a delay of 500ms before starting the mic
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        if (!tts.isSpeaking) { // Extra safety check
                                            startVoskListening()
                                        }
                                    }, 500)
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    private fun startVoskListening() {
        // Defaulting to English model for now.
        // You can pass voskModelIt here if you want to listen for Italian destinations.
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
        // SHUT DOWN THE MIC IMMEDIATELY
        stopVosk()


        val text = JSONObject(hypothesis).optString("text", "")
        if (text.isEmpty()) {
            // If we heard nothing, we need to decide if we re-enable
            // the mic or just wait. For now, let's just log it.
            return
        }

        // Process the text only after the mic is confirmed off
        runOnUiThread {
            if (isWaitingForConfirmation) {
                handleYesNoResponse(text)
            } else {
                handleGoalSelection(text)
            }
        }
    }

    override fun onPartialResult(p0: String?) {}
    override fun onFinalResult(p0: String?) {}
    override fun onError(e: Exception?) { resetVoiceFlow() }
    override fun onTimeout() { resetVoiceFlow() }

    // --- Voice Logic Flow ---

    private fun handleGoalSelection(goal: String) {
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
        // Bilingual check
        if (clean.contains("yes") || clean.contains("si") || clean.contains("sì")) {
            confirmationDialog?.dismiss()
            finalizeGoal()
        } else if (clean.contains("no")) {
            confirmationDialog?.dismiss()
            startVoiceCapture()
        } else {
            tts.speak("Please say yes or no.", TextToSpeech.QUEUE_FLUSH, null, "CONFIRM_PROMPT")
        }
    }

    private fun finalizeGoal() {
        isVoiceFlowActive = false
        isWaitingForConfirmation = false

        val dataCommand = mapSpeechToCommand(pendingGoal)

        tts.speak("Confirmed, going to $dataCommand. Connecting.", TextToSpeech.QUEUE_FLUSH, null, null)

        webSocketViewModel.setDestination(dataCommand)
        webSocketViewModel.connect()
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
        isVoiceFlowActive = true
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
            stopVosk()
            isVoiceFlowActive = false
            isWaitingForConfirmation = false
            confirmationDialog?.dismiss()
            val isConnected = webSocketViewModel.isSocketConnected.value
            startButton.isEnabled = !isConnected
            stopButton.isEnabled = isConnected
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