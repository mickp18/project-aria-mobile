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
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
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
    var label     : String                       = "",
    var text      : String                       = "",
    var confidence: Float                        = 0f,
    var bbox      : android.graphics.RectF       = android.graphics.RectF(),
    var timeStamp : Long                         = System.currentTimeMillis()
)

data class Instruction(
    val text      : String,
    val direction : Direction? = null,
    val shouldStop: Boolean    = false
)

sealed class NavigationEvent {
    data class Speak(val message: String) : NavigationEvent()
    object StopNavigation : NavigationEvent()
    /** Emitted after report is written; MainActivity uses the path to notify the user. */
    data class ReportReady(val filePath: String) : NavigationEvent()
}

sealed class ConnectionStatus {
    object DISCONNECTED : ConnectionStatus()
    object CONNECTING   : ConnectionStatus()
    object CONNECTED    : ConnectionStatus()
    object WAITING_STREAM_START : ConnectionStatus()
    data class FAILED(val error: String) : ConnectionStatus()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

class WebSocketViewModel(application: Application) : AndroidViewModel(application) {

    // ── External clients ────────────────────────────────────────────────────
    private val webSocketClient = WebSocketClient.getInstance()
    private lateinit var navTracker: NavigationTracker

    // ── Report manager ───────────────────────────────────────────────────────
    val reportManager = NavigationReportManager(application)

    // ── Public state ─────────────────────────────────────────────────────────
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    // track if the streaming has started
    private val _isNavigationReady = MutableStateFlow(false)
    val isNavigationReady: StateFlow<Boolean> = _isNavigationReady.asStateFlow()

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
        iouThreshold        = 0.3f,
        numThreads          = 2,
        maxResults          = 10,
        currentDelegate     = 0,
        context             = application
    )
    private val textRecognizer = TextRecognitionProcessor(application)

    // ── Navigation state ─────────────────────────────────────────────────────
    var destination        : String = ""
    var lastInstruction    : String = ""

    var lastInstructionTime: Long   = 0L
    // Tracks whether the last emitted instruction was positive (has a direction
    // or shouldStop). Replaces the old string-matching heuristic in overrideCooldown
    // which broke for instructions whose text contains neither "left", "right" nor
    // "arrived" (e.g. the stair sign with direction=STRAIGHT).
    private var lastInstructionWasPositive = false

    private var isStopping          = AtomicBoolean(false)

    private var lastSignTime             = 0L
    private var lastSignActivityTime = 0L

    private var lastTimeoutGuidanceTime  = 0L
    private var navigationConfidence     = 1.0f
    private var lastSideDetectionTime    = 0L

    // ── Staircase ────────────────────────────────────────────────────────────
    private var lastStairWarningTime   = 0L
    private val STAIR_WARNING_COOLDOWN = 10_000L
    private var lookingForStairs       = false
    private var lastStairHintTime      = 0L
    private val STAIR_HINT_COOLDOWN    = 12_000L
    private var lastTimeoutMessage     = ""

    // ── Frame stats ──────────────────────────────────────────────────────────
    private var frameCount     = 0
    private var droppedFrames  = 0
    private var lastFrameTime  = 0L
    private var firstFrameTime = 0L
    private var totalFrames    = 0
    private var lastStatsTime  = SystemClock.uptimeMillis()
    private val isProcessing   = AtomicBoolean(false)

    // ── Constants ────────────────────────────────────────────────────────────
    private val SPEECH_COOLDOWN            = 5000L
    private val OLD_SIGN_COOLDOWN          = 10_000L
    private val SIDE_COOLDOWN              = 8_000L
    private val PROXIMITY_MIN_AREA_EXIT    = 0.001f
    private val PROXIMITY_MIN_AREA_ROOMS   = 0.001f
    private val PROXIMITY_MIN_AREA_STAIR   = 0.08f
    private val DISTORTION_CONF            = 0.65f
    private val TIMEOUT_REPEAT_COOLDOWN    = 30_000L
    private var pendingDestination: String = ""

