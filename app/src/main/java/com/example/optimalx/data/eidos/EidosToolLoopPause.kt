package com.example.optimalx.data.eidos

/**
 * Honest pause copy for when a `send()` hits its [com.example.optimalx.data.eidos.prompt.ToolLoopPolicy]
 * tool-round cap. This is a safety circuit breaker — the loop stops instead of silently churning more
 * (billed) provider hops. The message never claims the task finished.
 */
object EidosToolLoopPause {

    /** Marker appended to paused assistant text so tests / future auto-continue logic can detect a cap pause. */
    const val MARKER = "[tool_loop_paused]"

    fun message(
        priorText: String,
        toolRounds: Int,
        pendingToolNames: List<String>,
    ): String = buildString {
        val trimmedPrior = priorText.trim()
        if (trimmedPrior.isNotEmpty()) {
            append(trimmedPrior)
            append("\n\n")
        }
        append("Paused after ")
        append(toolRounds)
        append(if (toolRounds == 1) " tool step" else " tool steps")
        append(" — not finished. ")
        if (pendingToolNames.isNotEmpty()) {
            append("Was about to run: ")
            append(pendingToolNames.joinToString(", "))
            append(". ")
        }
        append("Reply \"continue\" to resume, or start a new chat if this is unrelated.")
        append("\n\n")
        append(MARKER)
    }
}
