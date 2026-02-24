package com.example.projectariamobile

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────────────────────
// Data types
// ─────────────────────────────────────────────────────────────────────────────

data class Detection(
    var label     : String          = "",
    var text      : String          = "",
    var confidence: Float           = 0f,
    var bbox      : android.graphics.RectF = android.graphics.RectF(),
    var timeStamp : Long            = System.currentTimeMillis()
)

/**
 * Carries everything needed to speak a command and trigger side-effects.
 * buildInstruction() returns null when there is nothing to say.
 */
data class Instruction(
    val text      : String,
    val direction : Direction? = null,  // non-null → start compliance tracking
    val shouldStop: Boolean    = false  // true → emit StopNavigation after speaking
)

sealed class NavigationEvent {
    data class Speak(val message: String) : NavigationEvent()
    object StopNavigation : NavigationEvent()
}

sealed class ConnectionStatus {
    object DISCONNECTED : ConnectionStatus()
    object CONNECTING   : ConnectionStatus()
    object CONNECTED    : ConnectionStatus()
    data class FAILED(val error: String) : ConnectionStatus()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

class WebSocketViewModel(application: Application) : AndroidViewModel(application) {

    // ── External clients ────────────────────────────────────────────────────
    private val webSocketClient = WebSocketClient.getInstance()
    private lateinit var navTracker: NavigationTracker   // phone-IMU based tracker

