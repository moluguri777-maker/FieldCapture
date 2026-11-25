package com.example.cv

import android.graphics.Bitmap
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.QualityIssue
import com.example.domain.model.QualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure on-device computer vision image quality gate for field inspection frames.
 * Evaluates blur (Laplacian variance), darkness/exposure (BT.601 luminance histogram),
 * and framing (central ROI vs outer border Sobel edge distribution).
 */
class ImageQualityAnalyzer {

    suspend fun analyze(
        bitmap: Bitmap,
        commandType: CaptureCommandType
    ): QualityReport = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()

        // Downsample deterministically to max 320px width for fast, consistent scoring
        val targetW = min(320, max(64, bitmap.width))
        val targetH = max(64, (bitmap.height * (targetW.toFloat() / max(1, bitmap.width))).toInt())
        val scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        analyzePixels(pixels, w, h, commandType, startNs)
    }

    fun analyzePixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        commandType: CaptureCommandType,
        startNs: Long = System.nanoTime()
    ): QualityReport {

        val luma = DoubleArray(w * h)
        var sumLuma = 0.0
        var darkCount = 0
        var clippedCount = 0

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            luma[i] = y
            sumLuma += y
            if (y < 28.0) darkCount++
            if (y > 242.0) clippedCount++
        }

        val n = pixels.size.toDouble()
        val meanLuma = sumLuma / n
        val darkRatio = darkCount / n
        val clippedRatio = clippedCount / n

        // Laplacian variance for sharpness / motion blur
        var lapSum = 0.0
        var lapSqSum = 0.0
        var lapCount = 0

        // Sobel edge distribution for center vs border framing check
        var centerEdgeSum = 0.0
        var centerCount = 0
        var borderEdgeSum = 0.0
        var borderCount = 0

        val xMinCenter = (w * 0.14).toInt()
        val xMaxCenter = (w * 0.86).toInt()
        val yMinCenter = (h * 0.14).toInt()
        val yMaxCenter = (h * 0.86).toInt()

        for (y in 1 until h - 1) {
            val row = y * w
            val rowUp = (y - 1) * w
            val rowDown = (y + 1) * w
            for (x in 1 until w - 1) {
                val center = luma[row + x]
                val up = luma[rowUp + x]
                val down = luma[rowDown + x]
                val left = luma[row + x - 1]
                val right = luma[row + x + 1]

                val lap = up + down + left + right - 4.0 * center
                lapSum += lap
                lapSqSum += lap * lap
                lapCount++

                val gx = (luma[rowUp + x + 1] + 2.0 * right + luma[rowDown + x + 1]) -
                    (luma[rowUp + x - 1] + 2.0 * left + luma[rowDown + x - 1])
                val gy = (luma[rowDown + x - 1] + 2.0 * down + luma[rowDown + x + 1]) -
                    (luma[rowUp + x - 1] + 2.0 * up + luma[rowUp + x + 1])
                val mag = sqrt(gx * gx + gy * gy)

                val inCenter = x in xMinCenter..xMaxCenter && y in yMinCenter..yMaxCenter
                if (inCenter) {
                    centerEdgeSum += mag
                    centerCount++
                } else {
                    borderEdgeSum += mag
                    borderCount++
                }
            }
        }

        val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
        val lapVariance = if (lapCount > 0) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

        val centerMeanEdge = if (centerCount > 0) centerEdgeSum / centerCount else 0.0
        val borderMeanEdge = if (borderCount > 0) borderEdgeSum / borderCount else 0.0
        val framingScore = if (centerMeanEdge + borderMeanEdge > 1e-3) {
            (centerMeanEdge / (centerMeanEdge + borderMeanEdge * 0.85)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val blurThreshold = when (commandType) {
            CaptureCommandType.NAMEPLATE -> 95.0 // Nameplates need crisper OCR detail
            CaptureCommandType.DAMAGE, CaptureCommandType.NOTE -> 65.0
        }

        val issues = mutableListOf<QualityIssue>()
        if (lapVariance < blurThreshold) {
            issues += QualityIssue.BLURRY
        }
        if (meanLuma < 45.0 || darkRatio > 0.65) {
            issues += QualityIssue.TOO_DARK
        } else if (meanLuma > 230.0 || clippedRatio > 0.45) {
            issues += QualityIssue.OVEREXPOSED
        }
        if (framingScore < 0.36 || (centerMeanEdge < 8.0 && borderMeanEdge > 18.0)) {
            issues += QualityIssue.POOR_FRAMING
        }

        val summary = if (issues.isEmpty()) {
            "PASS • Sharpness ${lapVariance.toInt()} • Luma ${meanLuma.toInt()} • Framing ${(framingScore * 100).toInt()}%"
        } else {
            val reasons = issues.joinToString(", ") { issue ->
                when (issue) {
                    QualityIssue.BLURRY -> "Blur (${lapVariance.toInt()} < ${blurThreshold.toInt()})"
                    QualityIssue.TOO_DARK -> "Dark (Luma ${meanLuma.toInt()} < 45)"
                    QualityIssue.OVEREXPOSED -> "Overexposed (Luma ${meanLuma.toInt()})"
                    QualityIssue.POOR_FRAMING -> "Subject off-center (${(framingScore * 100).toInt()}%)"
                }
            }
            "CHECK FAILED • $reasons"
        }

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        return QualityReport(
            passed = issues.isEmpty(),
            blurScore = lapVariance,
            brightnessScore = meanLuma,
            framingScore = framingScore,
            darkPixelRatio = darkRatio,
            clippedPixelRatio = clippedRatio,
            issues = issues,
            summaryMessage = summary,
            latencyMs = elapsedMs
        )
    }
}
