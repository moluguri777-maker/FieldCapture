package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiExtractionService
import com.example.cv.ImageQualityAnalyzer
import com.example.data.local.InspectionJobEntity
import com.example.data.repository.FieldCaptureRepository
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.CaptureEvidenceUi
import com.example.domain.model.EstimateLineItem
import com.example.domain.model.EvalBatchSummaryUi
import com.example.domain.model.HttpTraceLog
import com.example.domain.model.OutboxItemUi
import com.example.domain.model.QualityReport
import com.example.domain.model.SessionLifecycleState
import com.example.domain.model.TelemetryRecordUi
import com.example.eval.BatchEvaluationEngine
import com.example.voice.ParsedVoiceCommand
import com.example.voice.VoiceCommandParser
import com.example.voice.VoiceInputController
import com.example.voice.VoiceParseResult
import com.example.voice.VoiceUiState
import com.example.wearable.DatWearableSessionGateway
import com.example.wearable.MockFeedScene
import com.example.wearable.WearableGatewayState
import com.example.wearable.WearableSessionGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingQualityReview(
    val command: ParsedVoiceCommand,
    val bitmap: Bitmap,
    val qualityReport: QualityReport
)

data class CapturePipelineUiState(
    val isExtracting: Boolean = false,
    val activeStepMessage: String? = null,
    val commandFeedback: String? = null,
    val pendingQualityReview: PendingQualityReview? = null,
    val liveQualityPreview: QualityReport? = null,
    val isDarkFieldTheme: Boolean = true
)

class FieldCaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FieldCaptureRepository(application)
    private val qualityAnalyzer = ImageQualityAnalyzer()
    private val geminiService = GeminiExtractionService()
    private val evalEngine = BatchEvaluationEngine(application, qualityAnalyzer, geminiService, repository)
    private val wearableGateway: WearableSessionGateway = DatWearableSessionGateway(application)

    private val voiceController = VoiceInputController(application) { transcript ->
        submitCommandText(transcript)
    }

    val wearableState: StateFlow<WearableGatewayState> = wearableGateway.state
    val voiceState: StateFlow<VoiceUiState> = voiceController.state

    private val _pipelineState = MutableStateFlow(CapturePipelineUiState())
    val pipelineState: StateFlow<CapturePipelineUiState> = _pipelineState.asStateFlow()

    val activeJob: StateFlow<InspectionJobEntity?> = repository.activeJobFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @Suppress("OPT_IN_USAGE")
    val captures: StateFlow<List<CaptureEvidenceUi>> = activeJob
        .flatMapLatest { job ->
            if (job == null) flowOf(emptyList()) else repository.observeCaptures(job.jobId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val estimateLineItems: StateFlow<List<EstimateLineItem>> = captures
        .map { repository.buildEstimateLineItems(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outboxItems: StateFlow<List<OutboxItemUi>> = repository.outboxFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val telemetryRecords: StateFlow<List<TelemetryRecordUi>> = repository.telemetryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val httpTraces: StateFlow<List<HttpTraceLog>> = repository.transportConfig.httpTraces
    val loopbackMode: StateFlow<Boolean> = repository.transportConfig.loopbackMode
    val baseUrl: StateFlow<String> = repository.transportConfig.baseUrl

    private val _exportedJobJson = MutableStateFlow<String?>(null)
    val exportedJobJson: StateFlow<String?> = _exportedJobJson.asStateFlow()

    private val _isSyncingNow = MutableStateFlow(false)
    val isSyncingNow: StateFlow<Boolean> = _isSyncingNow.asStateFlow()

    private val _importedEvalUris = MutableStateFlow<List<Uri>>(emptyList())
    val importedEvalUris: StateFlow<List<Uri>> = _importedEvalUris.asStateFlow()

    private val _evalSummary = MutableStateFlow(EvalBatchSummaryUi(totalFrames = 42))
    val evalSummary: StateFlow<EvalBatchSummaryUi> = _evalSummary.asStateFlow()

    private var evalJob: Job? = null

    init {
        wearableGateway.initializeSdk()
        viewModelScope.launch {
            repository.ensureDefaultJob()
        }
        viewModelScope.launch {
            combine(activeJob, wearableState) { job, hw -> job to hw }
                .collect { (job, hw) ->
                    if (job != null) {
                        repository.updateJobSessionTelemetry(
                            jobId = job.jobId,
                            sessionState = hw.lifecycleState.name,
                            disconnectsRecovered = hw.disconnectsRecovered
                        )
                    }
                    hw.previewFrame?.let { frame ->
                        val previewReport = qualityAnalyzer.analyze(frame, CaptureCommandType.NAMEPLATE)
                        _pipelineState.value = _pipelineState.value.copy(liveQualityPreview = previewReport)
                    }
                }
        }
    }

    fun toggleDarkFieldTheme() {
        _pipelineState.value = _pipelineState.value.copy(
            isDarkFieldTheme = !_pipelineState.value.isDarkFieldTheme
        )
    }

    // --- Wearable & Mock Device Kit Controls ---
    fun pairMockGlasses() = wearableGateway.pairMockGlasses()
    fun unpairMockGlasses() = wearableGateway.unpairMockGlasses()
    fun setMockPower(on: Boolean) = wearableGateway.setMockPower(on)
    fun setMockFolded(folded: Boolean) = wearableGateway.setMockFolded(folded)
    fun setMockDonned(donned: Boolean) = wearableGateway.setMockDonned(donned)
    fun selectMockScene(scene: MockFeedScene) = wearableGateway.selectMockScene(scene)
    fun toggleDeviceCameraFeed(enabled: Boolean) = wearableGateway.toggleDeviceCameraFeed(enabled)
    fun startSession() = wearableGateway.startSession()
    fun pauseSession() = wearableGateway.pauseSession()
    fun resumeSession() = wearableGateway.resumeSession()
    fun stopSession() = wearableGateway.stopSession()
    fun simulateUnexpectedDisconnect() = wearableGateway.simulateUnexpectedDisconnect()

    // --- Voice & Command Pipeline ---
    fun startVoiceListening() = voiceController.startListening()
    fun stopVoiceListening() = voiceController.stopListening()

    fun submitCommandText(rawCommand: String) {
        when (val parsed = VoiceCommandParser.parse(rawCommand)) {
            is VoiceParseResult.Matched -> executeParsedCommand(parsed.command)
            is VoiceParseResult.Unrecognized -> {
                _pipelineState.value = _pipelineState.value.copy(
                    commandFeedback = parsed.hint
                )
            }
        }
    }

    private fun executeParsedCommand(command: ParsedVoiceCommand) {
        viewModelScope.launch {
            val sessionState = wearableState.value.lifecycleState
            if (command.commandType != CaptureCommandType.NOTE &&
                sessionState != SessionLifecycleState.STREAMING &&
                sessionState != SessionLifecycleState.PAIRED_IDLE
            ) {
                if (!wearableState.value.hardware.paired) {
                    wearableGateway.pairMockGlasses()
                }
            }

            if (command is ParsedVoiceCommand.TechnicianNote) {
                val attachedFrame = wearableState.value.previewFrame
                val dummyQuality = QualityReport(
                    passed = true,
                    blurScore = 100.0,
                    brightnessScore = 128.0,
                    framingScore = 1.0,
                    darkPixelRatio = 0.0,
                    clippedPixelRatio = 0.0,
                    issues = emptyList(),
                    summaryMessage = "Voice Note (visual context optional)",
                    latencyMs = 1L
                )
                persistAndExtract(
                    command = command,
                    bitmap = attachedFrame,
                    qualityReport = dummyQuality
                )
                return@launch
            }

            _pipelineState.value = _pipelineState.value.copy(
                activeStepMessage = "Capturing frame via Meta DAT 1.0…",
                commandFeedback = null
            )

            val captureResult = wearableGateway.capturePhotoFrame()
            captureResult.onFailure { err ->
                _pipelineState.value = _pipelineState.value.copy(
                    activeStepMessage = null,
                    commandFeedback = "Capture failed: ${err.localizedMessage}"
                )
            }.onSuccess { bmp ->
                _pipelineState.value = _pipelineState.value.copy(
                    activeStepMessage = "Running on-device quality gates (blur / darkness / framing)…"
                )
                val qReport = qualityAnalyzer.analyze(bmp, command.commandType)
                if (!qReport.passed) {
                    _pipelineState.value = _pipelineState.value.copy(
                        activeStepMessage = null,
                        pendingQualityReview = PendingQualityReview(command, bmp, qReport),
                        commandFeedback = qReport.summaryMessage
                    )
                } else {
                    persistAndExtract(command, bmp, qReport)
                }
            }
        }
    }

    fun overrideQualityAndKeep() {
        val pending = _pipelineState.value.pendingQualityReview ?: return
        _pipelineState.value = _pipelineState.value.copy(pendingQualityReview = null)
        viewModelScope.launch {
            val overriddenReport = pending.qualityReport.copy(userOverridden = true)
            persistAndExtract(pending.command, pending.bitmap, overriddenReport)
        }
    }

    fun discardFailedCapture() {
        _pipelineState.value.pendingQualityReview ?: return
        _pipelineState.value = _pipelineState.value.copy(
            pendingQualityReview = null,
            commandFeedback = "Discarded low-quality frame — adjust lighting/framing and retry."
        )
    }

    private suspend fun persistAndExtract(
        command: ParsedVoiceCommand,
        bitmap: Bitmap?,
        qualityReport: QualityReport
    ) {
        val job = activeJob.value ?: repository.ensureDefaultJob()
        val captureId = "CAP-${UUID.randomUUID().toString().take(8).uppercase()}"
        val roomHint = (command as? ParsedVoiceCommand.CaptureDamage)?.room
        val surfaceHint = (command as? ParsedVoiceCommand.CaptureDamage)?.surface

        val localPath = bitmap?.let { repository.saveCaptureImage(captureId, it) }
        repository.saveInitialCapture(
            captureId = captureId,
            jobId = job.jobId,
            commandType = command.commandType,
            transcript = command.rawTranscript,
            room = roomHint,
            surface = surfaceHint,
            localImagePath = localPath,
            qualityReport = qualityReport
        )

        _pipelineState.value = _pipelineState.value.copy(
            isExtracting = true,
            activeStepMessage = "Extracting fields & confidence via Gemini 3.8 Flash…"
        )

        val aiRes = geminiService.extractEvidence(
            commandType = command.commandType,
            bitmap = bitmap,
            transcript = command.rawTranscript,
            roomHint = roomHint,
            surfaceHint = surfaceHint
        )

        aiRes.onSuccess { outcome ->
            repository.updateCaptureWithExtraction(
                captureId = captureId,
                bundle = outcome.bundle,
                errorMessage = null,
                aiLatencyMs = outcome.aiLatencyMs,
                promptTokens = outcome.promptTokens,
                outputTokens = outcome.outputTokens,
                estimatedCostUsd = outcome.estimatedCostUsd,
                qualityLatencyMs = qualityReport.latencyMs
            )
            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = false,
                activeStepMessage = null,
                commandFeedback = "Saved $captureId • Conf ${(outcome.bundle.overallConfidence * 100).toInt()}% • ${outcome.aiLatencyMs}ms"
            )
        }.onFailure { err ->
            repository.updateCaptureWithExtraction(
                captureId = captureId,
                bundle = null,
                errorMessage = err.localizedMessage ?: "AI extraction failed",
                aiLatencyMs = 0L,
                promptTokens = 0,
                outputTokens = 0,
                estimatedCostUsd = 0.0,
                qualityLatencyMs = qualityReport.latencyMs
            )
            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = false,
                activeStepMessage = null,
                commandFeedback = "Queued $captureId offline (AI error: ${err.localizedMessage})"
            )
        }
    }

    // --- Job Review & Estimate Actions ---
    fun updateFieldValue(captureId: String, fieldKey: String, newValue: String) {
        viewModelScope.launch {
            repository.updateExtractedField(captureId, fieldKey, newValue)
        }
    }

    fun toggleEstimateCode(captureId: String, code: String) {
        viewModelScope.launch {
            repository.toggleEstimateCode(captureId, code)
        }
    }

    fun deleteCapture(captureId: String) {
        viewModelScope.launch {
            repository.deleteCapture(captureId)
        }
    }

    fun refreshJobJsonExport() {
        val jobId = activeJob.value?.jobId ?: return
        viewModelScope.launch {
            _exportedJobJson.value = repository.buildStructuredJobJsonString(jobId)
        }
    }

    fun dismissJobJsonExport() {
        _exportedJobJson.value = null
    }

    // --- Sync Queue & REST Transport ---
    fun setLoopbackMode(enabled: Boolean) {
        repository.transportConfig.setLoopbackMode(enabled)
    }

    fun updateRestBaseUrl(url: String) {
        repository.transportConfig.setBaseUrl(url)
    }

    fun simulateNextHttp503() {
        repository.transportConfig.setSimulateNextFailure(true)
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncingNow.value = true
            repository.processPendingOutbox()
            _isSyncingNow.value = false
        }
    }

    fun clearSyncedOutbox() {
        viewModelScope.launch {
            repository.clearSyncedOutbox()
        }
    }

    fun isNetworkConnected(): Boolean = repository.isNetworkConnected()

    // --- 40-Frame Batch Evaluator & Cost/Latency Telemetry ---
    fun addImportedEvalUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _importedEvalUris.value = (_importedEvalUris.value + uris).distinct()
    }

    fun setEvalUseLiveAi(enabled: Boolean) {
        _evalSummary.value = _evalSummary.value.copy(useLiveAiInBatch = enabled)
    }

    fun start40FrameEvaluation() {
        if (_evalSummary.value.isRunning) return
        val jobId = activeJob.value?.jobId ?: "JOB-2026-4082"
        val useLive = _evalSummary.value.useLiveAiInBatch
        evalJob?.cancel()
        evalJob = viewModelScope.launch {
            val frames = evalEngine.collectEvaluationFrames(_importedEvalUris.value)
            evalEngine.runBatchEvaluation(jobId, frames, useLive).collect { summary ->
                _evalSummary.value = summary
            }
        }
    }

    fun stopEvaluation() {
        evalJob?.cancel()
        _evalSummary.value = _evalSummary.value.copy(isRunning = false)
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.release()
        wearableGateway.release()
    }
}
