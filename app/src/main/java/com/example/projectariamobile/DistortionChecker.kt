package com.example.projectariamobile
import android.graphics.Bitmap
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object DistortionChecker {
    /**
    * Helper that takes the full frame, crops it, and checks for distortion.
    */
    fun isSignDistorted(fullFrame: Bitmap, bbox: android.graphics.Rect): Boolean {
        // Use the shared extension to crop
        val cropped = fullFrame.crop(bbox)

        // Convert to Mat
        val roi = Mat()
        org.opencv.android.Utils.bitmapToMat(cropped, roi)

        // Run the analysis
        val result = isDistorted(roi)

        // Cleanup
        roi.release()
        if (cropped != fullFrame) cropped.recycle()

        return result
    }

    private fun isDistorted(roi: Mat): Boolean {
        // crop from bbox
        val gray = Mat()
        val edges = Mat()

        // Pre-processing
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(gray, edges, 75.0, 200.0)

        // Find Contours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        if (contours.isEmpty()) return true // Assume distorted if nothing found

        // Find the largest contour by area
        val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return true

        // Approximate to Polygon
        val contour2f = MatOfPoint2f(*largestContour.toArray())
        val approx2f = MatOfPoint2f()
        val peri = Imgproc.arcLength(contour2f, true)
        Imgproc.approxPolyDP(contour2f, approx2f, 0.04 * peri, true)

        val points = approx2f.toArray()

        // We expect 4 points for a rectangular sign
        if (points.size == 4) {
            // Sort points: Top-Left, Top-Right, Bottom-Right, Bottom-Left
            val sortedPoints = sortPoints(points)
            val tl = sortedPoints[0]
            val tr = sortedPoints[1]
            val br = sortedPoints[2]
            val bl = sortedPoints[3]

            // Calculate Side Lengths (Euclidean Distance)
            val leftHeight = dist(tl, bl)
            val rightHeight = dist(tr, br)
            val topWidth = dist(tl, tr)
            val bottomWidth = dist(bl, br)

            // Calculate Distortion Ratios
            val heightRatio = min(leftHeight, rightHeight) / max(leftHeight, rightHeight)
            val widthRatio = min(topWidth, bottomWidth) / max(topWidth, bottomWidth)

            // Threshold (0.8 means if one side is 20% smaller than the other, it's an angle)
            return heightRatio < 0.8 || widthRatio < 0.8
        }
        return true // Not a 4-sided polygon, likely viewed from a sharp edge
    }

    // Helper to calculate distance between points
    private fun dist(p1: Point, p2: Point): Double {
        return sqrt((p1.x - p2.x).pow(2.0) + (p1.y - p2.y).pow(2.0))
    }

    // Simple logic to order points clockwise from Top-Left
    private fun sortPoints(pts: Array<Point>): Array<Point> {
        val sortedByX = pts.sortedBy { it.x }
        val leftMost = sortedByX.take(2).sortedBy { it.y }
        val rightMost = sortedByX.takeLast(2).sortedBy { it.y }

        return arrayOf(
            leftMost[0],  // Top-Left
            rightMost[0], // Top-Right
            rightMost[1], // Bottom-Right
            leftMost[1]   // Bottom-Left
        )
    }
}
