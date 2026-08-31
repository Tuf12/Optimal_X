package com.example.optimalx.data.eidos.model

import com.example.optimalx.data.eidos.EidosNavigationTarget
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
    /** Full system prompt — used by Kimi, Anthropic, xAI, and OpenAI when no split is set. */
    val systemPrompt: String,
    /**
     * OpenAI GPT-5.6+ explicit cache: stable prefix (identity, rules, static location prose).
     * When non-blank, [volatileSystemSuffix] follows without a cache breakpoint.
     */
    val stableSystemPrefix: String? = null,
    val volatileSystemSuffix: String? = null,
    val conversationHistory: List<EidosMessage>,
    val toolDefinitions: List<EidosToolDefinition>,
    val userMessage: String,
    val previousResponseId: String? = null,
    /**
     * Stable id for provider prompt-cache routing (`prompt_cache_key` on OpenAI / xAI / Kimi).
     * Use conversation id when available; rollover/widget may use their own keys.
     */
    val promptCacheKey: String? = null,
    val phase: EidosRequestPhase = EidosRequestPhase.FULL,
    /**
     * Whether reasoning providers (Kimi) run with thinking enabled. Off for quick-response
     * scopes (widget Ask Eidos) to reduce first-token latency.
     */
    val thinkingEnabled: Boolean = true,
    /** OpenAI / xAI Responses `reasoning.effort` or `reasoning_effort` when non-null. */
    val reasoningEffort: String? = null,
    /** Kimi streaming only — live reasoning/content preview during send. */
    val streamListener: EidosStreamListener? = null,
    /** Absolute paths to on-disk images included with the user turn (local provider vision). */
    val attachedImagePaths: List<String> = emptyList(),
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
    /** Navigation chips for assistant replies (note/folder/workshop links). */
    val navigationTargets: List<EidosNavigationTarget> = emptyList(),
    /** True when the request failed due to network/transport issues (not a real model reply). */
    val transportFailure: Boolean = false,
)

/** Non-blank reasoning suitable for [ChatMessage.assistantReasoningContent] persistence. */
fun EidosResponse.persistableReasoningContent(): String? =
    formatPersistableReasoning(reasoningTrace, assistantReasoningContent)

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
