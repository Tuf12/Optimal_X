package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.prefetch.EidosPrefetchMetrics

/**
 * Circuit breaker for the tool-continuation loop. [maxToolRounds] is the maximum number of
 * tool-execution rounds allowed within a single `send()` before the loop pauses with an honest
 * "not finished" message. This is a safety backstop, not a token budget — correct transport
 * (see [TransportHints]) keeps each round cheap.
 */
data class ToolLoopPolicy(val maxToolRounds: Int)

/**
 * Per-profile transport policy for multi-hop tool continuations.
 *
 * - [incrementalContinuation]: RESPONSES_CHAINED providers (xAI/OpenAI) send `previous_response_id`
 *   + empty system + last tool round only on hop 2+. Never disable for workshop — that was the bug.
 * - [omitSystemOnContinuation]: when true, MESSAGES_CACHED providers omit the system block on hop 2+.
 *   Default false — omitting breaks prefix cache and drops manifest/subfolderId from model context.
 * - [toolResultReplayRounds]: how many recent tool rounds keep verbatim bodies in outbound replay;
 *   older bulky read results are stubbed.
 */
data class TransportHints(
    val incrementalContinuation: Boolean = true,
    val omitSystemOnContinuation: Boolean = false,
    val toolResultReplayRounds: Int = 3,
)

data class EidosScopeProfile(
    val id: String,
    val ontologyBlock: String,
    val locationBlock: String = "",
    val toolNames: Set<String>,
    val contextPolicy: EidosContextPolicy = EidosContextPolicy.DEFAULT,
    val loopPolicy: ToolLoopPolicy = ToolLoopPolicy(GLOBAL_MAX_TOOL_ROUNDS),
    val transportHints: TransportHints = TransportHints(),
    /**
     * Whether reasoning ("thinking") providers run with thinking enabled for this scope.
     * Defaults to true; set false for quick-response surfaces (e.g. widget Ask Eidos) where
     * first-token latency matters more than deep reasoning.
     */
    val thinkingEnabled: Boolean = true,
) {
    companion object {
        /** Global tool-round backstop for scopes that do not set a tighter cap. */
        const val GLOBAL_MAX_TOOL_ROUNDS = 13

        /** Workshop build/plan/edit — longer than chat, but bounded. */
        const val WORKSHOP_BUILD_MAX_TOOL_ROUNDS = 12

        /** Workshop Chat — read a little context, then answer. */
        const val WORKSHOP_CHAT_MAX_TOOL_ROUNDS = 2
    }
}

data class ComposedPrompt(
    val systemPrompt: String,
    val profileId: String,
    val entrySurface: EidosEntrySurface,
    /**
     * Stable instructions (identity, ontology, static location prose) — cached on OpenAI GPT-5.6+.
     * Volatile location, note excerpts, prefetch, and memory sit in [volatileSystemSuffix].
     */
    val stableSystemPrefix: String = "",
    val volatileSystemSuffix: String = "",
    val stablePrefixSha256: String = "",
    val sectionCharCountsJson: String = "{}",
    val prefetchMetrics: EidosPrefetchMetrics? = null,
)
