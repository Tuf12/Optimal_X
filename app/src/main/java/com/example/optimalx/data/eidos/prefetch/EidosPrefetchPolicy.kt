package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.semantic.SemanticScopeSearch

/** Per-profile prefetch limits. Pass lists come from [EidosRetrievalPlanner]. */
data class EidosPrefetchPolicy(
    val profileEnabled: Boolean = false,
    val maxChunks: Int = 6,
    val maxChars: Int = 3_500,
    val scoreThreshold: Float = SemanticScopeSearch.WEAK_SCORE_THRESHOLD,
    /** Minimum note chunks in the final block when eligible hits exist (global user notes). */
    val reservedNoteSlots: Int = 0,
    /** Minimum chat chunks in the final block when eligible hits exist. */
    val reservedChatSlots: Int = 0,
) {
    companion object {
        /** Recent indexed/live batches from the active thread (chronological). */
        const val ACTIVE_CONVERSATION_RECENT_CHUNKS: Int = 2

        /** Semantic top-k within the active thread. */
        const val ACTIVE_CONVERSATION_SEMANTIC_LIMIT: Int = 3

        val DISABLED = EidosPrefetchPolicy(profileEnabled = false)

        /** general.app, parent, subfolder */
        val MAIN_CHAT = EidosPrefetchPolicy(
            profileEnabled = true,
            reservedNoteSlots = 2,
            reservedChatSlots = 1,
        )

        /** widget.ask / widget.chat — memory + notes + chat prefetch, smaller caps than main chat. */
        val WIDGET_MEMORY = EidosPrefetchPolicy(
            profileEnabled = true,
            maxChunks = 4,
            maxChars = 2_000,
            reservedNoteSlots = 1,
            reservedChatSlots = 1,
        )

        /** workshop.chat — discuss project; prefetch workshop file/note chunks. */
        val WORKSHOP_CHAT = EidosPrefetchPolicy(
            profileEnabled = true,
            maxChunks = 5,
            maxChars = 2_500,
        )

        /** workshop.plan / workshop.edit — codegen orientation. */
        val WORKSHOP_BUILD = EidosPrefetchPolicy(
            profileEnabled = true,
            maxChunks = 6,
            maxChars = 3_500,
        )

        /** workshop.intake — early chat; files often empty. */
        val WORKSHOP_INTAKE = EidosPrefetchPolicy(
            profileEnabled = true,
            maxChunks = 3,
            maxChars = 1_500,
        )
    }
}
