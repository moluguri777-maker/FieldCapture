package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val VM_TAG = "FieldCaptureViewModel"

data class PendingQualityReview(
    val command: ParsedVoiceCommand,
    val bitmap: Bitmap,
    val qualityReport: QualityReport
)

data class CapturePipelineUiState(
    val isExtracting: Boolean = false,
    val activeStepMessage: String? = null,
    val commandFeedback: String? = null,
    val feedbackIsError: Boolean = false,
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
        .map { it?.jobId }
        .distinctUntilChanged()
        .flatMapLatest { jobId ->
            if (jobId == null) flowOf(emptyList()) else repository.observeCaptures(jobId)
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
            combine(
                activeJob.map { it?.jobId }.filterNotNull().distinctUntilChanged(),
                wearableState.map { it.lifecycleState.name to it.disconnectsRecovered }.distinctUntilChanged()
            ) { jobId, (sessionState, disconnects) ->
                Triple(jobId, sessionState, disconnects)
            }.collect { (jobId, sessionState, disconnects) ->
                runCatching {
                    repository.updateJobSessionTelemetry(
                        jobId = jobId,
                        sessionState = sessionState,
                        disconnectsRecovered = disconnects
                    )
                }.onFailure { err ->
                    Log.e(VM_TAG, "Failed to update job session telemetry", err)
                }
            }
        }
        viewModelScope.launch {
            wearableState
                .map { it.previewFrame }
                .distinctUntilChanged()
                .collect { frame ->
                    if (frame != null) {
                        runCatching {
                            qualityAnalyzer.analyze(frame, CaptureCommandType.NAMEPLATE)
                        }.onSuccess { previewReport ->
                            _pipelineState.value = _pipelineState.value.copy(liveQualityPreview = previewReport)
                        }
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
                    commandFeedback = parsed.hint,
                    feedbackIsError = true
                )
            }
        }
    }

    private fun executeParsedCommand(command: ParsedVoiceCommand) {
        if (_pipelineState.value.isExtracting || _pipelineState.value.activeStepMessage != null) {
            return
        }
        viewModelScope.launch {
            try {
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

                if (!wearableState.value.hardware.paired) {
                    _pipelineState.value = _pipelineState.value.copy(
                        activeStepMessage = "Pairing Mock Glasses via Meta DAT 1.0…",
                        commandFeedback = null,
                        feedbackIsError = false
                    )
                    val paired = wearableGateway.ensureMockGlassesPaired()
                    if (!paired) {
                        _pipelineState.value = _pipelineState.value.copy(
                            activeStepMessage = null,
                            commandFeedback = wearableState.value.lastError ?: "Unable to pair glasses for capture.",
                            feedbackIsError = true
                        )
                        return@launch
                    }
                }

                _pipelineState.value = _pipelineState.value.copy(
                    activeStepMessage = "Capturing frame via Meta DAT 1.0…",
                    commandFeedback = null,
                    feedbackIsError = false
                )

                val captureResult = wearableGateway.capturePhotoFrame()
                captureResult.onFailure { err ->
                    _pipelineState.value = _pipelineState.value.copy(
                        activeStepMessage = null,
                        commandFeedback = "Capture failed: ${err.localizedMessage ?: "No camera frame available"}",
                        feedbackIsError = true
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
                            commandFeedback = qReport.summaryMessage,
                            feedbackIsError = true
                        )
                    } else {
                        persistAndExtract(command, bmp, qReport)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(VM_TAG, "Unexpected capture pipeline error", e)
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Capture failed: ${e.localizedMessage ?: "Unexpected error"}",
                    feedbackIsError = true
                )
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
            commandFeedback = "Discarded low-quality frame — adjust lighting/framing and retry.",
            feedbackIsError = false
        )
    }

    private suspend fun persistAndExtract(
        command: ParsedVoiceCommand,
        bitmap: Bitmap?,
        qualityReport: QualityReport
    ) {
        val captureId = "CAP-${UUID.randomUUID().toString().take(8).uppercase()}"
        val roomHint = (command as? ParsedVoiceCommand.CaptureDamage)?.room
        val surfaceHint = (command as? ParsedVoiceCommand.CaptureDamage)?.surface

        try {
            val job = activeJob.value ?: repository.ensureDefaultJob()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(VM_TAG, "Failed to save capture evidence $captureId to Room", e)
            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = false,
                activeStepMessage = null,
                commandFeedback = "Database save failed: ${e.localizedMessage ?: "Unable to persist capture"}",
                feedbackIsError = true
            )
            return
        }

        _pipelineState.value = _pipelineState.value.copy(
            isExtracting = true,
            activeStepMessage = "Extracting fields & confidence via Gemini 3.8 Flash…",
            feedbackIsError = false
        )

        val aiRes = geminiService.extractEvidence(
            commandType = command.commandType,
            bitmap = bitmap,
            transcript = command.rawTranscript,
            roomHint = roomHint,
            surfaceHint = surfaceHint
        )

        aiRes.onSuccess { outcome ->
            try {
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
                    commandFeedback = "Saved $captureId • Conf ${(outcome.bundle.overallConfidence * 100).toInt()}% • ${outcome.aiLatencyMs}ms",
                    feedbackIsError = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(VM_TAG, "Failed to update extraction results for $captureId", e)
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Database error saving extraction: ${e.localizedMessage ?: "Unknown DB error"}",
                    feedbackIsError = true
                )
            }
        }.onFailure { err ->
            val errMsg = err.localizedMessage ?: "AI extraction failed"
            runCatching {
                repository.updateCaptureWithExtraction(
                    captureId = captureId,
                    bundle = null,
                    errorMessage = errMsg,
                    aiLatencyMs = 0L,
                    promptTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 0.0,
                    qualityLatencyMs = qualityReport.latencyMs
                )
            }.onFailure { dbErr ->
                Log.e(VM_TAG, "Failed to store extraction error state for $captureId", dbErr)
            }
            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = false,
                activeStepMessage = null,
                commandFeedback = "Saved $captureId locally (AI error: $errMsg)",
                feedbackIsError = true
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
