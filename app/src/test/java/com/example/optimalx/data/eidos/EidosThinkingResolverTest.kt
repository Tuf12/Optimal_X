package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosThinkingResolverTest {

    @Test
    fun kimi_lowDisablesThinking() {
        val resolved = EidosThinkingResolver.resolve(
            scopeAllowsThinking = true,
            userLevel = EidosThinkingLevel.LOW,
            provider = "kimi",
        )
        assertFalse(resolved.thinkingEnabled)
        assertNull(resolved.reasoningEffort)
    }

    @Test
    fun kimi_mediumAndHighEnableThinking() {
        for (level in listOf(EidosThinkingLevel.MEDIUM, EidosThinkingLevel.HIGH)) {
            val resolved = EidosThinkingResolver.resolve(
                scopeAllowsThinking = true,
                userLevel = level,
                provider = "kimi",
            )
            assertTrue(resolved.thinkingEnabled)
            assertNull(resolved.reasoningEffort)
        }
    }

    @Test
    fun openAi_mapsLevelToReasoningEffort() {
        val resolved = EidosThinkingResolver.resolve(
            scopeAllowsThinking = true,
            userLevel = EidosThinkingLevel.HIGH,
            provider = "openai",
        )
        assertTrue(resolved.thinkingEnabled)
        assertEquals("high", resolved.reasoningEffort)
    }

    @Test
    fun scopeLockOverridesUserLevel() {
        val resolved = EidosThinkingResolver.resolve(
            scopeAllowsThinking = false,
            userLevel = EidosThinkingLevel.HIGH,
            provider = "kimi",
        )
        assertFalse(resolved.thinkingEnabled)
        assertNull(resolved.reasoningEffort)
    }

    @Test
    fun anthropic_hasNoThinkingKnobs() {
        val resolved = EidosThinkingResolver.resolve(
            scopeAllowsThinking = true,
            userLevel = EidosThinkingLevel.HIGH,
            provider = "anthropic",
        )
        assertFalse(resolved.thinkingEnabled)
        assertNull(resolved.reasoningEffort)
    }
}
