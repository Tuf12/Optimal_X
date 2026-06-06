package com.example.optimalx.data.eidos

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * ON HOLD — syncs [AppIndexMaterializer] into tag_hint_lines when [EidosIndexFeature.isActive].
 * Callers may still invoke [requestSync]; it no-ops while the index is disabled.
 */
class AppIndexSyncService(
    private val materializer: AppIndexMaterializer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val queue = Channel<String>(capacity = Channel.CONFLATED)

    init {
        if (EidosIndexFeature.isActive) {
            scope.launch {
                while (true) {
                    val reason = queue.receive()
                    runCatching {
                        val result = materializer.bootstrapFullIndex()
                        Log.i(
                            "OptimalX.AppIndexSync",
                            "Synced index (reason=$reason, upserted=${result.upsertedCount}, deleted=${result.deletedCount})",
                        )
                    }.onFailure { error ->
                        Log.e("OptimalX.AppIndexSync", "Index sync failed (reason=$reason): ${error.message}", error)
                    }
                }
            }
        }
    }

    fun requestSync(reason: String) {
        if (!EidosIndexFeature.isActive) return
        queue.trySend(reason)
    }

    suspend fun syncNow(reason: String): AppIndexBootstrapResult {
        if (!EidosIndexFeature.isActive) {
            return AppIndexBootstrapResult(upsertedCount = 0, deletedCount = 0)
        }
        val result = materializer.bootstrapFullIndex()
        Log.i(
            "OptimalX.AppIndexSync",
            "SyncNow complete (reason=$reason, upserted=${result.upsertedCount}, deleted=${result.deletedCount})",
        )
        return result
    }
}
