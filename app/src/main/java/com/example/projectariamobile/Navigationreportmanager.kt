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
// Per-frame sample
// ─────────────────────────────────────────────────────────────────────────────

data class FrameSample(
    val frameIndex     : Int,
    val yoloMs         : Long,
    val ocrMs          : Long,
    val totalFrameMs   : Long,
    val detectionsCount: Int,
    val droppedBefore  : Int
)


// ─────────────────────────────────────────────────────────────────────────────
// Rejection reason — one value per disqualification gate in qualifyDetections
// ─────────────────────────────────────────────────────────────────────────────

enum class RejectionReason {
    NOT_TARGET,   // isSignPossibleTarget() returned false
    TOO_SMALL,    // area < PROXIMITY_MIN_AREA_* threshold
    DISTORTED,    // DistortionChecker flagged it AND confidence < DISTORTION_CONF
    OCR_EMPTY     // OCR ran but returned empty/null text
}

// ─────────────────────────────────────────────────────────────────────────────
// Rejected detection record
// ─────────────────────────────────────────────────────────────────────────────

data class RejectedDetection(
    val elapsedMs  : Long,
    val label      : String,
    val confidence : Float,   // YOLO confidence (0–1)
    val areaPct    : Float,   // bbox area as % of frame — compare to threshold × 100
    val reason     : RejectionReason,
    val ocrText    : String   // non-empty only for OCR_EMPTY (shows what OCR actually returned)
)
data class OcrResult(
    val elapsedMs   : Long,
    val label       : String,
    val rawOcrText  : String,   // exactly what ML Kit returned
    val matchedDest : Boolean,  // did FuzzyLogic.isMatch() return true?
    val ocrMs       : Long,
    val destAtTime  : String    // snapshot of destination at time of OCR call
)

private val ocrResults = CopyOnWriteArrayList<OcrResult>()

// ─────────────────────────────────────────────────────────────────────────────
// NavigationReportManager
// ─────────────────────────────────────────────────────────────────────────────

class NavigationReportManager(private val context: Context) {

    // ── Timing samples ───────────────────────────────────────────────────────
    private val frameSamples   = CopyOnWriteArrayList<FrameSample>()
    private var frameIndex     = 0

    // ── Detection class counters ─────────────────────────────────────────────
    private val detectionCounts = mutableMapOf<String, Int>()
    private val qualifiedCounts = mutableMapOf<String, Int>()
    private val instructionLog  = mutableListOf<Pair<Long, String>>()

    // ── Rejection log (thread-safe) ──────────────────────────────────────────
    private val rejectedDetections = CopyOnWriteArrayList<RejectedDetection>()

    // ── Frame counters ───────────────────────────────────────────────────────
    private var totalFramesReceived       = 0
    private var totalFramesDropped        = 0
    private var droppedSinceLastProcessed = 0

    // ── Session timing ───────────────────────────────────────────────────────
    private var sessionStartMs    = 0L
    private var navigationStartMs = 0L
    private var sessionEndMs      = 0L
    private var destination       = ""

    // ── Stair/warning counters ───────────────────────────────────────────────
    private var staircaseWarnings    = 0
    private var stairGuidanceHints   = 0
    private var timeoutGuidanceCount = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun startSession(dest: String) {
        frameSamples.clear()
        detectionCounts.clear()
        qualifiedCounts.clear()
        instructionLog.clear()
        rejectedDetections.clear()
        ocrResults.clear()
        frameIndex                = 0
        totalFramesReceived       = 0
        totalFramesDropped        = 0
        droppedSinceLastProcessed = 0
        staircaseWarnings         = 0
        stairGuidanceHints        = 0
        timeoutGuidanceCount      = 0
        destination               = dest
        sessionStartMs            = SystemClock.uptimeMillis()
        navigationStartMs         = sessionStartMs
    }

