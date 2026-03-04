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
    data class FAILED(val error: String) : ConnectionStatus()
}

// ─────────────────────────────────────────────────────────────────────────────
// Fork state machine
// ─────────────────────────────────────────────────────────────────────────────

sealed class ForkState {
    object None : ForkState()

    data class AtFork(
        val availableDirections : List<Direction>,
        val suggestedDirection  : Direction,
        val remainingDirections : List<Direction>,
        val detectedAt          : Long = SystemClock.uptimeMillis()
    ) : ForkState()

    data class Exploring(
        val suggestedDirection  : Direction,
        val remainingDirections : List<Direction>,
        val exploringStarted    : Long = SystemClock.uptimeMillis()
    ) : ForkState()

    object SuggestBack : ForkState()
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
    private var isStopping          = AtomicBoolean(false)

    private var lastSignTime             = 0L
    private var lastTimeoutGuidanceTime  = 0L
    private var navigationConfidence     = 1.0f
    private var lastSideDetectionTime    = 0L

    // ── Fork state ───────────────────────────────────────────────────────────
    private var forkState              : ForkState = ForkState.None
    private val FORK_EXPLORE_TIMEOUT   = 30_000L
    private var lastForkInstructionTime = 0L
    private val FORK_HINT_COOLDOWN     = 8_000L

    // ── Staircase ────────────────────────────────────────────────────────────
    private var lastStairWarningTime   = 0L
    private val STAIR_WARNING_COOLDOWN = 10_000L
    private var lookingForStairs       = false
    private var lastStairHintTime      = 0L
    private val STAIR_HINT_COOLDOWN    = 12_000L

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
    private val PROXIMITY_MIN_AREA_ROOMS   = 0.005f
    private val PROXIMITY_MIN_AREA_OPENING = 0.04f
    private val PROXIMITY_MIN_AREA_STAIR   = 0.08f
    private val DISTORTION_CONF            = 0.65f

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
                _connectionStatus.value  = ConnectionStatus.CONNECTED
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

