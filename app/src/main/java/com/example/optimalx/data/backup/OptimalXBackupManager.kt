package com.example.optimalx.data.backup

// Spec: app/docs/architecture/OPTIMALX_LINK.md — single source of truth for bulk in/out.
// This file is the SAF (Storage Access Framework) channel. The phone-side Ktor server
// scoped to the OptimalX Link screen and the PySide6 desktop app (see
// OPTIMALX_LINK_IMPLEMENTATION_PLAN.md) call into the same archive core
// (BackupArchive.writeBackupZip / extractBackupZip / planRestore).

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.preferences.DumpEditState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

object OptimalXBackupManager {

    const val BACKUP_MIME_TYPE = "application/zip"
    private const val TAG = "OptimalXBackup"
    internal const val ATTACHMENTS_DIR_NAME = "optimalx_files"
    internal const val WORKSHOP_DIR_NAME = "workshop"

    fun suggestedExportFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "optimalx-backup-$stamp.zip"
    }

    /**
     * SAF channel export. Builds a [BackupManifest] from current app state and
     * delegates the actual archive write to [BackupArchive.writeBackupZip].
     */
    fun export(context: Context, destination: Uri, appVersionName: String): BackupResult {
        return runCatching {
            // "wt" forces truncation when overwriting an existing SAF file. Without it
            // some providers leave trailing bytes from the previous file, which produces
            // a zip readers see as corrupt or as missing manifest.json.
            val out = context.contentResolver.openOutputStream(destination, "wt")
                ?: context.contentResolver.openOutputStream(destination)
                ?: return BackupResult.failure("Could not open export destination.")

            out.use { rawOut -> writeSnapshotTo(context, appVersionName, rawOut) }

            Log.i(TAG, "Export complete: dbVersion=${AppDatabase.SCHEMA_VERSION}, app=$appVersionName")
            BackupResult.success("Backup exported.")
        }.getOrElse {
            Log.e(TAG, "Export failed", it)
            BackupResult.failure(it.message ?: "Export failed.")
        }
    }

    /**
     * Streams a full OptimalX snapshot (manifest + checkpointed DB + attachments + workshop)
     * into [out]. Used by both the SAF channel and the OptimalX Link HTTP server so that
     * any channel observes identical archive bytes for the same app state.
     *
     * Performs `PRAGMA wal_checkpoint(FULL)` on the live database before reading the .db
     * file so the on-disk file reflects everything currently in WAL. The caller is
     * responsible for closing [out].
     *
     * @throws IllegalStateException if the database file is missing.
     */
    fun writeSnapshotTo(context: Context, appVersionName: String, out: OutputStream) {
        val db = AppDatabase.getInstance(context)
        checkpointDatabase(db)
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        check(dbFile.exists()) { "Database file not found at ${dbFile.path}" }

        val manifest = BackupManifest(
            formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
            exportEpochMs = System.currentTimeMillis(),
            appVersionName = appVersionName,
            dbVersion = AppDatabase.SCHEMA_VERSION,
            includesFiles = true,
        )
        val dumpEditJson = runBlocking { encodeDumpEditForBackup(context) }
        val filesDir = context.filesDir
        BufferedOutputStream(out).use { buffered ->
            BackupArchive.writeBackupZip(
                out = buffered,
                manifest = manifest,
                dbFile = dbFile,
                attachmentsDir = File(filesDir, ATTACHMENTS_DIR_NAME),
                workshopDir = File(filesDir, WORKSHOP_DIR_NAME),
                dumpEditJson = dumpEditJson,
            )
        }
    }

    private suspend fun encodeDumpEditForBackup(context: Context): String {
        val state = DumpEditPreferences.readState(context)
        return BackupArchive.json.encodeToString(
            DumpEditBackupPayload(
                content = state.content,
                aiLocked = state.aiLocked,
                aiBlind = state.aiBlind,
            ),
        )
    }

    private suspend fun restoreDumpEditFromBackup(context: Context, plan: RestorePlan.Ok) {
        val dumpEditFile = BackupArchive.dumpEditFile(plan) ?: return
        val payload = BackupArchive.parseDumpEditPayload(dumpEditFile) ?: return
        DumpEditPreferences.restoreState(
            context,
            DumpEditState(
                content = payload.content,
                aiLocked = payload.aiLocked,
                aiBlind = payload.aiBlind,
            ),
        )
    }

    /**
     * Returns counters for `/v1/status` responses on the OptimalX Link HTTP server.
     * Counts are file-system-based (not DB queries) so the status endpoint stays
     * cheap and works even when the DB is locked by another writer.
     */
    fun snapshotStats(context: Context): SnapshotStats {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val filesDir = context.filesDir
        val attachments = File(filesDir, ATTACHMENTS_DIR_NAME)
        val workshop = File(filesDir, WORKSHOP_DIR_NAME)
        val attachmentCount = countFilesRecursively(attachments)
        // A "workshop project" is a top-level child directory under the workshop folder.
        val workshopProjectCount = workshop
            .listFiles { f -> f.isDirectory }
            ?.size
            ?: 0
        return SnapshotStats(
            dbFileSize = if (dbFile.exists()) dbFile.length() else 0L,
            attachmentCount = attachmentCount,
            workshopProjectCount = workshopProjectCount,
        )
    }

    private fun countFilesRecursively(root: File): Int {
        if (!root.exists() || !root.isDirectory) return 0
        var count = 0
        root.walkTopDown().forEach { if (it.isFile) count += 1 }
        return count
    }

    fun import(context: Context, source: Uri): BackupResult {
        return when (detectImportKind(context, source)) {
            ImportKind.ZIP_BACKUP -> importZipBackup(context, source)
            ImportKind.SQLITE_DB -> importRawDatabase(context, source)
            ImportKind.UNKNOWN -> BackupResult.failure(
                "Unsupported file. Choose an OptimalX .zip backup or a raw optimalx.db from Device Explorer.",
            )
        }
    }

    private enum class ImportKind {
        ZIP_BACKUP,
        SQLITE_DB,
        UNKNOWN,
    }

    private fun detectImportKind(context: Context, source: Uri): ImportKind {
        val fileName = queryDisplayName(context, source)?.lowercase()
        if (fileName != null) {
            if (fileName.endsWith(".zip")) return ImportKind.ZIP_BACKUP
            if (fileName.endsWith(".db") || fileName.endsWith(".sqlite")) return ImportKind.SQLITE_DB
        }
        return readHeaderKind(context, source)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun readHeaderKind(context: Context, source: Uri): ImportKind {
        val header = ByteArray(16)
        val read = context.contentResolver.openInputStream(source)?.use { input ->
            input.read(header)
        } ?: return ImportKind.UNKNOWN
        if (read < 4) return ImportKind.UNKNOWN
        if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) return ImportKind.ZIP_BACKUP
        if (read >= 15 && header.copyOfRange(0, 15).decodeToString() == "SQLite format 3") {
            return ImportKind.SQLITE_DB
        }
        return ImportKind.UNKNOWN
    }

    private fun importZipBackup(context: Context, source: Uri): BackupResult {
        val tempDir = File(context.cacheDir, "optimalx-import-${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        return try {
            val input = context.contentResolver.openInputStream(source)
                ?: return BackupResult.failure("Could not read backup file.")

            val extracted = input.use { stream -> BackupArchive.extractBackupZip(stream, tempDir) }
            Log.i(TAG, "Extracted ${extracted.entryNames.size} entries: ${extracted.entryNames.take(20)}")

            when (val plan = BackupArchive.planRestore(
                extracted = extracted,
                hostSchemaVersion = AppDatabase.SCHEMA_VERSION,
                dbFileName = AppDatabase.DATABASE_NAME,
            )) {
                is RestorePlan.Ok -> applyRestore(context, plan)
                is RestorePlan.Failure -> {
                    Log.w(TAG, "Restore plan failure: ${plan.reason}")
                    BackupResult.failure(renderFailure(plan.reason))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Import failed", t)
            BackupResult.failure(t.message ?: "Import failed.")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Extract a snapshot zip stream into [stagingDir] and validate it against
     * the host's schema version. Used by the OptimalX Link HTTP channel
     * (`POST /v1/restore/full`) to vet an inbound archive before showing the
     * confirm dialog.
     *
     * The caller owns [stagingDir] and is responsible for deleting it once
     * the restore is applied or abandoned. Leaving the directory alive
     * between validation and apply is exactly the point — the dialog tap
     * happens between those two steps.
     */
    fun validateZipForRestore(input: java.io.InputStream, stagingDir: File): RestoreValidation {
        stagingDir.mkdirs()
        val extracted = try {
            BackupArchive.extractBackupZip(input, stagingDir)
        } catch (t: Throwable) {
            Log.e(TAG, "Restore validation: extract failed", t)
            return RestoreValidation.Failed(
                message = t.message ?: "Snapshot could not be extracted.",
            )
        }
        Log.i(
            TAG,
            "Restore validation: ${extracted.entryNames.size} entries, " +
                "first ${extracted.entryNames.take(20)}",
        )
        return when (
            val plan = BackupArchive.planRestore(
                extracted = extracted,
                hostSchemaVersion = AppDatabase.SCHEMA_VERSION,
                dbFileName = AppDatabase.DATABASE_NAME,
            )
        ) {
            is RestorePlan.Ok -> RestoreValidation.Ready(plan = plan)
            is RestorePlan.Failure -> {
                Log.w(TAG, "Restore validation: plan failure ${plan.reason}")
                RestoreValidation.Failed(message = renderFailure(plan.reason))
            }
        }
    }

    /**
     * Apply a previously-validated restore plan. The companion to
     * [validateZipForRestore]; the OptimalX Link `accept` flow on the
     * confirm dialog calls this on the user's tap.
     *
     * Does **not** delete the staging directory referenced by the plan — the
     * caller owns lifecycle (so reject + timeout paths can also clean it up
     * consistently).
     */
    fun applyValidatedRestore(context: Context, plan: RestorePlan.Ok): BackupResult {
        return applyRestore(context, plan)
    }

    private fun applyRestore(context: Context, plan: RestorePlan.Ok): BackupResult {
        AppDatabase.closeAndClearInstance()
        removeDatabaseFiles(context)
        copyDatabaseFile(context, plan.dbSource)
        restoreFilesDirectory(context, plan.effectiveRoot)
        runBlocking { restoreDumpEditFromBackup(context, plan) }

        Log.i(TAG, "Import complete: dbVersion=${plan.manifest.dbVersion}, app=${plan.manifest.appVersionName}")
        return BackupResult.success(
            "Backup imported. Reopen folders or restart the app if lists look stale.",
        )
    }

    private fun renderFailure(reason: FailureReason): String = when (reason) {
        is FailureReason.MissingManifest -> {
            val preview = reason.entriesSeen.take(5).joinToString(", ")
            val suffix = if (reason.entriesSeen.size > 5) ", ..." else ""
            val seen = if (reason.entriesSeen.isEmpty()) {
                "(no entries read)"
            } else {
                "[$preview$suffix]"
            }
            "Backup is missing manifest.json. The .zip may be corrupt or was re-archived. " +
                "Saw ${reason.entriesSeen.size} entries: $seen. Try exporting a fresh backup " +
                "(the previous export may have been truncated when overwriting an existing file)."
        }
        FailureReason.EmptyManifest -> "Backup manifest.json is empty."
        is FailureReason.InvalidManifest -> "Backup manifest.json could not be read: ${reason.reason}"
        is FailureReason.UnsupportedFormat ->
            "Unsupported backup format (version ${reason.formatVersion})."
        is FailureReason.BackupNewerThanHost ->
            "Backup requires a newer app (DB v${reason.backupDbVersion}; this app is v${reason.hostDbVersion})."
        is FailureReason.MissingDatabase -> "Backup is missing the database file."
    }

    private fun importRawDatabase(context: Context, source: Uri): BackupResult {
        val tempDb = File(context.cacheDir, "optimalx-import-${System.currentTimeMillis()}.db")
        return try {
            context.contentResolver.openInputStream(source)?.use { input ->
                tempDb.outputStream().use { output -> input.copyTo(output) }
            } ?: return BackupResult.failure("Could not read database file.")

            if (!isSqliteFile(tempDb)) {
                return BackupResult.failure("File is not a valid SQLite database.")
            }

            AppDatabase.closeAndClearInstance()
            removeDatabaseFiles(context)
            copyDatabaseFile(context, tempDb)

            BackupResult.success(
                "Database imported. Attached files and workshop projects were not included — " +
                    "file links may be broken unless you also restore files from a full .zip backup. " +
                    "Restart the app if lists look stale.",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Raw DB import failed", t)
            BackupResult.failure(t.message ?: "Database import failed.")
        } finally {
            tempDb.delete()
        }
    }

    private fun isSqliteFile(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        return file.inputStream().use { input ->
            val header = ByteArray(15)
            input.read(header) == 15 && header.decodeToString() == "SQLite format 3"
        }
    }

    private fun checkpointDatabase(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
    }

    private fun removeDatabaseFiles(context: Context) {
        val base = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
            .forEach { file ->
                if (file.exists()) file.delete()
            }
    }

    private fun copyDatabaseFile(context: Context, source: File) {
        val dest = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
    }

    private fun restoreFilesDirectory(context: Context, extractedRoot: File) {
        val filesDir = context.filesDir
        File(filesDir, ATTACHMENTS_DIR_NAME).deleteRecursively()
        File(filesDir, WORKSHOP_DIR_NAME).deleteRecursively()

        copyExtractedTree(
            extractedRoot,
            BackupArchive.FILES_ATTACHMENTS_PREFIX,
            File(filesDir, ATTACHMENTS_DIR_NAME),
        )
        copyExtractedTree(
            extractedRoot,
            BackupArchive.FILES_WORKSHOP_PREFIX,
            File(filesDir, WORKSHOP_DIR_NAME),
        )
    }

    private fun copyExtractedTree(extractedRoot: File, zipPrefix: String, destDir: File) {
        val sourceDir = File(extractedRoot, zipPrefix)
        if (!sourceDir.exists()) return
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { source ->
                val relative = source.relativeTo(sourceDir).path
                val target = File(destDir, relative)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
    }
}

data class BackupResult(
    val success: Boolean,
    val message: String,
) {
    companion object {
        fun success(message: String) = BackupResult(success = true, message = message)
        fun failure(message: String) = BackupResult(success = false, message = message)
    }
}

/**
 * Outcome of [OptimalXBackupManager.validateZipForRestore]. [Ready] carries a
 * [RestorePlan.Ok] the caller can later pass to
 * [OptimalXBackupManager.applyValidatedRestore]; [Failed] carries a
 * user-facing reason already rendered via the same formatter the SAF path
 * uses, so the OptimalX Link confirm screen and the existing import flow
 * surface identical wording.
 */
sealed interface RestoreValidation {
    data class Ready(val plan: RestorePlan.Ok) : RestoreValidation
    data class Failed(val message: String) : RestoreValidation
}

/**
 * Lightweight stat block surfaced by `GET /v1/status` on the OptimalX Link
 * server. Values are derived from disk inspection only and do not require
 * the database to be open.
 */
data class SnapshotStats(
    val dbFileSize: Long,
    val attachmentCount: Int,
    val workshopProjectCount: Int,
)
