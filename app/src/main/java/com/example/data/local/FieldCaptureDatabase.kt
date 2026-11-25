package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InspectionJobEntity::class,
        CaptureEvidenceEntity::class,
        SyncOutboxEntity::class,
        TelemetryMetricEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FieldCaptureDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var INSTANCE: FieldCaptureDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE capture_evidences ADD COLUMN evidenceSource TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                db.execSQL(
                    "ALTER TABLE capture_evidences ADD COLUMN imageSha256 TEXT"
                )
            }
        }

        fun getInstance(context: Context): FieldCaptureDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FieldCaptureDatabase::class.java,
                    "fieldcapture.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
