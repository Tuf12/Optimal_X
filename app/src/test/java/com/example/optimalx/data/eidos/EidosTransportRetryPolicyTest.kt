package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prompt.EidosEntrySurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class EidosTransportRetryPolicyTest {

    @Test
    fun internalAndBackgroundFailFast() {
        assertEquals(0L, EidosTransportRetryPolicy.initialAwaitMs(EidosEntrySurface.INTERNAL))
        assertEquals(0L, EidosTransportRetryPolicy.initialAwaitMs(EidosEntrySurface.BACKGROUND_WORKER))
        assertTrue(EidosTransportRetryPolicy.isFailFastSurface(EidosEntrySurface.INTERNAL))
        assertFalse(EidosTransportRetryPolicy.isFailFastSurface(EidosEntrySurface.APP_CHAT))
    }

    @Test
    fun interactiveWaitsForHandoff() {
        assertEquals(
            EidosTransportRetryPolicy.INTERACTIVE_HANDOFF_WAIT_MS,
            EidosTransportRetryPolicy.initialAwaitMs(EidosEntrySurface.APP_CHAT),
        )
    }

    @Test
    fun doesNotRetryWhenOffline() {
        assertFalse(
            EidosTransportRetryPolicy.shouldRetry(
                attemptsUsed = 1,
                maxAttempts = 2,
                hasValidatedInternet = false,
            ),
        )
    }

    @Test
    fun retriesOnceWhileOnline() {
        assertTrue(
            EidosTransportRetryPolicy.shouldRetry(
                attemptsUsed = 1,
                maxAttempts = 2,
                hasValidatedInternet = true,
            ),
        )
        assertFalse(
            EidosTransportRetryPolicy.shouldRetry(
                attemptsUsed = 2,
                maxAttempts = 2,
                hasValidatedInternet = true,
            ),
        )
    }

    @Test
    fun internalNeverRetriesTransientErrors() {
        assertEquals(
            1,
            EidosTransportRetryPolicy.maxAttempts(isTransientNetwork = true, isInternal = true),
        )
    }

    @Test
    fun interactiveCapsTransientRetriesAtTwo() {
        assertEquals(
            2,
            EidosTransportRetryPolicy.maxAttempts(isTransientNetwork = true, isInternal = false),
        )
    }

    @Test
    fun canceledOkHttpCallIsNotARetryableBlip() {
        assertTrue(EidosTransportRetryPolicy.isCanceledCall(IOException("Canceled")))
        assertTrue(
            EidosTransportRetryPolicy.isCanceledCall(
                IOException("request failed", IOException("Canceled")),
            ),
        )
        assertFalse(EidosTransportRetryPolicy.isCanceledCall(UnknownHostException("api.x.ai")))
    }
}
