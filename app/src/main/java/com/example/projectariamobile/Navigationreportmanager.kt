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
import java.util.concurrent.atomic.AtomicInteger

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
    val frameId        : Int,   // stable ID shared with image filenames
    val frameIndex     : Int,
    val yoloMs         : Long,
    val ocrMs          : Long,
    val totalFrameMs   : Long,
    val detectionsCount: Int,
    val droppedBefore  : Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Rejection reason
// ─────────────────────────────────────────────────────────────────────────────

enum class RejectionReason {
    NOT_TARGET,
    TOO_SMALL,
    DISTORTED,
    OCR_EMPTY
}

// ─────────────────────────────────────────────────────────────────────────────
// Rejected detection record
// ─────────────────────────────────────────────────────────────────────────────

data class RejectedDetection(
    val frameId    : Int,         // matches image filename prefix F#####
    val elapsedMs  : Long,
    val label      : String,
    val confidence : Float,
    val areaPct    : Float,
    val reason     : RejectionReason,
    val ocrText    : String
)

data class OcrResult(
    val frameId     : Int,         // matches image filename prefix F#####
    val elapsedMs   : Long,
    val label       : String,
    val rawOcrText  : String,
    val matchedDest : Boolean,
    val ocrMs       : Long,
    val destAtTime  : String
)

// ─────────────────────────────────────────────────────────────────────────────
// Compliance tracking
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Outcome of one compliance check, recorded 3 s after a directional instruction.
 *
 * [instructionText]  — the spoken instruction that triggered tracking
 * [expectedDir]      — the direction the app told the user to take
 * [actualDir]        — the direction the NavigationTracker measured (null = unknown)
 * [compliant]        — true/false/null: null means the tracker had no confident reading
 * [sensorConfidence] — the tracker's confidence value (0–1) at decision time
 * [reactionMs]       — ms from instruction emit to compliance-check completion
 * [correctionIssued] — whether a correction instruction was subsequently emitted
 */
