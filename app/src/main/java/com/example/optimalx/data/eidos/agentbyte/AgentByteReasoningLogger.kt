package com.example.optimalx.data.eidos.agentbyte

import com.example.optimalx.data.db.AppDatabase

/**
 * Retained for call-site compatibility. Reasoning system-folder logging was removed;
 * AgentByte rollover no longer persists ABR1 traces to disk.
 */
class AgentByteReasoningLogger(
    @Suppress("UNUSED_PARAMETER") db: AppDatabase,
    @Suppress("UNUSED_PARAMETER") scopeType: String,
    @Suppress("UNUSED_PARAMETER") scopeId: Long?,
    @Suppress("UNUSED_PARAMETER") promptLabel: String,
    @Suppress("UNUSED_PARAMETER") runId: String = "ab_${System.currentTimeMillis()}",
) {
    suspend fun appendStep(
        @Suppress("UNUSED_PARAMETER") snapshot: AgentByteLoop.IterationSnapshot,
        @Suppress("UNUSED_PARAMETER") prompt: AgentByteLoop.PromptPacket,
        @Suppress("UNUSED_PARAMETER") step: LoopStepResult,
    ) = Unit

    suspend fun appendResult(@Suppress("UNUSED_PARAMETER") result: AgentByteLoop.LoopResult) = Unit

    suspend fun appendExternalEvent(
        @Suppress("UNUSED_PARAMETER") toolName: String,
        @Suppress("UNUSED_PARAMETER") notes: String,
        @Suppress("UNUSED_PARAMETER") stateBefore: String = "",
        @Suppress("UNUSED_PARAMETER") stateAfter: String = "",
    ) = Unit

    suspend fun appendExternalResult(
        @Suppress("UNUSED_PARAMETER") outcome: String,
        @Suppress("UNUSED_PARAMETER") notes: String,
    ) = Unit
}
