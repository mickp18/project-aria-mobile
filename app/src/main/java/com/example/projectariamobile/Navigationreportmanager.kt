package com.example.projectariamobile

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// ─────────────────────────────────────────────────────────────────────────────
// Stop reason
// ─────────────────────────────────────────────────────────────────────────────

enum class StopReason {
    DESTINATION_FOUND,
    MANUAL_STOP,
    CONNECTION_FAILED
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-frame sample (one row in the raw timing table)
// ─────────────────────────────────────────────────────────────────────────────

data class FrameSample(
    val frameIndex     : Int,
    val yoloMs         : Long,
    val ocrMs          : Long,           // 0 if OCR was not run this frame
    val totalFrameMs   : Long,
    val detectionsCount: Int,
    val droppedBefore  : Int             // dropped frames since last processed frame
)

// ─────────────────────────────────────────────────────────────────────────────
// Fork event record
// ─────────────────────────────────────────────────────────────────────────────

data class ForkEvent(
    val timestampMs   : Long,
    val directions    : List<String>,
    val outcome       : String           // filled in when fork resolves
)

data class YoloDetection(
    val label : String,
    val confidence : Float,
    val bbox : android.graphics.RectF,
    val timestamp : Long
)

// ─────────────────────────────────────────────────────────────────────────────
// NavigationReportManager
// ─────────────────────────────────────────────────────────────────────────────

class NavigationReportManager(private val context: Context) {

    // ── Timing samples ───────────────────────────────────────────────────────
    private val frameSamples   = CopyOnWriteArrayList<FrameSample>()
    private var frameIndex     = 0

    // ── Detection class counters ─────────────────────────────────────────────
    private val detectionCounts = mutableMapOf<String, Int>()   // label → total seen
    private val qualifiedCounts = mutableMapOf<String, Int>()   // label → survived qualification
    private val instructionLog  = mutableListOf<Pair<Long, String>>() // (elapsed ms, text)
    private val yoloDetections  = mutableListOf<YoloDetection>()

    // ── Fork events ──────────────────────────────────────────────────────────
    private val forkEvents = mutableListOf<ForkEvent>()

    // ── Frame counters (mirrors ViewModel stats) ─────────────────────────────
    private var totalFramesReceived = 0
    private var totalFramesDropped  = 0
    private var droppedSinceLastProcessed = 0

    // ── Session timing ───────────────────────────────────────────────────────
    private var sessionStartMs   = 0L
    private var navigationStartMs = 0L   // set when startNavigation() is called
    private var sessionEndMs     = 0L
    private var destination      = ""

    // ── Stair/warning counters ───────────────────────────────────────────────
    private var staircaseWarnings = 0
    private var stairGuidanceHints = 0

    // ── Timeout guidance counter ─────────────────────────────────────────────
    private var timeoutGuidanceCount = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun startSession(dest: String) {
        frameSamples.clear()
        detectionCounts.clear()
        qualifiedCounts.clear()
        yoloDetections.clear()
        instructionLog.clear()
        forkEvents.clear()
        frameIndex               = 0
        totalFramesReceived      = 0
        totalFramesDropped       = 0
        droppedSinceLastProcessed = 0
        staircaseWarnings        = 0
        stairGuidanceHints       = 0
        timeoutGuidanceCount     = 0
        destination              = dest
        sessionStartMs           = SystemClock.uptimeMillis()
        navigationStartMs        = sessionStartMs
    }

