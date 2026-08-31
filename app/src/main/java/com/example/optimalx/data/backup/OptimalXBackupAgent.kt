package com.example.optimalx.data.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.optimalx.data.db.AppDatabase

/**
 * Prepares app data before Android Auto Backup reads files from disk.
 *
 * Room uses WAL mode; without a checkpoint the cloud copy of [optimalx.db] can lag behind
 * committed writes in [optimalx.db-wal]. Checkpoints here so each scheduled backup is current.
 */
class OptimalXBackupAgent : BackupAgent() {

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) {
        checkpointDatabase(applicationContext)
        // OptimalX uses full (file) backup via fullBackupContent rules, not key/value payloads.
    }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) {
        // Full restore is handled by the framework from fullBackupContent / dataExtractionRules.
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        checkpointDatabase(applicationContext)
        super.onFullBackup(data)
    }

    companion object {
        private const val TAG = "OptimalX.BackupAgent"

        fun checkpointDatabase(context: Context) {
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (!dbFile.isFile) return
            runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    db.execSQL("PRAGMA wal_checkpoint(FULL)")
                }
                Log.i(TAG, "WAL checkpoint complete (${dbFile.length()} bytes)")
            }.onFailure { e ->
                Log.w(TAG, "WAL checkpoint failed", e)
            }
        }
    }
}
