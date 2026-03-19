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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity(), RecognitionListener {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var tts: TextToSpeech

    // Vosk engines
    private var speechService: SpeechService? = null
    private var voskModelEn: Model? = null
    private var consecutiveVoskFailures = 0
    private val MAX_VOSK_RETRIES = 3

    // State tracking
    private var confirmationDialog: AlertDialog? = null
    private var pendingGoal: String = ""
    private var isWaitingForConfirmation = false
    private var isVoiceFlowActive = false

//    private val webSocketViewModel: WebSocketViewModel by viewModels()
    private val webSocketViewModel: WebSocketViewModel by viewModels()

    private var isAwaitingWakeWord = true

    // Permission handler
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startVoiceCapture()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        if (OpenCVLoader.initLocal()) {
            Log.i("OpenCV", "OpenCV loaded successfully")
        } else {
            Log.e("OpenCV", "OpenCV initialization failed")
        }

        setupUI()
        setupTTS()
        initVoskModels()
        observeViewModel()
    }

    override fun onPause() {
        super.onPause()
        if (isAwaitingWakeWord && !isVoiceFlowActive) stopVosk()
    }

    override fun onResume() {
        super.onResume()
        if (isAwaitingWakeWord && !isVoiceFlowActive && speechService == null) {
            startBackgroundListening()
        }
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupUI() {
        startButton = findViewById(R.id.startButton)
        stopButton  = findViewById(R.id.stopButton)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startButton.setOnClickListener { checkPermissionAndStart() }
        stopButton.setOnClickListener  { handleStop(StopReason.MANUAL_STOP) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vosk
    // ─────────────────────────────────────────────────────────────────────────

    private fun initVoskModels() {
        startButton.isEnabled = false
        StorageService.unpack(this, "vosk-model-small-en-us-0.15", "model-en",
            { enModel ->
                voskModelEn = enModel
                Log.d("VOSK", "English model loaded.")
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
                        .setNegativeButton("Exit")  { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
        )
    }

    private fun startBackgroundListening() {
        Log.d("LISTENER", "Waiting for start/stop command")
        stopVosk()
        isAwaitingWakeWord = true
        isVoiceFlowActive  = false

        val model = voskModelEn ?: run {
            Log.e("VOSK", "Model not loaded, cannot start listening"); return
        }

        try {
            val grammar = "[\"start\", \"stop\", \"cancel\"]"
            val rec     = Recognizer(model, 16000.0f, grammar)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            Log.d("VOSK", "Listening for wake word...")
        } catch (e: Exception) {
            consecutiveVoskFailures++
            Log.e("VOSK", "Background listen failed (attempt $consecutiveVoskFailures): ${e.message}")
            if (consecutiveVoskFailures >= MAX_VOSK_RETRIES) showVoskErrorDialog()
        }
    }

    private fun showVoskErrorDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Voice Recognition Error")
                .setMessage("Voice recognition is not working. You can still use manual buttons.")
                .setPositiveButton("OK") { _, _ -> consecutiveVoskFailures = 0 }
                .show()
        }
    }

    private fun startVoskListening() {
        stopVosk()
        isAwaitingWakeWord = false
        val modelToUse = voskModelEn ?: run { Log.e("VOSK", "Model not ready"); return }

        try {
            val grammar = "[\"yes\", \"no\", \"r one\", \"r two\", \"r three\", \"r four\", " +
                    "\"r one b\", \"r two b\", \"r three b\", " +
                     "\"r four b\", \"study room r one\", \"study room r two\", \"study\", \"room\", \"exit\"]"
            val rec = Recognizer(modelToUse, 16000.0f, grammar)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
        } catch (e: Exception) { resetVoiceFlow() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTS
    // ─────────────────────────────────────────────────────────────────────────

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
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({
                                            if (!tts.isSpeaking) startVoskListening()
                                        }, 100)
                                }
                            }
                        }
                    }
                })
            } else {
                Log.e("TTS", "TTS initialization failed")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vosk callbacks
    // ─────────────────────────────────────────────────────────────────────────

    override fun onResult(hypothesis: String) {
        val text = JSONObject(hypothesis).optString("text", "")
        if (text.isEmpty()) return

        if (text == "cancel") { stopVosk(); startBackgroundListening(); return }

        if (text == "stop" && webSocketViewModel.isSocketConnected.value) {
            handleStop(StopReason.MANUAL_STOP)
            return
        }

        if (isAwaitingWakeWord) {
            if (text == "start") { stopVosk(); runOnUiThread { startVoiceCapture() } }
        } else {
            stopVosk()
            runOnUiThread {
                if (isWaitingForConfirmation) handleYesNoResponse(text)
                else handleGoalSelection(text)
            }
        }
    }

    override fun onPartialResult(p0: String?) {}
    override fun onFinalResult(p0: String?)   {}
    override fun onError(e: Exception?)        { resetVoiceFlow() }
    override fun onTimeout()                   { resetVoiceFlow() }

    // ─────────────────────────────────────────────────────────────────────────
    // Voice flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleGoalSelection(goal: String) {
        confirmationDialog?.dismiss()
        pendingGoal              = goal
        isWaitingForConfirmation = true

        confirmationDialog = AlertDialog.Builder(this)
            .setTitle("Confirm Goal")
            .setMessage("Go to: ${mapSpeechToCommand(goal)}?")
            .setPositiveButton("Yes") { _, _ -> finalizeGoal() }
            .setNegativeButton("No")  { _, _ -> startVoiceCapture() }
            .setOnCancelListener     { resetVoiceFlow() }
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
            clean.contains("cancel") || (clean.contains("stop") && !tts.isSpeaking) -> {
                confirmationDialog?.dismiss()
                resetVoiceFlow()
            }
            else -> tts.speak(
                "Please say yes, no, or cancel.",
                TextToSpeech.QUEUE_FLUSH, null, "CONFIRM_PROMPT"
            )
        }
    }

    private fun finalizeGoal() {
        stopVosk()
        Log.d("VOICE_FLOW", "Finalizing goal: $pendingGoal")
        isVoiceFlowActive        = false
        isWaitingForConfirmation = false

        val dataCommand = mapSpeechToCommand(pendingGoal)
        tts.speak("Confirmed, going to $dataCommand. Connecting to glasses, please wait.", TextToSpeech.QUEUE_FLUSH, null, null)

        webSocketViewModel.startNavigation(dataCommand.lowercase())

        lifecycleScope.launch {
            webSocketViewModel.connect()
            val status = webSocketViewModel.connectionStatus.first {
                it is ConnectionStatus.CONNECTED || it is ConnectionStatus.FAILED
            }
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
                else -> { }
            }
        }
    }

    private fun showConnectionFailureDialog() {
        AlertDialog.Builder(this)
            .setTitle("Connection Failed")
            .setMessage("Could not connect to navigation service. Try again?")
            .setPositiveButton("Retry")  { _, _ -> finalizeGoal() }
            .setNegativeButton("Choose Different Destination") { _, _ -> startVoiceCapture() }
            .setOnCancelListener { resetVoiceFlow() }
            .show()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
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
        isVoiceFlowActive        = true
        isAwaitingWakeWord       = false
        startButton.isEnabled    = false
        stopButton.isEnabled     = false
        isWaitingForConfirmation = false
        tts.speak("Where do you want to go?", TextToSpeech.QUEUE_FLUSH, null, "GOAL_PROMPT")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ViewModel observation
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            webSocketViewModel.isSocketConnected.collect { isConnected ->
                if (!isVoiceFlowActive) {
                    startButton.isEnabled = !isConnected
                    stopButton.isEnabled  = isConnected
                }
            }
        }

        lifecycleScope.launch {
            webSocketViewModel.navigationEvents.collect { event ->
                when (event) {
                    is NavigationEvent.Speak -> {
                        tts.speak(event.message, TextToSpeech.QUEUE_ADD, null, null)
                    }

                    is NavigationEvent.StopNavigation -> {
                        Log.d("APP", "STOPPING NAVIGATION")

                        tts.speak("Destination found, ${mapDestinationCommand(webSocketViewModel.destination)}", TextToSpeech.QUEUE_FLUSH, null, null)
                        // Disconnect but do NOT call handleStop here — report already triggered
                        // by emitIfAllowed via saveReport(DESTINATION_FOUND)
                        stopVosk()
                        webSocketViewModel.disconnect()
                        startBackgroundListening()
                    }

                    is NavigationEvent.ReportReady -> {
                        // Notify user that a report was saved
                        Log.i("Report", "Report ready at: ${event.filePath}")
                        showReportSavedDialog(event.filePath)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stop & report
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Central stop handler. Always saves the report before tearing down.
     * [reason] is MANUAL_STOP when triggered by the button, DESTINATION_FOUND
     * when triggered automatically — in that case the report has already been
     * saved by emitIfAllowed, so we skip the extra call.
     */
    private fun handleStop(reason: StopReason = StopReason.MANUAL_STOP) {
        stopVosk()
        // Only save explicitly on manual stop; arrival saves its own report
        if (reason == StopReason.MANUAL_STOP) {
            webSocketViewModel.saveReport(StopReason.MANUAL_STOP)
        }
        webSocketViewModel.disconnect()
        tts.speak("Disconnected, stopping.", TextToSpeech.QUEUE_ADD, null, null)
        startBackgroundListening()
    }

    private fun showReportSavedDialog(filePath: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Session Report Saved")
                .setMessage("Report saved to:\n$filePath")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun resetVoiceFlow() {
        runOnUiThread {
            tts.stop()
            stopVosk()
            isVoiceFlowActive        = false
            isWaitingForConfirmation = false
            confirmationDialog?.dismiss()
            val isConnected       = webSocketViewModel.isSocketConnected.value
            startButton.isEnabled = !isConnected
            stopButton.isEnabled  = isConnected
            startBackgroundListening()
        }
    }

    private fun stopVosk() {
        speechService?.stop()
        speechService = null
    }
    private fun mapDestinationCommand(dest : String) : String{
        return when (dest){
            "exit" -> "Exit reached, you can leave now."
            else -> "You are in front of $dest"
        }
    }

    private fun mapSpeechToCommand(text: String): String {
        return when (text.lowercase().trim()) {
            "r one"   -> "r1"
            "r two"   -> "r2"
            "r three" -> "r3"
            "r four"  -> "r4"
            "r one b" -> "r1b"
            "r two b" -> "r2b"
            "r three b" -> "r3b"
            "r four b"  -> "r4b"
            "study room r one" -> "sala studio R1"
            "study room r two" -> "sala studio R2"
            else -> text.replace(" ", "")
        }
    }
}