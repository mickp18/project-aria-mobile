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
}

sealed class ConnectionStatus {
    object DISCONNECTED : ConnectionStatus()
    object CONNECTING   : ConnectionStatus()
    object CONNECTED    : ConnectionStatus()
    data class FAILED(val error: String) : ConnectionStatus()
}

// ─────────────────────────────────────────────────────────────────────────────
// Fork state machine
//
//  None
//   └─► AtFork          ← 2+ openings detected, no direction tried yet
//        └─► Exploring  ← user sent in one direction, tracking compliance
//             ├─► AtFork (next direction) ← direction failed, more to try
//             └─► SuggestBack            ← all directions exhausted
// ─────────────────────────────────────────────────────────────────────────────

sealed class ForkState {
    /** No fork is active. */
    object None : ForkState()

    /**
     * Fork detected. We have a list of available directions derived from
     * the opening bboxes. [suggestedDirection] is what we just told the user.
     * [remainingDirections] are still untried.
     */
    data class AtFork(
        val availableDirections : List<Direction>,
        val suggestedDirection  : Direction,
        val remainingDirections : List<Direction>,
        val detectedAt          : Long = SystemClock.uptimeMillis()
    ) : ForkState()

    /**
     * User is exploring the [suggestedDirection]. We are waiting to see if
     * they find what they need. [exploringStarted] is used to time out.
     */
    data class Exploring(
        val suggestedDirection  : Direction,
        val remainingDirections : List<Direction>,
        val exploringStarted    : Long = SystemClock.uptimeMillis()
    ) : ForkState()

    /**
     * All directions at the fork have been tried without success.
     * Tell the user to go back the way they came.
     */
    object SuggestBack : ForkState()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

class WebSocketViewModel(application: Application) : AndroidViewModel(application) {

    // ── External clients ────────────────────────────────────────────────────
    private val webSocketClient = WebSocketClient.getInstance()
    private lateinit var navTracker: NavigationTracker

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
        maxResults          = 10,   // raised: we now need to see all openings in a frame
        currentDelegate     = 0,
        context             = application
    )
    private val textRecognizer = TextRecognitionProcessor(application)

    // ── Navigation state ─────────────────────────────────────────────────────
    var destination      : String        = ""
    var lastInstruction  : String        = ""
    var lastInstructionTime: Long        = 0L
    private var isStopping               = AtomicBoolean(false)

    // Timeout / confidence
    private var lastSignTime             = 0L
    private var lastTimeoutGuidanceTime  = 0L
    private var navigationConfidence     = 1.0f

    // Side-sign hint rate-limiting
    private var lastSideDetectionTime    = 0L

    // ── Fork state ───────────────────────────────────────────────────────────
    private var forkState: ForkState = ForkState.None
    // How long (ms) we wait in Exploring before declaring that direction a failure
    private val FORK_EXPLORE_TIMEOUT     = 30_000L
    // Min time between repeating the same fork hint
    private var lastForkInstructionTime  = 0L
    private val FORK_HINT_COOLDOWN       = 8_000L
    // Rate-limit for staircase warning
    private var lastStairWarningTime     = 0L
    private val STAIR_WARNING_COOLDOWN   = 10_000L
    // Whether we are actively looking for stairs (destination is on another floor)
    private var lookingForStairs         = false
    private var lastStairHintTime        = 0L
    private val STAIR_HINT_COOLDOWN      = 12_000L

    // ── Frame stats ──────────────────────────────────────────────────────────
    private var frameCount     = 0
    private var droppedFrames  = 0
    private var lastFrameTime  = 0L
    private var firstFrameTime = 0L
    private var totalFrames    = 0
    private var lastStatsTime  = SystemClock.uptimeMillis()
    private val isProcessing   = AtomicBoolean(false)

    // ── Constants ────────────────────────────────────────────────────────────
    private val SPEECH_COOLDOWN         = 5000L
    private val OLD_SIGN_COOLDOWN       = 10_000L
    private val SIDE_COOLDOWN           = 8_000L
    private val PROXIMITY_MIN_AREA_EXIT  = 0.001f
    private val PROXIMITY_MIN_AREA_ROOMS = 0.005f
    private val PROXIMITY_MIN_AREA_OPENING = 0.04f  // opening must be ≥ 4 % of frame to count
    private val PROXIMITY_MIN_AREA_STAIR = 0.08f    // staircase warning only when it fills ≥ 8 %
    private val DISTORTION_CONF         = 0.65f

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

