package com.example.optimalx.data.eidos.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiPromptCacheTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun appendRequestFields_addsKeyOptionsAndExplicitBreakpointBlock() {
        val payload = buildJsonObject {
            OpenAiPromptCache.appendRequestFields(
                builder = this,
                promptCacheKey = "optimalx-conv-42",
                markStableSystemBreakpoint = true,
            )
        }

        assertEquals("optimalx-conv-42", payload["prompt_cache_key"]?.jsonPrimitive?.content)
        val options = payload["prompt_cache_options"]!!.jsonObject
        assertEquals("explicit", options["mode"]!!.jsonPrimitive.content)
        assertEquals("30m", options["ttl"]!!.jsonPrimitive.content)

        val block = OpenAiPromptCache.developerTextBlock("Stable rules", markCacheBreakpoint = true)
        assertEquals("input_text", block["type"]!!.jsonPrimitive.content)
        assertEquals("Stable rules", block["text"]!!.jsonPrimitive.content)
        assertEquals(
            "explicit",
            block["prompt_cache_breakpoint"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun developerMessage_usesContentArray() {
        val message = OpenAiPromptCache.developerMessage("Stable rules", markCacheBreakpoint = true)
        assertEquals("developer", message["role"]!!.jsonPrimitive.content)
        val content = message["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("Stable rules", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun appendRequestFields_omitsOptionsWhenNoStableBreakpoint() {
        val payload = buildJsonObject {
            OpenAiPromptCache.appendRequestFields(
                builder = this,
                promptCacheKey = "optimalx-conv-7",
                markStableSystemBreakpoint = false,
            )
        }

        assertEquals("optimalx-conv-7", payload["prompt_cache_key"]?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("prompt_cache_options"))
    }

    @Test
    fun appendRequestFields_skipsBlankCacheKey() {
        val payload = buildJsonObject {
            OpenAiPromptCache.appendRequestFields(
                builder = this,
                promptCacheKey = "  ",
                markStableSystemBreakpoint = true,
            )
        }

        assertFalse(payload.containsKey("prompt_cache_key"))
        assertTrue(payload.containsKey("prompt_cache_options"))
    }
}
