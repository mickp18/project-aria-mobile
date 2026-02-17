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
 * Combines:
 * 1. Direction compliance (verify user follows instructions)
 * 2. Movement detection (detect if user moved to new position)
 * 3. Position tracking (dead reckoning)
 *
 * Solves: Repeated detections from same sign when user hasn't moved
 */

class NavigationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Sensor readings
    private val accelData = FloatArray(3)
    private val magData = FloatArray(3)
    private val gyroData = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // ========================================================================
    // MOVEMENT & POSITION TRACKING
    // ========================================================================

    private var lastDetectionPosition = Position(0f, 0f, 0f)
    private var currentPosition = Position(0f, 0f, 0f)
    private var stepCount = 0
    private var lastStepTime = 0L
    private var lastAccelMagnitude = 0f

    // Movement thresholds
    private val STEP_THRESHOLD = 13.0f          // Acceleration threshold for step detection
    private val MIN_STEP_INTERVAL = 250L        // Minimum time between steps (ms)
    private val STEP_LENGTH = 0.75f             // Average step length in meters
    private val MOVEMENT_THRESHOLD = 1.5f       // Meters - minimum movement to consider "moved"
    private val HEADING_CHANGE_THRESHOLD = 45f  // Degrees - significant heading change

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
        val headingChanged = abs(normalizeAngle(getCurrentHeading() - lastDetectionPosition.heading))

        Log.d("MovementCheck",
            "Distance: ${String.format("%.2f", distanceMoved)}m, Heading change: ${headingChanged.toInt()}°")

        // User has moved if:
        // 1. Moved more than threshold distance, OR
        // 2. Turned significantly (even if didn't walk far)
        return distanceMoved >= MOVEMENT_THRESHOLD ||
                headingChanged >= HEADING_CHANGE_THRESHOLD
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
    fun shouldProcessDetection(signType: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val detectionKey = signType

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
        val headingChange = abs(normalizeAngle(currentPosition.heading - lastRecord.position.heading))

        val hasMoved = distanceFromLastDetection >= MOVEMENT_THRESHOLD ||
                headingChange >= HEADING_CHANGE_THRESHOLD

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
            currentHeading = getCurrentHeading(),
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
        complianceInitialHeading = getCurrentHeading()
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

    // ========================================================================
    // POSITION CALCULATION
    // ========================================================================

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
        val heading = getCurrentHeading()
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
                detectStep(event.values)
                detectMovementForCompliance(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magData, 0, 3)
                if (isTrackingCompliance) {
                    recordHeadingForCompliance()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, gyroData, 0, 3)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Step detection algorithm
     */
    private fun detectStep(accel: FloatArray) {
        val magnitude = sqrt(
            accel[0] * accel[0] +
                    accel[1] * accel[1] +
                    accel[2] * accel[2]
        )

        val now = SystemClock.uptimeMillis()

        // Detect peak in acceleration (step)
        if (magnitude > STEP_THRESHOLD &&
            magnitude > lastAccelMagnitude &&
            now - lastStepTime > MIN_STEP_INTERVAL) {

            stepCount++
            lastStepTime = now
            updatePosition()
        }

        lastAccelMagnitude = magnitude
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

        val heading = getCurrentHeading()
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

        val currentHeading = getCurrentHeading()
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
    // HELPER FUNCTIONS
    // ========================================================================

    private fun getCurrentHeading(): Float {
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelData, magData)) {
            return 0f
        }

        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        return (azimuth + 360f) % 360f
    }

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