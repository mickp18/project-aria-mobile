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
 *
 * HEADING SIGN CONVENTION (Android SensorManager.getOrientation):
 *
 *   Azimuth (orientation[0]) increases clockwise when viewed from above.
 *
 *     headingChange = normalizeAngle(currentHeading - initialHeading)
 *
 *     headingChange > 0  →  clockwise rotation  →  TURN RIGHT
 *     headingChange < 0  →  counter-clockwise   →  TURN LEFT
 *
 *   The previous code had RIGHT and LEFT swapped in both detectActualDirection()
 *   and checkComplianceMatch(), causing the tracker to always report the mirror
 *   of the actual turn (e.g. "you turned right" when the user turned left).
 */

class NavigationTracker(context: Context) : SensorEventListener {

    // ── IMU source selection ─────────────────────────────────────────────────
    private var glassesHeading: Float? = null
    private var glassesAccel: FloatArray? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope    = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accelData = FloatArray(3)
    private val magData   = FloatArray(3)
    private val gyroData  = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)

    fun injectGlassesImu(yaw: Float, accel: FloatArray? = null) {
        glassesHeading = (yaw + 360f) % 360f
        if (accel != null) {
            glassesAccel = accel.clone()
            processGlassesAccel(accel)
        }
        currentPosition.heading = getHeading()
        if (isTrackingCompliance) {
            recordHeadingForCompliance()
        }
    }

    fun headingSource(): String = if (glassesHeading != null) "GLASSES" else "PHONE"

    // ========================================================================
    // MOVEMENT & POSITION TRACKING
    // ========================================================================

    private var lastDetectionPosition = Position(0f, 0f, 0f)
    private var currentPosition = Position(0f, 0f, 0f)
    private var stepCount = 0
    private var lastStepTime = 0L

    private var lastAccelMagnitude = 0f
    private var accelPeak = 0f
    private var isRising = false

    private val accelWindow = ArrayDeque<Float>(20)
    private val ACCEL_WINDOW_SIZE = 20
    private var lastMotionTime = 0L

    private val STEP_THRESHOLD = 10.5f
    private val MIN_STEP_INTERVAL = 250L
    private val STEP_LENGTH = 0.75f
    private val MOVEMENT_THRESHOLD = 1.5f
    private val HEADING_CHANGE_THRESHOLD = 45f

    private val MOTION_ENERGY_THRESHOLD = 0.8f
    private val MOTION_WINDOW_MS = 3000L

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
    private val DETECTION_COOLDOWN = 3000L
    private val MAX_STATIONARY_REDETECTIONS = 2

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

        return distanceMoved >= MOVEMENT_THRESHOLD ||
                headingChanged >= HEADING_CHANGE_THRESHOLD ||
                accelVariance > 1.5f
    }

    fun recordDetectionPosition(signType: String) {
        lastDetectionPosition = currentPosition.copy()
        Log.d("MovementTrack",
            "Recorded detection position: x=${String.format("%.2f", currentPosition.x)}, " +
                    "y=${String.format("%.2f", currentPosition.y)}, heading=${currentPosition.heading.toInt()}°")
    }

    fun shouldProcessDetection(signType: String, signText: String = ""): Boolean {
        val now = SystemClock.uptimeMillis()
        val detectionKey = "$signType:$signText"
        val lastRecord = detectionHistory[detectionKey]

        if (lastRecord == null) {
            detectionHistory[detectionKey] = DetectionRecord(
                firstSeen = now,
                lastSeen = now,
                detectionCount = 1,
                position = currentPosition.copy()
            )
            recordDetectionPosition(signType)
            return true
        }

        if (now - lastRecord.lastSeen < DETECTION_COOLDOWN) {
            Log.d("DetectionFilter", "Sign $detectionKey on cooldown (${now - lastRecord.lastSeen}ms)")
            return false
        }

        val distanceFromLastDetection = calculateDistance(currentPosition, lastRecord.position)
        val headingChange = abs(normalizeAngle(getHeading() - lastRecord.position.heading))
        val accelVariance = getAccelVariance()

        val hasMoved = distanceFromLastDetection >= MOVEMENT_THRESHOLD ||
                headingChange >= HEADING_CHANGE_THRESHOLD ||
                accelVariance > 1.5f

        Log.d("DetectionFilter",
            "$detectionKey | dist=${String.format("%.2f", distanceFromLastDetection)}m | " +
                    "headingΔ=${headingChange.toInt()}° | variance=${String.format("%.3f", accelVariance)} | moved=$hasMoved"
        )

        if (!hasMoved) {
            if (lastRecord.stationaryRedetections >= MAX_STATIONARY_REDETECTIONS) {
                Log.d("DetectionFilter",
                    "Sign $detectionKey ignored - user hasn't moved " +
                            "(${lastRecord.stationaryRedetections} stationary redetections)")
                return false
            }
            lastRecord.stationaryRedetections++
            lastRecord.lastSeen = now
            Log.d("DetectionFilter",
                "Sign $detectionKey allowed (stationary redetection ${lastRecord.stationaryRedetections})")
            return true
        }

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

    fun resetDetectionHistory() {
        detectionHistory.clear()
        lastDetectionPosition = Position(0f, 0f, 0f)
        currentPosition = Position(0f, 0f, 0f)
        stepCount = 0
        Log.d("MovementTrack", "Detection history and position reset")
    }

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
        if (!isTrackingCompliance || expectedDirection == null) return null

        val elapsedTime = SystemClock.uptimeMillis() - complianceStartTime

        if (elapsedTime > 5000L) {
            isTrackingCompliance = false
            return analyzeCompliance()
        }

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

    private fun updatePosition() {
        val heading = getHeading()
        val headingRadians = Math.toRadians(heading.toDouble())
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
                if (glassesAccel == null) {
                    detectStep(event.values)
                    detectMovementForCompliance(event.values)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magData, 0, 3)
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

    private fun processGlassesAccel(accel: FloatArray) {
        detectStep(accel)
        detectMovementForCompliance(accel)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun detectStep(accel: FloatArray) {
        val magnitude = sqrt(
            accel[0] * accel[0] +
                    accel[1] * accel[1] +
                    accel[2] * accel[2]
        )

        val now = SystemClock.uptimeMillis()
        val netAccel = abs(magnitude - SensorManager.GRAVITY_EARTH)

        accelWindow.addLast(netAccel)
        if (accelWindow.size > ACCEL_WINDOW_SIZE) accelWindow.removeFirst()

        if (netAccel > MOTION_ENERGY_THRESHOLD) {
            lastMotionTime = now
        }

        val nowRising = magnitude > lastAccelMagnitude
        if (!nowRising && isRising && accelPeak > STEP_THRESHOLD) {
            if (now - lastStepTime > MIN_STEP_INTERVAL) {
                stepCount++
                lastStepTime = now
                updatePosition()
            }
        }

        isRising = nowRising
        if (nowRising) accelPeak = magnitude
        lastAccelMagnitude = magnitude
    }

    fun hasSignificantMotionRecently(): Boolean {
        val timeSinceMotion = SystemClock.uptimeMillis() - lastMotionTime
        return lastMotionTime > 0 && timeSinceMotion < MOTION_WINDOW_MS
    }

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

    /**
     * Android azimuth increases clockwise (same as a compass bearing).
     *
     *   headingChange = normalizeAngle(currentHeading - initialHeading)
     *
     *   positive → clockwise  → TURN RIGHT
     *   negative → counter-clockwise → TURN LEFT
     *
     * BUG FIX: the previous code had TURN_LEFT and TURN_RIGHT swapped,
     * so every compliance check reported the opposite of the real turn.
     */
    private fun detectActualDirection(headingChange: Float, isMoving: Boolean): Direction {
        if (!isMoving) return Direction.STATIONARY

        return when {
            abs(headingChange) < 20f                      -> Direction.STRAIGHT
            headingChange >  25f && headingChange <  150f -> Direction.TURN_RIGHT  // was TURN_LEFT
            headingChange < -25f && headingChange > -150f -> Direction.TURN_LEFT   // was TURN_RIGHT
            abs(headingChange) > 150f                     -> Direction.TURN_AROUND
            else                                          -> Direction.UNCLEAR
        }
    }

    /**
     * BUG FIX: mirrors the same swap as detectActualDirection.
     * Without this fix, even with correct direction labels the compliance
     * boolean was still wrong because the headingChange sign test was inverted.
     */
    private fun checkComplianceMatch(expected: Direction, actual: Direction, headingChange: Float): Boolean? {
        if (actual == Direction.STATIONARY || actual == Direction.UNCLEAR) return null

        return when (expected) {
            Direction.STRAIGHT    -> abs(headingChange) < 20f
            Direction.TURN_RIGHT  -> headingChange >  25f && headingChange <  150f  // was TURN_LEFT block
            Direction.TURN_LEFT   -> headingChange < -25f && headingChange > -150f  // was TURN_RIGHT block
            Direction.TURN_AROUND -> abs(headingChange) > 150f
            else                  -> null
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
    // HEADING
    // ========================================================================

    private fun getHeading(): Float {
        glassesHeading?.let { return it }
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelData, magData)) {
            return currentPosition.heading
        }
        SensorManager.getOrientation(rotationMatrix, orientation)
        return (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
    }

    private fun getAccel(): FloatArray = glassesAccel ?: accelData

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > 180f)  normalized -= 360f
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