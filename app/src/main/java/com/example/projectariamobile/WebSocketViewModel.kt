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

enum class DestinationType { ROOM, STUDY_ROOM, EXIT, FLOOR }
private var destinationType: DestinationType = DestinationType.ROOM

private var sessionImageFolder: String = ""

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
    private var lastInstructionWasPositive = false

    private var isStopping          = AtomicBoolean(false)

    private var lastSignTime             = 0L
    private var lastSignActivityTime     = 0L

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
    private val PROXIMITY_MIN_AREA_EXIT    = 0.001f  // exit_left / exit_right arrow signs
    private val PROXIMITY_MIN_AREA_ROOMS   = 0.001f
    private val PROXIMITY_MIN_AREA_STAIR   = 0.08f
    // door_exit is a full-size door — require it to fill ≥6% of the frame so
    // a door visible far down the corridor doesn't falsely trigger "Exit reached."
    private val PROXIMITY_MIN_AREA_DOOR    = 0.06f
    private val DISTORTION_CONF            = 0.65f
    private val OCR_CONF                   =  0.55f
    private var lastOcrRetryHintTime = 0L
    private val OCR_RETRY_COOLDOWN   = 2_000L
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
                reportManager.recordFrameReceived(dropped)

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

        // ── Obtain stable frame ID for this frame ─────────────────────────────
        // Every image saved and every report entry for this frame will carry this
        // same tag (e.g. "F00042"), making cross-referencing trivial.
        val frameId  = reportManager.nextFrameId()
        val frameTag = reportManager.frameTag(frameId)
        Log.d("Frame", "[$frameTag] processing")

        try {
            // ── YOLO ──────────────────────────────────────────────────────────
            val yoloStart = SystemClock.uptimeMillis()
            val results   = yoloDetector.detect(bitmap, 0)
            val yoloMs    = SystemClock.uptimeMillis() - yoloStart
            Log.i("YOLO", "[$frameTag] ${results.detections.size} detections in ${yoloMs}ms")

            results.detections.forEach { det ->
                Log.d("YOLO", "[$frameTag] ${det.category.label} (${det.category.confidence})")
                reportManager.recordDetection(det.category.label.lowercase(), qualified = false)
                val annotated = bitmap.drawYoloBbox(det)
                saveBitmapToGallery(
                    application, annotated,
                    // frameTag prefix makes this image findable from the report
                    fileName   = "${frameTag}_YOLO_${det.category.label}_${System.currentTimeMillis()}.jpg",
                    folderName = sessionImageFolder
                )
                annotated.recycle()
            }

            if (results.detections.isEmpty()) {
                reportManager.recordFrameProcessed(
                    frameId         = frameId,
                    yoloMs          = yoloMs,
                    ocrMs           = 0L,
                    totalMs         = SystemClock.uptimeMillis() - t0,
                    detectionsCount = 0
                )
                checkForTimeout()
                saveBitmapToGallery(
                    application, bitmap,
                    fileName   = "${frameTag}_YOLO_NO_DETECTION_${System.currentTimeMillis()}.jpg",
                    folderName = sessionImageFolder
                )
                return
            }

            // ── Split by category ─────────────────────────────────────────────
            val staircaseDetections = results.detections.filter {
                it.category.label.lowercase() == "staircase"
            }
            val signDetections = results.detections.filter {
                it.category.label.lowercase() != "staircase"
            }

            handleStaircaseProximity(staircaseDetections, bitmap, frameId)

            // ── Sign qualification + OCR ──────────────────────────────────────
            var pendingHint: String? = null
            var ocrMs = 0L

            val qualified = if (signDetections.isNotEmpty()) {
                val ocrStart = SystemClock.uptimeMillis()
                val q = qualifyDetections(signDetections, bitmap, frameId) { hint ->
                    if (pendingHint == null) pendingHint = hint
                }
                ocrMs = SystemClock.uptimeMillis() - ocrStart
                q
            } else emptyList()

            qualified.forEach { reportManager.recordDetection(it.label, qualified = true) }

            val totalFrameMs = SystemClock.uptimeMillis() - t0
            reportManager.recordFrameProcessed(
                frameId         = frameId,
                yoloMs          = yoloMs,
                ocrMs           = ocrMs,
                totalMs         = totalFrameMs,
                detectionsCount = results.detections.size
            )

            if (qualified.isEmpty()) {
                pendingHint?.let { emitIfAllowed(Instruction(it)) }
                checkForTimeout()
                return
            }

            // ── Exit arrival check (runs before pickBest) ─────────────────────
            // Evaluates door + overhead-sign combination for the EXIT destination.
            // If it produces an instruction we emit it and skip the normal path.
            if (destinationType == DestinationType.EXIT) {
                val exitInstruction = evaluateExitCondition(qualified)
                if (exitInstruction != null) {
                    emitIfAllowed(exitInstruction)
                    lastSignTime = SystemClock.uptimeMillis()
                    // If it's a stop instruction the coroutine in emitIfAllowed
                    // handles StopNavigation; we still fall through to finally.
                    if (exitInstruction.shouldStop) return
                    // Non-stop exit guidance emitted — still allow arrow signs below
                }
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
            Log.d("Timing", "[$frameTag] Frame in ${SystemClock.uptimeMillis() - t0}ms")
            Log.i("space", "----------------------------------------------")
        }
    }

    // =========================================================================
    //  STAIRCASE HANDLING
    // =========================================================================

    private suspend fun handleStaircaseProximity(
        detections: List<ObjectDetection>,
        bitmap    : Bitmap,
        frameId   : Int      // ← NEW param (reserved for future stair image saves)
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
            lookingForStairs = false

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
        frameId        : Int,
        onDisqualified : (hint: String) -> Unit
    ): List<Detection> {

        val frameArea = (bitmap.width * bitmap.height).toFloat()
        val qualified = mutableListOf<Detection>()
        val frameTag  = reportManager.frameTag(frameId)

        for (r in raw) {
            val label      = r.category.label.lowercase()
            val confidence = r.category.confidence
            val bbox       = r.boundingBox

            Log.d("imgsize", "[$frameTag] bbox: ${bbox.width()}")
            val cropRect = Rect(
                bbox.left.toInt(),
                bbox.top.toInt(),
                bbox.right.toInt(),
                bbox.bottom.toInt()
            )
            val bboxArea = bbox.width() * bbox.height()

            if (!isSignPossibleTarget(label)) {
                reportManager.recordRejectedDetection(
                    frameId    = frameId,
                    label      = label,
                    confidence = confidence,
                    bboxArea   = bboxArea,
                    frameArea  = frameArea,
                    reason     = RejectionReason.NOT_TARGET
                )
                continue
            }
            lastSignActivityTime = SystemClock.uptimeMillis()

            val isExitArrow = label in setOf("exit_left", "exit_right")
            val isDoor      = label == "door_exit"
            val isExitDown  = label == "exit_down"
            val area        = bboxArea / frameArea

            // Per-class area threshold:
            //   door_exit    → PROXIMITY_MIN_AREA_DOOR  (large physical door, must be close)
            //   arrow/down   → PROXIMITY_MIN_AREA_EXIT  (small wall/ceiling signs)
            //   room signs   → PROXIMITY_MIN_AREA_ROOMS
            val minArea = when {
                isDoor                      -> PROXIMITY_MIN_AREA_DOOR
                isExitArrow || isExitDown   -> PROXIMITY_MIN_AREA_EXIT
                else                        -> PROXIMITY_MIN_AREA_ROOMS
            }

            if (area < minArea) {
                Log.d("Qualify", "[$frameTag] $label too small (${String.format("%.3f", area)})")
                reportManager.recordRejectedDetection(
                    frameId    = frameId,
                    label      = label,
                    confidence = confidence,
                    bboxArea   = bboxArea,
                    frameArea  = frameArea,
                    reason     = RejectionReason.TOO_SMALL
                )
                onDisqualified("There's a sign ahead but you're too far. Move closer to read it.")
                continue
            }

            if (confidence < DISTORTION_CONF &&
                DistortionChecker.isSignDistorted(bitmap, cropRect)
            ) {
                reportManager.recordRejectedDetection(
                    frameId    = frameId,
                    label      = label,
                    confidence = confidence,
                    bboxArea   = bboxArea,
                    frameArea  = frameArea,
                    reason     = RejectionReason.DISTORTED
                )
                val side      = getSignPosition(bitmap, cropRect)
                val normLabel = mapLabel(label)
                val hint = if (side.isNotEmpty())
                    "There's a $normLabel on your $side. Turn to face it for a better reading."
                else
                    "There is a $normLabel in front."

                val now = SystemClock.uptimeMillis()
                if (now - lastSideDetectionTime > SIDE_COOLDOWN) {
                    lastSideDetectionTime = now
                    onDisqualified(hint)
                }
                continue
            }

            val det = Detection(label = label, confidence = confidence, bbox = bbox)

            if (requiresText(label)) {
                val t = SystemClock.uptimeMillis()
                // Returns Pair(text, avgElementConfidence)
                val (ocrText, ocrConfidence) = textRecognizer.recognizeTextInBoundingBox(
                    bitmap,
                    cropRect,
                    label,
                    sessionImageFolder,
                    frameTag
                )
                val ocrMs   = SystemClock.uptimeMillis() - t
                val rawText = ocrText?.lowercase() ?: ""
                val matched = isDestinationMatch(rawText)

                Log.i("OCR", "[$frameTag] $label → \"$rawText\" conf=${
                    if (ocrConfidence >= 0f) String.format("%.2f", ocrConfidence) else "n/a"
                } matched=$matched in ${ocrMs}ms")

                reportManager.recordOcrResult(
                    frameId       = frameId,
                    label         = label,
                    rawOcrText    = rawText,
                    matchedDest   = matched,
                    ocrMs         = ocrMs,
                    dest          = destination,
                    ocrConfidence = ocrConfidence
                )

                if (rawText.isEmpty()) {
                    reportManager.recordRejectedDetection(
                        frameId    = frameId,
                        label      = label,
                        confidence = confidence,
                        bboxArea   = bboxArea,
                        frameArea  = frameArea,
                        reason     = RejectionReason.OCR_EMPTY
                    )
                }

                det.text = rawText
            }

            if (requiresText(label) && det.text.isEmpty()) {
                val direction = getSignPosition(bitmap, cropRect)
                continue
            }
            if (confidence < OCR_CONF){
                reportManager.recordRejectedDetection(
                    frameId    = frameId,
                    label      = label,
                    confidence = confidence,
                    bboxArea   = bboxArea,
                    frameArea  = frameArea,
                    reason     = RejectionReason.OCR_LOW_CONFIDENCE
                )
                val now = SystemClock.uptimeMillis()
                if (now - lastOcrRetryHintTime > OCR_RETRY_COOLDOWN) {
                    lastOcrRetryHintTime = now
                    onDisqualified("Couldn't read properly the sign, please stay still")
                }

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
                { if (isDestinationMatch(it.text)) 1 else 0 },
                { it.confidence },
                { it.bbox.width() * it.bbox.height() }
            ))

    // ─────────────────────────────────────────────────────────────────────────
    // buildInstruction
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildInstruction(det: Detection): Instruction? {
        // Use the enum — it is the single source of truth set in startNavigation.
        val isExitSearch = destinationType == DestinationType.EXIT
        val match = isDestinationMatch(det.text)

        return when (det.label) {

            "room" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text       = "You've arrived. $destination is right here.",
                    shouldStop = true
                )
                else -> Instruction("This is not $destination. Keep looking.")
            }

            "room_direction_left" -> when {
                isExitSearch -> null
                match        -> Instruction(
                    text      = "$destination is on the left.",
                    direction = Direction.TURN_LEFT
                )
                else -> Instruction(
                    text      = "$destination is not on the left. Keep walking.",
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
                    text      = "$destination is not on the right. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            }

            // Arrow signs — direction hints only, handled here
            "exit_left"  -> if (isExitSearch) Instruction(
                text      = "The nearest exit is on your left.",
                direction = Direction.TURN_LEFT
            ) else null

            "exit_right" -> if (isExitSearch) Instruction(
                text      = "The nearest exit is on your right.",
                direction = Direction.TURN_RIGHT
            ) else null

            // door_exit and exit_down arrival logic is handled by
            // evaluateExitCondition() before pickBest — they never reach here.
            "door_exit", "exit_down" -> null

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
                else -> Instruction("The destination is not on this floor here. Keep looking.")
            }

            "staircase" -> null

            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // evaluateExitCondition
    // Called before pickBest when destinationType == EXIT.
    // Decides arrival / approach based on which exit signs are visible together.
    //
    //  door_exit alone (close enough)  → STOP  — door is always present, confirms arrival
    //  door_exit + exit_down together  → STOP  — belt-and-suspenders confirmation
    //  exit_down alone                 → GUIDE (straight) — door coming, not visible yet
    //  nothing relevant here           → null  — fall through to arrow-sign handling
    // ─────────────────────────────────────────────────────────────────────────
    private fun evaluateExitCondition(qualified: List<Detection>): Instruction? {
        val labels = qualified.map { it.label }.toSet()
        val hasDoor = "door_exit" in labels
        val hasSign = "exit_down" in labels

        return when {
            hasDoor ->
                // Door visible and close enough (threshold already enforced in
                // qualifyDetections) — exit confirmed regardless of overhead sign
                Instruction(
                    text       = "You've reached the exit. You can leave now.",
                    shouldStop = true
                )
            hasSign ->
                // Overhead sign visible but door not yet in frame — guide forward
                Instruction(
                    text      = "Exit is straight ahead. Keep walking.",
                    direction = Direction.STRAIGHT
                )
            else -> null  // only arrow signs present, fall through to pickBest
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // emitIfAllowed
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitIfAllowed(instruction: Instruction) {
        if (destination.isEmpty() || isStopping.get()) return

        val now        = SystemClock.uptimeMillis()
        val tooRecent  = (now - lastInstructionTime) < SPEECH_COOLDOWN
        val sameText   = lastInstruction == instruction.text
        val notOldYet  = (now - lastInstructionTime) < OLD_SIGN_COOLDOWN
        val isPositive = instruction.shouldStop || instruction.direction != null

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

        // Snapshot the text at emit time so the compliance log always carries
        // the exact instruction that triggered the tracking window, even if
        // lastInstruction changes before the 3 s delay elapses.
        val emittedText   = instruction.text
        val emittedAtMs   = now

        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.Speak(emittedText))

            instruction.direction?.let { dir ->
                navTracker.startTrackingCompliance(dir)
                delay(3_000L)
                checkUserCompliance(
                    instructionText = emittedText,
                    expectedDir     = dir,
                    emittedAtMs     = emittedAtMs
                )
            }

            if (instruction.shouldStop) {
                isStopping.set(true)
                lastInstruction            = ""
                lastInstructionTime        = 0L
                lastInstructionWasPositive = false
                lookingForStairs           = false
                navTracker.resetDetectionHistory()
                _navigationEvents.emit(NavigationEvent.StopNavigation)
                saveReport(StopReason.DESTINATION_FOUND)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compliance check
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun checkUserCompliance(
        instructionText : String,
        expectedDir     : Direction,
        emittedAtMs     : Long
    ) {
        val reactionMs   = SystemClock.uptimeMillis() - emittedAtMs
        val c            = navTracker.checkCompliance()

        // null result → tracker had no data; still log it as uncertain
        val compliant        = c?.compliant
        val sensorConfidence = c?.confidence ?: 0f
        val actualDirStr     = c?.actual?.name ?: "UNKNOWN"
        val expectedDirStr   = expectedDir.name

        var correctionIssued = false

        if (c != null && c.compliant == false && c.confidence > 0.7f) {
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
                    "Continue STRAIGHT ahead, do not turn."
                else ->
                    "Please go ${c.expected} instead of ${c.actual}."
            }
            _navigationEvents.emit(NavigationEvent.Speak(correction))
            correctionIssued = true
        }

        // Always record the outcome — compliant, non-compliant, or uncertain
        reportManager.recordComplianceResult(
            instructionText  = instructionText,
            expectedDir      = expectedDirStr,
            actualDir        = actualDirStr,
            compliant        = compliant,
            sensorConfidence = sensorConfidence,
            reactionMs       = reactionMs,
            correctionIssued = correctionIssued
        )

        Log.d("Compliance", "[$expectedDirStr → $actualDirStr] compliant=$compliant " +
                "conf=$sensorConfidence corrected=$correctionIssued rxn=${reactionMs}ms")

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

        val msg = when {
            gap < 15_000L  -> null
            gap < 45_000L  -> "No signs visible yet. Keep moving forward and scan the walls."
            gap < 75_000L  -> "Still no signs. Try turning slowly to check both sides."
            gap < 105_000L -> "I have not found signs in a while. Try retracing your steps to the last sign."
            else           -> "Consider asking someone nearby for directions to $destination."
        } ?: return

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
        "room_direction_left"  -> "directions sign"
        "room_direction_right" -> "directions sign"
        "exit_left"            -> "exit direction sign"
        "exit_right"           -> "exit direction sign"
        "exit_down"            -> "exit sign sign "
        "door_exit"            -> "exit door"
        "stair_sign"           -> "stair sign"
        else                   -> label
    }

    private fun requiresText(label: String) =
        label in setOf("room", "room_direction_left", "room_direction_right", "stair_sign")

    private fun isSignPossibleTarget(label: String): Boolean {
        return when {
            // When searching for an exit, only exit-class signs are relevant
            destinationType == DestinationType.EXIT ->
                label in setOf("exit_left", "exit_right", "door_exit", "exit_down")
            // door_exit always passes — when NOT exit-searching it triggers a
            // "go back, exit ahead" warning so the user doesn't walk into one
            label == "door_exit" -> true
            // All other exit-class signs are irrelevant when not exit-searching
            else -> label !in setOf("exit_left", "exit_right", "door_exit", "exit_down")
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
            bbox.left  > cx -> "right"
            else            -> ""
        }
    }

    private fun isDestinationMatch(ocrText: String): Boolean {
        // Split into individual lines and trim whitespace/CR so that the extra
        // "Study Room" (or "Sala Studio R2" repeated) lines on study-room signs
        // don't pollute the fuzzy comparison for the first line.
        val lines = ocrText
            .split("\n", "\r\n", "\r")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return when (destinationType) {
            DestinationType.STUDY_ROOM -> {
                // The sign has two lines: Italian name + English translation.
                // We need at least one line to contain a study keyword AND at
                // least one line (could be the same) to fuzzy-match the destination.
                val hasStudyKeyword = lines.any { line ->
                    line.contains("studio") || line.contains("study") ||
                            FuzzyLogic.isMatch(line, "studio") || FuzzyLogic.isMatch(line, "study")
                }
                hasStudyKeyword && lines.any { line -> FuzzyLogic.isMatch(line, destination) }
            }
            DestinationType.ROOM -> {
                // Study-room signs must NOT match a plain ROOM search — check every
                // line so "study room" on a second line still disqualifies the sign.
                val isStudyRoomSign = lines.any { line ->
                    line.contains("studio") || line.contains("study")
                }
                if (isStudyRoomSign) false
                else lines.any { line -> FuzzyLogic.isMatch(line, destination) }
            }
            else -> lines.any { line -> FuzzyLogic.isMatch(line, destination) }
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
        webSocketClient.setSocketUrl("ws://192.168.0.56:8080")
        //webSocketClient.setSocketUrl("ws://192.168.1.4:8080")
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
        pendingDestination = dest

        if (!_isNavigationReady.value) {
            Log.w("Nav", "Cannot start navigation: Streaming not ready. Destination stored: $dest")
            return
        }
        destinationType = when {
            dest.lowercase().contains("study room") ||
                    dest.lowercase().contains("aula studio") -> DestinationType.STUDY_ROOM
            // Accept "exit", "door_exit", or any user phrasing containing "exit"
            dest.lowercase().contains("exit")        -> DestinationType.EXIT
            dest.all { it.isDigit() }                -> DestinationType.FLOOR
            else                                     -> DestinationType.ROOM
        }

        applyPendingNavigation()
    }

    private fun applyPendingNavigation() {
        if (pendingDestination.isEmpty()) return

        destination                = pendingDestination
        pendingDestination         = ""
        lastInstruction            = ""
        lastInstructionTime        = 0L
        lastInstructionWasPositive = false
        lastSignTime               = 0L
        lastTimeoutMessage         = ""
        lookingForStairs           = false
        isStopping.set(false)

        reportManager.startSession(destination)
        sessionImageFolder = "Nav_${destination}_${
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        }"
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