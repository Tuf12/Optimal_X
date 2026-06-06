package com.example.optimalx.data.eidos

import android.util.Log
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosTokenUsage

/**
 * Token usage logging for Eidos LLM calls (Logcat tag [LOG_TAG]).
 * [EidosApiClient] only invokes this when the app is debuggable.
 * Filter Logcat with `OptimalX.Eidos.Usage` to inspect cache hits and per-round spend.
 */
object EidosUsageLogger {

    const val LOG_TAG = "OptimalX.Eidos.Usage"

    fun log(
        providerName: String,
        phase: EidosRequestPhase,
        round: Int,
        usage: EidosTokenUsage?,
        providerResponseId: String?,
        toolCallCount: Int,
    ) {
        val base = buildString {
            append("provider=")
            append(providerName)
            append(" phase=")
            append(phase.name)
            append(" round=")
            append(round)
            append(" tools=")
            append(toolCallCount)
            append(" resp=")
            append(providerResponseId?.take(24) ?: "-")
        }
        if (usage == null || usage.isEmpty()) {
            Log.d(LOG_TAG, "$base usage=(not reported)")
            return
        }
        Log.d(LOG_TAG, "$base ${usage.toLogFields()}")
    }
}
