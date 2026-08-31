package com.example.optimalx.data.eidos

import com.example.optimalx.data.litert.LitertLmDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosProviderFamilyTest {

    @Test
    fun responsesProviders_useIncrementalContinuation() {
        listOf("openai", "xai").forEach { provider ->
            val family = providerFamily(provider)
            assertEquals(EidosProviderFamily.RESPONSES_CHAINED, family)
            assertTrue(
                "Expected incremental continuation for $provider",
                providerFamilyUsesIncrementalToolContinuation(family),
            )
        }
    }

    @Test
    fun messagesProviders_doNotUseIncrementalContinuation() {
        listOf("anthropic", "kimi").forEach { provider ->
            val family = providerFamily(provider)
            assertEquals(EidosProviderFamily.MESSAGES_CACHED, family)
            assertFalse(
                "Messages providers append instead of chaining ($provider)",
                providerFamilyUsesIncrementalToolContinuation(family),
            )
        }
    }

    @Test
    fun localProvider_usesLocalConversationFamily() {
        val family = providerFamily(LitertLmDefaults.PROVIDER_ID)
        assertEquals(EidosProviderFamily.LOCAL_CONVERSATION, family)
        assertFalse(providerFamilyUsesIncrementalToolContinuation(family))
    }

    @Test
    fun messagesProviders_omitSystemOnContinuation_responsesDoNot() {
        assertTrue(
            providerFamilyOmitsSystemOnToolContinuation(EidosProviderFamily.MESSAGES_CACHED),
        )
        assertTrue(
            providerFamilyOmitsSystemOnToolContinuation(EidosProviderFamily.LOCAL_CONVERSATION),
        )
        assertFalse(
            providerFamilyOmitsSystemOnToolContinuation(EidosProviderFamily.RESPONSES_CHAINED),
        )
    }
}
