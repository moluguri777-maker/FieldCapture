package com.example

import com.example.ai.GeminiExtractionService
import com.example.cv.ImageQualityAnalyzer
import com.example.data.local.CaptureEvidenceEntity
import com.example.data.local.InspectionDao
import com.example.data.local.InspectionJobEntity
import com.example.data.local.SyncOutboxEntity
import com.example.data.local.TelemetryMetricEntity
import com.example.data.repository.FieldCaptureRepository
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.EstimateCodeCatalog
import com.example.domain.model.QualityIssue
import com.example.domain.model.QualityReport
import com.example.domain.model.SyncStatus
import com.example.voice.ParsedVoiceCommand
import com.example.voice.VoiceCommandParser
import com.example.voice.VoiceParseResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class ExampleUnitTest {

    @Test
    fun voiceParser_matchesAllThreeCommands() {
        val nameplate = VoiceCommandParser.parse("capture nameplate")
        assertTrue(nameplate is VoiceParseResult.Matched)
        assertEquals(CaptureCommandType.NAMEPLATE, (nameplate as VoiceParseResult.Matched).command.commandType)

        val damage = VoiceCommandParser.parse("capture damage basement drywall")
        assertTrue(damage is VoiceParseResult.Matched)
        val dmgCmd = (damage as VoiceParseResult.Matched).command as ParsedVoiceCommand.CaptureDamage
        assertEquals("Basement", dmgCmd.room)
        assertEquals("Drywall", dmgCmd.surface)

        val note = VoiceCommandParser.parse("note: Main breaker lug shows scorch marks")
        assertTrue(note is VoiceParseResult.Matched)
        val noteCmd = (note as VoiceParseResult.Matched).command as ParsedVoiceCommand.TechnicianNote
        assertEquals("Main breaker lug shows scorch marks", noteCmd.noteText)

        val invalid = VoiceCommandParser.parse("hello world")
        assertTrue(invalid is VoiceParseResult.Unrecognized)
    }

    @Test
    fun estimateCatalog_hasFourteenCodesAndMatchesDomainText() {
        assertEquals(14, EstimateCodeCatalog.items.size)
        val hvacMatches = EstimateCodeCatalog.matchFromText("Carrier condenser R-410A refrigerant leak")
        assertTrue(hvacMatches.any { it.code.startsWith("HVC-") })

        val waterMatches = EstimateCodeCatalog.matchFromText("Basement drywall water stain and mold")
        assertTrue(waterMatches.any { it.code == "DRY-RPL-02" || it.code == "WTR-DRY-01" })
    }

    @Test
    fun qualityAnalyzer_flagsDarkAndBlurry_andPassesSharpCentered() {
        val analyzer = ImageQualityAnalyzer()
        val w = 160
        val h = 120

        // 1. Dark flat image -> TOO_DARK & BLURRY
        val darkPixels = IntArray(w * h) { (12 shl 16) or (12 shl 8) or 12 }
        val darkReport = analyzer.analyzePixels(darkPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertFalse(darkReport.passed)
        assertTrue(darkReport.issues.contains(QualityIssue.TOO_DARK))
        assertTrue(darkReport.issues.contains(QualityIssue.BLURRY))

        // 2. Sharp high-contrast centered grid -> PASS
        val sharpPixels = IntArray(w * h) { (215 shl 16) or (215 shl 8) or 215 }
        for (y in 28 until 92) {
            for (x in 36 until 124) {
                if ((x % 8 == 0) || (y % 8 == 0)) {
                    sharpPixels[y * w + x] = (15 shl 16) or (15 shl 8) or 15
                }
            }
        }
        val sharpReport = analyzer.analyzePixels(sharpPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertTrue("Expected sharp centered grid to pass, got: ${sharpReport.summaryMessage}", sharpReport.passed)
    }

    @Test
    fun qualityAnalyzer_sampleNameplateDrawable_passesNameplateGate() {
        val sampleFile = listOf(
            File("src/main/res/drawable/img_sample_nameplate_1795506161252.jpg"),
            File("app/src/main/res/drawable/img_sample_nameplate_1795506161252.jpg")
        ).firstOrNull { it.exists() }
        assertNotNull("Expected bundled sample nameplate drawable on disk", sampleFile)

        val img = ImageIO.read(sampleFile!!)
        val w = 320
        val h = (img.height * (w.toFloat() / img.width)).toInt()
        val scaled = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()

        val pixels = IntArray(w * h)
        scaled.getRGB(0, 0, w, h, pixels, 0, w)

        val report = ImageQualityAnalyzer().analyzePixels(pixels, w, h, CaptureCommandType.NAMEPLATE)
        assertTrue("Sample nameplate should pass quality gate, got: ${report.summaryMessage}", report.passed)
    }

    @Test
    fun geminiExtractionService_parsesNameplateFieldsAndEstimateCodes() {
        val rawJson = """
            {
              "equipmentType": {"value": "HVAC Condenser", "confidence": 0.96},
              "manufacturer": {"value": "Carrier", "confidence": 0.98},
              "modelNumber": {"value": "24ACC636A003", "confidence": 0.97},
              "serialNumber": {"value": "3121E48291", "confidence": 0.95},
              "manufactureDate": {"value": "Unknown", "confidence": 0.35},
              "voltagePhase": {"value": "208-230V 1PH 60HZ", "confidence": 0.94},
              "amperageOrBtu": {"value": "Unknown", "confidence": 0.40},
              "refrigerantType": {"value": "R-410A", "confidence": 0.96},
              "summary": "Carrier 24ACC636A003 residential R-410A condenser unit.",
              "estimateCodes": ["HVC-CMP-10", "HVC-COI-11"]
            }
        """.trimIndent()

        val bundle = GeminiExtractionService().parseStructuredBundle(rawJson, CaptureCommandType.NAMEPLATE)
        assertEquals("Carrier", bundle.fields["manufacturer"]?.value)
        assertEquals("24ACC636A003", bundle.fields["modelNumber"]?.value)
        assertEquals("3121E48291", bundle.fields["serialNumber"]?.value)
        assertEquals("Unknown", bundle.fields["manufactureDate"]?.value)
        assertTrue(bundle.fields["manufactureDate"]?.needsReview == true)
        assertEquals("R-410A", bundle.fields["refrigerantType"]?.value)
        assertEquals(listOf("HVC-CMP-10", "HVC-COI-11"), bundle.suggestedEstimateCodes)
    }

    @Test
    fun geminiExtractionService_fallsBackToDomainCodesWhenEstimateCodesEmpty() {
        val rawJson = """
            {
              "equipmentType": {"value": "Condenser", "confidence": 0.92},
              "manufacturer": {"value": "Carrier", "confidence": 0.95},
              "modelNumber": {"value": "24ACC636A003", "confidence": 0.94},
              "serialNumber": {"value": "3121E48291", "confidence": 0.93},
              "manufactureYear": {"value": "2021", "confidence": 0.82},
              "voltagePhase": {"value": "208-230V 1PH", "confidence": 0.91},
              "amperageOrBtu": {"value": "35A", "confidence": 0.80},
              "refrigerantType": {"value": "R-410A", "confidence": 0.95},
              "summary": "Carrier condenser using R-410A refrigerant.",
              "estimateCodes": []
            }
        """.trimIndent()

        val bundle = GeminiExtractionService().parseStructuredBundle(rawJson, CaptureCommandType.NAMEPLATE)
        assertEquals("2021", bundle.fields["manufactureDate"]?.value)
        assertTrue(bundle.suggestedEstimateCodes.contains("HVC-CMP-10"))
    }

    @Test
    fun repository_capturePersistenceSurvivesTelemetryAndSyncUpdates_andGeneratesLineItems() = runTest {
        val dao = InMemoryInspectionDao()
        val repository = FieldCaptureRepository(
            appContext = null,
            dao = dao,
            scheduleWorkerOnEnqueue = false
        )

        val job = repository.ensureDefaultJob()
        val quality = QualityReport(
            passed = true,
            blurScore = 6931.0,
            brightnessScore = 127.0,
            framingScore = 0.85,
            darkPixelRatio = 0.01,
            clippedPixelRatio = 0.01,
            issues = emptyList(),
            summaryMessage = "PASS • Sharpness 6931 • Luma 127 • Framing 85%",
            latencyMs = 14L
        )

        val captureId = "CAP-NAMEPLATE01"
        repository.saveInitialCapture(
            captureId = captureId,
            jobId = job.jobId,
            commandType = CaptureCommandType.NAMEPLATE,
            transcript = "capture nameplate",
            room = null,
            surface = null,
            localImagePath = "/tmp/captures/$captureId.jpg",
            qualityReport = quality
        )

        // Simulate telemetry updates during streaming & reconnect recovery — must NOT cascade-delete capture
        repository.updateJobSessionTelemetry(job.jobId, "STREAMING", 0)
        repository.updateJobSessionTelemetry(job.jobId, "RECONNECTING", 1)
        repository.updateJobSessionTelemetry(job.jobId, "STREAMING", 1)

        val rawJson = """
            {
              "equipmentType": {"value": "HVAC Condenser", "confidence": 0.95},
              "manufacturer": {"value": "Carrier", "confidence": 0.98},
              "modelNumber": {"value": "24ACC636A003", "confidence": 0.97},
              "serialNumber": {"value": "3121E48291", "confidence": 0.96},
              "manufactureDate": {"value": "2021", "confidence": 0.85},
              "voltagePhase": {"value": "208-230V 1PH 60HZ", "confidence": 0.93},
              "amperageOrBtu": {"value": "35A", "confidence": 0.81},
              "refrigerantType": {"value": "R-410A", "confidence": 0.97},
              "summary": "Carrier 24ACC636A003 condenser nameplate.",
              "estimateCodes": ["HVC-CMP-10", "HVC-COI-11"]
            }
        """.trimIndent()
        val bundle = GeminiExtractionService().parseStructuredBundle(rawJson, CaptureCommandType.NAMEPLATE)

        repository.updateCaptureWithExtraction(
            captureId = captureId,
            bundle = bundle,
            errorMessage = null,
            aiLatencyMs = 840L,
            promptTokens = 420,
            outputTokens = 160,
            estimatedCostUsd = 0.00016,
            qualityLatencyMs = quality.latencyMs
        )

        val captures = repository.observeCaptures(job.jobId).first()
        assertEquals(1, captures.size)
        val saved = captures.first()
        assertEquals(captureId, saved.captureId)
        assertEquals("Carrier", saved.extraction?.fields?.get("manufacturer")?.value)
        assertEquals("24ACC636A003", saved.extraction?.fields?.get("modelNumber")?.value)
        assertEquals("3121E48291", saved.extraction?.fields?.get("serialNumber")?.value)
        assertEquals("/tmp/captures/$captureId.jpg", saved.localImagePath)

        val lineItems = repository.buildEstimateLineItems(captures)
        assertEquals(2, lineItems.size)
        assertEquals(listOf("HVC-CMP-10", "HVC-COI-11"), lineItems.map { it.code })

        // Verify loopback sync updates job revision without cascade-deleting captures
        val syncedItems = repository.processPendingOutbox()
        assertTrue(syncedItems >= 1)
        val postSyncCaptures = repository.observeCaptures(job.jobId).first()
        assertEquals(1, postSyncCaptures.size)
        assertEquals(SyncStatus.SYNCED, postSyncCaptures.first().syncStatus)

        val exportedJson = repository.buildStructuredJobJsonString(job.jobId)
        assertTrue(exportedJson.contains("24ACC636A003"))
        assertTrue(exportedJson.contains("HVC-CMP-10"))
    }
}

private class InMemoryInspectionDao : InspectionDao {
    private val jobs = MutableStateFlow<Map<String, InspectionJobEntity>>(emptyMap())
    private val captures = MutableStateFlow<Map<String, CaptureEvidenceEntity>>(emptyMap())
    private val outbox = MutableStateFlow<Map<String, SyncOutboxEntity>>(emptyMap())
    private val telemetry = MutableStateFlow<List<TelemetryMetricEntity>>(emptyList())

    override fun observeActiveJob(): Flow<InspectionJobEntity?> =
        jobs.map { map -> map.values.maxByOrNull { it.createdAtEpochMs } }

    override suspend fun getJobById(jobId: String): InspectionJobEntity? = jobs.value[jobId]

    override suspend fun insertJobIfAbsent(job: InspectionJobEntity) {
        if (!jobs.value.containsKey(job.jobId)) {
            jobs.value = jobs.value + (job.jobId to job)
        }
    }

    override suspend fun updateJobSessionTelemetry(
        jobId: String,
        sessionState: String,
        disconnectsRecovered: Int,
        updatedAtEpochMs: Long
    ) {
        val current = jobs.value[jobId] ?: return
        jobs.value = jobs.value + (
            jobId to current.copy(
                sessionState = sessionState,
                disconnectsRecovered = disconnectsRecovered,
                updatedAtEpochMs = updatedAtEpochMs
            )
        )
    }

    override suspend fun updateJobSyncState(jobId: String, syncStatus: String, updatedAtEpochMs: Long) {
        val current = jobs.value[jobId] ?: return
        jobs.value = jobs.value + (
            jobId to current.copy(
                syncStatus = syncStatus,
                updatedAtEpochMs = updatedAtEpochMs
            )
        )
    }

    override suspend fun updateJobSynced(
        jobId: String,
        serverRevision: Int,
        syncStatus: String,
        updatedAtEpochMs: Long
    ) {
        val current = jobs.value[jobId] ?: return
        jobs.value = jobs.value + (
            jobId to current.copy(
                serverRevision = serverRevision,
                syncStatus = syncStatus,
                updatedAtEpochMs = updatedAtEpochMs
            )
        )
    }

    override fun observeCapturesForJob(jobId: String): Flow<List<CaptureEvidenceEntity>> =
        captures.map { map ->
            map.values.filter { it.jobId == jobId }.sortedByDescending { it.createdAtEpochMs }
        }

    override suspend fun getCapturesForJob(jobId: String): List<CaptureEvidenceEntity> =
        captures.value.values.filter { it.jobId == jobId }.sortedBy { it.createdAtEpochMs }

    override suspend fun getCaptureById(captureId: String): CaptureEvidenceEntity? =
        captures.value[captureId]

    override suspend fun upsertCapture(capture: CaptureEvidenceEntity) {
        require(jobs.value.containsKey(capture.jobId)) {
            "FOREIGN KEY constraint failed: jobId ${capture.jobId} does not exist"
        }
        captures.value = captures.value + (capture.captureId to capture)
    }

    override suspend fun deleteCapture(captureId: String) {
        captures.value = captures.value - captureId
    }

    override fun observeOutbox(): Flow<List<SyncOutboxEntity>> =
        outbox.map { it.values.sortedByDescending { item -> item.createdAtEpochMs } }

    override suspend fun getPendingOutboxItems(): List<SyncOutboxEntity> =
        outbox.value.values
            .filter { it.status == "QUEUED" || it.status == "FAILED" }
            .sortedBy { it.createdAtEpochMs }

    override suspend fun upsertOutboxItem(item: SyncOutboxEntity) {
        outbox.value = outbox.value + (item.outboxId to item)
    }

    override suspend fun clearSyncedOutbox() {
        outbox.value = outbox.value.filterValues { it.status != "SYNCED" }
    }

    override fun observeTelemetry(): Flow<List<TelemetryMetricEntity>> =
        telemetry.map { it.sortedByDescending { m -> m.timestampEpochMs } }

    override suspend fun getAllTelemetry(): List<TelemetryMetricEntity> = telemetry.value

    override suspend fun insertTelemetry(metric: TelemetryMetricEntity) {
        telemetry.value = telemetry.value + metric
    }
}
