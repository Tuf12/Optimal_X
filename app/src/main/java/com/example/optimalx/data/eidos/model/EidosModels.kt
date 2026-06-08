package com.example.optimalx.data.eidos.model

import com.example.optimalx.data.eidos.ReasoningPersistPolicy

import kotlinx.serialization.json.JsonObject

/** First HTTP call for a user turn vs continuation after local tool execution. */
enum class EidosRequestPhase {
    FULL,
    TOOL_CONTINUATION,
}

/**
 * Standard request shape used by all providers.
 */
data class EidosRequest(
    val systemPrompt: String,
    val conversationHistory: List<EidosMessage>,
    val toolDefinitions: List<EidosToolDefinition>,
    val userMessage: String,
    val previousResponseId: String? = null,
    /**
     * Stable id for provider prompt-cache routing (xAI `prompt_cache_key`).
     * Use conversation id when available; rollover/widget may use their own keys.
     */
    val promptCacheKey: String? = null,
    val phase: EidosRequestPhase = EidosRequestPhase.FULL,
    /** Kimi streaming only — live reasoning/content preview during send. */
    val streamListener: EidosStreamListener? = null,
    /**
     * Developer API trace — invoked once per provider HTTP round with exact outbound JSON
     * (no Authorization header) and parsed response summary.
     */
    val onProviderExchange: (suspend (requestBodyJson: String, response: EidosResponse) -> Unit)? = null,
)

/** Normalized token usage from a provider response `usage` object. */
data class EidosTokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Responses / Chat Completions: cached prefix tokens (often in `*_details`). */
    val cachedInputTokens: Int? = null,
    /** Anthropic: tokens written into cache on this request. */
    val cacheCreationInputTokens: Int? = null,
    /** Anthropic: tokens read from cache on this request. */
    val cacheReadInputTokens: Int? = null,
    val reasoningTokens: Int? = null,
) {
    fun isEmpty(): Boolean =
        inputTokens == null &&
            outputTokens == null &&
            totalTokens == null &&
            cachedInputTokens == null &&
            cacheCreationInputTokens == null &&
            cacheReadInputTokens == null &&
            reasoningTokens == null

    /** Compact Logcat field string for [EidosUsageLogger]. */
    fun toLogFields(): String = buildString {
        inputTokens?.let { append("input=$it ") }
        outputTokens?.let { append("output=$it ") }
        totalTokens?.let { append("total=$it ") }
        cachedInputTokens?.let { append("cached=$it ") }
        cacheReadInputTokens?.let { append("cache_read=$it ") }
        cacheCreationInputTokens?.let { append("cache_create=$it ") }
        reasoningTokens?.let { append("reasoning=$it ") }
    }.trim()
}

/**
 * Standard response shape returned from all providers.
 */
data class EidosResponse(
    val textResponse: String,
    val toolCalls: List<EidosToolCall> = emptyList(),
    val providerResponseId: String? = null,
    val usage: EidosTokenUsage? = null,
    /** Provider thinking/reasoning text when exposed (Kimi, OpenAI/xAI summaries). */
    val assistantReasoningContent: String? = null,
    /** All thinking blocks from this user turn (tool hops + final), for chat aggregation. */
    val reasoningTrace: List<EidosReasoningHop> = emptyList(),
    /** Phase 1 — workshop send stopped at per-chunk tool-hop cap (Auto-Continue prep). */
    val workshopPausedForToolCap: Boolean = false,
    val workshopToolRoundsCompleted: Int = 0,
    /** Phase 1.5 — chunk ended for handoff (tool cap or model-authored section). */
    val workshopPausedForHandoff: Boolean = false,
)

/** Non-blank final-hop reasoning for [ChatMessage.assistantReasoningContent] only. */
fun EidosResponse.persistableReasoningContent(): String? =
    ReasoningPersistPolicy.finalHopForChat(reasoningTrace, assistantReasoningContent)

enum class EidosRole {
    USER,
    ASSISTANT,
    TOOL,
}

data class EidosMessage(
    val role: EidosRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val assistantToolCalls: List<EidosToolCall> = emptyList(),
    /** Kimi K2.x: preserved across tool rounds when thinking is enabled. */
    val assistantReasoningContent: String? = null,
)

data class EidosToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
    val requiresConfirmation: Boolean = false,
    val isModifying: Boolean = false,
)

data class EidosToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

sealed interface ToolExecutionResult {
    data class Success(
        val content: String,
        val modifiedSystem: Boolean,
    ) : ToolExecutionResult

    data class Failure(
        val message: String,
    ) : ToolExecutionResult
}

interface ToolExecutor {
    suspend fun execute(toolName: String, argumentsJson: String): ToolExecutionResult
}

interface ConfirmationHandler {
    suspend fun confirm(toolName: String, argumentsJson: String): Boolean
}
