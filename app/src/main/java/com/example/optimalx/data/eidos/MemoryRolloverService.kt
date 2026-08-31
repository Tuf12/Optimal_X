package com.example.optimalx.data.eidos

import android.content.Context
import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.preferences.ApiKeyNames
import com.example.optimalx.data.preferences.EncryptedSettingKeys
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.getEncryptedPrefs
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MemoryRolloverService(
    private val context: Context,
    private val database: AppDatabase,
    private val eidosApiClient: EidosApiClient,
    private val embeddingEngine: EmbeddingEngine,
    private val semanticIndexer: SemanticIndexer,
) {
    private val rolloverOrchestrator by lazy {
        RolloverOrchestrator(
            context = context,
            database = database,
            eidosApiClient = eidosApiClient,
            embeddingEngine = embeddingEngine,
            semanticIndexer = semanticIndexer,
        )
    }

    suspend fun runMemoryRollover(timestamp: Long = System.currentTimeMillis()): MemoryRolloverResult = withContext(Dispatchers.IO) {
        Log.i(
            MemoryRolloverScheduler.LOG_TAG,
            "runMemoryRollover invoked timestamp=$timestamp dateKey=${dateKeyFromTimestamp(timestamp)}",
        )
        if (!EidosSystemFeatureFlags.MEMORY_ROLLOVER_ENABLED) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.SKIPPED,
                message = "Nightly memory rollover is paused.",
                dailyCleared = false,
            )
        }
        val provider = resolveActiveProvider()
        if (!hasApiKeyForProvider(provider)) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "No API key configured for ${providerDisplayName(provider)}. Add one in Settings before rollover.",
                dailyCleared = false,
            )
        }

        val dailySnapshot = readDailyMemorySnapshot()
        if (dailySnapshot.content.isBlank()) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.SKIPPED,
                message = "Daily Memory is empty. Nothing to roll over.",
                dailyCleared = false,
            )
        }

        val journalBefore = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_JOURNAL)
        val ltmBefore = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_MEMORY)

        val rolloverId = buildRolloverId(timestamp)
        val text = rolloverOrchestrator.run(
            timestamp = timestamp,
            rolloverId = rolloverId,
            preloadedDailyContent = dailySnapshot.content,
        ).trim()
        if (text.contains(ROLLOVER_EMPTY_MARKER)) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.SKIPPED,
                message = "Daily Memory was empty during rollover. Nothing was changed.",
                dailyCleared = false,
            )
        }
        val ok = text.contains(ROLLOVER_OK_MARKER)
        if (!ok) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = if (text.isBlank()) {
                    "Rollover did not complete and returned no status marker. Daily Memory was left untouched."
                } else {
                    "Rollover did not complete cleanly (${text.take(180)}). Daily Memory was left untouched."
                },
                dailyCleared = false,
            )
        }

        val report = parseRolloverReport(text)
            ?: return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "Rollover reported success marker but missing structured completion report. Daily Memory was left untouched.",
                dailyCleared = false,
            )

        val journalAfter = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_JOURNAL)
        val ltmAfter = getSystemFolderMaxUpdatedAt(SystemFolderNames.EIDOS_MEMORY)

        val journalAdvanced = journalAfter > journalBefore
        val journalContainsRolloverId = journalContainsRolloverId(rolloverId, timestamp)
        if (!report.journalWritten || !journalAdvanced || !journalContainsRolloverId) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "Rollover did not verify Journal write for rollover_id=$rolloverId. Daily Memory was left untouched.",
                dailyCleared = false,
            )
        }

        if (report.ltmPromotions > 0 && ltmAfter <= ltmBefore) {
            return@withContext MemoryRolloverResult(
                status = MemoryRolloverStatus.FAILED,
                message = "Rollover reported LTM promotions but Long-Term Memory did not update. Daily Memory was left untouched.",
                dailyCleared = false,
            )
        }

        clearDailyMemory(dailySnapshot.subfolderIds)

        Log.i(MemoryRolloverScheduler.LOG_TAG, "runMemoryRollover SUCCESS daily cleared")
        MemoryRolloverResult(
            status = MemoryRolloverStatus.SUCCESS,
            message = "Memory rollover completed (journalRead=${report.journalRead}, journal=${report.journalWritten}, ltmPromotions=${report.ltmPromotions}) and Daily Memory was cleared.",
            dailyCleared = true,
        )
    }.also { result ->
        if (result.status != MemoryRolloverStatus.SUCCESS) {
            Log.i(MemoryRolloverScheduler.LOG_TAG, "runMemoryRollover ended: ${result.status} — ${result.message}")
        }
    }

    private suspend fun resolveActiveProvider(): String {
        val encPrefs = getEncryptedPrefs(context)
        return encPrefs.getString(EncryptedSettingKeys.ACTIVE_PROVIDER, null)
            ?: context.settingsDataStore.data.first()[SettingsKeys.ACTIVE_PROVIDER]
            ?: SettingsDefaults.ACTIVE_PROVIDER
    }

    private fun hasApiKeyForProvider(provider: String): Boolean {
        val encPrefs = getEncryptedPrefs(context)
        val keyName = when (provider) {
            "openai" -> ApiKeyNames.OPENAI
            "anthropic" -> ApiKeyNames.ANTHROPIC
            "kimi" -> ApiKeyNames.KIMI
            else -> ApiKeyNames.XAI
        }
        return !encPrefs.getString(keyName, null).isNullOrBlank()
    }

    private suspend fun readDailyMemorySnapshot(): DailyMemorySnapshot {
        val parent = database.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)
            ?: return DailyMemorySnapshot()
        val entries = database.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
            .mapNotNull { sf ->
                val note = database.noteDao().getBySubfolderOnce(sf.id) ?: return@mapNotNull null
                if (note.aiBlind) return@mapNotNull null
                val content = note.content.trim()
                if (content.isBlank()) null else sf to content
            }
            .sortedBy { it.first.name }
        if (entries.isEmpty()) return DailyMemorySnapshot()

        val merged = entries.joinToString(separator = "\n\n") { (sf, content) ->
            "[day=${sf.name}]\n$content"
        }
        return DailyMemorySnapshot(
            content = merged,
            subfolderIds = entries.map { it.first.id },
        )
    }

    private suspend fun clearDailyMemory(subfolderIds: List<Long>) {
        if (subfolderIds.isEmpty()) return
        subfolderIds.forEach { subfolderId ->
            val note = database.noteDao().getBySubfolderOnce(subfolderId) ?: return@forEach
            database.noteDao().update(note.copy(content = "", updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun getSystemFolderMaxUpdatedAt(systemFolderName: String): Long {
        val parent = database.parentFolderDao().getSystemFolderByName(systemFolderName) ?: return 0L
        return database.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
            .maxOfOrNull { sf ->
                val noteUpdated = database.noteDao().getBySubfolderOnce(sf.id)?.updatedAt ?: 0L
                maxOf(sf.updatedAt, noteUpdated)
            } ?: 0L
    }

    private suspend fun journalContainsRolloverId(rolloverId: String, timestamp: Long): Boolean {
        val parent = database.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_JOURNAL) ?: return false
        val day = dateKeyFromTimestamp(timestamp)
        val subfolder = database.subfolderDao().getAllByParentOnce(parent.id)
            .firstOrNull { it.deletedAt == null && it.name == day }
            ?: return false
        val note = database.noteDao().getBySubfolderOnce(subfolder.id) ?: return false
        return note.content.contains("rollover_id=$rolloverId")
    }

    private fun parseRolloverReport(text: String): RolloverReport? {
        val reportLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(ROLLOVER_OK_MARKER) }
            ?: return null

        val tokens = reportLine.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val fields = tokens.drop(1)
            .mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                token.substring(0, idx).trim().lowercase() to token.substring(idx + 1).trim()
            }
            .toMap()

        val journalWritten = fields["journal_written"]?.equals("true", ignoreCase = true) ?: false
        val journalRead = fields["journal_read"]?.equals("true", ignoreCase = true) ?: false
        val ltmPromotions = fields["ltm_promotions"]?.toIntOrNull() ?: return null
        return RolloverReport(
            journalRead = journalRead,
            journalWritten = journalWritten,
            ltmPromotions = ltmPromotions.coerceAtLeast(0),
        )
    }

    private fun dateKeyFromTimestamp(timestamp: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }

    private fun providerDisplayName(provider: String): String = when (provider) {
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "kimi" -> "Kimi (Moonshot)"
        else -> "xAI"
    }

    private fun buildRolloverId(timestamp: Long): String {
        val datePart = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
        return "rr_$datePart"
    }

    companion object {
        private const val ROLLOVER_OK_MARKER = "ROLLOVER_OK"
        private const val ROLLOVER_EMPTY_MARKER = "ROLLOVER_EMPTY"

        internal fun rolloverPhaseAllowlistSnapshotForTests(): Map<String, List<String>> =
            RolloverOrchestrator.phaseAllowlistSnapshotForTests()
    }
}

enum class MemoryRolloverStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
}

data class MemoryRolloverResult(
    val status: MemoryRolloverStatus,
    val message: String,
    val dailyCleared: Boolean,
)

private data class DailyMemorySnapshot(
    val content: String = "",
    val subfolderIds: List<Long> = emptyList(),
)
