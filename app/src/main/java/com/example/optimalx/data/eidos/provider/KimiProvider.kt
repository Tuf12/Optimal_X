package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.EidosHistoryTrimmer
import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosStreamUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

const val DEFAULT_KIMI_MODEL = "kimi-k2.6"

private const val KIMI_MAX_TOKENS = 32_384

/**
 * Moonshot Kimi via OpenAI-compatible Chat Completions.
 * Uses Anthropic-style [cache_control] markers and [prompt_cache_key] for context caching.
 *
 * Thinking output (`reasoning_content`) is persisted for chat UI; outbound API replay uses
 * [EidosHistoryTrimmer.prepareKimiOutboundHistory] — full reasoning only during in-flight tool loops.
 *
 * @see <a href="https://platform.kimi.ai/docs/api/chat">Kimi Chat API</a>
 */
class KimiProvider(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json,
    private val model: String = DEFAULT_KIMI_MODEL,
    /**
     * Injected by [EidosApiClient] for the main chat loop (schemas already loaded there).
     * Null for lightweight callers (e.g. panel `eidosInfer`) that attach no Formula tools.
     */
    private val formulaToolService: KimiFormulaToolService? = null,
) : EidosProvider {

    override suspend fun send(request: EidosRequest): EidosResponse {
        val allowedToolNames = buildAllowedToolNames(request)
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(KIMI_MAX_TOKENS))
            put("stream", JsonPrimitive(true))
            put(
                "stream_options",
                buildJsonObject {
                    put("include_usage", JsonPrimitive(true))
                },
            )
            put("messages", buildMessages(request))
            val tools = buildTools(request)
            if (tools.isNotEmpty()) {
                put("tools", tools)
            }
            put(
                "thinking",
                buildThinkingBlock(
                    thinkingEnabled = request.thinkingEnabled,
                    phase = request.phase,
                    conversationHistory = request.conversationHistory,
                ),
            )
            if (!request.promptCacheKey.isNullOrBlank()) {
                put("prompt_cache_key", JsonPrimitive(request.promptCacheKey))
            }
        }

        val requestBody = json.encodeToString(JsonObject.serializer(), payload)
        val accumulator = KimiStreamAccumulator(allowedToolNames)
        request.streamListener?.onStreamUpdate(EidosStreamUpdate())
        postJsonStream(
            client = client,
            url = "https://api.moonshot.ai/v1/chat/completions",
            bearerToken = apiKey,
            body = requestBody,
        ) { chunk ->
            accumulator.applyChunk(chunk, request.streamListener)
        }

        val response = accumulator.buildResponse()
        request.emitProviderExchange(requestBody, response)
        return response
    }

    private fun buildAllowedToolNames(request: EidosRequest): Set<String> {
        val names = request.toolDefinitions.map { it.name }.toMutableSet()
        formulaToolService?.formulaToolSchemas().orEmpty().forEach { tool ->
            tool["function"]?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { names += it }
        }
        return names
    }

    private fun buildTools(request: EidosRequest): kotlinx.serialization.json.JsonArray {
        val formulaTools = formulaToolService?.formulaToolSchemas().orEmpty()
        val localTools = request.toolDefinitions.map { def ->
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put(
                    "function",
                    buildJsonObject {
                        put("name", JsonPrimitive(def.name))
                        put("description", JsonPrimitive(def.description))
                        put("parameters", def.parametersSchema)
                    },
                )
            }
        }
        val allTools = formulaTools + localTools
        if (allTools.isEmpty()) return buildJsonArray { }
        return buildJsonArray {
            allTools.forEachIndexed { index, tool ->
                add(if (index == allTools.lastIndex) withEphemeralCacheControl(tool) else tool)
            }
        }
    }

    private fun withEphemeralCacheControl(tool: JsonObject): JsonObject = buildJsonObject {
        tool.forEach { (key, value) -> put(key, value) }
        put("cache_control", PromptCacheMarkers.ephemeralCacheControl())
    }

    private fun buildMessages(request: EidosRequest) = buildJsonArray {
        // Omitted on tool continuations (blank system): the cached prefix carries it.
        if (request.systemPrompt.isNotBlank()) {
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put(
                        "content",
                        buildJsonArray {
                            add(PromptCacheMarkers.cachedTextContentBlock(request.systemPrompt, markCache = true))
                        },
                    )
                },
            )
        }

        val outboundHistory = EidosHistoryTrimmer.prepareKimiOutboundHistory(
            request.conversationHistory,
            request.phase,
        )
        val lastHistoryIndex = outboundHistory.lastIndex
        outboundHistory.forEachIndexed { index, message ->
            add(message.toKimiMessage(cacheBreakpoint = index == lastHistoryIndex))
        }

        if (request.userMessage.isNotBlank() || request.attachedImagePaths.isNotEmpty()) {
            val images = ChatVisionUserContent.encodePaths(request.attachedImagePaths)
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put(
                        "content",
                        ChatVisionUserContent.chatCompletionsContent(request.userMessage, images),
                    )
                },
            )
        }
    }

    private fun EidosMessage.toKimiMessage(cacheBreakpoint: Boolean): JsonObject {
        return when (role) {
            EidosRole.USER -> buildJsonObject {
                put("role", JsonPrimitive("user"))
                putUserContent(content, cacheBreakpoint)
            }

            EidosRole.ASSISTANT -> buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                if (assistantToolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            assistantToolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(call.id))
                                        put("type", JsonPrimitive("function"))
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", JsonPrimitive(call.name))
                                                put("arguments", JsonPrimitive(call.argumentsJson))
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
                assistantReasoningContent?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    put("reasoning_content", JsonPrimitive(reasoning))
                }
                when {
                    content.isNotBlank() -> put(
                        "content",
                        buildJsonArray {
                            add(PromptCacheMarkers.cachedTextContentBlock(content, markCache = cacheBreakpoint))
                        },
                    )
                    assistantToolCalls.isNotEmpty() -> put("content", JsonNull)
                }
            }

            EidosRole.TOOL -> buildJsonObject {
                put("role", JsonPrimitive("tool"))
                put("tool_call_id", JsonPrimitive(toolCallId.orEmpty()))
                toolName?.takeIf { it.isNotBlank() }?.let { name ->
                    put("name", JsonPrimitive(name))
                }
                put("content", JsonPrimitive(content))
            }
        }
    }

    private fun buildThinkingBlock(
        thinkingEnabled: Boolean,
        phase: EidosRequestPhase,
        conversationHistory: List<EidosMessage>,
    ): JsonObject = buildJsonObject {
        if (!thinkingEnabled) {
            put("type", JsonPrimitive("disabled"))
            return@buildJsonObject
        }
        put("type", JsonPrimitive("enabled"))
        val preserveThinking = phase == EidosRequestPhase.TOOL_CONTINUATION ||
            EidosHistoryTrimmer.kimiNeedsPreservedThinking(conversationHistory)
        if (preserveThinking) {
            put("keep", JsonPrimitive("all"))
        }
    }

    private fun JsonObjectBuilder.putUserContent(text: String, cacheBreakpoint: Boolean) {
        if (cacheBreakpoint) {
            put(
                "content",
                buildJsonArray {
                    add(PromptCacheMarkers.cachedTextContentBlock(text, markCache = true))
                },
            )
        } else {
            put("content", JsonPrimitive(text))
        }
    }
}
