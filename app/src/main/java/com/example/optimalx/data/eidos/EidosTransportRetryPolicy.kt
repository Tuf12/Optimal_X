package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import java.io.IOException

/**
 * Caps cloud-provider retries so intermittent cellular / Wi-Fi loss cannot storm dead HTTP calls.
 *
 * Interactive sends may wait briefly for a handoff. Internal and background work fail immediately
 * when there is no validated internet, and never retry a transport error while offline.
 */
object EidosTransportRetryPolicy {
    const val INTERACTIVE_HANDOFF_WAIT_MS = 15_000L
    const val FAIL_FAST_WAIT_MS = 0L
    const val RETRY_REVALIDATE_WAIT_MS = 2_000L
    const val PRELOAD_AWAIT_MS = 0L

    const val DEFAULT_MAX_ATTEMPTS = 2
    const val TRANSIENT_MAX_ATTEMPTS = 2

    fun isFailFastSurface(entrySurface: EidosEntrySurface): Boolean =
        entrySurface == EidosEntrySurface.INTERNAL ||
            entrySurface == EidosEntrySurface.BACKGROUND_WORKER

    fun initialAwaitMs(entrySurface: EidosEntrySurface): Long =
        if (isFailFastSurface(entrySurface)) FAIL_FAST_WAIT_MS else INTERACTIVE_HANDOFF_WAIT_MS

    fun retryRevalidateWaitMs(entrySurface: EidosEntrySurface): Long =
        if (isFailFastSurface(entrySurface)) FAIL_FAST_WAIT_MS else RETRY_REVALIDATE_WAIT_MS

    fun maxAttempts(
        isTransientNetwork: Boolean,
        isInternal: Boolean,
    ): Int {
        if (isInternal) return 1
        if (!isTransientNetwork) return DEFAULT_MAX_ATTEMPTS
        return TRANSIENT_MAX_ATTEMPTS
    }

    fun shouldRetry(
        attemptsUsed: Int,
        maxAttempts: Int,
        hasValidatedInternet: Boolean,
    ): Boolean {
        if (!hasValidatedInternet) return false
        return attemptsUsed < maxAttempts
    }

    /** OkHttp surfaces user/dispatcher cancel as IOException("Canceled"), not CancellationException. */
    fun isCanceledCall(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException) {
                val message = current.message.orEmpty()
                if (message.contains("Canceled", ignoreCase = true) ||
                    message.contains("cancelled", ignoreCase = true)
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    fun retryDelayMs(isDns: Boolean, attemptsUsed: Int): Long =
        if (isDns) {
            (2_000L shl (attemptsUsed - 1).coerceAtMost(2)).coerceAtMost(8_000L)
        } else {
            2_500L
        }
}
