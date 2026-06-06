package com.example.optimalx.data.eidos.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiStreamParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun accumulator_collectsReasoningThenContent() {
        val accumulator = KimiStreamAccumulator(allowedToolNames = emptySet())
        val chunks = listOf(
            """{"id":"cmpl-1","choices":[{"delta":{"reasoning_content":"Step one"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":" and two"}}]}""",
            """{"choices":[{"delta":{"content":"Hello"}}]}""",
            """{"choices":[{"delta":{"content":" world"},"finish_reason":"stop","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}]}""",
        )
        chunks.forEach { line ->
            parseKimiSseDataLine("data: $line", json) { accumulator.applyChunk(it, listener = null) }
        }

        val response = accumulator.buildResponse()
        assertEquals("cmpl-1", response.providerResponseId)
        assertEquals("Step one and two", response.assistantReasoningContent)
        assertEquals("Hello world", response.textResponse)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(10, response.usage?.inputTokens)
    }

    @Test
    fun accumulator_collectsStreamingToolCalls() {
        val accumulator = KimiStreamAccumulator(allowedToolNames = setOf("web_search"))
        val chunks = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"news\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        chunks.forEach { line ->
            parseKimiSseDataLine("data: $line", json) { accumulator.applyChunk(it, listener = null) }
        }

        val response = accumulator.buildResponse()
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_1", response.toolCalls[0].id)
        assertEquals("web_search", response.toolCalls[0].name)
        assertEquals("{\"query\":\"news\"}", response.toolCalls[0].argumentsJson)
    }

    @Test
    fun parseKimiSseDataLine_ignoresDoneAndComments() {
        var count = 0
        parseKimiSseDataLine("data: [DONE]", json) { count++ }
        parseKimiSseDataLine(": keep-alive", json) { count++ }
        parseKimiSseDataLine("", json) { count++ }
        assertEquals(0, count)
    }
}
