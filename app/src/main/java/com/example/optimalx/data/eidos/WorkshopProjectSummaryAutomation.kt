package com.example.optimalx.data.eidos

import android.content.Context
import android.util.Log
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Regenerates workshop [Subfolder.projectSummary] after spec milestones — without user action.
 *
 * Triggers:
 * - Generate specs kickoff finished successfully (always).
 * - Doc-align pass finished successfully (spec .md changed — align only runs when stale).
 *
 * Skips when the bounded spec digest matches the digest at the last successful summary.
 */
object WorkshopProjectSummaryAutomation {

    private const val TAG = "WorkshopProjectSummary"
    private val running = ConcurrentHashMap<Long, Boolean>()

    suspend fun onWorkshopSendSucceeded(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        docAlignScope: WorkshopDocAlignScope?,
    ) {
        val pendingSpecGenerate = WorkshopProjectPreferences.isPendingProjectSummaryAfterSpecGenerate(
            context,
            subfolderId,
        )
        when {
            pendingSpecGenerate -> {
                WorkshopProjectPreferences.setPendingProjectSummaryAfterSpecGenerate(
                    context,
                    subfolderId,
                    false,
                )
                maybeRegenerate(context, db, subfolderId, force = true, reason = "spec_generate")
            }
            docAlignScope != null ->
                maybeRegenerate(
                    context,
                    db,
                    subfolderId,
                    force = false,
                    reason = "doc_align_${docAlignScope.name.lowercase()}",
                )
        }
    }

    suspend fun onWorkshopSendFailedAfterSpecGenerateKickoff(context: Context, subfolderId: Long) {
        if (WorkshopProjectPreferences.isPendingProjectSummaryAfterSpecGenerate(context, subfolderId)) {
            WorkshopProjectPreferences.setPendingProjectSummaryAfterSpecGenerate(context, subfolderId, false)
        }
    }

    suspend fun recordDigestAfterManualGenerate(context: Context, db: AppDatabase, subfolderId: Long) {
        val digest = computeBoundedSpecDigest(db, subfolderId)
        if (digest.isNotBlank()) {
            WorkshopProjectPreferences.setProjectSummarySpecDigest(context, subfolderId, digest)
        }
    }

    private suspend fun maybeRegenerate(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        force: Boolean,
        reason: String,
    ) {
        if (running.putIfAbsent(subfolderId, true) != null) {
            Log.d(TAG, "skip subfolderId=$subfolderId reason=$reason (already running)")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val digest = computeBoundedSpecDigest(db, subfolderId)
                if (digest.isBlank()) {
                    Log.d(TAG, "skip subfolderId=$subfolderId reason=$reason (no spec markdown)")
                    return@withContext
                }
                if (!force) {
                    val stored = WorkshopProjectPreferences.getProjectSummarySpecDigest(context, subfolderId)
                    if (stored == digest) {
                        Log.d(TAG, "skip subfolderId=$subfolderId reason=$reason (spec digest unchanged)")
                        return@withContext
                    }
                    if (stored.isEmpty()) {
                        val existing = db.subfolderDao().getById(subfolderId)?.projectSummary?.trim().orEmpty()
                        if (existing.isNotEmpty()) {
                            WorkshopProjectPreferences.setProjectSummarySpecDigest(context, subfolderId, digest)
                            Log.d(TAG, "seed digest subfolderId=$subfolderId reason=$reason (legacy summary)")
                            return@withContext
                        }
                    }
                }
                val app = context.applicationContext as? OptimalXApplication
                if (app == null) {
                    Log.w(TAG, "skip subfolderId=$subfolderId reason=$reason (no application)")
                    return@withContext
                }
                when (val result = app.contentSummaryService.generateWorkshopProjectSummary(subfolderId)) {
                    ContentSummaryResult.Success -> {
                        WorkshopProjectPreferences.setProjectSummarySpecDigest(context, subfolderId, digest)
                        Log.i(TAG, "regenerated subfolderId=$subfolderId reason=$reason")
                    }
                    is ContentSummaryResult.Failed ->
                        Log.w(TAG, "failed subfolderId=$subfolderId reason=$reason: ${result.message}")
                }
            }
        } finally {
            running.remove(subfolderId)
        }
    }

    suspend fun computeBoundedSpecDigest(db: AppDatabase, subfolderId: Long): String {
        val files = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val body = WorkshopSpecMarkdown.loadBounded(files)
        if (body.isBlank()) return ""
        return sha256Hex(body)
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