    fun endSession(reason: StopReason): File? {
        sessionEndMs = SystemClock.uptimeMillis()
        return writeReport(reason)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame recording API  (called from ViewModel)
    // ─────────────────────────────────────────────────────────────────────────

    fun recordFrameReceived(dropped: Boolean) {
        totalFramesReceived++
        if (dropped) {
            totalFramesDropped++
            droppedSinceLastProcessed++
        }
    }

    fun recordFrameProcessed(
        yoloMs         : Long,
        ocrMs          : Long,
        totalMs        : Long,
        detectionsCount: Int
    ) {
        frameSamples.add(
            FrameSample(
                frameIndex      = frameIndex++,
                yoloMs          = yoloMs,
                ocrMs           = ocrMs,
                totalFrameMs    = totalMs,
                detectionsCount = detectionsCount,
                droppedBefore   = droppedSinceLastProcessed
            )
        )
        droppedSinceLastProcessed = 0
    }

    fun recordDetection(label: String, qualified: Boolean) {
        detectionCounts[label] = (detectionCounts[label] ?: 0) + 1
        if (qualified) qualifiedCounts[label] = (qualifiedCounts[label] ?: 0) + 1
    }

    fun recordInstruction(text: String) {
        val elapsed = SystemClock.uptimeMillis() - navigationStartMs
        instructionLog.add(Pair(elapsed, text))
    }

    fun recordForkDetected(directions: List<String>) {
        val elapsed = SystemClock.uptimeMillis() - navigationStartMs
        forkEvents.add(ForkEvent(elapsed, directions, outcome = "pending"))
    }

    fun recordForkOutcome(outcome: String) {
        if (forkEvents.isNotEmpty()) {
            val last = forkEvents.last()
            forkEvents[forkEvents.lastIndex] = last.copy(outcome = outcome)
        }
    }

    fun recordYoloDetection(label: String, confidence: Float, bbox: android.graphics.RectF, timestamp: Long)
    {
        yoloDetections.add(YoloDetection(label, confidence, bbox, timestamp))
    }

    fun recordStaircaseWarning()  { staircaseWarnings++ }
    fun recordStairGuidanceHint() { stairGuidanceHints++ }
    fun recordTimeoutGuidance()   { timeoutGuidanceCount++ }

    // ─────────────────────────────────────────────────────────────────────────
    // Report generation
    // ─────────────────────────────────────────────────────────────────────────

    private fun writeReport(reason: StopReason): File? {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "NavReports"
            )
            dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file      = File(dir, "nav_report_${destination}_$timestamp.txt")

            file.writeText(buildReportText(reason))
            Log.i("Report", "Saved to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("Report", "Failed to save report: ${e.message}", e)
            null
        }
    }