    fun endSession(reason: StopReason): File? {
        sessionEndMs = SystemClock.uptimeMillis()
        return writeReport(reason)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame recording API
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
    // ── OCR results log ───────────────────────────────────────────────────────
    private val ocrResults = CopyOnWriteArrayList<OcrResult>()

    fun recordOcrResult(
        label       : String,
        rawOcrText  : String,
        matchedDest : Boolean,
        ocrMs       : Long,
        dest        : String
    ) {
        ocrResults.add(OcrResult(
            elapsedMs   = SystemClock.uptimeMillis() - navigationStartMs,
            label       = label,
            rawOcrText  = rawOcrText,
            matchedDest = matchedDest,
            ocrMs       = ocrMs,
            destAtTime  = dest
        ))
    }

    /**
     * Record a detection that was rejected during qualification.
     * Safe to call from any thread (Dispatchers.Default).
     *
     * @param label      YOLO class label
     * @param confidence YOLO confidence (0–1)
     * @param bboxArea   bbox.width() * bbox.height()  (pixels²)
     * @param frameArea  bitmap.width * bitmap.height  (pixels²)
     * @param reason     which gate rejected it
     * @param ocrText    pass OCR result if reason == OCR_EMPTY, else leave default ""
     */
    fun recordRejectedDetection(
        label      : String,
        confidence : Float,
        bboxArea   : Float,
        frameArea  : Float,
        reason     : RejectionReason,
        ocrText    : String = ""
    ) {
        val elapsed = SystemClock.uptimeMillis() - navigationStartMs
        val areaPct = if (frameArea > 0f) (bboxArea / frameArea) * 100f else 0f
        rejectedDetections.add(
            RejectedDetection(
                elapsedMs  = elapsed,
                label      = label,
                confidence = confidence,
                areaPct    = areaPct,
                reason     = reason,
                ocrText    = ocrText
            )
        )
    }

    fun recordInstruction(text: String) {
        val elapsed = SystemClock.uptimeMillis() - navigationStartMs
        instructionLog.add(Pair(elapsed, text))
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

        val sessionDurationMs = sessionEndMs - sessionStartMs
        val navDurationMs     = sessionEndMs - navigationStartMs
        val samples           = frameSamples.toList()

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
        val avgFps   = if (navDurationMs > 0) (samples.size * 1000.0 / navDurationMs) else 0.0
        val dropRate = if (totalFramesReceived > 0) (totalFramesDropped * 100.0 / totalFramesReceived) else 0.0
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
                val raw  = detectionCounts[label] ?: 0
                val qual = qualifiedCounts[label]  ?: 0
                sb.appendLine("  ${padR(label, 24)}  ${padL(raw.toString(), 7)}  ${padL(qual.toString(), 9)}")
            }
        }
        sb.appendLine()

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

        // ── OCR results ───────────────────────────────────────────────────────────
        // ── OCR results ───────────────────────────────────────────────────────────
        val ocr = ocrResults.toList()
        sb.appendLine("── OCR RESULTS  (${ocr.size} total calls) ──────────────────────────")

        if (ocr.isEmpty()) {
            sb.appendLine("  (no OCR calls recorded)")
        } else {
            val nonEmpty = ocr.filter { it.rawOcrText.isNotEmpty() }
            val empty    = ocr.filter { it.rawOcrText.isEmpty() }
            val matched  = nonEmpty.filter { it.matchedDest }
            val missed   = nonEmpty.filter { !it.matchedDest }

            val nonEmptyRate = if (ocr.isNotEmpty()) nonEmpty.size * 100.0 / ocr.size else 0.0
            val matchRate    = if (nonEmpty.isNotEmpty()) matched.size * 100.0 / nonEmpty.size else 0.0

            sb.appendLine("  Total OCR calls     : ${ocr.size}")
            sb.appendLine("  Non-empty results   : ${nonEmpty.size}  (${fmtPct(nonEmptyRate)})")
            sb.appendLine("  Empty results       : ${empty.size}")
            sb.appendLine("  Destination matches : ${matched.size}  (${fmtPct(matchRate)} of non-empty)")
            sb.appendLine("  Non-matches         : ${missed.size}")
            sb.appendLine()

            // Average latency split by outcome
            if (nonEmpty.isNotEmpty()) {
                val avgMatchMs  = if (matched.isNotEmpty()) matched.map { it.ocrMs }.average() else 0.0
                val avgMissMs   = if (missed.isNotEmpty())  missed.map  { it.ocrMs }.average() else 0.0
                val avgEmptyMs  = if (empty.isNotEmpty())   empty.map   { it.ocrMs }.average() else 0.0
                sb.appendLine("  Avg OCR latency by outcome:")
                sb.appendLine("    Match   : ${fmtDec(avgMatchMs)} ms")
                sb.appendLine("    No-match: ${fmtDec(avgMissMs)} ms")
                sb.appendLine("    Empty   : ${fmtDec(avgEmptyMs)} ms")
                sb.appendLine()
            }

            // Per-label breakdown
            sb.appendLine("  Per-label summary:")
            sb.appendLine("  ${padR("Label", 24)}  ${padL("Calls",5)}  ${padL("NonEmpty",8)}  ${padL("Matched",7)}  ${padL("AvgMs",6)}")
            sb.appendLine("  ${"-".repeat(60)}")
            ocr.groupBy { it.label }.toSortedMap().forEach { (lbl, hits) ->
                val ne  = hits.count { it.rawOcrText.isNotEmpty() }
                val m   = hits.count { it.matchedDest }
                val avg = hits.map { it.ocrMs }.average()
                sb.appendLine("  ${padR(lbl, 24)}  ${padL(hits.size.toString(),5)}  ${padL(ne.toString(),8)}  ${padL(m.toString(),7)}  ${padL(fmtDec(avg),6)}")
            }
            sb.appendLine()

            // Non-match log — the most useful part for manual analysis
            // Shows exactly what OCR returned vs. what was expected
            if (missed.isNotEmpty()) {
                sb.appendLine("  NON-MATCH detail (OCR returned text but it didn't match destination):")
                sb.appendLine("  ${padR("Time", 10)}  ${padR("Label", 22)}  ${padR("Dest", 10)}  Raw OCR text")
                sb.appendLine("  ${"-".repeat(75)}")
                missed.forEach { r ->
                    sb.appendLine(
                        "  ${padR("+${fmtMs(r.elapsedMs)}", 10)}  " +
                                "${padR(r.label, 22)}  " +
                                "${padR(r.destAtTime, 10)}  " +
                                "\"${r.rawOcrText}\""
                    )
                }
                sb.appendLine()
            }
        }

