package com.example.optimalx.data.eidos

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosActiveSendRegistryTest {

    @Test
    fun unregisterIfOwned_clearsMatchingJob() {
        val job = Job()
        EidosActiveSendRegistry.register(42L, job)
        assertTrue(EidosActiveSendRegistry.isActive(42L))

        EidosActiveSendRegistry.unregisterIfOwned(42L, job)
        assertFalse(EidosActiveSendRegistry.isActive(42L))

        job.complete()
    }

    @Test
    fun unregisterIfOwned_ignoresStaleJob() {
        val activeJob = Job()
        val staleJob = Job()
        EidosActiveSendRegistry.register(7L, activeJob)
        assertTrue(EidosActiveSendRegistry.isActive(7L))

        EidosActiveSendRegistry.unregisterIfOwned(7L, staleJob)
        assertTrue(EidosActiveSendRegistry.isActive(7L))

        EidosActiveSendRegistry.unregisterIfOwned(7L, activeJob)
        assertFalse(EidosActiveSendRegistry.isActive(7L))

        activeJob.complete()
        staleJob.complete()
    }
}
