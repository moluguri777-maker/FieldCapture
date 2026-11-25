package com.example.eval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.example.R
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiExtractionService
import com.example.cv.ImageQualityAnalyzer
import com.example.data.repository.FieldCaptureRepository
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.EvalBatchSummaryUi
import com.example.domain.model.EvalFrameResultUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class EvalInputFrame(
    val label: String,
    val sourceKind: String,
    val commandType: CaptureCommandType,
    val roomHint: String?,
    val surfaceHint: String?,
    val transcript: String,
    val loader: () -> Bitmap?
)

class BatchEvaluationEngine(
    private val context: Context,
    private val qualityAnalyzer: ImageQualityAnalyzer,
    private val geminiService: GeminiExtractionService,
    private val repository: FieldCaptureRepository
) {
    private val appContext = context.applicationContext

    suspend fun collectEvaluationFrames(importedUris: List<Uri>): List<EvalInputFrame> = withContext(Dispatchers.IO) {
        val frames = mutableListOf<EvalInputFrame>()

        // 1. User-imported genuine photos via zero-permission Photo Picker
        importedUris.forEachIndexed { idx, uri ->
            frames += EvalInputFrame(
                label = "Imported Photo #${idx + 1}",
                sourceKind = "PhotoPicker",
                commandType = if (idx % 2 == 0) CaptureCommandType.DAMAGE else CaptureCommandType.NAMEPLATE,
                roomHint = "Utility",
                surfaceHint = "Wall",
                transcript = if (idx % 2 == 0) "capture damage utility wall" else "capture nameplate",
                loader = {
                    runCatching {
                        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
            )
        }

        // 2. Genuine frames already captured during Mock Device Kit / live sessions
        val captureDir = File(appContext.filesDir, "captures")
        val savedFiles = captureDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        savedFiles.take(15).forEachIndexed { idx, file ->
            frames += EvalInputFrame(
                label = "Captured Frame #${idx + 1}",
                sourceKind = "SessionCapture",
                commandType = CaptureCommandType.DAMAGE,
                roomHint = "Field",
                surfaceHint = "Inspection",
                transcript = "capture damage field inspection",
                loader = { BitmapFactory.decodeFile(file.absolutePath) }
            )
        }

        // 3. Fill up to 42 frames using genuine inspection scene crops, ROI shifts, and lighting perturbations
        val baseScenes = listOf(
            Triple(R.drawable.img_sample_nameplate_1795506161252, CaptureCommandType.NAMEPLATE, "capture nameplate"),
            Triple(R.drawable.img_sample_damage_drywall_1795506162478, CaptureCommandType.DAMAGE, "capture damage basement drywall"),
            Triple(R.drawable.img_sample_electrical_panel_1795506162369, CaptureCommandType.DAMAGE, "capture damage utility panel")
        )

        var variationIdx = 0
        while (frames.size < 42) {
            val (resId, cmdType, transcript) = baseScenes[variationIdx % baseScenes.size]
            val mode = variationIdx % 7
            val labelPrefix = when (cmdType) {
                CaptureCommandType.NAMEPLATE -> "HVAC Nameplate"
                CaptureCommandType.DAMAGE -> if (resId == R.drawable.img_sample_electrical_panel_1795506162369) "Breaker Panel" else "Drywall Water"
                CaptureCommandType.NOTE -> "Field Note"
            }
            val variationTag = when (mode) {
                0 -> "Nominal"
                1 -> "Tight ROI Crop"
                2 -> "Dim Basement (-40% Luma)"
                3 -> "Motion Blur Sim"
                4 -> "Off-Center Framing"
                5 -> "Bright Glare (+35%)"
                else -> "Angle Crop"
            }
            val capturedMode = mode
            val capturedResId = resId
            frames += EvalInputFrame(
                label = "$labelPrefix #$variationIdx ($variationTag)",
                sourceKind = "MockKitDataset",
                commandType = cmdType,
                roomHint = if (resId == R.drawable.img_sample_electrical_panel_1795506162369) "Utility" else "Basement",
                surfaceHint = if (resId == R.drawable.img_sample_electrical_panel_1795506162369) "Panel" else "Drywall",
                transcript = transcript,
                loader = {
                    val raw = BitmapFactory.decodeResource(appContext.resources, capturedResId)
                    applyFieldVariation(raw, capturedMode)
                }
            )
            variationIdx++
        }

        frames
    }

    fun runBatchEvaluation(
        jobId: String,
        frames: List<EvalInputFrame>,
        useLiveGeminiAi: Boolean
    ): Flow<EvalBatchSummaryUi> = flow {
        val results = mutableListOf<EvalFrameResultUi>()
        emit(
            EvalBatchSummaryUi(
                totalFrames = frames.size,
                completedFrames = 0,
                isRunning = true,
                useLiveAiInBatch = useLiveGeminiAi
            )
        )

        for ((idx, frame) in frames.withIndex()) {
            val bmp = frame.loader()
            if (bmp == null) {
                results += EvalFrameResultUi(
                    index = idx + 1,
                    frameLabel = frame.label,
                    sourceKind = frame.sourceKind,
                    commandType = frame.commandType,
                    qualityPassed = false,
                    blurScore = 0.0,
                    brightnessScore = 0.0,
                    framingScore = 0.0,
                    qualityIssues = emptyList(),
                    aiSucceeded = false,
                    meanConfidence = 0f,
                    matchedCodes = emptyList(),
                    qualityLatencyMs = 0L,
                    aiLatencyMs = 0L,
                    promptTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 0.0,
                    notes = "Decode error"
                )
                continue
            }

            val qReport = qualityAnalyzer.analyze(bmp, frame.commandType)
            var aiSucceeded = false
            var meanConf = 0f
            var codes = emptyList<String>()
            var aiLatency = 0L
            var promptTokens = 0
            var outputTokens = 0
            var costUsd = 0.0
            var notes = qReport.summaryMessage

            if (useLiveGeminiAi && qReport.passed) {
                val aiRes = geminiService.extractEvidence(
                    commandType = frame.commandType,
                    bitmap = bmp,
                    transcript = frame.transcript,
                    roomHint = frame.roomHint,
                    surfaceHint = frame.surfaceHint
                )
                aiRes.onSuccess { outcome ->
                    aiSucceeded = true
                    meanConf = outcome.bundle.overallConfidence
                    codes = outcome.bundle.suggestedEstimateCodes
                    aiLatency = outcome.aiLatencyMs
                    promptTokens = outcome.promptTokens
                    outputTokens = outcome.outputTokens
                    costUsd = outcome.estimatedCostUsd
                    notes = outcome.bundle.summary
                    repository.recordEvalTelemetry(
                        jobId = jobId,
                        captureId = "EVAL-${idx + 1}-${UUID.randomUUID().toString().take(6)}",
                        qualityLatencyMs = qReport.latencyMs,
                        aiLatencyMs = outcome.aiLatencyMs,
                        promptTokens = outcome.promptTokens,
                        outputTokens = outcome.outputTokens,
                        estimatedCostUsd = outcome.estimatedCostUsd,
                        modelId = GEMINI_MODEL_ID
                    )
                }.onFailure { err ->
                    notes = "AI Error: ${err.localizedMessage}"
                }
            } else if (!useLiveGeminiAi) {
                repository.recordEvalTelemetry(
                    jobId = jobId,
                    captureId = "EVAL-CV-${idx + 1}",
                    qualityLatencyMs = qReport.latencyMs,
                    aiLatencyMs = 0L,
                    promptTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 0.0,
                    modelId = "on-device-cv-gate"
                )
                notes = if (qReport.passed) {
                    "CV Gate PASS (Enable Live Gemini AI toggle for multimodal extraction)"
                } else {
                    qReport.summaryMessage
                }
            }

            bmp.recycle()

            results += EvalFrameResultUi(
                index = idx + 1,
                frameLabel = frame.label,
                sourceKind = frame.sourceKind,
                commandType = frame.commandType,
                qualityPassed = qReport.passed,
                blurScore = qReport.blurScore,
                brightnessScore = qReport.brightnessScore,
                framingScore = qReport.framingScore,
                qualityIssues = qReport.issues,
                aiSucceeded = aiSucceeded,
                meanConfidence = meanConf,
                matchedCodes = codes,
                qualityLatencyMs = qReport.latencyMs,
                aiLatencyMs = aiLatency,
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = costUsd,
                notes = notes
            )

            emit(buildSummary(frames.size, results.toList(), isRunning = idx < frames.lastIndex, useLiveGeminiAi))
        }

        val finalSummary = buildSummary(frames.size, results.toList(), isRunning = false, useLiveGeminiAi)
        emit(finalSummary.copy(exportedJsonReport = buildEvalExportJson(finalSummary)))
    }.flowOn(Dispatchers.Default)

    private fun buildSummary(
        total: Int,
        results: List<EvalFrameResultUi>,
        isRunning: Boolean,
        useLiveAi: Boolean
    ): EvalBatchSummaryUi {
        val latencies = results.map { it.qualityLatencyMs + it.aiLatencyMs }.sorted()
        val p50 = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else 0L
        val p95 = if (latencies.isNotEmpty()) latencies[((latencies.size - 1) * 0.95).toInt()] else 0L
        val aiDone = results.filter { it.aiSucceeded }
        val meanConf = if (aiDone.isNotEmpty()) aiDone.map { it.meanConfidence }.average().toFloat() else 0f

        return EvalBatchSummaryUi(
            totalFrames = total,
            completedFrames = results.size,
            isRunning = isRunning,
            qualityPassCount = results.count { it.qualityPassed },
            aiSuccessCount = aiDone.size,
            meanConfidence = meanConf,
            p50TotalLatencyMs = p50,
            p95TotalLatencyMs = p95,
            totalTokens = results.sumOf { it.promptTokens + it.outputTokens },
            totalCostUsd = results.sumOf { it.estimatedCostUsd },
            useLiveAiInBatch = useLiveAi,
            results = results
        )
    }

    private fun buildEvalExportJson(summary: EvalBatchSummaryUi): String {
        val root = JSONObject()
        root.put("totalFrames", summary.totalFrames)
        root.put("qualityPassCount", summary.qualityPassCount)
        root.put("aiSuccessCount", summary.aiSuccessCount)
        root.put("meanConfidence", summary.meanConfidence)
        root.put("p50TotalLatencyMs", summary.p50TotalLatencyMs)
        root.put("p95TotalLatencyMs", summary.p95TotalLatencyMs)
        root.put("totalTokens", summary.totalTokens)
        root.put("totalCostUsd", summary.totalCostUsd)
        val arr = JSONArray()
        summary.results.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("index", r.index)
                    put("label", r.frameLabel)
                    put("source", r.sourceKind)
                    put("qualityPassed", r.qualityPassed)
                    put("blurScore", r.blurScore)
                    put("brightnessScore", r.brightnessScore)
                    put("framingScore", r.framingScore)
                    put("aiSucceeded", r.aiSucceeded)
                    put("meanConfidence", r.meanConfidence)
                    put("qualityLatencyMs", r.qualityLatencyMs)
                    put("aiLatencyMs", r.aiLatencyMs)
                    put("promptTokens", r.promptTokens)
                    put("outputTokens", r.outputTokens)
                    put("estimatedCostUsd", r.estimatedCostUsd)
                }
            )
        }
        root.put("frames", arr)
        return root.toString(2)
    }

    private fun applyFieldVariation(src: Bitmap, mode: Int): Bitmap {
        return when (mode) {
            0 -> src // Nominal genuine image
            1 -> {
                // Tight center ROI crop
                val w = (src.width * 0.72f).toInt()
                val h = (src.height * 0.72f).toInt()
                val x = (src.width - w) / 2
                val y = (src.height - h) / 2
                val cropped = Bitmap.createBitmap(src, x, y, w, h)
                src.recycle()
                cropped
            }
            2 -> adjustBrightness(src, 0.25f) // Dim basement underexposed test case
            3 -> {
                // Simulated motion blur via downscale + upscale
                val tiny = Bitmap.createScaledBitmap(src, (src.width / 8).coerceAtLeast(32), (src.height / 8).coerceAtLeast(24), true)
                val blurred = Bitmap.createScaledBitmap(tiny, src.width, src.height, true)
                tiny.recycle()
                src.recycle()
                blurred
            }
            4 -> {
                // Off-center shifted framing
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawRGB(14, 18, 22)
                canvas.drawBitmap(src, src.width * 0.35f, src.height * 0.28f, null)
                src.recycle()
                out
            }
            5 -> adjustBrightness(src, 1.55f) // Bright glare test case
            else -> {
                val w = (src.width * 0.85f).toInt()
                val h = (src.height * 0.85f).toInt()
                val cropped = Bitmap.createBitmap(src, 0, 0, w, h)
                src.recycle()
                cropped
            }
        }
    }

    private fun adjustBrightness(src: Bitmap, scale: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, 0f,
                0f, scale, 0f, 0f, 0f,
                0f, 0f, scale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out
    }
}
