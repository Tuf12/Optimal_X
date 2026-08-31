package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

private const val ANTHROPIC_MAX_TOKENS = 32_384

class AnthropicProvider(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json,
) : EidosProvider {

    override suspend fun send(request: EidosRequest): EidosResponse {
        val payload = buildJsonObject {
            put("model", JsonPrimitive("claude-sonnet-4-6"))
            put("max_tokens", JsonPrimitive(ANTHROPIC_MAX_TOKENS))
            // Omitted on tool continuations (blank system): the cached prefix carries it.
            if (request.systemPrompt.isNotBlank()) {
                put(
                    "system",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(request.systemPrompt))
                                put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
                            }
                        )
                    }
                )
            }
            put("messages", buildMessages(request))
            put("tools", buildTools(request))
        }

        val requestBody = json.encodeToString(JsonObject.serializer(), payload)
        val responseText = postJson(
            client = client,
            url = "https://api.anthropic.com/v1/messages",
            bearerToken = null,
            body = requestBody,
            extraHeaders = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "anthropic-beta" to "web-fetch-2025-09-10",
            ),
        )

        val root = json.parseToJsonElement(responseText).jsonObject

        val content = root["content"]?.jsonArray.orEmpty()
        val text = content
            .mapNotNull { item ->
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
            .joinToString("\n")
            .trim()

        val localToolNames = request.toolDefinitions.map { it.name }.toSet()
        val toolCalls = content
            .mapNotNull { item ->
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                if (name !in localToolNames) return@mapNotNull null
                EidosToolCall(
                    id = obj["id"]?.jsonPrimitive?.content.orEmpty(),
                    name = name,
                    argumentsJson = obj["input"]?.toString().orEmpty(),
                )
            }

        val response = EidosResponse(
            textResponse = text,
            toolCalls = toolCalls,
            providerResponseId = root["id"]?.jsonPrimitive?.contentOrNull,
            usage = ProviderUsageParser.fromResponseRoot(root),
        )
        request.emitProviderExchange(requestBody, response)
        return response
    }

    private fun buildTools(request: EidosRequest) = buildJsonArray {
        val totalTools = request.toolDefinitions.size + 2
        add(
            buildJsonObject {
                put("type", JsonPrimitive("web_search_20250305"))
                put("name", JsonPrimitive("web_search"))
                put("max_uses", JsonPrimitive(3))
                maybeCacheTool(index = 0, totalTools = totalTools)
            }
        )
        add(
            buildJsonObject {
                put("type", JsonPrimitive("web_fetch_20250910"))
                put("name", JsonPrimitive("web_fetch"))
                put("max_uses", JsonPrimitive(3))
                put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                put("max_content_tokens", JsonPrimitive(20_000))
                maybeCacheTool(index = 1, totalTools = totalTools)
            }
        )
        request.toolDefinitions.forEachIndexed { localIndex, def ->
            val index = localIndex + 2
            add(
                buildJsonObject {
                    put("name", JsonPrimitive(def.name))
                    put("description", JsonPrimitive(def.description))
                    put("input_schema", def.parametersSchema)
                    maybeCacheTool(index = index, totalTools = totalTools)
                }
            )
        }
    }

    private fun JsonObjectBuilder.maybeCacheTool(index: Int, totalTools: Int) {
        if (index == totalTools - 1) {
            put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
        }
    }

    private fun buildMessages(request: EidosRequest) = buildJsonArray {
        val lastHistoryIndex = request.conversationHistory.lastIndex
        request.conversationHistory.forEachIndexed { index, message ->
            add(message.toAnthropicMessage(cacheBreakpoint = index == lastHistoryIndex))
        }

        if (request.userMessage.isNotBlank() || request.attachedImagePaths.isNotEmpty()) {
            val images = ChatVisionUserContent.encodePaths(request.attachedImagePaths)
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", ChatVisionUserContent.anthropicContent(request.userMessage, images))
                }
            )
        }
    }

    private fun EidosMessage.toAnthropicMessage(cacheBreakpoint: Boolean): JsonObject {
        return when (role) {
            EidosRole.USER -> buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(content))
                                if (cacheBreakpoint) {
                                    put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
                                }
                            }
                        )
                    }
                )
            }

            EidosRole.ASSISTANT -> buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put(
                    "content",
                    buildJsonArray {
                        val hasAssistantText = content.isNotBlank()
                        val totalBlocks = assistantToolCalls.size + if (hasAssistantText) 1 else 0
                        var blockIndex = 0
                        if (content.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(content))
                                    if (cacheBreakpoint && blockIndex == totalBlocks - 1) {
                                        put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
                                    }
                                }
                            )
                            blockIndex += 1
                        }
                        assistantToolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("tool_use"))
                                    put("id", JsonPrimitive(call.id))
                                    put("name", JsonPrimitive(call.name))
                                    put("input", json.parseToJsonElement(call.argumentsJson))
                                    if (cacheBreakpoint && blockIndex == totalBlocks - 1) {
                                        put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
                                    }
                                }
                            )
                            blockIndex += 1
                        }
                    }
                )
            }

            EidosRole.TOOL -> buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(toolCallId.orEmpty()))
                                put("content", JsonPrimitive(content))
                                if (cacheBreakpoint) {
                                    put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
                                }
                            }
                        )
                    }
                )
            }
        }
    }

}