                if (isProcessing.get()) { droppedFrames++; updateStats(); return }
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
            val yoloStart = SystemClock.uptimeMillis()
            val results   = yoloDetector.detect(bitmap, 0)
            Log.i("YOLO", "${results.detections.size} detections in " +
                    "${SystemClock.uptimeMillis() - yoloStart}ms")

            if (results.detections.isEmpty()) { checkForTimeout(); return }

            // ── Separate detections by category ──────────────────────────────
            val openingDetections   = results.detections.filter {
                it.category.label.lowercase() == "opening"
            }
            val staircaseDetections = results.detections.filter {
                it.category.label.lowercase() == "staircase"
            }
            val signDetections = results.detections.filter {
                it.category.label.lowercase() !in setOf("opening", "staircase")
            }

            // ── Handle openings (fork logic) ──────────────────────────────────
            handleOpenings(openingDetections, bitmap)

            // ── Handle staircase proximity warning ────────────────────────────
            handleStaircaseProximity(staircaseDetections, bitmap)

            // ── Standard sign pipeline ────────────────────────────────────────
            if (signDetections.isEmpty()) { checkForTimeout(); return }

            var pendingHint: String? = null
            val qualified = qualifyDetections(signDetections, bitmap) { hint ->
                if (pendingHint == null) pendingHint = hint
            }

            if (qualified.isEmpty()) {
                pendingHint?.let { emitIfAllowed(Instruction(it)) }
                checkForTimeout()
                return
            }

            val best = pickBest(qualified) ?: run { checkForTimeout(); return }
            val instruction = buildInstruction(best) ?: run { checkForTimeout(); return }
            emitIfAllowed(instruction)

