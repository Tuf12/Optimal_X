package com.example.optimalx.data.eidos.model

/**
 * One provider thinking block from a user turn — initial call, a tool hop, or the final answer.
 * Aggregated into [ChatMessage.assistantReasoningContent] for the chat Reasoning dropdown.
 */
data class EidosReasoningHop(
    val round: Int,
    val phase: EidosRequestPhase,
    val toolNames: List<String>,
    val reasoning: String,
    val isFinal: Boolean,
) {
    fun sectionHeader(): String = when {
        isFinal -> "### Final answer"
        toolNames.size == 1 -> "### Before ${toolNames.single()}"
        toolNames.isNotEmpty() -> "### Before ${toolNames.joinToString(", ")}"
        phase == EidosRequestPhase.FULL -> "### Round ${round + 1}"
        else -> "### Tool continuation ${round + 1}"
    }
}

/** Attach collected hops to the outgoing response for chat persistence. */
fun EidosResponse.withReasoningTrace(hops: List<EidosReasoningHop>): EidosResponse =
    copy(reasoningTrace = hops)

/**
 * Builds chat-persistable reasoning: single-hop turns stay plain text;
 * multi-hop turns get section headers for the dropdown.
 */
fun formatPersistableReasoning(
    hops: List<EidosReasoningHop>,
    finalFallback: String?,
): String? {
    val sections = hops.mapNotNull { hop ->
        hop.reasoning.trim().takeIf { it.isNotBlank() }?.let { hop to it }
    }
    if (sections.isEmpty()) {
        return finalFallback?.trim()?.takeIf { it.isNotBlank() }
    }
    if (sections.size == 1 && sections.first().first.isFinal) {
        return sections.first().second
    }
    return sections.joinToString(separator = "\n\n") { (hop, text) ->
        "${hop.sectionHeader()}\n$text"
    }
}

fun recordReasoningHop(
    hops: MutableList<EidosReasoningHop>,
    response: EidosResponse,
    round: Int,
    phase: EidosRequestPhase,
) {
    val reasoning = response.assistantReasoningContent?.trim()?.takeIf { it.isNotBlank() } ?: return
    val isFinal = response.toolCalls.isEmpty()
    hops += EidosReasoningHop(
        round = round,
        phase = phase,
        toolNames = if (isFinal) emptyList() else response.toolCalls.map { it.name },
        reasoning = reasoning,
        isFinal = isFinal,
    )
}