    // ─────────────────────────────────────────────────────────────────────────
    // init
    // ─────────────────────────────────────────────────────────────────────────
    init {
        navTracker = NavigationTracker(application)

        webSocketClient.setListener(object : WebSocketClient.SocketListener {

            override fun onMessage(message: String) {
                _messages.value = "New Message: $message"
                try {
                    val json = JSONObject(message)
                    if (json.optString("type") == "STREAM_STARTED") {
                        _isNavigationReady.value = true
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        Log.d("Socket", "Navigation is now ready.")
                        // If startNavigation() was called before the stream was ready,
                        // the destination is waiting in pendingDestination — apply it now.
                        applyPendingNavigation()
                    }
                    if (json.optString("type") == "STATUS_UPDATE") {
                        val payload = json.getJSONObject("payload")

                        if (payload.optString("status") == "stopped") {
                            _isSocketConnected.value = false
                            _connectionStatus.value  = ConnectionStatus.DISCONNECTED
                            webSocketClient.disconnect()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onBinaryMessage(bytes: ByteArray) {
                val receiveTime = SystemClock.uptimeMillis()
                frameCount++; totalFrames++

                val dropped = isProcessing.get()
                reportManager.recordFrameReceived(dropped)   // ← report

                if (dropped) { droppedFrames++; updateStats(); return }
                if (firstFrameTime == 0L) firstFrameTime = receiveTime
                lastFrameTime = receiveTime

                viewModelScope.launch(Dispatchers.Default) {
                    if (isProcessing.compareAndSet(false, true)) {
                        try { processFrame(bytes) }
                        catch (e: Throwable) { Log.e("Frame", e.localizedMessage ?: "", e) }
                        finally { isProcessing.set(false); updateStats() }
                    }
                }
            }

            override fun onOpen() {
                _isSocketConnected.value = true
                _connectionStatus.value  = ConnectionStatus.WAITING_STREAM_START
                frameCount = 0; droppedFrames = 0; totalFrames = 0
                lastFrameTime = 0L; firstFrameTime = 0L
                lastStatsTime = SystemClock.uptimeMillis()
            }

            override fun onError(error: String) {
                _isSocketConnected.value = false
                _connectionStatus.value  = ConnectionStatus.FAILED(error)
            }
        })
    }

    // =========================================================================
    //  FRAME PIPELINE
    // =========================================================================

    private suspend fun processFrame(bytes: ByteArray) {
        val t0     = SystemClock.uptimeMillis()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

        Log.d("process", "Exposure corrected")
        try {
            // ── YOLO ──────────────────────────────────────────────────────────
            val yoloStart = SystemClock.uptimeMillis()
            val results   = yoloDetector.detect(bitmap, 0)
            val yoloMs    = SystemClock.uptimeMillis() - yoloStart
            Log.i("YOLO", "${results.detections.size} detections in ${yoloMs}ms")

            // Record all raw detections
            results.detections.forEach { det ->
                Log.d("YOLO", "${det.category.label} (${det.category.confidence})")
                reportManager.recordDetection(det.category.label.lowercase(), qualified = false)
                saveBitmapToGallery(application, bitmap, "YOLO_${det.category.label}_${System.currentTimeMillis()}.jpg")
            }

            if (results.detections.isEmpty()) {
                reportManager.recordFrameProcessed(yoloMs, 0L, SystemClock.uptimeMillis() - t0, 0)
                checkForTimeout()
                return
            }

            // ── Split by category ─────────────────────────────────────────────
            val staircaseDetections = results.detections.filter {
                it.category.label.lowercase() == "staircase"
            }
            val signDetections      = results.detections.filter {
                it.category.label.lowercase() != "staircase"
            }

            handleStaircaseProximity(staircaseDetections, bitmap)

            // ── Sign qualification + OCR ──────────────────────────────────────
            var pendingHint: String? = null
            var ocrMs = 0L

            val qualified = if (signDetections.isNotEmpty()) {
                val ocrStart = SystemClock.uptimeMillis()
                val q = qualifyDetections(signDetections, bitmap) { hint ->
                    if (pendingHint == null) pendingHint = hint
                }
                ocrMs = SystemClock.uptimeMillis() - ocrStart
                q
            } else emptyList()

            // Record qualified detection counts
            qualified.forEach { reportManager.recordDetection(it.label, qualified = true) }

            val totalFrameMs = SystemClock.uptimeMillis() - t0
            reportManager.recordFrameProcessed(yoloMs, ocrMs, totalFrameMs, results.detections.size)

            if (qualified.isEmpty()) {
                pendingHint?.let { emitIfAllowed(Instruction(it)) }
                checkForTimeout()
                return
            }

            val best        = pickBest(qualified) ?: run { checkForTimeout(); return }
            val instruction = buildInstruction(best) ?: run { checkForTimeout(); return }
            emitIfAllowed(instruction)

            if (isSignPossibleTarget(best.label)) {
                lastSignTime         = SystemClock.uptimeMillis()
                navigationConfidence = 1.0f
            }

        } finally {
            bitmap.recycle()
            checkForTimeout()
            Log.d("Timing", "Frame in ${SystemClock.uptimeMillis() - t0}ms")
            Log.i("space", "----------------------------------------------")
        }
    }

    // =========================================================================
    //  STAIRCASE HANDLING
    // =========================================================================

    private suspend fun handleStaircaseProximity(
        detections: List<ObjectDetection>,
        bitmap    : Bitmap
    ) {
        if (detections.isEmpty()) return

        val frameArea    = (bitmap.width * bitmap.height).toFloat()
        val now          = SystemClock.uptimeMillis()
        val closestStair = detections.maxByOrNull {
            it.boundingBox.width() * it.boundingBox.height()
        } ?: return
        Log.d("staircase", "closest: ${closestStair.category.label}")
        val area      = (closestStair.boundingBox.width() * closestStair.boundingBox.height()) / frameArea
        val direction = openingToDirection(closestStair.boundingBox, bitmap)

        if (lookingForStairs) {
            if (now - lastStairHintTime < STAIR_HINT_COOLDOWN) return
            lastStairHintTime = now
            reportManager.recordStairGuidanceHint()
            val hint = "There are stairs ${directionLabel(direction)}. Use them to reach $destination."
            emitIfAllowed(Instruction(hint, direction = direction))
            return
        }

        if (area < PROXIMITY_MIN_AREA_STAIR) return
        if (now - lastStairWarningTime < STAIR_WARNING_COOLDOWN) return
        lastStairWarningTime = now
        reportManager.recordStaircaseWarning()
        emitIfAllowed(Instruction("Caution — stairs ahead. Watch your step."))
    }

    // =========================================================================
    //  QUALIFICATION PIPELINE
    // =========================================================================

    private suspend fun qualifyDetections(
        raw            : List<ObjectDetection>,
        bitmap         : Bitmap,
        onDisqualified : (hint: String) -> Unit
    ): List<Detection> {

        val frameArea = (bitmap.width * bitmap.height).toFloat()
        val qualified = mutableListOf<Detection>()

        for (r in raw) {
            val label      = r.category.label.lowercase()
            val confidence = r.category.confidence
            val bbox       = r.boundingBox

            val scaleX = bitmap.width.toFloat()  / 800
            val scaleY = bitmap.height.toFloat() / 800
            Log.d("imgsize", "bbox: ${bbox.width()}")
            val cropRect = Rect(
                (bbox.left.toInt()),
                (bbox.top.toInt()),
                (bbox.right.toInt()),
                (bbox.bottom.toInt())
            )
            val bboxArea   = bbox.width() * bbox.height()
            Log.d("imgsize", "bitmap; ${bitmap.width}")

            Log.d("imgsize", "rect; ${cropRect.width()}")

            if (!isSignPossibleTarget(label)) {
                // exit_left / exit_right when not exit-searching — still silent rejections
                reportManager.recordRejectedDetection(label, confidence, bboxArea, frameArea, RejectionReason.NOT_TARGET)
                continue
            }
            lastSignActivityTime = SystemClock.uptimeMillis()

            val isExit = label in setOf("exit_left", "exit_right", "exit")
            val area   = bboxArea / frameArea

            if ((area < PROXIMITY_MIN_AREA_EXIT && isExit) ||
                (area < PROXIMITY_MIN_AREA_ROOMS && !isExit)
            ) {
                Log.d("Qualify", "$label too small (${String.format("%.3f", area)})")
                reportManager.recordRejectedDetection(label, confidence, bboxArea, frameArea, RejectionReason.TOO_SMALL)
                onDisqualified("There's a sign ahead but you're too far. Move closer to read it.")
                continue
            }

            if (confidence < DISTORTION_CONF &&
                DistortionChecker.isSignDistorted(bitmap, cropRect)
            ) {
                reportManager.recordRejectedDetection(label, confidence, bboxArea, frameArea, RejectionReason.DISTORTED)
                val side = getSignPosition(bitmap, cropRect)
                val normLabel = mapLabel(label)
                val hint = if (side.isNotEmpty())
                    "There's a $normLabel sign on your $side. Turn to face it for a better reading."
                else
                    "There is a $normLabel sign in front."

                val now = SystemClock.uptimeMillis()
                if (now - lastSideDetectionTime > SIDE_COOLDOWN) {
                    lastSideDetectionTime = now
                    onDisqualified(hint)
                }
                continue
            }

            val det = Detection(label = label, confidence = confidence, bbox = bbox)
            if (requiresText(label)) {
                reportManager.recordRejectedDetection(label, confidence, bboxArea, frameArea, RejectionReason.OCR_EMPTY)
                val t       = SystemClock.uptimeMillis()
                val ocrText = textRecognizer.recognizeTextInBoundingBox(bitmap, cropRect, label)
                Log.i("OCR", "$label → \"$ocrText\" in ${SystemClock.uptimeMillis() - t}ms")
                det.text = ocrText?.lowercase() ?: ""
            }

            if (requiresText(label) && det.text.isEmpty()) {
                val direction = getSignPosition(bitmap, cropRect)
                onDisqualified("I can see a sign on the  $direction but can't read it yet. Move a bit closer.")
                continue
            }

            qualified.add(det)
        }

        return qualified
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pickBest
    // ─────────────────────────────────────────────────────────────────────────

    private fun pickBest(qualified: List<Detection>): Detection? =
        qualified.maxWithOrNull(
            compareBy(
                { if (FuzzyLogic.isMatch(it.text, destination)) 1 else 0 },
                { it.confidence },
                { it.bbox.width() * it.bbox.height() }
            ))

    // ─────────────────────────────────────────────────────────────────────────
    // buildInstruction
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildInstruction(det: Detection): Instruction? {
        val isExitSearch = destination.lowercase() == "exit"
        val match        = FuzzyLogic.isMatch(det.text, destination)

        return when (det.label) {

            "room" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text       = "You've arrived. $destination is right here.",
                    shouldStop = true
                )
                else -> Instruction("This isn't $destination. Keep looking.")
            }

            "room_direction_left" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text      = "$destination is on the left.",
                    direction = Direction.TURN_LEFT
                )
                else -> Instruction(
                    text      = "$destination isn't on the left. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            }

            "room_direction_right" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text      = "$destination is on the right.",
                    direction = Direction.TURN_RIGHT
                )
                else -> Instruction(
                    text      = "$destination isn't on the right. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            }

            "exit_left"  -> if (isExitSearch) Instruction(
                text      = "The nearest exit is on your left.",
                direction = Direction.TURN_LEFT
            ) else null

            "exit_right" -> if (isExitSearch) Instruction(
                text      = "The nearest exit is on your right.",
                direction = Direction.TURN_RIGHT
            ) else null

            "exit" -> if (isExitSearch) Instruction(
                text       = "Exit reached.",
                shouldStop = true
            ) else Instruction(
                text      = "Go back, exit in front.",
                direction = Direction.TURN_AROUND
            )

            "stair_sign" -> when {
                isExitSearch -> null
                match -> {
                    lookingForStairs  = true
                    lastStairHintTime = 0L
                    Instruction(
                        text      = "Your destination is on another floor. I'll guide you to the stairs.",
                        direction = Direction.STRAIGHT
                    )
                }
                else -> Instruction("The destination isn't on this floor here. Keep looking.")
            }

            "staircase" -> null

            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // emitIfAllowed
    //
    // FIX: replaced the old overrideCooldown string-matching heuristic with an
    // explicit lastInstructionWasPositive boolean.
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitIfAllowed(instruction: Instruction) {
        if (destination.isEmpty() || isStopping.get()) return

        val now        = SystemClock.uptimeMillis()
        val tooRecent  = (now - lastInstructionTime) < SPEECH_COOLDOWN
        val sameText   = lastInstruction == instruction.text
        val notOldYet  = (now - lastInstructionTime) < OLD_SIGN_COOLDOWN
        val isPositive = instruction.shouldStop || instruction.direction != null

        // Allow a positive instruction to immediately follow a negative one only
        // when they arrive within 2 s of each other — the sign gave a bad OCR read
        // on one frame and a good one on the next. Guard: the previous instruction
        // must have been negative, so a positive cannot bypass its own cooldown.
        val overrideCooldown = isPositive && !lastInstructionWasPositive &&
                (now - lastInstructionTime) < 2_000L

        if (!overrideCooldown && (tooRecent || (sameText && notOldYet))) {
            Log.d("SpeechGate", "Blocked — tooRecent=$tooRecent same=$sameText notOldYet=$notOldYet")
            return
        }

        lastInstruction            = instruction.text
        lastInstructionTime        = now
        lastInstructionWasPositive = isPositive
        reportManager.recordInstruction(instruction.text)
        Log.d("SpeechGate", "Emitting: \"${instruction.text}\"  dir=${instruction.direction}")

        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.Speak(instruction.text))

            instruction.direction?.let { dir ->
                navTracker.startTrackingCompliance(dir)
                delay(3_000L)
                checkUserCompliance()
            }

            if (instruction.shouldStop) {
                isStopping.set(true)
                lastInstruction            = ""
                lastInstructionTime        = 0L
                lastInstructionWasPositive = false
                lookingForStairs           = false
                navTracker.resetDetectionHistory()
                _navigationEvents.emit(NavigationEvent.StopNavigation)
                // Auto-save report on arrival
                saveReport(StopReason.DESTINATION_FOUND)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compliance check
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun checkUserCompliance() {
        val c = navTracker.checkCompliance() ?: run { navTracker.stopTrackingCompliance(); return }

        if (c.compliant == false && c.confidence > 0.7f) {
            val correction = when {
                c.expected == Direction.TURN_LEFT  && c.actual == Direction.TURN_RIGHT ->
                    "You turned right. The destination is on the LEFT. Turn around."
                c.expected == Direction.TURN_RIGHT && c.actual == Direction.TURN_LEFT ->
                    "You turned left. The destination is on the RIGHT. Turn around."
                c.expected == Direction.TURN_LEFT  && c.actual == Direction.STRAIGHT ->
                    "You're going straight. Please turn LEFT."
                c.expected == Direction.TURN_RIGHT && c.actual == Direction.STRAIGHT ->
                    "You're going straight. Please turn RIGHT."
                c.expected == Direction.STRAIGHT   && c.actual != Direction.STRAIGHT ->
                    "Continue STRAIGHT ahead, don't turn."
                else ->
                    "Please go ${c.expected} instead of ${c.actual}."
            }
            _navigationEvents.emit(NavigationEvent.Speak(correction))
        }

        navTracker.stopTrackingCompliance()
    }

    // =========================================================================
    //  TIMEOUT GUIDANCE
    // =========================================================================

    private fun checkForTimeout() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTimeoutGuidanceTime < 2_000L) return

        if (lastSignTime == 0L && now > 5_000L) {
            Log.d("TIMEOUT", "loog")
            giveTimeoutGuidance("Looking for signs to $destination. Please move forward slowly.", now)
            return
        }

        val gap = now - maxOf(lastSignTime, lastSignActivityTime)

        // Each bucket is wider — user stays in it longer before escalating
        val msg = when {
            gap < 15_000L  -> null   // silent, user is just walking
            gap < 45_000L  -> "No signs visible yet. Keep moving forward and scan the walls."
            gap < 75_000L  -> "Still no signs. Try turning slowly to check both sides."
            gap < 105_000L -> "I haven't found signs in a while. Try retracing your steps to the last sign."
            else           -> "Consider asking someone nearby for directions to $destination."
        } ?: return

        // Don't repeat the same message within 30 seconds
        if (msg == lastTimeoutMessage && now - lastTimeoutGuidanceTime < TIMEOUT_REPEAT_COOLDOWN) return

        lastTimeoutMessage = msg
        giveTimeoutGuidance(msg, now)
    }

    private fun giveTimeoutGuidance(message: String, now: Long) {
        if (message == lastInstruction) return
        if (now - lastInstructionTime < SPEECH_COOLDOWN) return
        lastInstruction            = message
        lastInstructionTime        = now
        lastInstructionWasPositive = false
        lastTimeoutGuidanceTime    = now
        reportManager.recordTimeoutGuidance()
        reportManager.recordInstruction("[TIMEOUT] $message")
        viewModelScope.launch { _navigationEvents.emit(NavigationEvent.Speak(message)) }
    }

    // =========================================================================
    //  REPORT
    // =========================================================================

    fun saveReport(reason: StopReason = StopReason.MANUAL_STOP) {
        if (destination.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = reportManager.endSession(reason)
            file?.let { _navigationEvents.emit(NavigationEvent.ReportReady(it.absolutePath)) }
        }
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================
    private fun mapLabel(label: String) = when (label) {
        "room_direction_left"  -> "directions"
        "room_direction_right" -> "directions"
        "exit_left"            -> "exit direction"
        "exit_right"           -> "exit direction"
        "stair_sign"           -> "stair sign"
        else                   -> label
    }

    private fun requiresText(label: String) =
        label in setOf("room", "room_direction_left", "room_direction_right", "stair_sign")

    private fun isSignPossibleTarget(label: String): Boolean {
        return when {
            destination.lowercase() == "exit" -> label in setOf("exit_left", "exit_right", "exit")
            label == "exit"                   -> true   // always let through — warns user they hit an exit
            else                              -> label !in setOf("exit_left", "exit_right")
        }
    }

    private fun openingToDirection(bbox: android.graphics.RectF, bitmap: Bitmap): Direction {
        val cx     = (bbox.left + bbox.right) / 2f
        val frameW = bitmap.width.toFloat()
        return when {
            cx < frameW * 0.35f -> Direction.TURN_LEFT
            cx > frameW * 0.65f -> Direction.TURN_RIGHT
            else                -> Direction.STRAIGHT
        }
    }

    private fun directionLabel(dir: Direction) = when (dir) {
        Direction.TURN_LEFT  -> "left"
        Direction.TURN_RIGHT -> "right"
        Direction.STRAIGHT   -> "straight ahead"
        else                 -> dir.name.lowercase()
    }

    private fun getSignPosition(bitmap: Bitmap, bbox: Rect): String {
        val cx = bitmap.width / 2
        return when {
            bbox.right < cx -> "left"
            bbox.left > cx  -> "right"
            else            -> ""
        }
    }

    // =========================================================================
    //  STATS
    // =========================================================================

    @SuppressLint("DefaultLocale")
    private fun updateStats() {
        val now     = SystemClock.uptimeMillis()
        val elapsed = (now - lastStatsTime) / 1000.0
        if (elapsed < 5.0) return

        val fps      = frameCount / elapsed
        val dropRate = if (frameCount > 0) droppedFrames * 100.0 / frameCount else 0.0
        _frameStats.value = String.format(
            "FPS: %.1f | Frames: %d | Dropped: %d (%.1f%%)",
            fps, frameCount, droppedFrames, dropRate
        )
        Log.i("Stats", _frameStats.value)
        frameCount = 0; droppedFrames = 0; lastStatsTime = now
    }

    // =========================================================================
    //  CONNECTION
    // =========================================================================

    fun connect() {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        webSocketClient.setSocketUrl("ws://192.168.1.2:8080")
        webSocketClient.connect()
        webSocketClient.sendMessage("start")
    }

    fun disconnect() {
        webSocketClient.sendMessage("stop")
        webSocketClient.disconnect()
        _isSocketConnected.value = false
        _connectionStatus.value  = ConnectionStatus.DISCONNECTED
    }

    fun startNavigation(dest: String) {
        // Always store the destination — even if the stream isn't ready yet.
        // When STREAM_STARTED fires, applyPendingNavigation() will pick this up.
        pendingDestination = dest

        if (!_isNavigationReady.value) {
            Log.w("Nav", "Cannot start navigation: Streaming not ready. Destination stored: $dest")
            return
        }

        applyPendingNavigation()
    }

    // Called either immediately from startNavigation (if stream is ready),
    // or from onMessage when STREAM_STARTED arrives (if startNavigation was called first).
    private fun applyPendingNavigation() {
        if (pendingDestination.isEmpty()) return

        destination                = pendingDestination
        pendingDestination         = ""          // clear so it can't fire twice
        lastInstruction            = ""
        lastInstructionTime        = 0L
        lastInstructionWasPositive = false
        lastSignTime               = 0L
        lastTimeoutMessage         = ""
        lookingForStairs           = false
        isStopping.set(false)
        reportManager.startSession(destination)
        navTracker.resetDetectionHistory()
        Log.d("Nav", "Navigation started for: $destination")
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        navTracker.stopSensors()
        _isSocketConnected.value = false
        _connectionStatus.value  = ConnectionStatus.DISCONNECTED
    }

    class WebSocketViewModelFactory(private val application: Application) :
        ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WebSocketViewModel::class.java))
                @Suppress("UNCHECKED_CAST") return WebSocketViewModel(application) as T
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    fun isConnecting(): Boolean = _connectionStatus.value is ConnectionStatus.CONNECTING
}