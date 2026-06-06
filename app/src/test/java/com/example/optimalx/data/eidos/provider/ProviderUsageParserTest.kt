package com.example.optimalx.data.eidos.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderUsageParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun responses_api_parses_cached_tokens_from_details() {
        val root = json.parseToJsonElement(
            """
            {
              "id": "resp_1",
              "usage": {
                "input_tokens": 1200,
                "output_tokens": 80,
                "total_tokens": 1280,
                "input_tokens_details": { "cached_tokens": 950 }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val usage = ProviderUsageParser.fromResponseRoot(root)
        requireNotNull(usage)
        assertEquals(1200, usage.inputTokens)
        assertEquals(80, usage.outputTokens)
        assertEquals(1280, usage.totalTokens)
        assertEquals(950, usage.cachedInputTokens)
    }

    @Test
    fun anthropic_parses_cache_read_and_creation() {
        val root = json.parseToJsonElement(
            """
            {
              "id": "msg_1",
              "usage": {
                "input_tokens": 500,
                "output_tokens": 120,
                "cache_creation_input_tokens": 0,
                "cache_read_input_tokens": 480
              }
            }
            """.trimIndent(),
        ).jsonObject

        val usage = ProviderUsageParser.fromResponseRoot(root)
        requireNotNull(usage)
        assertEquals(500, usage.inputTokens)
        assertEquals(120, usage.outputTokens)
        assertEquals(0, usage.cacheCreationInputTokens)
        assertEquals(480, usage.cacheReadInputTokens)
    }

    @Test
    fun chat_completions_parses_prompt_tokens_and_cached() {
        val root = json.parseToJsonElement(
            """
            {
              "id": "chatcmpl-1",
              "usage": {
                "prompt_tokens": 2000,
                "completion_tokens": 150,
                "total_tokens": 2150,
                "prompt_tokens_details": { "cached_tokens": 1800 }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val usage = ProviderUsageParser.fromResponseRoot(root)
        requireNotNull(usage)
        assertEquals(2000, usage.inputTokens)
        assertEquals(150, usage.outputTokens)
        assertEquals(2150, usage.totalTokens)
        assertEquals(1800, usage.cachedInputTokens)
    }

    @Test
    fun missing_usage_returns_null() {
        val root = json.parseToJsonElement("""{ "id": "resp_x" }""").jsonObject
        assertNull(ProviderUsageParser.fromResponseRoot(root))
    }
}
