package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        InspectionJobEntity::class,
        CaptureEvidenceEntity::class,
        SyncOutboxEntity::class,
        TelemetryMetricEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FieldCaptureDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var INSTANCE: FieldCaptureDatabase? = null

        fun getInstance(context: Context): FieldCaptureDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FieldCaptureDatabase::class.java,
                    "fieldcapture.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
