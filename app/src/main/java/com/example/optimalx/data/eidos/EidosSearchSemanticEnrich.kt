package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.ConversationScopes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object EidosSearchSemanticEnrich {

    /**
     * Returns enriched args when auto-scope applies; null when args should pass through unchanged.
     */
    fun enrich(
        args: JsonObject,
        currentScopeType: String?,
        currentSubfolderId: Long?,
        currentParentFolderId: Long?,
        workshopHostLink: WorkshopHostLink? = null,
    ): JsonObject? {
        val workshopScopeId = when (currentScopeType) {
            ConversationScopes.PANEL_WORKSHOP, ConversationScopes.PANEL_RUNNER -> currentSubfolderId
            else -> null
        }
        if (workshopScopeId != null) {
            return enrichWorkshopScope(args, workshopScopeId, workshopHostLink)
        }
        if (currentScopeType == ConversationScopes.PARENT && currentParentFolderId != null) {
            if (args.containsKey("scopeType") || args.containsKey("scopeId")) {
                return null
            }
            return buildJsonObject {
                args.forEach { (key, value) -> put(key, value) }
                put("scopeType", JsonPrimitive("parent"))
                put("scopeId", JsonPrimitive(currentParentFolderId))
            }
        }
        return null
    }

    private fun enrichWorkshopScope(
        args: JsonObject,
        workshopScopeId: Long,
        workshopHostLink: WorkshopHostLink?,
    ): JsonObject? {
        if (args.containsKey("scopeType") && args.containsKey("scopeId")) {
            return null
        }
        val explicitScopeType = args.scopeTypeValue()
        val scopeType = explicitScopeType ?: "local_first"
        if (args.containsKey("scopeId")) {
            if (explicitScopeType == null) {
                return buildJsonObject {
                    args.forEach { (key, value) -> put(key, value) }
                    put("scopeType", JsonPrimitive(scopeType))
                }
            }
            return null
        }
        val scopeId = resolveWorkshopScopeId(scopeType, workshopScopeId, workshopHostLink) ?: return null
        return buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            if (explicitScopeType == null) {
                put("scopeType", JsonPrimitive(scopeType))
            }
            put("scopeId", JsonPrimitive(scopeId))
        }
    }

    private fun resolveWorkshopScopeId(
        scopeType: String,
        workshopScopeId: Long,
        workshopHostLink: WorkshopHostLink?,
    ): Long? = when (scopeType) {
        "local_first" -> workshopScopeId
        "subfolder", "local_only" -> workshopHostLink?.targetSubfolderId
        "parent", "current_parent", "current_branch" -> workshopHostLink?.parentFolderId
        else -> null
    }

    private fun JsonObject.scopeTypeValue(): String? =
        get("scopeType")?.toString()?.trim('"')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
