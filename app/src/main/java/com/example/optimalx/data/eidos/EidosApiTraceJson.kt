package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosTokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object EidosApiTraceJson {

    private val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun formatRequest(bodyJson: String): String = runCatching {
        val element = pretty.parseToJsonElement(bodyJson)
        pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    }.getOrDefault(bodyJson)

    fun summarizeResponse(response: EidosResponse): String {
        val payload = buildJsonObject {
            put("textResponse", JsonPrimitive(response.textResponse))
            put(
                "toolCalls",
                buildJsonArray {
                    response.toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(call.id))
                                put("name", JsonPrimitive(call.name))
                                put("argumentsJson", JsonPrimitive(call.argumentsJson))
                            },
                        )
                    }
                },
            )
            put("providerResponseId", JsonPrimitive(response.providerResponseId ?: ""))
            response.assistantReasoningContent?.takeIf { it.isNotBlank() }?.let {
                put("assistantReasoningContent", JsonPrimitive(it))
            }
            response.usage?.let { put("usage", usageObject(it)) }
            if (response.reasoningTrace.isNotEmpty()) {
                put(
                    "reasoningTraceHopCount",
                    JsonPrimitive(response.reasoningTrace.size),
                )
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), payload)
    }

    private fun usageObject(usage: EidosTokenUsage): JsonObject = buildJsonObject {
        usage.inputTokens?.let { put("inputTokens", JsonPrimitive(it)) }
        usage.outputTokens?.let { put("outputTokens", JsonPrimitive(it)) }
        usage.totalTokens?.let { put("totalTokens", JsonPrimitive(it)) }
        usage.cachedInputTokens?.let { put("cachedInputTokens", JsonPrimitive(it)) }
        usage.reasoningTokens?.let { put("reasoningTokens", JsonPrimitive(it)) }
    }
}
