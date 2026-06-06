package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.dao.EidosApiTraceDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.EidosApiTraceRound
import com.example.optimalx.data.model.EidosApiTraceRun
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists one trace [run][EidosApiTraceRun] per user send, with one [round][EidosApiTraceRound]
 * per provider HTTP call (initial + each tool continuation).
 */
class EidosApiTraceRecorder(
    private val dao: EidosApiTraceDao,
) {
    private val mutex = Mutex()
    private var activeRunId: Long? = null
    private var roundIndex: Int = 0
    private var finished: Boolean = false

    suspend fun beginRun(
        conversationId: Long?,
        conversationTitle: String,
        directory: EidosApiTraceDirectory,
        scopeType: String,
        provider: String,
        userMessage: String,
    ) {
        mutex.withLock {
            finished = false
            roundIndex = 0
            val now = System.currentTimeMillis()
            val runId = dao.insertRun(
                EidosApiTraceRun(
                    conversationId = conversationId,
                    conversationTitle = conversationTitle.ifBlank { "Eidos" },
                    directoryKey = directory.key,
                    directoryLabel = directory.label,
                    scopeType = scopeType,
                    provider = provider,
                    userMessagePreview = userMessage.trim().take(240),
                    startedAtMillis = now,
                    roundCount = 0,
                    status = "in_progress",
                ),
            )
            activeRunId = runId
            pruneOldRunsIfNeeded()
        }
    }

    suspend fun recordExchange(
        phase: EidosRequestPhase,
        requestBodyJson: String,
        response: EidosResponse,
    ) {
        mutex.withLock {
            val runId = activeRunId ?: return
            if (finished) return
            val index = roundIndex
            dao.insertRound(
                EidosApiTraceRound(
                    runId = runId,
                    roundIndex = index,
                    phase = phase.name,
                    requestJson = EidosApiTraceJson.formatRequest(requestBodyJson),
                    responseJson = EidosApiTraceJson.summarizeResponse(response),
                    recordedAtMillis = System.currentTimeMillis(),
                ),
            )
            roundIndex += 1
            dao.updateRunRoundCount(runId, roundIndex)
        }
    }

    suspend fun finishRun(status: String) {
        mutex.withLock {
            val runId = activeRunId ?: return
            if (finished) return
            finished = true
            dao.finishRun(
                runId = runId,
                finishedAt = System.currentTimeMillis(),
                status = status,
                roundCount = roundIndex,
            )
            activeRunId = null
        }
    }

    private suspend fun pruneOldRunsIfNeeded() {
        val count = dao.countRuns()
        if (count <= MAX_STORED_RUNS) return
        val excess = count - MAX_STORED_RUNS
        dao.deleteRunsByIds(dao.oldestRunIds(excess))
    }

    companion object {
        private const val MAX_STORED_RUNS = 250

        suspend fun createIfEnabled(context: Context, database: AppDatabase): EidosApiTraceRecorder? {
            if (!EidosApiTraceFeature.isEnabled(context)) return null
            return EidosApiTraceRecorder(database.eidosApiTraceDao())
        }
    }
}