data class ComplianceEvent(
    val elapsedMs         : Long,
    val instructionText   : String,
    val expectedDir       : String,
    val actualDir         : String,
    val compliant         : Boolean?,   // null = tracker had no confident reading
    val sensorConfidence  : Float,
    val reactionMs        : Long,
    val correctionIssued  : Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// NavigationReportManager
// ─────────────────────────────────────────────────────────────────────────────

class NavigationReportManager(private val context: Context) {

    // ── Frame ID counter — atomic so Dispatchers.Default threads are safe ────
    private val frameIdCounter = AtomicInteger(0)

    // ── Timing samples ───────────────────────────────────────────────────────
    private val frameSamples   = CopyOnWriteArrayList<FrameSample>()
    private var frameIndex     = 0

    // ── Detection class counters ─────────────────────────────────────────────
    private val detectionCounts = mutableMapOf<String, Int>()
    private val qualifiedCounts = mutableMapOf<String, Int>()
    private val instructionLog  = mutableListOf<Pair<Long, String>>()

    // ── OCR + rejection + compliance logs (thread-safe) ─────────────────────
    private val ocrResults         = CopyOnWriteArrayList<OcrResult>()
    private val rejectedDetections = CopyOnWriteArrayList<RejectedDetection>()
    private val complianceLog      = CopyOnWriteArrayList<ComplianceEvent>()

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
    // Frame ID API
    //
    // Call nextFrameId() ONCE at the very start of processFrame().
    // Pass the returned Int to every image save and every recordXxx() call for
    // that frame.  frameTag() gives you the zero-padded string used in filenames.
    //
    //   val frameId = reportManager.nextFrameId()
    //   val tag     = reportManager.frameTag(frameId)   // → "F00042"
    //   saveBitmapToGallery(..., fileName = "${tag}_YOLO_room_....jpg", ...)
    // ─────────────────────────────────────────────────────────────────────────

    fun nextFrameId(): Int = frameIdCounter.getAndIncrement()

    fun frameTag(frameId: Int): String = "F%05d".format(frameId)

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
        complianceLog.clear()
        frameIdCounter.set(0)
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

    /** Call after YOLO + OCR finish for one frame. */
    fun recordFrameProcessed(
        frameId        : Int,
        yoloMs         : Long,
        ocrMs          : Long,
        totalMs        : Long,
        detectionsCount: Int
    ) {
        frameSamples.add(
            FrameSample(
                frameId         = frameId,
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

    fun recordOcrResult(
        frameId     : Int,
        label       : String,
        rawOcrText  : String,
        matchedDest : Boolean,
        ocrMs       : Long,
        dest        : String
    ) {
        ocrResults.add(OcrResult(
            frameId     = frameId,
            elapsedMs   = SystemClock.uptimeMillis() - navigationStartMs,
            label       = label,
            rawOcrText  = rawOcrText,
            matchedDest = matchedDest,
            ocrMs       = ocrMs,
            destAtTime  = dest
        ))
    }

    fun recordRejectedDetection(
        frameId    : Int,
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
                frameId    = frameId,
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

    /**
     * Call from checkUserCompliance() after the NavigationTracker returns a result.
     *
     * @param instructionText   the spoken instruction that started tracking
     * @param expectedDir       direction the app instructed (e.g. "TURN_LEFT")
     * @param actualDir         direction the tracker measured (e.g. "STRAIGHT"), or "UNKNOWN"
     * @param compliant         tracker's compliant field (true/false/null)
     * @param sensorConfidence  tracker's confidence value
     * @param reactionMs        ms elapsed from instruction emit to this check
     * @param correctionIssued  true if a correction was spoken to the user
     */
    fun recordComplianceResult(
        instructionText  : String,
        expectedDir      : String,
        actualDir        : String,
        compliant        : Boolean?,
        sensorConfidence : Float,
        reactionMs       : Long,
        correctionIssued : Boolean
    ) {
        complianceLog.add(
            ComplianceEvent(
                elapsedMs        = SystemClock.uptimeMillis() - navigationStartMs,
                instructionText  = instructionText,
                expectedDir      = expectedDir,
                actualDir        = actualDir,
                compliant        = compliant,
                sensorConfidence = sensorConfidence,
                reactionMs       = reactionMs,
                correctionIssued = correctionIssued
            )
        )
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
        sb.appendLine("  HOW TO MATCH REPORT ENTRIES TO SAVED IMAGES")
        sb.appendLine("  ─────────────────────────────────────────────")
        sb.appendLine("  Every saved image filename starts with a frame tag, e.g.:")
        sb.appendLine("    F00042_YOLO_room_1234567890.jpg")
        sb.appendLine("    F00042_OCR_raw_room_1234567890.jpg")
        sb.appendLine("    F00042_OCR_annotated_room_1234567890.jpg")
        sb.appendLine("  Search this report for 'F00042' to find every log entry")
        sb.appendLine("  (YOLO detections, OCR results, rejections) for that frame.")
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

        // ── Per-frame YOLO log ─────────────────────────────────────────────────
        sb.appendLine("── PER-FRAME YOLO LOG ──────────────────────────────────────")
        sb.appendLine("  ${padR("FrameTag", 9)}  ${padL("YoloMs",7)}  ${padL("OcrMs",6)}  ${padL("TotalMs",8)}  ${padL("Dets",5)}  ${padL("DroppedBefore",13)}")
        sb.appendLine("  ${"-".repeat(60)}")
        samples.forEach { s ->
            sb.appendLine(
                "  ${padR(frameTag(s.frameId), 9)}  " +
                        "${padL(s.yoloMs.toString(), 7)}  " +
                        "${padL(if (s.ocrMs > 0) s.ocrMs.toString() else "-", 6)}  " +
                        "${padL(s.totalFrameMs.toString(), 8)}  " +
                        "${padL(s.detectionsCount.toString(), 5)}  " +
                        "${padL(s.droppedBefore.toString(), 13)}"
            )
        }
        sb.appendLine()

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
            sb.appendLine("  Average YOLO detections/frame : ${fmtDec(samples.map { it.detectionsCount }.average())}")
            sb.appendLine()
        }

        // ── Guidance events ───────────────────────────────────────────────────
        sb.appendLine("── GUIDANCE EVENTS ─────────────────────────────────────────")
        sb.appendLine("  Instructions emitted    : ${instructionLog.size}")
        sb.appendLine("  Timeout guidance count  : $timeoutGuidanceCount")
        sb.appendLine("  Staircase warnings      : $staircaseWarnings")
        sb.appendLine("  Stair guidance hints    : $stairGuidanceHints")
        sb.appendLine()

        // ── OCR results ───────────────────────────────────────────────────────
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

            if (nonEmpty.isNotEmpty()) {
                val avgMatchMs = if (matched.isNotEmpty()) matched.map { it.ocrMs }.average() else 0.0
                val avgMissMs  = if (missed.isNotEmpty())  missed.map  { it.ocrMs }.average() else 0.0
                val avgEmptyMs = if (empty.isNotEmpty())   empty.map   { it.ocrMs }.average() else 0.0
                sb.appendLine("  Avg OCR latency by outcome:")
                sb.appendLine("    Match   : ${fmtDec(avgMatchMs)} ms")
                sb.appendLine("    No-match: ${fmtDec(avgMissMs)} ms")
                sb.appendLine("    Empty   : ${fmtDec(avgEmptyMs)} ms")
                sb.appendLine()
            }

            // Per-label summary
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

            // ── Full chronological OCR log — every call with its frameId ─────
            sb.appendLine("  FULL OCR LOG — use FrameTag to find matching images:")
            sb.appendLine("  ${padR("FrameTag", 9)}  ${padR("Time", 10)}  ${padR("Label", 22)}  ${padL("OcrMs",5)}  ${padR("Dest",10)}  M  Raw OCR text")
            sb.appendLine("  ${"-".repeat(90)}")
            ocr.forEach { r ->
                val matchFlag = if (r.matchedDest) "✓" else "✗"
                val preview   = r.rawOcrText.take(50).replace("\n", "↵")
                sb.appendLine(
                    "  ${padR(frameTag(r.frameId), 9)}  " +
                            "${padR("+${fmtMs(r.elapsedMs)}", 10)}  " +
                            "${padR(r.label, 22)}  " +
                            "${padL(r.ocrMs.toString(), 5)}  " +
                            "${padR(r.destAtTime, 10)}  " +
                            "$matchFlag  \"$preview\""
                )
            }
            sb.appendLine()

            // Non-match detail
            if (missed.isNotEmpty()) {
                sb.appendLine("  NON-MATCH DETAIL (OCR returned text but it didn't match destination):")
                sb.appendLine("  ${padR("FrameTag", 9)}  ${padR("Time", 10)}  ${padR("Label", 22)}  ${padR("Dest", 10)}  Raw OCR text")
                sb.appendLine("  ${"-".repeat(80)}")
                missed.forEach { r ->
                    sb.appendLine(
                        "  ${padR(frameTag(r.frameId), 9)}  " +
                                "${padR("+${fmtMs(r.elapsedMs)}", 10)}  " +
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
            val byReason = rejected.groupBy { it.reason }
            sb.appendLine("  By reason:")
            RejectionReason.entries.forEach { r ->
                val n = byReason[r]?.size ?: 0
                if (n > 0) sb.appendLine("    ${padR(r.name, 14)} : $n")
            }
            sb.appendLine()

            val tooSmall = byReason[RejectionReason.TOO_SMALL]
            if (!tooSmall.isNullOrEmpty()) {
                sb.appendLine("  TOO_SMALL — area% stats per label:")
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

            // Full chronological log with frameId
            sb.appendLine("  FULL REJECTION LOG — use FrameTag to find matching images:")
            sb.appendLine("  ${padR("FrameTag", 9)}  ${padR("Time", 10)}  ${padR("Label", 22)}  ${padL("Conf",5)}  ${padL("Area%",6)}  Reason")
            sb.appendLine("  ${"-".repeat(80)}")
            rejected.forEach { d ->
                val detail = when (d.reason) {
                    RejectionReason.OCR_EMPTY ->
                        if (d.ocrText.isNotEmpty()) "OCR_EMPTY  got=\"${d.ocrText}\"" else "OCR_EMPTY"
                    else -> d.reason.name
                }
                sb.appendLine(
                    "  ${padR(frameTag(d.frameId), 9)}  " +
                            "${padR("+${fmtMs(d.elapsedMs)}", 10)}  " +
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

        // ── Compliance tracking ───────────────────────────────────────────────
        val compliance = complianceLog.toList()
        sb.appendLine("── DIRECTION COMPLIANCE ────────────────────────────────────")

        if (compliance.isEmpty()) {
            sb.appendLine("  (no directional instructions were emitted this session)")
        } else {
            val checked      = compliance.size
            val compliantN   = compliance.count { it.compliant == true }
            val nonCompliant = compliance.count { it.compliant == false }
            val uncertain    = compliance.count { it.compliant == null }
            val corrections  = compliance.count { it.correctionIssued }
            val complianceRate = if (checked > 0) compliantN * 100.0 / checked else 0.0
            val correctionRate = if (nonCompliant > 0) corrections * 100.0 / nonCompliant else 0.0
            val avgReactionMs  = if (checked > 0) compliance.map { it.reactionMs }.average() else 0.0

            sb.appendLine("  Directional instructions checked : $checked")
            sb.appendLine("  Compliant                        : $compliantN  (${fmtPct(complianceRate)})")
            sb.appendLine("  Non-compliant                    : $nonCompliant")
            sb.appendLine("  Uncertain (low sensor confidence): $uncertain")
            sb.appendLine("  Corrections issued               : $corrections  (${fmtPct(correctionRate)} of non-compliant)")
            sb.appendLine("  Avg reaction window              : ${fmtDec(avgReactionMs)} ms")
            sb.appendLine()

            // Per-direction breakdown
            sb.appendLine("  Per expected-direction breakdown:")
            sb.appendLine("  ${padR("Expected", 14)}  ${padL("Checks",6)}  ${padL("OK",4)}  ${padL("FAIL",5)}  ${padL("?",4)}  ${padL("Corrections",11)}")
            sb.appendLine("  ${"-".repeat(55)}")
            compliance.groupBy { it.expectedDir }.toSortedMap().forEach { (dir, events) ->
                val ok   = events.count { it.compliant == true }
                val fail = events.count { it.compliant == false }
                val unk  = events.count { it.compliant == null }
                val cor  = events.count { it.correctionIssued }
                sb.appendLine(
                    "  ${padR(dir, 14)}  ${padL(events.size.toString(), 6)}  " +
                            "${padL(ok.toString(), 4)}  ${padL(fail.toString(), 5)}  " +
                            "${padL(unk.toString(), 4)}  ${padL(cor.toString(), 11)}"
                )
            }
            sb.appendLine()

            // Confusion matrix: expected vs actual for non-compliant cases only
            val failed = compliance.filter { it.compliant == false }
            if (failed.isNotEmpty()) {
                sb.appendLine("  NON-COMPLIANT DETAIL (expected → actual):")
                sb.appendLine("  ${padR("Time", 10)}  ${padR("Expected", 14)}  ${padR("Actual", 14)}  ${padL("Conf",5)}  ${padL("RxnMs",6)}  Cor  Instruction")
                sb.appendLine("  ${"-".repeat(85)}")
                failed.forEach { e ->
                    sb.appendLine(
                        "  ${padR("+${fmtMs(e.elapsedMs)}", 10)}  " +
                                "${padR(e.expectedDir, 14)}  " +
                                "${padR(e.actualDir, 14)}  " +
                                "${padL(fmtDec(e.sensorConfidence.toDouble()), 5)}  " +
                                "${padL(e.reactionMs.toString(), 6)}  " +
                                "${if (e.correctionIssued) "YES" else "no "}  " +
                                "\"${e.instructionText.take(40)}\""
                    )
                }
                sb.appendLine()
            }

            // Uncertain events — useful to tune the 0.7 confidence threshold
            val unkEvents = compliance.filter { it.compliant == null }
            if (unkEvents.isNotEmpty()) {
                sb.appendLine("  UNCERTAIN CHECKS (sensor confidence below threshold):")
                sb.appendLine("  ${padR("Time", 10)}  ${padR("Expected", 14)}  ${padR("Actual", 14)}  ${padL("Conf",5)}  Instruction")
                sb.appendLine("  ${"-".repeat(75)}")
                unkEvents.forEach { e ->
                    sb.appendLine(
                        "  ${padR("+${fmtMs(e.elapsedMs)}", 10)}  " +
                                "${padR(e.expectedDir, 14)}  " +
                                "${padR(e.actualDir, 14)}  " +
                                "${padL(fmtDec(e.sensorConfidence.toDouble()), 5)}  " +
                                "\"${e.instructionText.take(40)}\""
                    )
                }
                sb.appendLine()
            }
        }

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
        private const val PROXIMITY_MIN_AREA_EXIT    = 0.001f
        private const val PROXIMITY_MIN_AREA_ROOMS   = 0.003f
        private const val PROXIMITY_MIN_AREA_OPENING = 0.04f
        private const val PROXIMITY_MIN_AREA_STAIR   = 0.08f
    }
}