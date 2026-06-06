package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosStreamListener
import com.example.optimalx.data.eidos.model.EidosStreamUpdate
import com.example.optimalx.data.eidos.model.EidosToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Accumulates OpenAI-compatible Kimi SSE chunks into a final [EidosResponse].
 * Reasoning deltas arrive before content deltas per Moonshot K2.6 streaming docs.
 */
class KimiStreamAccumulator(
    private val allowedToolNames: Set<String>,
) {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private val toolCallsByIndex = linkedMapOf<Int, MutableToolCall>()

    var providerResponseId: String? = null
        private set
    private var usageRoot: JsonObject? = null

    fun applyChunk(root: JsonObject, listener: EidosStreamListener?) {
        root["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            providerResponseId = it
        }

        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        if (choice != null) {
            val delta = choice["delta"]?.jsonObject
            if (delta != null) {
                delta["reasoning_content"]?.let(::reasoningContentAsString)?.takeIf { it.isNotEmpty() }?.let {
                    reasoning.append(it)
                }
                delta["content"]?.let(::contentAsString)?.takeIf { it.isNotEmpty() }?.let {
                    content.append(it)
                }
                delta["tool_calls"]?.jsonArray?.forEach { item ->
                    applyToolCallDelta(item.jsonObject)
                }
            }
            root["usage"]?.jsonObject?.let { usageRoot = it }
            choice["usage"]?.jsonObject?.let { usageRoot = it }
        } else {
            root["usage"]?.jsonObject?.let { usageRoot = it }
        }

        listener?.onStreamUpdate(
            EidosStreamUpdate(
                reasoningText = reasoning.toString(),
                contentText = content.toString(),
            ),
        )
    }

    fun buildResponse(): EidosResponse {
        val toolCalls = toolCallsByIndex.entries
            .sortedBy { it.key }
            .mapNotNull { (_, call) -> call.toEidosToolCall(allowedToolNames) }

        return EidosResponse(
            textResponse = content.toString().trim(),
            toolCalls = toolCalls,
            providerResponseId = providerResponseId,
            usage = usageRoot?.let(ProviderUsageParser::parseUsageObject),
            assistantReasoningContent = reasoning.toString().trim().takeIf { it.isNotBlank() },
        )
    }

    private fun applyToolCallDelta(obj: JsonObject) {
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
        val call = toolCallsByIndex.getOrPut(index) { MutableToolCall() }
        obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { call.id = it }
        val fn = obj["function"]?.jsonObject ?: return
        fn["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { call.name = it }
        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { call.arguments.append(it) }
    }

    private class MutableToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun toEidosToolCall(allowedToolNames: Set<String>): EidosToolCall? {
            if (id.isBlank() || name.isBlank() || name !in allowedToolNames) return null
            return EidosToolCall(
                id = id,
                name = name,
                argumentsJson = arguments.toString(),
            )
        }
    }
}

internal fun reasoningContentAsString(element: kotlinx.serialization.json.JsonElement): String? {
    return when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.contentOrNull else element.content
        else -> element.toString()
    }
}

private fun contentAsString(element: kotlinx.serialization.json.JsonElement): String? {
    return when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.contentOrNull else element.content
        else -> element.toString()
    }
}

/** Parses `data: {...}` SSE lines from Kimi chat completion streams. */
fun parseKimiSseDataLine(line: String, json: Json, onChunk: (JsonObject) -> Unit) {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(":")) return
    if (!trimmed.startsWith("data:")) return
    val payload = trimmed.removePrefix("data:").trim()
    if (payload == "[DONE]" || payload.isEmpty()) return
    val root = json.parseToJsonElement(payload).jsonObject
    onChunk(root)
}
