package com.example.optimalx.data.litert

import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object LitertLmToolArgumentCodec {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun toJsonString(arguments: Map<String, Any?>): String {
        val obj = buildJsonObject(arguments)
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun fromJsonString(argumentsJson: String): Map<String, Any?> {
        if (argumentsJson.isBlank()) return emptyMap()
        val element = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull()
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, value) -> jsonElementToKotlin(value) }
    }

    fun toLitertToolCalls(
        calls: List<com.example.optimalx.data.eidos.model.EidosToolCall>,
    ): List<ToolCall> = calls.map { call ->
        ToolCall(
            name = call.name,
            arguments = fromJsonString(call.argumentsJson),
        )
    }

    private fun buildJsonObject(map: Map<String, Any?>): JsonObject =
        JsonObject(
            map.mapValues { (_, value) -> kotlinToJsonElement(value) },
        )

    private fun kotlinToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value.toDouble())
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        else -> JsonPrimitive(value.toString())
    }

    private fun jsonElementToKotlin(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { (_, v) -> jsonElementToKotlin(v) }
        is kotlinx.serialization.json.JsonArray ->
            element.map { jsonElementToKotlin(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            element.intOrNull != null -> element.intOrNull
            else -> element.content
        }
    }
}
