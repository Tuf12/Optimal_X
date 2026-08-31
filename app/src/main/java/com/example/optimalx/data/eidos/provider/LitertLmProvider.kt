package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosResponse
import com.example.optimalx.data.eidos.model.EidosStreamUpdate
import com.example.optimalx.data.litert.LitertLmBackend
import com.example.optimalx.data.litert.LitertLmEngineHolder
import com.example.optimalx.data.litert.LitertLmHistoryMapper
import com.example.optimalx.data.litert.LitertLmMultimodal
import com.example.optimalx.data.litert.LitertLmMessageText
import com.example.optimalx.data.litert.LitertLmOpenApiTool
import com.example.optimalx.data.litert.LitertLmToolRegistry
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import java.io.File

class LitertLmProvider(
    private val engineHolder: LitertLmEngineHolder,
    private val modelPath: String,
    private val backend: LitertLmBackend,
) : EidosProvider {

    override suspend fun send(request: EidosRequest): EidosResponse {
        engineHolder.beginUse()
        try {
            return sendInternal(request)
        } finally {
            engineHolder.endUse()
        }
    }

    private suspend fun sendInternal(request: EidosRequest): EidosResponse {
        val userText = request.userMessage.trim()
        val imagePaths = request.attachedImagePaths.filter { path -> File(path).isFile }
        val prepareResult = engineHolder.prepare(
            modelPath,
            backend,
            includeVision = imagePaths.isNotEmpty(),
        )
        if (prepareResult.isFailure) {
            val error = prepareResult.exceptionOrNull()
            val detail = error?.message ?: engineHolder.lastError.value ?: "unknown error"
            return EidosResponse(
                textResponse =
                    "Local Gemma model could not be loaded: $detail. " +
                        "Open Settings → Local Gemma to download, verify path, or try CPU backend.",
            )
        }
        val engine = engineHolder.getEngine()
            ?: return EidosResponse(textResponse = "Local Gemma engine is not available after load.")

        val systemInstruction = when {
            request.phase == EidosRequestPhase.TOOL_CONTINUATION -> null
            request.systemPrompt.isBlank() -> null
            else -> Contents.of(request.systemPrompt)
        }
        val initialMessages = LitertLmHistoryMapper.toInitialMessages(request.conversationHistory)
        val litertTools = LitertLmToolRegistry.toolProviders(request.toolDefinitions)
        val conversationConfig = if (systemInstruction != null) {
            ConversationConfig(
                systemInstruction = systemInstruction,
                initialMessages = initialMessages,
                tools = litertTools,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                automaticToolCalling = false,
            )
        } else {
            ConversationConfig(
                initialMessages = initialMessages,
                tools = litertTools,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                automaticToolCalling = false,
            )
        }
        val historyChars = request.conversationHistory.sumOf { it.content.length }
        android.util.Log.i(
            "OptimalX.LitertLm",
            "send phase=${request.phase} history=${request.conversationHistory.size} " +
                "historyChars=$historyChars systemChars=${request.systemPrompt.length} " +
                "tools=${request.toolDefinitions.size} images=${imagePaths.size}",
        )
        if (userText.isEmpty() && imagePaths.isEmpty() && request.phase == EidosRequestPhase.FULL) {
            return EidosResponse(textResponse = "No user message to send to the local model.")
        }

        request.streamListener?.onStreamUpdate(EidosStreamUpdate())

        return engine.createConversation(conversationConfig).use { conversation ->
            sendWithStreaming(conversation, userText, imagePaths, request)
        }
    }

    private suspend fun sendWithStreaming(
        conversation: Conversation,
        userText: String,
        imagePaths: List<String>,
        request: EidosRequest,
    ): EidosResponse {
        // LiteRT streaming emits deltas via Message.toString(); Gallery appends them.
        val accumulatedRaw = StringBuilder()
        var contentText = ""
        var finalMessage: Message? = null
        val outboundFlow: Flow<Message> = when {
            imagePaths.isNotEmpty() -> {
                @OptIn(ExperimentalApi::class)
                conversation.sendMessageAsync(
                    LitertLmMultimodal.userMessageContents(userText, imagePaths),
                )
            }
            else -> {
                @OptIn(ExperimentalApi::class)
                conversation.sendMessageAsync(userText)
            }
        }
        outboundFlow
            .catch { throw it }
            .collect { message ->
                finalMessage = message
                val delta = LitertLmMessageText.streamDelta(message)
                if (delta.isEmpty()) return@collect
                accumulatedRaw.append(delta)
                val rendered = LitertLmMessageText.displayTextFromRaw(accumulatedRaw.toString())
                if (rendered.isNotBlank()) {
                    contentText = rendered
                    request.streamListener?.onStreamUpdate(
                        EidosStreamUpdate(contentText = contentText),
                    )
                }
            }

        val toolCalls = LitertLmHistoryMapper.toEidosToolCalls(
            finalMessage?.toolCalls.orEmpty(),
        )
        var finalText = LitertLmMessageText.displayTextFromRaw(accumulatedRaw.toString()).trim()
        if (finalText.isEmpty()) {
            finalText = contentText.trim()
        }
        if (finalText.isEmpty() && finalMessage != null) {
            finalText = LitertLmMessageText.finalDisplayText(finalMessage!!).trim()
        }
        if (finalText.isEmpty() && toolCalls.isEmpty()) {
            return EidosResponse(textResponse = "Local model returned an empty response.")
        }
        if (toolCalls.isEmpty() && LitertLmMessageText.isDegenerateAssistantText(finalText)) {
            android.util.Log.w(
                "OptimalX.LitertLm",
                "Discarding degenerate local reply (${finalText.length} chars, history=${request.conversationHistory.size})",
            )
            return EidosResponse(
                textResponse =
                    "The local model produced a garbled reply. Send again, or switch to a cloud provider.",
            )
        }

        val response = EidosResponse(
            textResponse = finalText,
            toolCalls = toolCalls,
        )
        request.emitProviderExchange(
            requestBodyJson = buildTraceJson(userText, imagePaths, request),
            response = response,
        )
        return response
    }

    private fun buildTraceJson(
        userText: String,
        imagePaths: List<String>,
        request: EidosRequest,
    ): String {
        val toolNames = LitertLmOpenApiTool.traceToolNames(request.toolDefinitions)
        val imageCount = imagePaths.size
        return """{"provider":"local","userMessage":"${escapeJson(userText.take(200))}","imageCount":$imageCount,"historyCount":${request.conversationHistory.size},"toolDefinitions":$toolNames}"""
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
