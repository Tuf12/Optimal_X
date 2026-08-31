package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosTokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Normalizes provider-specific `usage` JSON into [EidosTokenUsage].
 *
 * Field names differ by API:
 * - Responses (xAI, OpenAI): `input_tokens`, `output_tokens`, `input_tokens_details.cached_tokens`,
 *   `input_tokens_details.cache_write_tokens` (GPT-5.6+)
 * - Anthropic Messages: `cache_read_input_tokens`, `cache_creation_input_tokens`
 * - Chat Completions (Kimi): `prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens`
 */
object ProviderUsageParser {

    fun fromResponseRoot(root: JsonObject): EidosTokenUsage? {
        val usage = root["usage"]?.jsonObject ?: return null
        return parseUsageObject(usage)
    }

    fun parseUsageObject(usage: JsonObject): EidosTokenUsage {
        val input = firstInt(usage, "input_tokens", "prompt_tokens")
        val output = firstInt(usage, "output_tokens", "completion_tokens")
        val total = firstInt(usage, "total_tokens")
        val inputDetails = usage["input_tokens_details"]?.jsonObject
        val promptDetails = usage["prompt_tokens_details"]?.jsonObject
        val cacheWriteFromDetails = inputDetails?.let { firstInt(it, "cache_write_tokens") }
            ?: promptDetails?.let { firstInt(it, "cache_write_tokens") }
        val cacheCreation = firstInt(usage, "cache_creation_input_tokens") ?: cacheWriteFromDetails
        val cacheRead = firstInt(usage, "cache_read_input_tokens")
        val cachedFromDetails = inputDetails?.let { firstInt(it, "cached_tokens") }
            ?: promptDetails?.let { firstInt(it, "cached_tokens") }
        val cachedTopLevel = firstInt(usage, "cached_tokens")
        val cached = cachedFromDetails ?: cachedTopLevel
        val reasoning = firstInt(usage, "reasoning_tokens")
            ?: usage["output_tokens_details"]?.jsonObject?.let { firstInt(it, "reasoning_tokens") }

        return EidosTokenUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            cachedInputTokens = cached,
            cacheCreationInputTokens = cacheCreation,
            cacheReadInputTokens = cacheRead,
            reasoningTokens = reasoning,
        )
    }

    private fun firstInt(obj: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val value = obj[key]?.jsonPrimitive?.intOrNull
                ?: obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (value != null) return value
        }
        return null
    }
}
