package com.example.optimalx.data.sync

import android.content.Context
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SyncOperationResult {
    data class Success(
        val message: String,
        val applied: Map<String, Int>,
        val skipped: Map<String, Int>,
        val conflicts: List<SyncConflictDto>,
        val serverTime: Long,
        val receivedRows: Int = 0,
        val receivedSummary: String? = null,
    ) : SyncOperationResult

    data class Failure(val message: String) : SyncOperationResult
}

class SyncService(
    private val context: Context,
    private val db: AppDatabase,
    private val preferences: SyncPreferences = SyncPreferences(context),
    private val api: SyncApi = SyncApi(),
    private val pushBuilder: SyncPushBuilder = SyncPushBuilder(context, db),
    private val pullApplier: SyncPullApplier = SyncPullApplier(context, db),
) {

    suspend fun testConnection(host: String, port: Int, token: String): SyncOperationResult =
        withContext(Dispatchers.IO) {
            val endpoint = SyncPairingUri.normalizeEndpoint(host, port, token)
            when (val result = api.status(endpoint)) {
                is SyncCallResult.Failure -> SyncOperationResult.Failure(result.message)
                is SyncCallResult.Success -> {
                    preferences.saveEndpointAfterSuccessfulTest(endpoint)
                    SyncOperationResult.Success(
                        message = "Connected to ${endpoint.host}:${endpoint.port} (db v${result.value.dbVersion})",
                        applied = emptyMap(),
                        skipped = emptyMap(),
                        conflicts = emptyList(),
                        serverTime = result.value.serverTime,
                    )
                }
            }
        }

    suspend fun push(): SyncOperationResult = withContext(Dispatchers.IO) {
        runSyncOperation { endpoint, deviceId, lastSyncAt ->
            val tables = pushBuilder.buildPushPayload(lastSyncAt)
            val request = SyncPushPullRequest(
                deviceId = deviceId,
                lastSyncAt = lastSyncAt,
                tiers = listOf(1, 2),
                tables = tables,
            )
            when (val result = api.push(endpoint, request)) {
                is SyncCallResult.Failure -> SyncOperationResult.Failure(result.message)
                is SyncCallResult.Success -> {
                    val body = result.value
                    SyncOperationResult.Success(
                        message = formatResultMessage("Push", body),
                        applied = body.applied,
                        skipped = body.skipped,
                        conflicts = body.conflicts,
                        serverTime = body.serverTime,
                    )
                }
            }
        }
    }

    suspend fun pull(full: Boolean = false): SyncOperationResult = withContext(Dispatchers.IO) {
        runSyncOperation { endpoint, deviceId, lastSyncAt ->
            val watermark = if (full) 0L else lastSyncAt
            val request = SyncPushPullRequest(
                deviceId = deviceId,
                lastSyncAt = watermark,
                tiers = listOf(1, 2),
            )
            when (val result = api.pull(endpoint, request)) {
                is SyncCallResult.Failure -> SyncOperationResult.Failure(result.message)
                is SyncCallResult.Success -> {
                    val body = result.value
                    val payload = body.tables
                        ?: return@runSyncOperation SyncOperationResult.Failure("Pull response missing tables")
                    val receivedRows = payload.totalRows()
                    val receivedSummary = payload.summaryLabel()
                    if (receivedRows == 0) {
                        Log.w(
                            TAG,
                            "Pull returned 0 rows (full=$full, lastSyncAt=$watermark). " +
                                "If desktop has changes, check desktop pull.js uses client lastSyncAt and bumps updatedAt on save.",
                        )
                    }
                    val localStats = pullApplier.applyAll(payload)
                    val mergedConflicts = body.conflicts + localStats.conflicts
                    SyncOperationResult.Success(
                        message = formatPullMessage(full, watermark, body, localStats, receivedSummary),
                        applied = mergeCounts(body.applied, localStats.applied),
                        skipped = mergeCounts(body.skipped, localStats.skipped),
                        conflicts = mergedConflicts,
                        serverTime = body.serverTime,
                        receivedRows = receivedRows,
                        receivedSummary = receivedSummary,
                    )
                }
            }
        }
    }

    suspend fun resolveConflict(
        conflict: SyncConflictDto,
        keepLocal: Boolean,
    ): SyncOperationResult = withContext(Dispatchers.IO) {
        runSyncOperation { endpoint, deviceId, _ ->
            val choice = if (keepLocal) "keep_local" else "keep_remote"
            val request = SyncResolveRequest(
                resolutions = listOf(
                    SyncResolutionDto(
                        table = conflict.table,
                        globalId = conflict.globalId,
                        choice = choice,
                        mergedRow = null,
                    ),
                ),
            )
            when (val result = api.resolve(endpoint, request)) {
                is SyncCallResult.Failure -> SyncOperationResult.Failure(result.message)
                is SyncCallResult.Success -> {
                    val body = result.value
                    if (!keepLocal && body.tables != null) {
                        pullApplier.applyAll(body.tables)
                    }
                    SyncOperationResult.Success(
                        message = "Resolved ${conflict.table} ${conflict.globalId} ($choice)",
                        applied = body.applied,
                        skipped = body.skipped,
                        conflicts = body.conflicts,
                        serverTime = body.serverTime,
                    )
                }
            }
        }
    }

    suspend fun applyPairingUri(raw: String): Boolean {
        val endpoint = SyncPairingUri.parse(raw) ?: return false
        preferences.saveDraft(endpoint.host, endpoint.port, endpoint.token)
        return true
    }

    private suspend fun runSyncOperation(
        block: suspend (SyncEndpoint, String, Long) -> SyncOperationResult,
    ): SyncOperationResult {
        val snapshot = preferences.readSnapshot()
        val endpoint = preferences.buildEndpoint(snapshot)
            ?: return SyncOperationResult.Failure("Set desktop host and bearer token, then Test connection.")
        val deviceId = preferences.ensureDeviceId()
        val lastSyncAt = snapshot.lastSyncAt
        val outcome = block(endpoint, deviceId, lastSyncAt)
        if (outcome is SyncOperationResult.Success && outcome.serverTime > 0L) {
            preferences.updateLastSyncAt(outcome.serverTime)
        }
        return outcome
    }

    private fun formatResultMessage(
        label: String,
        body: SyncApplyResponse,
        local: SyncApplyStats? = null,
    ): String {
        val applied = mergeCounts(body.applied, local?.applied ?: emptyMap()).values.sum()
        val skipped = mergeCounts(body.skipped, local?.skipped ?: emptyMap()).values.sum()
        val conflicts = body.conflicts.size + (local?.conflicts?.size ?: 0)
        return buildString {
            append(label)
            append(" complete — applied ")
            append(applied)
            append(", skipped ")
            append(skipped)
            if (conflicts > 0) {
                append(", conflicts ")
                append(conflicts)
            }
        }
    }

    private fun mergeCounts(
        a: Map<String, Int>,
        b: Map<String, Int>,
    ): Map<String, Int> {
        val out = a.toMutableMap()
        b.forEach { (k, v) -> out[k] = (out[k] ?: 0) + v }
        return out
    }

    private fun formatPullMessage(
        full: Boolean,
        watermark: Long,
        body: SyncApplyResponse,
        local: SyncApplyStats,
        receivedSummary: String,
    ): String {
        val applied = mergeCounts(body.applied, local.applied).values.sum()
        val skipped = mergeCounts(body.skipped, local.skipped).values.sum()
        val conflicts = body.conflicts.size + local.conflicts.size
        return buildString {
            append(if (full) "Full pull" else "Pull")
            append(" complete — ")
            append(receivedSummary)
            append("; applied ")
            append(applied)
            append(", skipped ")
            append(skipped)
            if (conflicts > 0) {
                append(", conflicts ")
                append(conflicts)
            }
            if (applied == 0 && skipped == 0 && body.tables?.totalRows() == 0) {
                append(
                    ". Desktop sent no rows since watermark $watermark — " +
                        "edit on desktop (must bump updatedAt) or use Full pull.",
                )
            } else if (applied == 0 && skipped > 0) {
                append(". Local copies are newer; try editing on desktop first.")
            }
        }
    }

    companion object {
        private const val TAG = "SyncService"
    }
}
