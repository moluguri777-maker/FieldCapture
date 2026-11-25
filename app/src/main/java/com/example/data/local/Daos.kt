package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InspectionDao {

    @Query("SELECT * FROM inspection_jobs ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeActiveJob(): Flow<InspectionJobEntity?>

    @Query("SELECT * FROM inspection_jobs WHERE jobId = :jobId")
    suspend fun getJobById(jobId: String): InspectionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: InspectionJobEntity)

    @Query("SELECT * FROM capture_evidences WHERE jobId = :jobId ORDER BY createdAtEpochMs DESC")
    fun observeCapturesForJob(jobId: String): Flow<List<CaptureEvidenceEntity>>

    @Query("SELECT * FROM capture_evidences WHERE jobId = :jobId ORDER BY createdAtEpochMs ASC")
    suspend fun getCapturesForJob(jobId: String): List<CaptureEvidenceEntity>

    @Query("SELECT * FROM capture_evidences WHERE captureId = :captureId")
    suspend fun getCaptureById(captureId: String): CaptureEvidenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapture(capture: CaptureEvidenceEntity)

    @Query("DELETE FROM capture_evidences WHERE captureId = :captureId")
    suspend fun deleteCapture(captureId: String)

    @Query("SELECT * FROM sync_outbox ORDER BY createdAtEpochMs DESC")
    fun observeOutbox(): Flow<List<SyncOutboxEntity>>

    @Query("SELECT * FROM sync_outbox WHERE status IN ('QUEUED', 'FAILED') ORDER BY createdAtEpochMs ASC")
    suspend fun getPendingOutboxItems(): List<SyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutboxItem(item: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE status = 'SYNCED'")
    suspend fun clearSyncedOutbox()

    @Query("SELECT * FROM telemetry_metrics ORDER BY timestampEpochMs DESC")
    fun observeTelemetry(): Flow<List<TelemetryMetricEntity>>

    @Query("SELECT * FROM telemetry_metrics")
    suspend fun getAllTelemetry(): List<TelemetryMetricEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(metric: TelemetryMetricEntity)
}
