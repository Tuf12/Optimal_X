package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.ConversationScopes

/**
 * Panel bridge prompt block for Eidos system prompts when a live panel preview is registered.
 */
object PanelBridgeContext {

    suspend fun buildPromptBlock(
        registry: PanelBridgeRegistry?,
        currentSubfolderId: Long,
        currentScopeType: String,
    ): String? {
        val snapshot = registry?.snapshot(
            currentSubfolderId = currentSubfolderId,
            currentScopeType = currentScopeType,
        ) ?: return null
        if (!snapshot.hasEligiblePanel) return null

        val fnList = if (snapshot.availableFunctions.isEmpty()) {
            "(none reported)"
        } else {
            snapshot.availableFunctions.joinToString(", ")
        }
        val eventLines = if (snapshot.recentEvents.isEmpty()) {
            "- Recent panel events: (none)"
        } else {
            snapshot.recentEvents.joinToString(
                separator = "\n",
                prefix = "- Recent panel events:\n",
            ) { evt ->
                "  - ${evt.name} @ ${evt.timestamp}: ${evt.payloadJson.take(220)}"
            }
        }
        return """
            Panel Bridge (ACTIVE — call_panel_function will reach live panel JS):
            - Context: ${snapshot.activeContextType ?: "unknown"} (workshopSubfolderId=${snapshot.activeWorkshopSubfolderId ?: -1})
            - Registered functions: $fnList
            - getState: functionName=getState, args="{}" — returns panel/game state from script.js panelGetState
            - runAction: functionName=runAction, args JSON string e.g. {"action":"newGame"} or {"action":"move","x":1} — handled by script.js panelHandleAction
            - Game/agent loop: getState → plan → runAction → getState until done; use search_semantic or workshop_read_file(query) for script.js action names
            $eventLines
        """.trimIndent()
    }

    suspend fun runnerBridgeBlock(
        registry: PanelBridgeRegistry?,
        currentSubfolderId: Long,
    ): String = buildPromptBlock(
        registry = registry,
        currentSubfolderId = currentSubfolderId,
        currentScopeType = ConversationScopes.PANEL_RUNNER,
    ) ?: PanelPlatformSpec.inactivePanelRunnerBridgeContextBlock()

    suspend fun workshopEditBridgeBlock(
        registry: PanelBridgeRegistry?,
        currentSubfolderId: Long,
        workshopEidosMode: WorkshopEidosMode,
        workshopProjectPhase: WorkshopProjectPhase?,
    ): String? {
        val editWithBridge = WorkshopEidosMode.normalizeToUserChip(workshopEidosMode) == WorkshopEidosMode.EDIT &&
            (workshopProjectPhase?.allowsCallPanelFunction == true)
        if (!editWithBridge) return null
        return buildPromptBlock(
            registry = registry,
            currentSubfolderId = currentSubfolderId,
            currentScopeType = ConversationScopes.PANEL_WORKSHOP,
        ) ?: PanelPlatformSpec.inactivePanelBridgeContextBlock()
    }
}
