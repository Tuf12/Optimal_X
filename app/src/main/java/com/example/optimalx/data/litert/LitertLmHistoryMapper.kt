package com.example.optimalx.data.litert

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosToolCall
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall

object LitertLmHistoryMapper {

    fun toInitialMessages(history: List<EidosMessage>): List<Message> =
        history.mapNotNull { message -> toLitertMessage(message) }

    private fun toLitertMessage(message: EidosMessage): Message? {
        val text = LitertLmGemmaChatMarkup.stripForHistory(message.content.trim())
        return when (message.role) {
            EidosRole.USER -> if (text.isEmpty()) null else Message.user(text)
            EidosRole.ASSISTANT -> {
                if (text.isEmpty() && message.assistantToolCalls.isEmpty()) return null
                val litertToolCalls = LitertLmToolArgumentCodec.toLitertToolCalls(
                    message.assistantToolCalls,
                )
                Message.model(
                    contents = Contents.of(text.ifEmpty { "" }),
                    toolCalls = litertToolCalls,
                )
            }
            EidosRole.TOOL -> {
                val toolName = message.toolName?.trim().orEmpty()
                if (toolName.isEmpty() || text.isEmpty()) return null
                Message.tool(
                    Contents.of(
                        Content.ToolResponse(toolName, text),
                    ),
                )
            }
        }
    }

    fun toEidosToolCalls(toolCalls: List<ToolCall>): List<EidosToolCall> =
        toolCalls.mapIndexed { index, call ->
            EidosToolCall(
                id = "litert-${call.name}-$index",
                name = call.name,
                argumentsJson = LitertLmToolArgumentCodec.toJsonString(call.arguments),
            )
        }
}