    // ── Public state ─────────────────────────────────────────────────────────
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    private val _connectionStatus =
        MutableStateFlow<ConnectionStatus>(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _messages = MutableStateFlow("")

    private val _frameStats = MutableStateFlow("")
    val frameStats: StateFlow<String> = _frameStats.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    // ── ML components ────────────────────────────────────────────────────────
    val yoloDetector = YoloDetector(
        confidenceThreshold = 0.5f,
        iouThreshold = 0.3f,
        numThreads = 2,
        maxResults = 3,
        currentDelegate = 0,
        context = application
    )
    private val textRecognizer = TextRecognitionProcessor(application)

    // ── Navigation state ─────────────────────────────────────────────────────
    var destination: String = ""
    var lastInstruction: String = ""
    var lastInstructionTime: Long = 0L
    private var isStopping = AtomicBoolean(false)

    // Timeout / confidence
    private var lastSignTime = 0L
    private var lastTimeoutGuidanceTime = 0L
    private var navigationConfidence = 1.0f

    // Side-sign hint rate-limiting
    private var lastSideDetectionTime = 0L

    // ── Frame stats ──────────────────────────────────────────────────────────
    private var frameCount = 0
    private var droppedFrames = 0
    private var lastFrameTime = 0L
    private var firstFrameTime = 0L
    private var totalFrames = 0
    private var lastStatsTime = SystemClock.uptimeMillis()
    private val isProcessing = AtomicBoolean(false)

    // ── Constants ────────────────────────────────────────────────────────────
    private val SPEECH_COOLDOWN = 5000L   // min gap between any two spoken commands
    private val OLD_SIGN_COOLDOWN = 10_000L   // same instruction may repeat after this
    private val SIDE_COOLDOWN = 8_000L   // rate-limit for "sign on your side" hints
    private val PROXIMITY_MIN_AREA_EXIT = 0.001f   // bbox must be ≥ 1.8 % of frame area
    private val PROXIMITY_MIN_AREA_ROOMS = 0.005f
    private val DISTORTION_CONF = 0.65f    // confidence below which distortion is checked

    // ─────────────────────────────────────────────────────────────────────────
    // init — wire WebSocket listener
    // ─────────────────────────────────────────────────────────────────────────
    init {
        navTracker = NavigationTracker(application)

        webSocketClient.setListener(object : WebSocketClient.SocketListener {

            override fun onMessage(message: String) {
                _messages.value = "New Message: $message"
                try {
                    val json = JSONObject(message)
                    if (json.optString("type") == "STATUS_UPDATE") {
                        val payload = json.getJSONObject("payload")
                        if (payload.optString("status") == "stopped") {
                            _isSocketConnected.value = false
                            _connectionStatus.value = ConnectionStatus.DISCONNECTED
                            webSocketClient.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onBinaryMessage(bytes: ByteArray) {
                val receiveTime = SystemClock.uptimeMillis()
                frameCount++; totalFrames++

                if (isProcessing.get()) {
                    droppedFrames++; updateStats(); return
                }
                if (firstFrameTime == 0L) firstFrameTime = receiveTime
                lastFrameTime = receiveTime

                viewModelScope.launch(Dispatchers.Default) {
                    if (isProcessing.compareAndSet(false, true)) {
                        try {
                            processFrame(bytes)
                        } catch (e: Throwable) {
                            Log.e("Frame", e.localizedMessage ?: "", e)
                        } finally {
                            isProcessing.set(false); updateStats()
                        }
                    }
                }
            }

            override fun onOpen() {
                _isSocketConnected.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED
                frameCount = 0; droppedFrames = 0; totalFrames = 0
                lastFrameTime = 0L; firstFrameTime = 0L
                lastStatsTime = SystemClock.uptimeMillis()
            }

            override fun onError(error: String) {
                _isSocketConnected.value = false
                _connectionStatus.value = ConnectionStatus.FAILED(error)
            }
        })
    }

    // =========================================================================
    //  FRAME PIPELINE
    // =========================================================================

    /**
     * Top-level orchestrator. Steps are numbered to match the design doc.
     */
    private suspend fun processFrame(bytes: ByteArray) {
        // 1. Decode
        val t0 = SystemClock.uptimeMillis()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

        try {
            // 2. YOLO
            val yoloStart = SystemClock.uptimeMillis()
            val results = yoloDetector.detect(bitmap, 0)
            Log.i(
                "YOLO",
                "${results.detections.size} detections in ${SystemClock.uptimeMillis() - yoloStart}ms"
            )

            // 3. Nothing found → only timeout check
            if (results.detections.isEmpty()) {
                checkForTimeout()
                return
            }

            // 4. Qualify each detection
            var pendingHint: String? = null
            val qualified = qualifyDetections(results.detections, bitmap) { hint ->
                if (pendingHint == null) pendingHint = hint   // keep first/best hint
            }

            // 5. Nothing survived qualification
            if (qualified.isEmpty()) {
                pendingHint?.let { emitIfAllowed(Instruction(it)) }
                checkForTimeout()
                return
            }

            // 6. Pick best among qualified
            val best = pickBest(qualified) ?: run { checkForTimeout(); return }

            // 7. Movement gate — same sign, user hasn't moved?
//            if (!navTracker.shouldProcessDetection(best.label)) {
//                Log.d("Pipeline", "Movement gate blocked ${best.label}")
//                checkForTimeout()
//                return
//            }

            // 8. Build instruction (pure, no side-effects)
            val instruction = buildInstruction(best) ?: run { checkForTimeout(); return }

            // 9. Speech gate + emit
            emitIfAllowed(instruction)

            // Keep track of last useful sign time (for timeout guidance)
            if (isSignPossibleTarget(best.label)) {
                lastSignTime = SystemClock.uptimeMillis()
                navigationConfidence = 1.0f
            }

        } finally {
            bitmap.recycle()
            // 10. Timeout — exactly once per frame
            checkForTimeout()
            Log.d("Timing", "Frame in ${SystemClock.uptimeMillis() - t0}ms\n")
            Log.i("space", "----------------------------------------------")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4  qualifyDetections
    //
    // Filters raw YOLO results down to detections that are:
    //   a) a relevant sign type for the current destination
    //   b) big enough on screen (user is close enough)
    //   c) not distorted / side-angled
    //   d) have readable text (or are an exit sign that needs none)
    //
    // Every rejection fires onDisqualified(hint) so the caller can collect a
    // single "get closer" message without emitting it directly from here.
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun qualifyDetections(
        raw: List<ObjectDetection>,
        bitmap: Bitmap,
        onDisqualified: (hint: String) -> Unit
    ): List<Detection> {

        val frameArea = (bitmap.width * bitmap.height).toFloat()
        val qualified = mutableListOf<Detection>()

        for (r in raw) {
            val label = r.category.label.lowercase()
            val confidence = r.category.confidence
            val bbox = r.boundingBox
            val cropRect = Rect(
                bbox.left.toInt(), bbox.top.toInt(),
                bbox.right.toInt(), bbox.bottom.toInt()
            )

            // 4a — relevant for current destination?
            if (!isSignPossibleTarget(label)) continue   // silent — not a user-facing event
            val isExit = (label == "exit_left" || label == "exit_right")
            // 4b — proximity: is the sign close enough to read?
            val area = (bbox.width() * bbox.height()) / frameArea
            Log.d("Area", "bitmap area: $frameArea, label area : $area")
            if ((area < PROXIMITY_MIN_AREA_EXIT && isExit) || (area < PROXIMITY_MIN_AREA_ROOMS && !isExit)) {
                Log.d("Qualify", "$label too small (${String.format("%.3f", area)})")
                onDisqualified("There's a sign ahead but you're too far. Move closer to read it.")
                continue
            }

            // 4c — distortion check (only worth paying for when confidence is low)
            if (confidence < DISTORTION_CONF && DistortionChecker.isSignDistorted(
                    bitmap,
                    cropRect
                )
            ) {
                val side = getSignPosition(bitmap, cropRect)
                val hint = if (side.isNotEmpty())
                    "There's a sign on your $side. Turn to face it for a better reading."
                else
                    "A sign is at an angle. Move closer and face it directly."
                Log.d("Qualify", "$label distorted, side=$side")

                // Rate-limit the side-hint so it isn't spammed
                val now = SystemClock.uptimeMillis()
                if (now - lastSideDetectionTime > SIDE_COOLDOWN) {
                    lastSideDetectionTime = now
                    onDisqualified(hint)
                }
                continue
            }

            // 4d — run OCR on text-bearing signs
            val det = Detection(label = label, confidence = confidence, bbox = bbox)
            if (requiresText(label)) {
                val t = SystemClock.uptimeMillis()
                val ocrText = textRecognizer.recognizeTextInBoundingBox(bitmap, cropRect, label)
                Log.i("OCR", "$label → \"$ocrText\" in ${SystemClock.uptimeMillis() - t}ms")
                det.text = ocrText?.lowercase() ?: ""
            }
            // Exit signs qualify without text — fall through

            // 4e — text-requiring sign with unreadable result
            if (requiresText(label) && det.text.isEmpty()) {
                onDisqualified("I can see a sign but can't read it yet. Move a bit closer.")
                continue
            }

            qualified.add(det)
        }

        return qualified
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 6  pickBest
    //
    // Single winner from qualified list.
    // Positive match > high YOLO confidence > larger bbox (= closer).
    // ─────────────────────────────────────────────────────────────────────────

    private fun pickBest(qualified: List<Detection>): Detection? =
        qualified.maxWithOrNull(
            compareBy(
                { if (FuzzyLogic.isMatch(it.text, destination)) 1 else 0 },
                { it.confidence },
                { it.bbox.width() * it.bbox.height() }
            ))

    // ─────────────────────────────────────────────────────────────────────────
    // Step 8  buildInstruction
    //
    // Pure function: Detection → Instruction?
    // Returns null when the detection has nothing actionable to say.
    // Sets Instruction.direction so compliance tracking starts automatically.
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildInstruction(det: Detection): Instruction? {
        val isExitSearch = destination == "exit"
        val match = FuzzyLogic.isMatch(det.text, destination)

        return when (det.label) {

            "room" -> when {
                isExitSearch -> null
                match -> Instruction(
                    text = "You've arrived. $destination is right here.",
                    shouldStop = true
                )

                else -> Instruction("This isn't $destination. Keep looking.")
            }

            "room_direction_left" -> when {
                isExitSearch -> null
                match -> Instruction(
                    text = "Your destination is on the left.",
                    direction = Direction.TURN_LEFT
                )

                else -> Instruction(
                    text = "The destination isn't on the left. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            }

            "room_direction_right" -> when {
                isExitSearch -> null
                match -> Instruction(
                    text = "Your destination is on the right.",
                    direction = Direction.TURN_RIGHT
                )

                else -> Instruction(
                    text = "The destination isn't on the right. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            }

            "exit_left" -> if (isExitSearch) Instruction(
                text = "The nearest exit is on your left.",
                direction = Direction.TURN_LEFT
            ) else null

            "exit_right" -> if (isExitSearch) Instruction(
                text = "The nearest exit is on your right.",
                direction = Direction.TURN_RIGHT
            ) else null

            "stairs" -> when {
                isExitSearch -> null
                match -> Instruction(
                    text = "Your destination is on another floor. Find the stairs ahead.",
                    direction = Direction.STRAIGHT
                )

                else -> Instruction("The destination isn't up these stairs. Keep looking.")
            }

            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 9  emitIfAllowed
    //
    // Single owner of all speech-gate logic.
    // Rules:
    //   • Block if within SPEECH_COOLDOWN of the last utterance.
    //   • Block if text is identical to last instruction AND it hasn't been
    //     long enough (OLD_SIGN_COOLDOWN) to repeat it.
    //   • Otherwise: record, emit, start compliance if direction is set.
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitIfAllowed(instruction: Instruction) {
        if (destination.isEmpty() || isStopping.get()) return

        val now = SystemClock.uptimeMillis()
        val tooRecent = (now - lastInstructionTime) < SPEECH_COOLDOWN
        val sameText = lastInstruction == instruction.text
        val notOldYet = (now - lastInstructionTime) < OLD_SIGN_COOLDOWN
        // CRITICAL: Positive instructions override negative ones
        // Positive = arrival (shouldStop) OR directional (direction != null)
        // Negative = filler like "This isn't R1" or "keep walking"
        val isPositive = instruction.shouldStop || instruction.direction != null
        val lastWasNegative = !lastInstruction.contains("arrived") &&
                !lastInstruction.contains("left") &&
                !lastInstruction.contains("right")
//                              !lastInstruction.contains("ahead")

        // Override cooldown if: this is positive AND last was negative AND within 2s
        val overrideCooldown = isPositive && lastWasNegative &&
                (now - lastInstructionTime) < 2_000L

        if (!overrideCooldown && (tooRecent || (sameText && notOldYet))) {
            Log.d(
                "SpeechGate",
                "Blocked — tooRecent=$tooRecent same=$sameText notOldYet=$notOldYet"
            )
            return
        }
//        if (tooRecent || (sameText && notOldYet)) {
//            Log.d("SpeechGate", "Blocked — tooRecent=$tooRecent same=$sameText notOldYet=$notOldYet")
//            return
//        }

        lastInstruction = instruction.text
        lastInstructionTime = now
        Log.d("SpeechGate", "Emitting: \"${instruction.text}\"  dir=${instruction.direction}")

        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.Speak(instruction.text))

            // Compliance tracking — only for directional instructions
            instruction.direction?.let { dir ->
                navTracker.startTrackingCompliance(dir)
                delay(3_000L)
                checkUserCompliance()
            }

            // Arrival — stop navigation after speaking
            if (instruction.shouldStop) {
                isStopping.set(true)
                lastInstruction = ""
                lastInstructionTime = 0L
                navTracker.resetDetectionHistory()
                _navigationEvents.emit(NavigationEvent.StopNavigation)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compliance check (called from emitIfAllowed coroutine after 3 s delay)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun checkUserCompliance() {
        val c = navTracker.checkCompliance() ?: run { navTracker.stopTrackingCompliance(); return }

        if (c.compliant == false && c.confidence > 0.7f) {
            val correction = when {
                c.expected == Direction.TURN_LEFT && c.actual == Direction.TURN_RIGHT ->
                    "You turned right. The destination is on the LEFT. Turn around."

                c.expected == Direction.TURN_RIGHT && c.actual == Direction.TURN_LEFT ->
                    "You turned left. The destination is on the RIGHT. Turn around."

                c.expected == Direction.TURN_LEFT && c.actual == Direction.STRAIGHT ->
                    "You're going straight. Please turn LEFT."

                c.expected == Direction.TURN_RIGHT && c.actual == Direction.STRAIGHT ->
                    "You're going straight. Please turn RIGHT."

                c.expected == Direction.STRAIGHT && c.actual != Direction.STRAIGHT ->
                    "Continue STRAIGHT ahead, don't turn."

                else ->
                    "Please go ${c.expected} instead of ${c.actual}."
            }
            _navigationEvents.emit(NavigationEvent.Speak(correction))
        }

        navTracker.stopTrackingCompliance()
    }

    // =========================================================================
    //  TIMEOUT GUIDANCE  (unchanged logic, one call per frame)
    // =========================================================================

    private fun checkForTimeout() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTimeoutGuidanceTime < 2_000L) return

        if (lastSignTime == 0L && now > 5_000L) {
            giveTimeoutGuidance(
                "Looking for signs to $destination. Please move forward slowly.",
                now
            )
            return
        }

        val gap = now - lastSignTime
        navigationConfidence = when {
            gap < 30_000L -> 1.0f
            gap < 55_000L -> 0.7f
            gap < 75_000L -> 0.5f
            gap < 95_000L -> 0.3f
            else -> 0.1f
        }

        val msg = when (gap) {
            in 15_000L..30_000L -> "No new signs. Continue forward and look for signs."
            in 35_000L..55_000L -> "Still no signs. Keep moving and scan the walls."
            in 55_000L..75_000L -> "No signs for ${gap / 1000} seconds. Turn slowly to scan the area."
            else -> if (gap > 95_000L) "Consider asking for directions to $destination or turn around and go back to previous sign." else null
        }
        msg?.let { giveTimeoutGuidance(it, now) }
    }

    private fun giveTimeoutGuidance(message: String, now: Long) {
        if (message == lastInstruction) return
        if (now - lastInstructionTime < SPEECH_COOLDOWN) return
        lastInstruction = message
        lastInstructionTime = now
        lastTimeoutGuidanceTime = now
        viewModelScope.launch { _navigationEvents.emit(NavigationEvent.Speak(message)) }
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** Signs that need readable text to be actionable. */
    private fun requiresText(label: String) =
        label in setOf("room", "room_direction_left", "room_direction_right", "stairs")

    /** Returns false for signs irrelevant to the current destination. */
    private fun isSignPossibleTarget(label: String): Boolean {
        val isExitSign = label == "exit_left" || label == "exit_right"
        return if (destination == "exit") isExitSign else !isExitSign
    }

    /** "left" | "right" | "" based on bbox centre vs frame centre. */
    private fun getSignPosition(bitmap: Bitmap, bbox: Rect): String {
        val cx = bitmap.width / 2
        return when {
            bbox.right < cx -> "left"
            bbox.left > cx -> "right"
            else -> ""
        }
    }

    // =========================================================================
    //  STATS
    // =========================================================================

    @SuppressLint("DefaultLocale")
    private fun updateStats() {
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - lastStatsTime) / 1000.0
        if (elapsed < 5.0) return

        val fps = frameCount / elapsed
        val dropRate = if (frameCount > 0) droppedFrames * 100.0 / frameCount else 0.0
        _frameStats.value = String.format(
            "FPS: %.1f | Frames: %d | Dropped: %d (%.1f%%)",
            fps,
            frameCount,
            droppedFrames,
            dropRate
        )
        Log.i("Stats", _frameStats.value)
        frameCount = 0; droppedFrames = 0; lastStatsTime = now
    }

    // =========================================================================
    //  CONNECTION
    // =========================================================================

    fun connect() {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        // webSocketClient.setSocketUrl("ws://192.168.1.98:8080")
        webSocketClient.setSocketUrl("ws://192.168.0.56:8080")
        webSocketClient.connect()
        webSocketClient.sendMessage("start")
    }

    fun disconnect() {
        webSocketClient.sendMessage("stop")
        webSocketClient.disconnect()
        _isSocketConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun startNavigation(dest: String) {
        destination = dest
        lastInstruction = ""
        lastInstructionTime = 0L
        lastSignTime = 0L
        isStopping.set(false)   // ← bug-fix: reset so navigation can start again
        navTracker.resetDetectionHistory()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        navTracker.stopSensors()
        _isSocketConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    class WebSocketViewModelFactory(private val application: Application) :
        ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WebSocketViewModel::class.java))
                @Suppress("UNCHECKED_CAST") return WebSocketViewModel(application) as T
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    fun isConnecting(): Boolean {
        return _connectionStatus.value is ConnectionStatus.CONNECTING
    }
}