            if (isSignPossibleTarget(best.label)) {
                lastSignTime        = SystemClock.uptimeMillis()
                navigationConfidence = 1.0f
                // Success in a fork direction → resolve the fork
                if (forkState is ForkState.Exploring) {
                    Log.d("Fork", "Sign found while exploring — resolving fork")
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

    /**
     * Called every frame with the list of raw `opening` detections.
     *
     * State machine transitions:
     *   None          → if ≥2 big-enough openings: compute directions, emit first suggestion
     *   AtFork        → user acknowledged, navTracker should start; transition to Exploring
     *   Exploring     → if timeout elapsed with no sign found: try next direction or SuggestBack
     *   SuggestBack   → rate-limited reminder to retrace steps
     */
    private suspend fun handleOpenings(
        detections: List<ObjectDetection>,
        bitmap: Bitmap
    ) {
        val frameArea = (bitmap.width * bitmap.height).toFloat()

        // Filter to only openings that are big enough to be real navigable paths
        val validOpenings = detections.filter { det ->
            val b    = det.boundingBox
            val area = (b.width() * b.height()) / frameArea
            area >= PROXIMITY_MIN_AREA_OPENING
        }

        val now = SystemClock.uptimeMillis()

        when (val state = forkState) {

            // ── No active fork: check if we just found one ───────────────────
            is ForkState.None -> {
                if (validOpenings.size < 2) return   // straight corridor, nothing to do

                val directions = validOpenings
                    .map { opening -> openingToDirection(opening.boundingBox, bitmap) }
                    .distinct()
                    .sortedBy { it.ordinal }   // deterministic order

                if (directions.size < 2) return      // all openings map to same direction

                val suggested  = directions.first()
                val remaining  = directions.drop(1)

                forkState = ForkState.AtFork(
                    availableDirections  = directions,
                    suggestedDirection   = suggested,
                    remainingDirections  = remaining
                )

                Log.d("Fork", "Fork detected — directions: $directions, suggesting $suggested")
                emitForkHint(
                    "There's a fork ahead. Try going ${directionLabel(suggested)} first.",
                    suggested
                )
            }

            // ── Fork announced, start exploration tracking ───────────────────
            is ForkState.AtFork -> {
                // Transition to Exploring as soon as enough time has passed
                // (user should have started moving after our announcement)
                if (now - state.detectedAt > 3_000L) {
                    forkState = ForkState.Exploring(
                        suggestedDirection = state.suggestedDirection,
                        remainingDirections = state.remainingDirections
                    )
                    navTracker.startTrackingCompliance(state.suggestedDirection)
                    Log.d("Fork", "Transitioning to Exploring toward ${state.suggestedDirection}")
                }
            }

            // ── Exploring: check if direction failed (timeout) ───────────────
            is ForkState.Exploring -> {
                val elapsed = now - state.exploringStarted
                if (elapsed < FORK_EXPLORE_TIMEOUT) return   // still within timeout, wait

                Log.d("Fork", "Explore timeout — direction ${state.suggestedDirection} failed")
                navTracker.stopTrackingCompliance()

                if (state.remainingDirections.isEmpty()) {
                    // All directions tried — tell user to go back
                    forkState = ForkState.SuggestBack
                    emitForkHint(
                        "No luck in any direction at that fork. Go back the way you came and look for other signs.",
                        direction = null
                    )
                } else {
                    // Try the next direction
                    val nextDirection = state.remainingDirections.first()
                    val newRemaining  = state.remainingDirections.drop(1)

                    forkState = ForkState.AtFork(
                        availableDirections  = listOf(nextDirection) + newRemaining,
                        suggestedDirection   = nextDirection,
                        remainingDirections  = newRemaining
                    )

                    emitForkHint(
                        "Nothing found that way. Go back to the fork and try ${directionLabel(nextDirection)} instead.",
                        nextDirection
                    )
                    Log.d("Fork", "Suggesting next direction: $nextDirection")
                }
            }

            // ── All directions exhausted: periodic reminder ──────────────────
            is ForkState.SuggestBack -> {
                if (now - lastForkInstructionTime > FORK_HINT_COOLDOWN * 2) {
                    emitForkHint(
                        "Please retrace your steps and try a different route.",
                        direction = null
                    )
                }
            }
        }
    }

    /**
     * Emits a fork-specific instruction, respecting its own cooldown so
     * it doesn't spam on every frame.
     */
    private suspend fun emitForkHint(message: String, direction: Direction?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastForkInstructionTime < FORK_HINT_COOLDOWN) return
        lastForkInstructionTime = now
        lastInstruction         = message
        lastInstructionTime     = now
        _navigationEvents.emit(NavigationEvent.Speak(message))
        direction?.let { navTracker.startTrackingCompliance(it) }
    }

    /**
     * Maps an opening bounding box to a navigable direction based on where
     * its center falls horizontally in the frame.
     */
    private fun openingToDirection(bbox: android.graphics.RectF, bitmap: Bitmap): Direction {
        val cx     = (bbox.left + bbox.right) / 2f
        val frameW = bitmap.width.toFloat()
        return when {
            cx < frameW * 0.35f -> Direction.TURN_LEFT
            cx > frameW * 0.65f -> Direction.TURN_RIGHT
            else                -> Direction.STRAIGHT
        }
    }

    private fun directionLabel(dir: Direction): String = when (dir) {
        Direction.TURN_LEFT  -> "left"
        Direction.TURN_RIGHT -> "right"
        Direction.STRAIGHT   -> "straight ahead"
        else                 -> dir.name.lowercase()
    }

    // =========================================================================
    //  STAIRCASE HANDLING
    // =========================================================================

    /**
     * Warns the user when a staircase is directly ahead and large enough
     * to be an imminent hazard. Also guides to stairs when [lookingForStairs].
     */
    private suspend fun handleStaircaseProximity(
        detections: List<ObjectDetection>,
        bitmap: Bitmap
    ) {
        if (detections.isEmpty()) return

        val frameArea = (bitmap.width * bitmap.height).toFloat()
        val now = SystemClock.uptimeMillis()

        val closestStair = detections.maxByOrNull {
            it.boundingBox.width() * it.boundingBox.height()
        } ?: return

        val area      = (closestStair.boundingBox.width() * closestStair.boundingBox.height()) / frameArea
        val direction = openingToDirection(closestStair.boundingBox, bitmap)

        if (lookingForStairs) {
            // User needs stairs to reach their floor — guide them toward it
            if (now - lastStairHintTime < STAIR_HINT_COOLDOWN) return
            lastStairHintTime = now

            val hint = "There are stairs ${directionLabel(direction)}. Use them to reach $destination."
            emitIfAllowed(Instruction(hint, direction = direction))
            return
        }

        // Proximity warning: only fire when stairs fill a significant portion of frame
        if (area < PROXIMITY_MIN_AREA_STAIR) return
        if (now - lastStairWarningTime < STAIR_WARNING_COOLDOWN) return
        lastStairWarningTime = now

        emitIfAllowed(Instruction("Caution — stairs ahead. Watch your step."))
    }

    // =========================================================================
    //  QUALIFICATION PIPELINE  (signs only — openings handled separately)
    // =========================================================================

    private suspend fun qualifyDetections(
        raw: List<ObjectDetection>,
        bitmap: Bitmap,
        onDisqualified: (hint: String) -> Unit
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

            val isExit = (label == "exit_left" || label == "exit_right" || label == "exit")
            val area   = (bbox.width() * bbox.height()) / frameArea
            Log.d("Area", "bitmap area: $frameArea, label area: $area")

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
                Log.d("Qualify", "$label distorted, side=$side")

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
                    text      = "You've arrived. $destination is right here.",
                    shouldStop = true
                )
                else -> Instruction("This isn't $destination. Keep looking.")
            }

