package com.example.optimalx.voice.pipeline

/**
 * Merges partial and final transcript hypotheses into a persistent draft buffer.
 *
 * The assembler is the source of truth for the text shown in the input bar
 * while the mic is active. It must:
 *   - never drop previously committed text
 *   - always reflect the latest partial on top of committed text
 *   - commit partial text if the session is stopped before a final arrives
 */
class TranscriptAssembler {

    private var committed: String = ""
    private var partial: String = ""

    /** Seed the buffer with existing input so voice appends to typed text. */
    fun setBase(text: String) {
        committed = text.trim()
        partial = ""
    }

    /** Update the current partial without modifying committed text. */
    fun setPartial(text: String) {
        partial = text.trim()
    }

    /** Commit a final transcript chunk. Replaces current partial. */
    fun commitFinal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            committed = if (committed.isEmpty()) trimmed else "$committed $trimmed"
        }
        partial = ""
    }

    /** Treat the current partial as final. No-op if partial is empty. */
    fun flushPartialAsFinal() {
        if (partial.isNotEmpty()) {
            commitFinal(partial)
        }
    }

    /** Live display string combining committed + current partial. */
    fun displayText(): String = when {
        committed.isEmpty() && partial.isEmpty() -> ""
        committed.isEmpty() -> partial
        partial.isEmpty() -> committed
        else -> "$committed $partial"
    }

    /** Return everything captured so far and reset the buffer. */
    fun takeAndReset(): String {
        flushPartialAsFinal()
        val out = committed
        committed = ""
        partial = ""
        return out
    }

    /** Return everything captured so far without clearing state. */
    fun snapshot(): String {
        val draft = if (partial.isEmpty()) committed else if (committed.isEmpty()) partial else "$committed $partial"
        return draft
    }

    fun reset() {
        committed = ""
        partial = ""
    }

    val committedText: String get() = committed
    val partialText: String get() = partial
}
