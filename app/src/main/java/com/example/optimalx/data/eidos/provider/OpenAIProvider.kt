package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class OpenAIProvider(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json,
) : EidosProvider {

    override suspend fun send(request: EidosRequest): EidosResponse {
        val markStableSystemBreakpoint = shouldMarkStableSystemBreakpoint(request)
        val payload = buildJsonObject {
            put("model", JsonPrimitive("gpt-5.6-luna"))
            request.reasoningEffort?.let { effort ->
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", JsonPrimitive(effort))
                        put("summary", JsonPrimitive("auto"))
                    },
                )
            }
            put("text", buildJsonObject { put("verbosity", JsonPrimitive("low")) })
            put("tools", buildTools(request))
            if (!request.previousResponseId.isNullOrBlank()) {
                put("previous_response_id", JsonPrimitive(request.previousResponseId))
            }
            OpenAiPromptCache.appendRequestFields(
                builder = this,
                promptCacheKey = request.promptCacheKey,
                markStableSystemBreakpoint = markStableSystemBreakpoint,
            )
            put("input", buildInput(request, markStableSystemBreakpoint))
        }

        val requestBody = json.encodeToString(JsonObject.serializer(), payload)
        val responseText = postJson(
            client = client,
            url = "https://api.openai.com/v1/responses",
            bearerToken = apiKey,
            body = requestBody,
        )

        val root = json.parseToJsonElement(responseText).jsonObject
        val output = root["output"]?.jsonArray

        val localToolNames = request.toolDefinitions.map { it.name }.toSet()
        val toolCalls = output
            ?.mapNotNull { item ->
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "function_call") return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                if (name !in localToolNames) return@mapNotNull null
                EidosToolCall(
                    id = obj["call_id"]?.jsonPrimitive?.content.orEmpty(),
                    name = name,
                    argumentsJson = obj["arguments"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            .orEmpty()

        val reasoningText = ResponsesReasoningParser.extractReasoningText(output)

        val textParts = output
            ?.flatMap { item ->
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") return@flatMap emptyList()
                obj["content"]?.jsonArray?.mapNotNull { contentItem ->
                    val contentObj = contentItem.jsonObject
                    if (contentObj["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                        contentObj["text"]?.jsonPrimitive?.contentOrNull
                    } else {
                        null
                    }
                }.orEmpty()
            }
            .orEmpty()

        val response = EidosResponse(
            textResponse = textParts.joinToString("\n").trim(),
            toolCalls = toolCalls,
            providerResponseId = root["id"]?.jsonPrimitive?.contentOrNull,
            usage = ProviderUsageParser.fromResponseRoot(root),
            assistantReasoningContent = reasoningText.takeIf { it.isNotBlank() },
        )
        request.emitProviderExchange(requestBody, response)
        return response
    }

    private fun buildTools(request: EidosRequest) = buildJsonArray {
        add(
            buildJsonObject {
                put("type", JsonPrimitive("web_search"))
                put("search_context_size", JsonPrimitive("low"))
            }
        )
        request.toolDefinitions.forEach { def ->
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("name", JsonPrimitive(def.name))
                    put("description", JsonPrimitive(def.description))
                    put("parameters", def.parametersSchema)
                }
            )
        }
    }

    private fun shouldMarkStableSystemBreakpoint(request: EidosRequest): Boolean {
        val incremental = request.phase == EidosRequestPhase.TOOL_CONTINUATION &&
            !request.previousResponseId.isNullOrBlank()
        if (incremental) return false
        val stable = request.stableSystemPrefix?.trim().orEmpty()
        if (stable.isNotBlank()) return true
        return request.systemPrompt.isNotBlank()
    }

    private fun buildInput(
        request: EidosRequest,
        markStableSystemBreakpoint: Boolean,
    ) = buildJsonArray {
        val incremental = request.phase == EidosRequestPhase.TOOL_CONTINUATION &&
            !request.previousResponseId.isNullOrBlank()

        if (!incremental) {
            appendSystemInstructions(request, markStableSystemBreakpoint)
        }

        request.conversationHistory.forEach { message ->
            when (message.role) {
                EidosRole.USER -> addUserMessage(message.content)
                EidosRole.ASSISTANT -> {
                    if (message.assistantToolCalls.isNotEmpty()) {
                        message.assistantToolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function_call"))
                                    put("call_id", JsonPrimitive(call.id))
                                    put("name", JsonPrimitive(call.name))
                                    put("arguments", JsonPrimitive(call.argumentsJson))
                                }
                            )
                        }
                    }
                    if (message.content.isNotBlank()) {
                        addAssistantMessage(message.content)
                    }
                }
                EidosRole.TOOL -> {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("function_call_output"))
                            put("call_id", JsonPrimitive(message.toolCallId.orEmpty()))
                            put("output", JsonPrimitive(message.content))
                        }
                    )
                }
            }
        }

        if (request.userMessage.isNotBlank() || request.attachedImagePaths.isNotEmpty()) {
            addUserMessage(request.userMessage, request.attachedImagePaths)
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.appendSystemInstructions(
        request: EidosRequest,
        markStableSystemBreakpoint: Boolean,
    ) {
        val stable = request.stableSystemPrefix?.trim().orEmpty()
        val volatile = request.volatileSystemSuffix?.trim().orEmpty()
        if (stable.isNotBlank()) {
            add(
                OpenAiPromptCache.developerMessage(
                    text = stable,
                    markCacheBreakpoint = markStableSystemBreakpoint,
                ),
            )
            if (volatile.isNotBlank()) {
                add(OpenAiPromptCache.developerMessage(text = volatile, markCacheBreakpoint = false))
            }
            return
        }

        if (request.systemPrompt.isBlank()) return
        add(
            buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", buildJsonArray {
                    add(
                        if (markStableSystemBreakpoint) {
                            OpenAiPromptCache.developerTextBlock(
                                text = request.systemPrompt,
                                markCacheBreakpoint = true,
                            )
                        } else {
                            buildJsonObject {
                                put("type", JsonPrimitive("input_text"))
                                put("text", JsonPrimitive(request.systemPrompt))
                            }
                        },
                    )
                })
            },
        )
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addUserMessage(
        content: String,
        imagePaths: List<String> = emptyList(),
    ) {
        val images = ChatVisionUserContent.encodePaths(imagePaths)
        add(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", ChatVisionUserContent.openAiInputContent(content, images))
            }
        )
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addAssistantMessage(content: String) {
        add(
            buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("content", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("output_text"))
                            put("text", JsonPrimitive(content))
                        }
                    )
                })
            }
        )
    }

}
