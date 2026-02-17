package com.example.projectariamobile

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.*

/**
 * UNIFIED NAVIGATION TRACKER
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │  IMU DATA SOURCES                                        │
 * │                                                          │
 * │  PHONE sensors (always available):                       │
 * │    - Accelerometer → step detection, motion energy       │
 * │    - Magnetometer  → phone compass heading (fallback)    │
 * │                                                          │
 * │  GLASSES IMU (feed via injectGlassesImu() when ready):   │
 * │    - Heading/orientation → replaces phone heading        │
 * │    - Acceleration → can replace phone steps              │
 * │                                                          │
 * │  RIGHT NOW: phone-only mode. Call injectGlassesImu()     │
 * │  once you stream IMU data from Aria alongside video.     │
 * └─────────────────────────────────────────────────────────┘
 */

class NavigationTracker(context: Context) : SensorEventListener {

    // ── IMU source selection ─────────────────────────────────────────────────
    // When glassesHeading is non-null it is used instead of the phone compass.
    // When glassesAccel is non-null it is used instead of the phone accel.
    private var glassesHeading: Float? = null   // degrees 0-360, null = use phone
    private var glassesAccel: FloatArray? = null // [x,y,z] m/s², null = use phone

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope    = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Raw phone sensor readings (always collected as fallback)
    private val accelData = FloatArray(3)
    private val magData   = FloatArray(3)
    private val gyroData  = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)

    /**
     * Call this every time you receive an IMU packet from the Aria glasses.
     * Once called, the tracker uses glasses data for heading (and optionally
     * acceleration) instead of the phone sensors.
     *
     * @param yaw   Glasses yaw in degrees (0-360). This is the heading.
     * @param accel Optional [x,y,z] linear acceleration from glasses (m/s²).
     *              Pass null to keep using phone accelerometer for steps.
     */
    fun injectGlassesImu(yaw: Float, accel: FloatArray? = null) {
        glassesHeading = (yaw + 360f) % 360f
        if (accel != null) {
            glassesAccel = accel.clone()
            // Route glasses accel through the same step/motion pipeline
            processGlassesAccel(accel)
        }
        // Always update position heading with the best available source
        currentPosition.heading = getHeading()
        if (isTrackingCompliance) {
            recordHeadingForCompliance()
        }
    }

    /** Which heading source is active right now. Useful for debug UI. */
    fun headingSource(): String = if (glassesHeading != null) "GLASSES" else "PHONE"

    // ========================================================================
    // MOVEMENT & POSITION TRACKING
    // ========================================================================

    private var lastDetectionPosition = Position(0f, 0f, 0f)
    private var currentPosition = Position(0f, 0f, 0f)
    private var stepCount = 0
    private var lastStepTime = 0L

    // FIX 2: Proper peak detection state
    private var lastAccelMagnitude = 0f
    private var accelPeak = 0f
    private var isRising = false

    // FIX 3: Accelerometer energy window (replaces step-only movement)
    private val accelWindow = ArrayDeque<Float>(20)
    private val ACCEL_WINDOW_SIZE = 20
    private var lastMotionTime = 0L

    // Movement thresholds
    // FIX 2: Much lower threshold for head-mounted device
    private val STEP_THRESHOLD = 10.5f          // Was 13.0 - head-mounted needs lower value
    private val MIN_STEP_INTERVAL = 250L
    private val STEP_LENGTH = 0.75f
    private val MOVEMENT_THRESHOLD = 1.5f
    private val HEADING_CHANGE_THRESHOLD = 45f

    // FIX 3: Motion energy threshold (variance-based, not step-based)
    private val MOTION_ENERGY_THRESHOLD = 0.8f  // m/s² std deviation to count as "in motion"
    private val MOTION_WINDOW_MS = 3000L        // Consider "still moving" for 3s after last motion

    // ========================================================================
    // DIRECTION COMPLIANCE TRACKING
    // ========================================================================

    private var isTrackingCompliance = false
    private var expectedDirection: Direction? = null
    private var complianceStartTime = 0L
    private var complianceInitialHeading = 0f
    private var complianceHeadingHistory = mutableListOf<HeadingMeasurement>()
    private var complianceMovementDetected = false

    // ========================================================================
    // DETECTION FILTERING
    // ========================================================================

    private val detectionHistory = mutableMapOf<String, DetectionRecord>()
    private val DETECTION_COOLDOWN = 3000L      // 3 seconds minimum between same detections
    private val MAX_STATIONARY_REDETECTIONS = 2 // Max times to detect same sign without moving

    init {
        startSensors()
    }

    private fun startSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    // ========================================================================
    // MOVEMENT DETECTION API
    // ========================================================================

    /**
     * Check if user has moved since last detection
     * Call this BEFORE processing a new detection
     *
     * @return true if user has moved enough to allow new detection
     */
    fun hasMovedSinceLastDetection(signType: String): Boolean {
        val distanceMoved = calculateDistanceFromLastDetection()
        val headingChanged = abs(normalizeAngle(getHeading() - lastDetectionPosition.heading))
        val accelVariance = getAccelVariance()
        val recentMotion = hasSignificantMotionRecently()

        Log.d("MovementCheck",
            "Distance: ${String.format("%.2f", distanceMoved)}m | " +
                    "Heading Δ: ${headingChanged.toInt()}° | " +
                    "AccelVariance: ${String.format("%.3f", accelVariance)} | " +
                    "RecentMotion: $recentMotion | " +
                    "Steps: $stepCount"
        )

        // User has moved if ANY of these are true:
        // 1. Dead reckoning distance (step-based)
        // 2. Significant heading change
        // 3. Accelerometer variance shows movement (catches non-step motion)
        return distanceMoved >= MOVEMENT_THRESHOLD ||
                headingChanged >= HEADING_CHANGE_THRESHOLD ||
                accelVariance > 1.5f  // High variance = definitely moving
    }

    /**
     * Mark current position as location of last detection
     * Call this AFTER accepting a detection
     */
    fun recordDetectionPosition(signType: String) {
        lastDetectionPosition = currentPosition.copy()

        Log.d("MovementTrack",
            "Recorded detection position: x=${String.format("%.2f", currentPosition.x)}, " +
                    "y=${String.format("%.2f", currentPosition.y)}, heading=${currentPosition.heading.toInt()}°")
    }

    /**
     * Advanced detection filtering with spam prevention
     *
     * @param signType Type of sign detected (e.g., "room_direction_left")
     * @param signText OCR text from sign (e.g., "R1")
     * @return true if detection should be processed, false if should be ignored
     */
    fun shouldProcessDetection(signType: String, signText: String = ""): Boolean {
        val now = SystemClock.uptimeMillis()
        val detectionKey = "$signType:$signText"

        val lastRecord = detectionHistory[detectionKey]

        if (lastRecord == null) {
            // First time seeing this sign - always process
            detectionHistory[detectionKey] = DetectionRecord(
                firstSeen = now,
                lastSeen = now,
                detectionCount = 1,
                position = currentPosition.copy()
            )
            recordDetectionPosition(signType)
            return true
        }

        // Check cooldown
        if (now - lastRecord.lastSeen < DETECTION_COOLDOWN) {
            Log.d("DetectionFilter", "Sign $detectionKey on cooldown (${now - lastRecord.lastSeen}ms)")
            return false
        }

        // Check if user has moved since last detection of this sign
        val distanceFromLastDetection = calculateDistance(currentPosition, lastRecord.position)
        // FIX 1: Use live heading instead of stale position heading
        val headingChange = abs(normalizeAngle(getHeading() - lastRecord.position.heading))
        val accelVariance = getAccelVariance()

        val hasMoved = distanceFromLastDetection >= MOVEMENT_THRESHOLD ||
                headingChange >= HEADING_CHANGE_THRESHOLD ||
                accelVariance > 1.5f  // High variance = device is moving

        Log.d("DetectionFilter",
            "$detectionKey | dist=${String.format("%.2f", distanceFromLastDetection)}m | " +
                    "headingΔ=${headingChange.toInt()}° | variance=${String.format("%.3f", accelVariance)} | moved=$hasMoved"
        )

        if (!hasMoved) {
            // User hasn't moved - check redetection count
            if (lastRecord.stationaryRedetections >= MAX_STATIONARY_REDETECTIONS) {
                Log.d("DetectionFilter",
                    "Sign $detectionKey ignored - user hasn't moved " +
                            "(${lastRecord.stationaryRedetections} stationary redetections)")
                return false
            }

            // Allow but increment counter
            lastRecord.stationaryRedetections++
            lastRecord.lastSeen = now
            Log.d("DetectionFilter",
                "Sign $detectionKey allowed (stationary redetection ${lastRecord.stationaryRedetections})")
            return true
        }

        // User has moved - reset and allow
        lastRecord.lastSeen = now
        lastRecord.detectionCount++
        lastRecord.stationaryRedetections = 0
        lastRecord.position = currentPosition.copy()

        recordDetectionPosition(signType)

        Log.d("DetectionFilter",
            "Sign $detectionKey allowed - user moved ${String.format("%.2f", distanceFromLastDetection)}m, " +
                    "heading changed ${headingChange.toInt()}°")

        return true
    }

    /**
     * Clear detection history (call when starting new navigation)
     */
    fun resetDetectionHistory() {
        detectionHistory.clear()
        lastDetectionPosition = Position(0f, 0f, 0f)
        currentPosition = Position(0f, 0f, 0f)
        stepCount = 0

        Log.d("MovementTrack", "Detection history and position reset")
    }

    /**
     * Get current movement state
     */
    fun getMovementState(): MovementState {
        val distanceMoved = calculateDistanceFromLastDetection()
        val isMoving = SystemClock.uptimeMillis() - lastStepTime < 2000L

        return MovementState(
            distanceFromLastDetection = distanceMoved,
            totalSteps = stepCount,
            currentHeading = getHeading(),
            isMoving = isMoving,
            currentPosition = currentPosition.copy()
        )
    }

    // ========================================================================
    // DIRECTION COMPLIANCE API
    // ========================================================================

    fun startTrackingCompliance(direction: Direction) {
        isTrackingCompliance = true
        expectedDirection = direction
        complianceStartTime = SystemClock.uptimeMillis()
        complianceInitialHeading = getHeading()
        complianceHeadingHistory.clear()
        complianceMovementDetected = false

        Log.d("ComplianceTracker", "Started tracking: $direction, Initial heading: ${complianceInitialHeading.toInt()}°")
    }

    fun checkCompliance(): ComplianceStatus? {
        if (!isTrackingCompliance || expectedDirection == null) {
            return null
        }

        val elapsedTime = SystemClock.uptimeMillis() - complianceStartTime

        // Auto-stop after 5 seconds
        if (elapsedTime > 5000L) {
            isTrackingCompliance = false
            return analyzeCompliance()
        }

        // Need at least 1 second of data
        if (elapsedTime < 1000L) {
            return ComplianceStatus(
                expected = expectedDirection!!,
                actual = null,
                compliant = null,
                confidence = 0f,
                reason = "Collecting data..."
            )
        }

        return analyzeCompliance()
    }

    fun stopTrackingCompliance() {
        isTrackingCompliance = false
        expectedDirection = null
    }

    /**
     * Call this to understand why movement isn't being detected.
     * Log this every few seconds to see raw sensor values.
     */
    fun debugSensorValues(): String {
        val accel = getAccel()
        val magnitude = sqrt(accel[0]*accel[0] + accel[1]*accel[1] + accel[2]*accel[2])
        val netAccel = abs(magnitude - SensorManager.GRAVITY_EARTH)

        return """
            === Movement Debug ===
            Heading source:   ${headingSource()}
            Accel source:     ${if (glassesAccel != null) "GLASSES" else "PHONE"}
            Accel magnitude:  ${String.format("%.2f", magnitude)} m/s²  (gravity=9.8)
            Net acceleration: ${String.format("%.2f", netAccel)} m/s²   (>0 when moving)
            Accel variance:   ${String.format("%.3f", getAccelVariance())}           (>1.5 = moving)
            Step threshold:   $STEP_THRESHOLD m/s²
            Steps detected:   $stepCount
            ----------------------
            Current heading:  ${String.format("%.1f", getHeading())}°
            Last det heading: ${String.format("%.1f", lastDetectionPosition.heading)}°
            Heading delta:    ${String.format("%.1f", abs(normalizeAngle(getHeading() - lastDetectionPosition.heading)))}°
            Heading threshold:$HEADING_CHANGE_THRESHOLD°
            ----------------------
            Distance moved:   ${String.format("%.2f", calculateDistanceFromLastDetection())}m
            Distance thresh:  ${MOVEMENT_THRESHOLD}m
            Recent motion:    ${hasSignificantMotionRecently()}
            Last motion:      ${SystemClock.uptimeMillis() - lastMotionTime}ms ago
            =====================
        """.trimIndent()
    }

    private fun calculateDistanceFromLastDetection(): Float {
        return calculateDistance(currentPosition, lastDetectionPosition)
    }

    private fun calculateDistance(pos1: Position, pos2: Position): Float {
        val dx = pos1.x - pos2.x
        val dy = pos1.y - pos2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Update position using dead reckoning
     * Called when a step is detected
     */
    private fun updatePosition() {
        val heading = getHeading()
        val headingRadians = Math.toRadians(heading.toDouble())

        // Update position based on heading and step length
        currentPosition.x += (STEP_LENGTH * sin(headingRadians)).toFloat()
        currentPosition.y += (STEP_LENGTH * cos(headingRadians)).toFloat()
        currentPosition.heading = heading
    }

    // ========================================================================
    // SENSOR EVENT HANDLING
    // ========================================================================

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelData, 0, 3)
                // Only use phone accel for step/motion if glasses accel not available
                if (glassesAccel == null) {
                    detectStep(event.values)
                    detectMovementForCompliance(event.values)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magData, 0, 3)
                // Always update heading from best available source
                currentPosition.heading = getHeading()
                if (isTrackingCompliance) {
                    recordHeadingForCompliance()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, gyroData, 0, 3)
            }
        }
    }

    /**
     * Called from injectGlassesImu() to process glasses accelerometer data
     * through the same step/motion pipeline as the phone accel.
     */
    private fun processGlassesAccel(accel: FloatArray) {
        detectStep(accel)
        detectMovementForCompliance(accel)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * FIX 2: Proper peak detection step algorithm
     * Old: fired when magnitude > last (only on rising edge = misses most peaks)
     * New: detects actual peak (was rising, now falling, above threshold)
     */
    private fun detectStep(accel: FloatArray) {
        val magnitude = sqrt(
            accel[0] * accel[0] +
                    accel[1] * accel[1] +
                    accel[2] * accel[2]
        )

        val now = SystemClock.uptimeMillis()
        val netAccel = abs(magnitude - SensorManager.GRAVITY_EARTH)

        // FIX 3: Feed energy window for variance-based detection
        accelWindow.addLast(netAccel)
        if (accelWindow.size > ACCEL_WINDOW_SIZE) accelWindow.removeFirst()

        // If there's significant motion energy, record time
        if (netAccel > MOTION_ENERGY_THRESHOLD) {
            lastMotionTime = now
        }

        // Peak detection: were rising, now falling, and peak was above threshold
        val nowRising = magnitude > lastAccelMagnitude
        if (!nowRising && isRising && accelPeak > STEP_THRESHOLD) {
            // Peak detected!
            if (now - lastStepTime > MIN_STEP_INTERVAL) {
                stepCount++
                lastStepTime = now
                updatePosition()
                Log.d("StepDetection", "Step #$stepCount detected, peak=$accelPeak")
            }
        }

        isRising = nowRising
        if (nowRising) accelPeak = magnitude
        lastAccelMagnitude = magnitude
    }

    /**
     * FIX 3: Check if device has had significant motion recently
     * Uses accelerometer energy variance, not just step count.
     * Works even when steps aren't cleanly detected (e.g., head-mounted device).
     */
    fun hasSignificantMotionRecently(): Boolean {
        val timeSinceMotion = SystemClock.uptimeMillis() - lastMotionTime
        return lastMotionTime > 0 && timeSinceMotion < MOTION_WINDOW_MS
    }

    /**
     * FIX 3: Get accelerometer variance over recent window
     * High variance = device is moving
     */
    private fun getAccelVariance(): Float {
        if (accelWindow.size < 5) return 0f
        val mean = accelWindow.average().toFloat()
        return accelWindow.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private fun detectMovementForCompliance(accel: FloatArray) {
        if (!isTrackingCompliance) return

        val magnitude = sqrt(
            accel[0] * accel[0] +
                    accel[1] * accel[1] +
                    accel[2] * accel[2]
        )

        val netAccel = abs(magnitude - SensorManager.GRAVITY_EARTH)

        if (netAccel > 1.5f) {
            complianceMovementDetected = true
        }
    }

    private fun recordHeadingForCompliance() {
        if (!isTrackingCompliance) return

        val heading = getHeading()
        val timestamp = SystemClock.uptimeMillis()

        complianceHeadingHistory.add(HeadingMeasurement(heading, timestamp))

        // Keep only last 5 seconds
        complianceHeadingHistory.removeAll {
            timestamp - it.timestamp > 5000L
        }
    }

    // ========================================================================
    // COMPLIANCE ANALYSIS
    // ========================================================================

    private fun analyzeCompliance(): ComplianceStatus {
        val expected = expectedDirection ?: return ComplianceStatus(
            expected = Direction.UNKNOWN,
            actual = Direction.UNKNOWN,
            compliant = false,
            confidence = 0f,
            reason = "No expected direction set"
        )

        if (complianceHeadingHistory.size < 5) {
            return ComplianceStatus(
                expected = expected,
                actual = null,
                compliant = null,
                confidence = 0f,
                reason = "Insufficient heading data",
                isMoving = complianceMovementDetected
            )
        }

        val currentHeading = getHeading()
        val headingChange = normalizeAngle(currentHeading - complianceInitialHeading)

        val actualDirection = detectActualDirection(headingChange, complianceMovementDetected)
        val isCompliant = checkComplianceMatch(expected, actualDirection, headingChange)
        val confidence = calculateConfidence(headingChange, complianceMovementDetected)
        val reason = generateReason(expected, actualDirection, headingChange, complianceMovementDetected)

        return ComplianceStatus(
            expected = expected,
            actual = actualDirection,
            compliant = isCompliant,
            confidence = confidence,
            reason = reason,
            headingChange = headingChange,
            isMoving = complianceMovementDetected
        )
    }

    private fun detectActualDirection(headingChange: Float, isMoving: Boolean): Direction {
        if (!isMoving) return Direction.STATIONARY

        return when {
            abs(headingChange) < 20f -> Direction.STRAIGHT
            headingChange > 25f && headingChange < 150f -> Direction.TURN_LEFT
            headingChange < -25f && headingChange > -150f -> Direction.TURN_RIGHT
            abs(headingChange) > 150f -> Direction.TURN_AROUND
            else -> Direction.UNCLEAR
        }
    }

    private fun checkComplianceMatch(expected: Direction, actual: Direction, headingChange: Float): Boolean? {
        if (actual == Direction.STATIONARY || actual == Direction.UNCLEAR) return null

        return when (expected) {
            Direction.STRAIGHT -> abs(headingChange) < 20f
            Direction.TURN_LEFT -> headingChange > 25f && headingChange < 150f
            Direction.TURN_RIGHT -> headingChange < -25f && headingChange > -150f
            Direction.TURN_AROUND -> abs(headingChange) > 150f
            else -> null
        }
    }

    private fun calculateConfidence(headingChange: Float, isMoving: Boolean): Float {
        if (!isMoving) return 0.3f

        val clarity = min(abs(headingChange) / 90f, 1f)
        val dataQuality = min(complianceHeadingHistory.size / 20f, 1f)

        return (clarity * 0.7f + dataQuality * 0.3f).coerceIn(0f, 1f)
    }

    private fun generateReason(
        expected: Direction,
        actual: Direction,
        headingChange: Float,
        isMoving: Boolean
    ): String {
        if (!isMoving) return "User is not moving yet"
        if (actual == Direction.UNCLEAR) return "Movement detected but direction unclear"

        val angle = abs(headingChange).toInt()

        return when {
            expected == actual -> "User correctly ${expected.name.lowercase()} (~$angle°)"
            else -> "User performed $actual instead of $expected"
        }
    }

    // ========================================================================
    // HEADING: uses glasses IMU when available, phone compass as fallback
    // ========================================================================

    /**
     * Best available heading (degrees, 0-360).
     * Priority: glasses yaw > phone magnetometer.
     */
    private fun getHeading(): Float {
        glassesHeading?.let { return it }  // glasses data available → use it

        // Fallback: phone compass
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelData, magData)) {
            return currentPosition.heading  // can't compute, return last known
        }
        SensorManager.getOrientation(rotationMatrix, orientation)
        return (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
    }

    /**
     * Best available acceleration array [x,y,z] in m/s².
     * Priority: glasses accel > phone accel.
     */
    private fun getAccel(): FloatArray = glassesAccel ?: accelData

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

data class Position(
    var x: Float,
    var y: Float,
    var heading: Float
)

data class DetectionRecord(
    val firstSeen: Long,
    var lastSeen: Long,
    var detectionCount: Int,
    var position: Position,
    var stationaryRedetections: Int = 0
)

data class MovementState(
    val distanceFromLastDetection: Float,
    val totalSteps: Int,
    val currentHeading: Float,
    val isMoving: Boolean,
    val currentPosition: Position
)

data class HeadingMeasurement(
    val heading: Float,
    val timestamp: Long
)

data class ComplianceStatus(
    val expected: Direction,
    val actual: Direction?,
    val compliant: Boolean?,
    val confidence: Float,
    val reason: String,
    val headingChange: Float = 0f,
    val isMoving: Boolean = false
)

enum class Direction {
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    TURN_AROUND,
    STATIONARY,
    UNCLEAR,
    UNKNOWN
}