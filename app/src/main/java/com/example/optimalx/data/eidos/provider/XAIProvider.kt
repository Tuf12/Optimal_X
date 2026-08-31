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

/** Default when no preference is stored; matches SettingsDefaults.XAI_MODEL. */
const val DEFAULT_GROK_MODEL = "grok-4.3"

data class XaiModelChoice(val modelId: String, val label: String)

val XAI_MODEL_CHOICES: List<XaiModelChoice> = listOf(
    XaiModelChoice("grok-4.3", "Grok 4.3"),
    XaiModelChoice("grok-build-0.1", "Grok Build 0.1"),
)

class XAIProvider(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json,
    private val model: String = DEFAULT_GROK_MODEL,
) : EidosProvider {

    override suspend fun send(request: EidosRequest): EidosResponse {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            if (!model.startsWith("grok-build")) {
                request.reasoningEffort?.let { effort ->
                    put("reasoning_effort", JsonPrimitive(effort))
                }
            }
            put("input", buildInput(request))
            put("tools", buildTools(request))
            if (!request.previousResponseId.isNullOrBlank()) {
                put("previous_response_id", JsonPrimitive(request.previousResponseId))
            }
            if (!request.promptCacheKey.isNullOrBlank()) {
                put("prompt_cache_key", JsonPrimitive(request.promptCacheKey))
            }
        }

        val requestBody = json.encodeToString(JsonObject.serializer(), payload)
        val responseText = postJson(
            client = client,
            url = "https://api.x.ai/v1/responses",
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
                    val type = contentObj["type"]?.jsonPrimitive?.contentOrNull
                    if (type == "output_text" || type == "text") {
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
                put("enable_image_understanding", JsonPrimitive(false))
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

    private fun buildInput(request: EidosRequest) = buildJsonArray {
        val incremental = request.phase == EidosRequestPhase.TOOL_CONTINUATION &&
            !request.previousResponseId.isNullOrBlank()

        if (!incremental && request.systemPrompt.isNotBlank()) {
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("input_text"))
                                    put("text", JsonPrimitive(request.systemPrompt))
                                }
                            )
                        }
                    )
                }
            )
        }

        request.conversationHistory.forEach { message ->
            when (message.role) {
                EidosRole.USER -> add(message.toXaiMessage())
                EidosRole.ASSISTANT -> {
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
                    if (message.content.isNotBlank()) {
                        add(message.toXaiMessage())
                    }
                }
                EidosRole.TOOL -> add(message.toXaiMessage())
            }
        }

        if (request.userMessage.isNotBlank() || request.attachedImagePaths.isNotEmpty()) {
            val images = ChatVisionUserContent.encodePaths(request.attachedImagePaths)
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", ChatVisionUserContent.openAiInputContent(request.userMessage, images))
                }
            )
        }
    }

    private fun EidosMessage.toXaiMessage(): JsonObject {
        return when (role) {
            EidosRole.USER -> buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("input_text"))
                                put("text", JsonPrimitive(content))
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
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("output_text"))
                                put("text", JsonPrimitive(content))
                            }
                        )
                    }
                )
            }

            EidosRole.TOOL -> buildJsonObject {
                put("type", JsonPrimitive("function_call_output"))
                put("call_id", JsonPrimitive(toolCallId.orEmpty()))
                put("output", JsonPrimitive(content))
            }
        }
    }

}
