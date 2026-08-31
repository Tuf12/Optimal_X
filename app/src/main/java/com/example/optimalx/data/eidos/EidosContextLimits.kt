package com.example.optimalx.data.eidos

/**
 * Tool-first context rules for Eidos system prompts (see EIDOS_LLM_CONTEXT_CLEANUP.md).
 * Conversation thread memory uses [ConversationOutboundHistory] plus active-conversation prefetch.
 */
object EidosContextLimits {

    /** Injected on every Eidos call — keep stable for provider prompt-cache prefixes. */
    val TOOL_FIRST_CONTEXT_RULES: String = """
        Context policy (OptimalX):
        - Do not assume full note, file, folder-tree, or entire workshop project source is inlined in this prompt.
        - Panel Workshop scope includes a compact project file list (fileReferenceId per file) and a bounded open-tab excerpt when applicable.
        - Retrieval: call search_semantic(query) — it returns chunk_text passages with location and ids. Answer from those chunks directly.
        - Use read_file/workshop_read_file to expand a file line range when needed — not as a required second hop for Q&A.
        - Note Q&A and edits: read_note for the current subfolder note; search_semantic for passages; edit_note_section with startLine/endLine from hits (or oldString fallback).
        - write_note_summary appends durable folder-memory bullets for the active subfolder; user may edit summary in the editor.
        - Prefer scopeType=local_first with current subfolder/parent ids when the question is location-specific; expansionPolicy defaults to expand_if_weak.
        - search_chat_history is keyword fallback for exact chat phrases only.
        - Active location IDs in this prompt are authoritative for default create/write targets.
        - Stored summaries in prompt are orientation only; trust search_semantic chunks for facts.
        - Long chat threads may include a rolling conversation summary in message history; use search_semantic or read_conversation for full detail.
    """.trimIndent()

    /** Appended on main chat profiles when prefetch is enabled (Phase 2). */
    val PREFETCH_RETRIEVAL_RULES: String = """
        Retrieved context (when present):
        - ## Retrieved context may already include relevant Daily, LTM, Journal, user note, workshop file, or past chat passages for this message.
        - Answer from those passages when sufficient; call search_semantic when you need more detail or are editing.
        - Workshop edits: use line numbers from file semantic hits (lineNumbersApplyTo=file) — not conversation chunk line numbers.
        - Summaries inlined elsewhere in this prompt are orientation only; trust retrieved passages and search_semantic for facts.
    """.trimIndent()
}