        try {
            // ── YOLO ──────────────────────────────────────────────────────────
            val yoloStart = SystemClock.uptimeMillis()
            val results   = yoloDetector.detect(bitmap, 0)
            val yoloMs    = SystemClock.uptimeMillis() - yoloStart
            Log.i("YOLO", "${results.detections.size} detections in ${yoloMs}ms")

            // Record all raw detections
            results.detections.forEach { det ->
                reportManager.recordDetection(det.category.label.lowercase(), qualified = false)
            }

            if (results.detections.isEmpty()) {
                reportManager.recordFrameProcessed(yoloMs, 0L, SystemClock.uptimeMillis() - t0, 0)
                checkForTimeout()
                return
            }

            // ── Split by category ─────────────────────────────────────────────
            val openingDetections   = results.detections.filter {
                it.category.label.lowercase() == "opening"
            }
            val staircaseDetections = results.detections.filter {
                it.category.label.lowercase() == "staircase"
            }
            val signDetections      = results.detections.filter {
                it.category.label.lowercase() !in setOf("opening", "staircase")
            }

            handleOpenings(openingDetections, bitmap)
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
                if (forkState is ForkState.Exploring) {
                    reportManager.recordForkOutcome("Resolved — sign found while exploring")
                    forkState = ForkState.None
                }
            }

        } finally {
            bitmap.recycle()
            checkForTimeout()
            Log.d("Timing", "Frame in ${SystemClock.uptimeMillis() - t0}ms")
            Log.i("space", "----------------------------------------------")
        }
    }

    // =========================================================================
    //  FORK / OPENING LOGIC
    // =========================================================================

    private suspend fun handleOpenings(
        detections: List<ObjectDetection>,
        bitmap    : Bitmap
    ) {
        val frameArea     = (bitmap.width * bitmap.height).toFloat()
        val validOpenings = detections.filter { det ->
            val b = det.boundingBox
            (b.width() * b.height()) / frameArea >= PROXIMITY_MIN_AREA_OPENING
        }
        val now = SystemClock.uptimeMillis()

        when (val state = forkState) {

            is ForkState.None -> {
                if (validOpenings.size < 2) return

                val directions = validOpenings
                    .map { openingToDirection(it.boundingBox, bitmap) }
                    .distinct()
                    .sortedBy { it.ordinal }

                if (directions.size < 2) return

                val suggested = directions.first()
                val remaining = directions.drop(1)

                reportManager.recordForkDetected(directions.map { directionLabel(it) })

                forkState = ForkState.AtFork(
                    availableDirections  = directions,
                    suggestedDirection   = suggested,
                    remainingDirections  = remaining
                )

                Log.d("Fork", "Fork detected — $directions, suggesting $suggested")
                emitForkHint(
                    "There's a fork ahead. Try going ${directionLabel(suggested)} first.",
                    suggested
                )
            }

            is ForkState.AtFork -> {
                if (now - state.detectedAt > 3_000L) {
                    forkState = ForkState.Exploring(
                        suggestedDirection  = state.suggestedDirection,
                        remainingDirections = state.remainingDirections
                    )
                    navTracker.startTrackingCompliance(state.suggestedDirection)
                    Log.d("Fork", "Exploring toward ${state.suggestedDirection}")
                }
            }

            is ForkState.Exploring -> {
                val elapsed = now - state.exploringStarted
                if (elapsed < FORK_EXPLORE_TIMEOUT) return

                navTracker.stopTrackingCompliance()
                Log.d("Fork", "Explore timeout — ${state.suggestedDirection} failed")

                if (state.remainingDirections.isEmpty()) {
                    reportManager.recordForkOutcome("All directions exhausted — sent user back")
                    forkState = ForkState.SuggestBack
                    emitForkHint(
                        "No luck in any direction at that fork. Go back the way you came and look for other signs.",
                        direction = null
                    )
                } else {
                    val next   = state.remainingDirections.first()
                    val newRem = state.remainingDirections.drop(1)
                    reportManager.recordForkOutcome(
                        "Direction ${directionLabel(state.suggestedDirection)} failed — trying ${directionLabel(next)}"
                    )
                    forkState = ForkState.AtFork(
                        availableDirections  = listOf(next) + newRem,
                        suggestedDirection   = next,
                        remainingDirections  = newRem
                    )
                    emitForkHint(
                        "Nothing found that way. Go back to the fork and try ${directionLabel(next)} instead.",
                        next
                    )
                }
            }

            is ForkState.SuggestBack -> {
                if (now - lastForkInstructionTime > FORK_HINT_COOLDOWN * 2) {
                    emitForkHint("Please retrace your steps and try a different route.", direction = null)
                }
            }
        }
    }

    private suspend fun emitForkHint(message: String, direction: Direction?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastForkInstructionTime < FORK_HINT_COOLDOWN) return
        lastForkInstructionTime = now
        lastInstruction         = message
        lastInstructionTime     = now
        reportManager.recordInstruction("[FORK] $message")
        _navigationEvents.emit(NavigationEvent.Speak(message))
        direction?.let { navTracker.startTrackingCompliance(it) }
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
            val cropRect   = Rect(
                bbox.left.toInt(), bbox.top.toInt(),
                bbox.right.toInt(), bbox.bottom.toInt()
            )

            if (!isSignPossibleTarget(label)) continue

            val isExit = label in setOf("exit_left", "exit_right", "exit")
            val area   = (bbox.width() * bbox.height()) / frameArea

            if ((area < PROXIMITY_MIN_AREA_EXIT && isExit) ||
                (area < PROXIMITY_MIN_AREA_ROOMS && !isExit)
            ) {
                Log.d("Qualify", "$label too small (${String.format("%.3f", area)})")
                onDisqualified("There's a sign ahead but you're too far. Move closer to read it.")
                continue
            }

            if (confidence < DISTORTION_CONF &&
                DistortionChecker.isSignDistorted(bitmap, cropRect)
            ) {
                val side = getSignPosition(bitmap, cropRect)
                val hint = if (side.isNotEmpty())
                    "There's a sign on your $side. Turn to face it for a better reading."
                else
                    "A sign is at an angle. Move closer and face it directly."

                val now = SystemClock.uptimeMillis()
                if (now - lastSideDetectionTime > SIDE_COOLDOWN) {
                    lastSideDetectionTime = now
                    onDisqualified(hint)
                }
                continue
            }

            val det = Detection(label = label, confidence = confidence, bbox = bbox)
            if (requiresText(label)) {
                val t       = SystemClock.uptimeMillis()
                val ocrText = textRecognizer.recognizeTextInBoundingBox(bitmap, cropRect, label)
                Log.i("OCR", "$label → \"$ocrText\" in ${SystemClock.uptimeMillis() - t}ms")
                det.text = ocrText?.lowercase() ?: ""
            }

            if (requiresText(label) && det.text.isEmpty()) {
                onDisqualified("I can see a sign but can't read it yet. Move a bit closer.")
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
                    text      = "Your destination is on the left.",
                    direction = Direction.TURN_LEFT
                )
                else -> Instruction(
                    text      = "The destination isn't on the left. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            }

            "room_direction_right" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text      = "Your destination is on the right.",
                    direction = Direction.TURN_RIGHT
                )
                else -> Instruction(
                    text      = "The destination isn't on the right. Keep walking.",
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
            ) else null

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
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitIfAllowed(instruction: Instruction) {
        if (destination.isEmpty() || isStopping.get()) return

        val now           = SystemClock.uptimeMillis()
        val tooRecent     = (now - lastInstructionTime) < SPEECH_COOLDOWN
        val sameText      = lastInstruction == instruction.text
        val notOldYet     = (now - lastInstructionTime) < OLD_SIGN_COOLDOWN
        val isPositive    = instruction.shouldStop || instruction.direction != null
        val lastWasNeg    = !lastInstruction.contains("arrived") &&
                !lastInstruction.contains("left") &&
                !lastInstruction.contains("right")
        val overrideCooldown = isPositive && lastWasNeg && (now - lastInstructionTime) < 2_000L

        if (!overrideCooldown && (tooRecent || (sameText && notOldYet))) {
            Log.d("SpeechGate", "Blocked — tooRecent=$tooRecent same=$sameText notOldYet=$notOldYet")
            return
        }

        lastInstruction     = instruction.text
        lastInstructionTime = now
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
                lastInstruction     = ""
                lastInstructionTime = 0L
                lookingForStairs    = false
                forkState           = ForkState.None
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
        if (forkState !is ForkState.None) return   // fork state handles its own pacing

        if (lastSignTime == 0L && now > 5_000L) {
            giveTimeoutGuidance("Looking for signs to $destination. Please move forward slowly.", now)
            return
        }

        val gap = now - lastSignTime
        navigationConfidence = when {
            gap < 30_000L -> 1.0f
            gap < 55_000L -> 0.7f
            gap < 75_000L -> 0.5f
            gap < 95_000L -> 0.3f
            else          -> 0.1f
        }

        val msg = when (gap) {
            in 15_000L..30_000L -> "No new signs. Continue forward and look for signs."
            in 35_000L..55_000L -> "Still no signs. Keep moving and scan the walls."
            in 55_000L..75_000L -> "No signs for ${gap / 1000} seconds. Turn slowly to scan the area."
            else -> if (gap > 95_000L) "Consider asking for directions to $destination or turn around." else null
        }
        msg?.let { giveTimeoutGuidance(it, now) }
    }

    private fun giveTimeoutGuidance(message: String, now: Long) {
        if (message == lastInstruction) return
        if (now - lastInstructionTime < SPEECH_COOLDOWN) return
        lastInstruction         = message
        lastInstructionTime     = now
        lastTimeoutGuidanceTime = now
        reportManager.recordTimeoutGuidance()
        reportManager.recordInstruction("[TIMEOUT] $message")
        viewModelScope.launch { _navigationEvents.emit(NavigationEvent.Speak(message)) }
    }

    // =========================================================================
    //  REPORT
    // =========================================================================

    /**
     * Called by MainActivity's stop button (manual stop) or internally on arrival.
     * Writes the report on an IO thread and emits [NavigationEvent.ReportReady].
     */
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

    private fun requiresText(label: String) =
        label in setOf("room", "room_direction_left", "room_direction_right", "stair_sign")

    private fun isSignPossibleTarget(label: String): Boolean {
        val isExitSign = label in setOf("exit_left", "exit_right", "exit")
        return if (destination.lowercase() == "exit") isExitSign else !isExitSign
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
        destination         = dest
        lastInstruction     = ""
        lastInstructionTime = 0L
        lastSignTime        = 0L
        lookingForStairs    = false
        forkState           = ForkState.None
        isStopping.set(false)
        reportManager.startSession(dest)   // ← begin collecting stats
        navTracker.resetDetectionHistory()
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