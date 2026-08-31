package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosNetworkMonitorTest {

    @Test
    fun capabilitiesHaveValidatedInternet_falseWhenNull() {
        assertFalse(EidosNetworkMonitor.capabilitiesHaveValidatedInternet(null))
    }

    @Test
    fun noNetworkGraceIsMuchShorterThanHandoffWait() {
        assertTrue(EidosNetworkMonitor.NO_NETWORK_GRACE_MS < EidosNetworkMonitor.DEFAULT_AWAIT_MS)
        assertTrue(EidosNetworkMonitor.NO_NETWORK_GRACE_MS <= 2_000L)
    }
}
