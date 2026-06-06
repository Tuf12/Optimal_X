package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopBuildKickoffTest {

    @Test
    fun fromStored_parsesKnownValues() {
        assertEquals(WorkshopBuildKickoff.DESIGN, WorkshopBuildKickoff.fromStored("DESIGN"))
        assertEquals(WorkshopBuildKickoff.PLAN, WorkshopBuildKickoff.fromStored("plan"))
    }

    @Test
    fun fromStored_nullForBlank() {
        assertNull(WorkshopBuildKickoff.fromStored(null))
        assertNull(WorkshopBuildKickoff.fromStored(""))
    }
}
