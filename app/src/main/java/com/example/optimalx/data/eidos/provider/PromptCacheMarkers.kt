package com.example.optimalx.data.eidos.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Anthropic-style `cache_control: { type: "ephemeral" }` markers.
 * Supported by Anthropic Messages API and Kimi K2.x Chat Completions (Moonshot).
 */
object PromptCacheMarkers {

    fun ephemeralCacheControl(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("ephemeral"))
    }

    fun JsonObjectBuilder.putEphemeralCacheControl() {
        put("cache_control", ephemeralCacheControl())
    }

    fun cachedTextContentBlock(text: String, markCache: Boolean): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
        if (markCache) {
            putEphemeralCacheControl()
        }
    }
}