        // ── Rejected detection log ────────────────────────────────────────────
        val rejected = rejectedDetections.toList()
        sb.appendLine("── REJECTED DETECTIONS  (${rejected.size} total) ───────────────────────")
        sb.appendLine("  Area thresholds for reference:")
        sb.appendLine("    exit=${fmtDec(PROXIMITY_MIN_AREA_EXIT    * 100.0)}%  " +
                "room=${fmtDec(PROXIMITY_MIN_AREA_ROOMS   * 100.0)}%  " +
                "opening=${fmtDec(PROXIMITY_MIN_AREA_OPENING * 100.0)}%  " +
                "stair=${fmtDec(PROXIMITY_MIN_AREA_STAIR   * 100.0)}%")
        sb.appendLine()
        if (rejected.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            // Summary counts by reason
            val byReason = rejected.groupBy { it.reason }
            sb.appendLine("  By reason:")
            RejectionReason.entries.forEach { r ->
                val n = byReason[r]?.size ?: 0
                if (n > 0) sb.appendLine("    ${padR(r.name, 14)} : $n")
            }
            sb.appendLine()

            // Area distribution per label for TOO_SMALL — the most useful data
            // for deciding whether to lower a threshold.
            val tooSmall = byReason[RejectionReason.TOO_SMALL]
            if (!tooSmall.isNullOrEmpty()) {
                sb.appendLine("  TOO_SMALL — area% stats per label  (lower threshold if max is close to it):")
                tooSmall.groupBy { it.label }.toSortedMap().forEach { (lbl, hits) ->
                    val areas = hits.map { it.areaPct }.sorted()
                    val p50   = areas[(areas.size * 0.50).toInt().coerceAtMost(areas.size - 1)]
                    sb.appendLine(
                        "    ${padR(lbl, 24)}  n=${padL(hits.size.toString(), 4)}  " +
                                "min=${fmtDec(areas.first().toDouble())}  " +
                                "p50=${fmtDec(p50.toDouble())}  " +
                                "max=${fmtDec(areas.last().toDouble())}"
                    )
                }
                sb.appendLine()
            }

            // Chronological full log
            sb.appendLine("  ${padR("Time", 10)}  ${padR("Label", 22)}  ${padL("Conf",5)}  ${padL("Area%",6)}  Reason")
            sb.appendLine("  ${"-".repeat(75)}")
            rejected.forEach { d ->
                val detail = when (d.reason) {
                    RejectionReason.OCR_EMPTY ->
                        if (d.ocrText.isNotEmpty()) "OCR_EMPTY  got=\"${d.ocrText}\"" else "OCR_EMPTY"
                    else -> d.reason.name
                }
                sb.appendLine(
                    "  ${padR("+${fmtMs(d.elapsedMs)}", 10)}  " +
                            "${padR(d.label, 22)}  " +
                            "${padL(fmtDec(d.confidence.toDouble()), 5)}  " +
                            "${padL(fmtDec(d.areaPct.toDouble()), 6)}  " +
                            detail
                )
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
        val s = ms / 1000; val m = s / 60
        return if (m > 0) "${m}m ${s % 60}s" else "${s}s (${ms}ms)"
    }

    private fun fmtDec(d: Double)       = String.format(Locale.US, "%.2f", d)
    private fun fmtPct(d: Double)       = String.format(Locale.US, "%.1f%%", d)
    private fun padR(s: String, n: Int) = s.padEnd(n)
    private fun padL(s: String, n: Int) = s.padStart(n)

    private fun percentile(values: List<Long>, pct: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val idx    = ((pct / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        // Mirrored from ViewModel so the report can print them without
        // needing a reference back to the ViewModel.
        private const val PROXIMITY_MIN_AREA_EXIT    = 0.001f
        private const val PROXIMITY_MIN_AREA_ROOMS   = 0.003f
        private const val PROXIMITY_MIN_AREA_OPENING = 0.04f
        private const val PROXIMITY_MIN_AREA_STAIR   = 0.08f
    }
}