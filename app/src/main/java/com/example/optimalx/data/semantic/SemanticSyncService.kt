package com.example.optimalx.data.semantic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class SemanticSyncService(
    private val materializer: SemanticMaterializer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val queue = Channel<String>(capacity = Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                val reason = queue.receive()
                runCatching {
                    val result = materializer.syncForReason(reason)
                    if (result.actionLabel != "noop") {
                        Log.i(
                            TAG,
                            "Synced semantic index (reason=$reason, action=${result.actionLabel}, upserted=${result.upsertedCount}, deleted=${result.deletedCount})",
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Semantic sync failed (reason=$reason): ${error.message}", error)
                }
            }
        }
    }

    fun requestSync(reason: String) {
        queue.trySend(reason)
    }

    suspend fun syncNow(reason: String): SemanticSyncResult {
        val result = materializer.syncForReason(reason)
        Log.i(
            TAG,
            "SyncNow complete (reason=$reason, action=${result.actionLabel}, upserted=${result.upsertedCount}, deleted=${result.deletedCount})",
        )
        return result
    }

    private companion object {
        const val TAG = "OptimalX.SemanticSync"
    }
}
