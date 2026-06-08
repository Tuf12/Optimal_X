package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosContextLimitsTest {

    @Test
    fun effectiveMemoryDepth_usesConversationWhenSet() {
        assertEquals(
            EidosContextLimits.MEMORY_HIGH,
            EidosContextLimits.effectiveMemoryDepth(EidosContextLimits.MEMORY_HIGH, EidosContextLimits.MEMORY_LOW),
        )
    }

    @Test
    fun effectiveMemoryDepth_fallsBackToSettings() {
        assertEquals(
            EidosContextLimits.MEMORY_MEDIUM,
            EidosContextLimits.effectiveMemoryDepth(null, EidosContextLimits.MEMORY_MEDIUM),
        )
    }

    @Test
    fun workshopToolFirstContextRules_areWorkshopScoped() {
        val workshop = EidosContextLimits.WORKSHOP_TOOL_FIRST_CONTEXT_RULES
        val general = EidosContextLimits.TOOL_FIRST_CONTEXT_RULES
        assertTrue(workshop.contains("Panel Workshop context policy"))
        assertTrue(workshop.contains("workshop_read_file"))
        assertFalse(workshop.contains("read_note"))
        assertTrue(general.contains("read_note"))
    }

    @Test
    fun nextConversationMemoryDepth_cycles() {
        assertEquals(EidosContextLimits.MEMORY_LOW, EidosContextLimits.nextConversationMemoryDepth(null))
        assertEquals(
            EidosContextLimits.MEMORY_MEDIUM,
            EidosContextLimits.nextConversationMemoryDepth(EidosContextLimits.MEMORY_LOW),
        )
        assertEquals(
            EidosContextLimits.MEMORY_HIGH,
            EidosContextLimits.nextConversationMemoryDepth(EidosContextLimits.MEMORY_MEDIUM),
        )
        assertNull(EidosContextLimits.nextConversationMemoryDepth(EidosContextLimits.MEMORY_HIGH))
    }
}
