package com.example.optimalx.data.eidos.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * GPT-5.6+ Responses API prompt caching — see OpenAI prompt caching guide.
 *
 * Uses explicit breakpoints so volatile location/memory/prefetch sits *after* the cached
 * stable prefix (identity, rules, static location prose + top-level tool definitions). Pair with a stable
 * [com.example.optimalx.data.eidos.model.EidosRequest.promptCacheKey] per conversation.
 */
object OpenAiPromptCache {

    const val TTL_30M = "30m"

    fun appendRequestFields(
        builder: JsonObjectBuilder,
        promptCacheKey: String?,
        markStableSystemBreakpoint: Boolean,
    ) {
        if (!promptCacheKey.isNullOrBlank()) {
            builder.put("prompt_cache_key", JsonPrimitive(promptCacheKey))
        }
        if (markStableSystemBreakpoint) {
            builder.put(
                "prompt_cache_options",
                buildJsonObject {
                    put("mode", JsonPrimitive("explicit"))
                    put("ttl", JsonPrimitive(TTL_30M))
                },
            )
        }
    }

    fun developerTextBlock(text: String, markCacheBreakpoint: Boolean): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("input_text"))
        put("text", JsonPrimitive(text))
        if (markCacheBreakpoint) {
            put("prompt_cache_breakpoint", explicitBreakpoint())
        }
    }

    fun developerMessage(text: String, markCacheBreakpoint: Boolean): JsonObject = buildJsonObject {
        put("role", JsonPrimitive("developer"))
        put(
            "content",
            buildJsonArray {
                add(developerTextBlock(text, markCacheBreakpoint))
            },
        )
    }

    private fun explicitBreakpoint(): JsonObject = buildJsonObject {
        put("mode", JsonPrimitive("explicit"))
    }
}
