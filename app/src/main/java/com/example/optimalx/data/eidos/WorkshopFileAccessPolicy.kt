package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.ToolExecutionResult

/**
 * Workshop file read/write rules for Eidos tools (Plan vs Edit vs build kickoff).
 * Spec `.md` **writes** in Edit are frozen during design review and update until Accept
 * (doc align). **Reads** are always allowed in Edit — use workshop_read_file for specs.
 */
object WorkshopFileAccessPolicy {

    fun markdownWriteFailure(fileName: String): ToolExecutionResult.Failure? {
        if (!PanelPlatformSpec.isMarkdownWorkshopFile(fileName)) return null
        val mode = WorkshopEidosSession.currentMode() ?: return null
        val phase = WorkshopEidosSession.currentPhase() ?: return null
        val updateSection = WorkshopEidosSession.currentUpdateSection()

        if (mode == WorkshopEidosMode.PLAN && PanelPlatformSpec.isPlanMarkdownFile(fileName)) {
            return null
        }
        if (WorkshopEidosSession.currentDocAlignScope() != null && mode == WorkshopEidosMode.PLAN) {
            return null
        }
        if (mode.isBuildFamily) {
            return ToolExecutionResult.Failure(
                "Build kickoff writes runtime files only — no .md changes. Specs align on Accept.",
            )
        }
        val chip = WorkshopEidosMode.normalizeToUserChip(mode)
        if (chip == WorkshopEidosMode.PLAN) {
            return ToolExecutionResult.Failure(
                "PLAN mode only allows spec .md files (${PanelPlatformSpec.PLAN_MARKDOWN_FILES.joinToString(", ")}). " +
                    "Switch to Edit for $fileName.",
            )
        }
        if (chip == WorkshopEidosMode.EDIT) {
            if (phase.freezesMarkdownWritesForEidos(updateSection)) {
                return ToolExecutionResult.Failure(
                    "Edit mode: spec .md writes are frozen until Accept (doc align). " +
                        "Use workshop_read_file to read $fileName; use Plan mode to edit specs before Accept.",
                )
            }
            return ToolExecutionResult.Failure(
                "Edit mode does not write spec .md files — use Plan mode for ${fileName}.",
            )
        }
        return null
    }

    /** Plan and Chat may read any project file; Edit may read spec `.md` (writes use [markdownWriteFailure]). */
    fun markdownReadFailure(fileName: String): ToolExecutionResult.Failure? {
        if (!PanelPlatformSpec.isMarkdownWorkshopFile(fileName)) return null
        val mode = WorkshopEidosSession.currentMode() ?: return null
        if (mode == WorkshopEidosMode.PLAN || mode == WorkshopEidosMode.CHAT) return null
        if (WorkshopEidosSession.currentDocAlignScope() != null) return null
        if (mode.isBuildFamily) return null
        return null
    }

    fun runtimeWriteFailure(fileName: String): ToolExecutionResult.Failure? {
        if (PanelPlatformSpec.isMarkdownWorkshopFile(fileName)) return null
        val mode = WorkshopEidosSession.currentMode() ?: return null
        if (mode.isBuildFamily) return null
        return when (WorkshopEidosMode.normalizeToUserChip(mode)) {
            WorkshopEidosMode.CHAT -> ToolExecutionResult.Failure(
                "CHAT mode does not allow file writes. Switch to Plan or Edit.",
            )
            WorkshopEidosMode.PLAN -> ToolExecutionResult.Failure(
                "PLAN mode only allows .md spec files. Switch to Edit to change $fileName.",
            )
            WorkshopEidosMode.EDIT -> null
            else -> null
        }
    }

    fun writeFailure(fileName: String): ToolExecutionResult.Failure? =
        markdownWriteFailure(fileName) ?: runtimeWriteFailure(fileName)
}