    private fun buildReportText(reason: StopReason): String {
        val sb = StringBuilder()

        val sessionDurationMs  = sessionEndMs - sessionStartMs
        val navDurationMs      = sessionEndMs - navigationStartMs
        val samples            = frameSamples.toList()

        // ── Header ────────────────────────────────────────────────────────────
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  NAVIGATION SESSION REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  Destination   : $destination")
        sb.appendLine("  Stop reason   : $reason")
        sb.appendLine("  Session date  : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("  Session duration    : ${fmtMs(sessionDurationMs)}")
        sb.appendLine("  Navigation duration : ${fmtMs(navDurationMs)}")
        sb.appendLine()

        // ── Frame throughput ──────────────────────────────────────────────────
        sb.appendLine("── FRAME THROUGHPUT ────────────────────────────────────────")
        val avgFps = if (navDurationMs > 0)
            (samples.size * 1000.0 / navDurationMs) else 0.0
        val dropRate = if (totalFramesReceived > 0)
            (totalFramesDropped * 100.0 / totalFramesReceived) else 0.0

        sb.appendLine("  Frames received   : $totalFramesReceived")
        sb.appendLine("  Frames processed  : ${samples.size}")
        sb.appendLine("  Frames dropped    : $totalFramesDropped  (${fmtPct(dropRate)})")
        sb.appendLine("  Average FPS       : ${fmtDec(avgFps)}")
        sb.appendLine()

        // ── YOLO timing ───────────────────────────────────────────────────────
        if (samples.isNotEmpty()) {
            val yoloTimes = samples.map { it.yoloMs }
            sb.appendLine("── YOLO INFERENCE TIMING ───────────────────────────────────")
            sb.appendLine("  Min   : ${yoloTimes.min()} ms")
            sb.appendLine("  Max   : ${yoloTimes.max()} ms")
            sb.appendLine("  Mean  : ${fmtDec(yoloTimes.average())} ms")
            sb.appendLine("  P50   : ${percentile(yoloTimes, 50)} ms")
            sb.appendLine("  P95   : ${percentile(yoloTimes, 95)} ms")
            sb.appendLine("  P99   : ${percentile(yoloTimes, 99)} ms")
            sb.appendLine()

            // ── OCR timing (only frames where OCR ran) ─────────────────────
            val ocrTimes = samples.filter { it.ocrMs > 0 }.map { it.ocrMs }
            sb.appendLine("── OCR TIMING  (${ocrTimes.size} frames with OCR) ──────────────")
            if (ocrTimes.isNotEmpty()) {
                sb.appendLine("  Min   : ${ocrTimes.min()} ms")
                sb.appendLine("  Max   : ${ocrTimes.max()} ms")
                sb.appendLine("  Mean  : ${fmtDec(ocrTimes.average())} ms")
                sb.appendLine("  P50   : ${percentile(ocrTimes, 50)} ms")
                sb.appendLine("  P95   : ${percentile(ocrTimes, 95)} ms")
            } else {
                sb.appendLine("  No OCR calls recorded.")
            }
            sb.appendLine()

            // ── Total frame processing time ────────────────────────────────
            val totalTimes = samples.map { it.totalFrameMs }
            sb.appendLine("── TOTAL FRAME PROCESSING TIME ─────────────────────────────")
            sb.appendLine("  Min   : ${totalTimes.min()} ms")
            sb.appendLine("  Max   : ${totalTimes.max()} ms")
            sb.appendLine("  Mean  : ${fmtDec(totalTimes.average())} ms")
            sb.appendLine("  P95   : ${percentile(totalTimes, 95)} ms")
            sb.appendLine()
        }

        // ── Detection counts ──────────────────────────────────────────────────
        sb.appendLine("── YOLO DETECTIONS PER CLASS ───────────────────────────────")
        sb.appendLine("  ${padR("Label", 24)}  ${padL("Raw", 7)}  ${padL("Qualified", 9)}")
        sb.appendLine("  ${"-".repeat(44)}")
        val allLabels = (detectionCounts.keys + qualifiedCounts.keys).toSortedSet()
        if (allLabels.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            allLabels.forEach { label ->
                val raw  = detectionCounts[label]  ?: 0
                val qual = qualifiedCounts[label]  ?: 0
                sb.appendLine("  ${padR(label, 24)}  ${padL(raw.toString(), 7)}  ${padL(qual.toString(), 9)}")
            }
        }
        sb.appendLine()

        // Display all the yolo detections
        sb.appendLine("── YOLO DETECTIONS ───────────────────────────────────────────")
        for (det : YoloDetection in yoloDetections){
            val area = det.bbox.width()*det.bbox.height()
            sb.appendLine("  ${padR(det.label, 24)}  ${padR(det.confidence.toString(), 10)}  ${padR(area.toString(), 10)}  ${padR(det.timestamp.toString(), 10)}")
        }


        // ── Average detections per processed frame ────────────────────────────
        if (samples.isNotEmpty()) {
            val avgDet = samples.map { it.detectionsCount }.average()
            sb.appendLine("  Average YOLO detections/frame : ${fmtDec(avgDet)}")
            sb.appendLine()
        }

        // ── Guidance events ───────────────────────────────────────────────────
        sb.appendLine("── GUIDANCE EVENTS ─────────────────────────────────────────")
        sb.appendLine("  Instructions emitted    : ${instructionLog.size}")
        sb.appendLine("  Timeout guidance count  : $timeoutGuidanceCount")
        sb.appendLine("  Staircase warnings      : $staircaseWarnings")
        sb.appendLine("  Stair guidance hints    : $stairGuidanceHints")
        sb.appendLine()

        // ── Fork events ───────────────────────────────────────────────────────
        sb.appendLine("── FORK EVENTS  (${forkEvents.size} total) ──────────────────────────")
        if (forkEvents.isEmpty()) {
            sb.appendLine("  No forks encountered.")
        } else {
            forkEvents.forEachIndexed { i, ev ->
                sb.appendLine("  Fork ${i + 1}:")
                sb.appendLine("    At       : +${fmtMs(ev.timestampMs)}")
                sb.appendLine("    Dirs     : ${ev.directions.joinToString(", ")}")
                sb.appendLine("    Outcome  : ${ev.outcome}")
            }
        }
        sb.appendLine()

        // ── Instruction timeline ──────────────────────────────────────────────
        sb.appendLine("── INSTRUCTION TIMELINE ────────────────────────────────────")
        if (instructionLog.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            instructionLog.forEach { (elapsedMs, text) ->
                sb.appendLine("  +${fmtMs(elapsedMs).padStart(9)}  →  $text")
            }
        }
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  END OF REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")

        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        return if (m > 0) "${m}m ${s % 60}s" else "${s}s (${ms}ms)"
    }

    private fun fmtDec(d: Double)   = String.format(Locale.US, "%.2f", d)
    private fun fmtPct(d: Double)   = String.format(Locale.US, "%.1f%%", d)
    private fun padR(s: String, n: Int) = s.padEnd(n)
    private fun padL(s: String, n: Int) = s.padStart(n)

    private fun percentile(values: List<Long>, pct: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val idx    = ((pct / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}