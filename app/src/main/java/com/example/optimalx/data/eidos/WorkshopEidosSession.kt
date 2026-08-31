package com.example.optimalx.data.eidos

import java.util.concurrent.atomic.AtomicReference

/**
 * Per-request workshop tool policy while [EidosApiClient] runs tool calls.
 */
object WorkshopEidosSession {
    private data class ActiveState(
        val mode: WorkshopEidosMode,
        val phase: WorkshopProjectPhase?,
        val docAlignScope: WorkshopDocAlignScope?,
        val updateSection: WorkshopUpdateSection?,
        val conversationId: Long?,
    )

    private val active = AtomicReference<ActiveState?>(null)

    fun begin(
        mode: WorkshopEidosMode,
        phase: WorkshopProjectPhase? = null,
        docAlignScope: WorkshopDocAlignScope? = null,
        updateSection: WorkshopUpdateSection? = null,
        conversationId: Long? = null,
    ) {
        active.set(ActiveState(mode, phase, docAlignScope, updateSection, conversationId))
    }

    fun end() {
        active.set(null)
    }

    fun currentMode(): WorkshopEidosMode? = active.get()?.mode

    fun currentPhase(): WorkshopProjectPhase? = active.get()?.phase

    fun currentDocAlignScope(): WorkshopDocAlignScope? = active.get()?.docAlignScope

    fun currentUpdateSection(): WorkshopUpdateSection? = active.get()?.updateSection

    /** Conversation that initiated the current workshop tool round; carried into DIFF_REVIEW checkpoints. */
    fun currentConversationId(): Long? = active.get()?.conversationId
}