            "room_direction_left" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text      = "Your destination is on the left.",
                    direction  = Direction.TURN_LEFT
                )
                else -> Instruction(
                    text      = "The destination isn't on the left. Keep walking.",
                    direction  = Direction.STRAIGHT
                )
            }

            "room_direction_right" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text      = "Your destination is on the right.",
                    direction  = Direction.TURN_RIGHT
                )
                else -> Instruction(
                    text      = "The destination isn't on the right. Keep walking.",
                    direction  = Direction.STRAIGHT
                )
            }

            "exit_left" -> if (isExitSearch) Instruction(
                text      = "The nearest exit is on your left.",
                direction  = Direction.TURN_LEFT
            ) else null

            "exit_right" -> if (isExitSearch) Instruction(
                text      = "The nearest exit is on your right.",
                direction  = Direction.TURN_RIGHT
            ) else null

            // Plain exit sign directly in front — user has reached it
            "exit" -> if (isExitSearch) Instruction(
                text      = "Exit reached.",
                shouldStop = true
            ) else null

            // Floor-direction sign for rooms (e.g. "R4 – 2nd floor")
            "stair_sign" -> when {
                isExitSearch -> null
                match -> {
                    // Room is on another floor — start looking for stairs
                    lookingForStairs = true
                    lastStairHintTime = 0L   // allow immediate stair hint
                    Instruction(
                        text      = "Your destination is on another floor. I'll guide you to the stairs.",
                        direction  = Direction.STRAIGHT
                    )
                }
                else -> Instruction("The destination isn't on this floor here. Keep looking.")
            }

            // `staircase` proximity handled in handleStaircaseProximity; nothing to do here
            "staircase" -> null

            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // emitIfAllowed
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitIfAllowed(instruction: Instruction) {
        if (destination.isEmpty() || isStopping.get()) return

        val now          = SystemClock.uptimeMillis()
        val tooRecent    = (now - lastInstructionTime) < SPEECH_COOLDOWN
        val sameText     = lastInstruction == instruction.text
        val notOldYet    = (now - lastInstructionTime) < OLD_SIGN_COOLDOWN
        val isPositive   = instruction.shouldStop || instruction.direction != null
        val lastWasNeg   = !lastInstruction.contains("arrived") &&
                !lastInstruction.contains("left") &&
                !lastInstruction.contains("right")
        val overrideCooldown = isPositive && lastWasNeg && (now - lastInstructionTime) < 2_000L

        if (!overrideCooldown && (tooRecent || (sameText && notOldYet))) {
            Log.d("SpeechGate", "Blocked — tooRecent=$tooRecent same=$sameText notOldYet=$notOldYet")
            return
        }

        lastInstruction     = instruction.text
        lastInstructionTime = now
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
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkUserCompliance
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

        // Don't override fork guidance with generic timeout messages
        if (forkState !is ForkState.None) return

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
        viewModelScope.launch { _navigationEvents.emit(NavigationEvent.Speak(message)) }
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