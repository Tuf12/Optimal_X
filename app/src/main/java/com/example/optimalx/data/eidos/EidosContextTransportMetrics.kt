package com.example.optimalx.data.eidos

import android.util.Log

/**
 * Per-`send()` transport health signal. Tracks input tokens across the HTTP rounds of one send and
 * logs the compounding factor (hop-N input / hop-1 input). A high factor means stable context
 * (system prompt + old history) is being re-transmitted every hop — the token-burn regression this
 * whole subsystem guards against. Debug-only; filter Logcat with [LOG_TAG].
 */
class EidosContextTransportMetrics(
    private val providerName: String,
    private val enabled: Boolean,
) {
    private var firstHopInputTokens: Int? = null

    fun record(round: Int, inputTokens: Int?) {
        if (!enabled || inputTokens == null || inputTokens <= 0) return
        val first = firstHopInputTokens
        if (first == null) {
            firstHopInputTokens = inputTokens
            return
        }
        val factor = inputTokens.toDouble() / first.toDouble()
        val message = "provider=$providerName round=$round input=$inputTokens hop1=$first " +
            "compounding=${"%.2f".format(factor)}x"
        if (factor > WARN_FACTOR) {
            Log.w(LOG_TAG, "$message (high — stable context may be re-sent every hop)")
        } else {
            Log.d(LOG_TAG, message)
        }
    }

    companion object {
        const val LOG_TAG = "OptimalX.Eidos.Transport"
        const val WARN_FACTOR = 2.5
    }
}
