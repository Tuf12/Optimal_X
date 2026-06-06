package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.repository.PanelStateRepository
import java.io.File

/**
 * Read-only project facts shared by Panel Workshop, Panel Runner, and (list summaries) Panel Gallery.
 * Does not include workshop modes, open-file excerpts, or write instructions.
 */
object PanelProjectKnowledge {

    enum class Mode {
        /** Single project on Panel Runner — full knowledge block. */
        RUNNER,
        /** One line on the gallery list card. */
        GALLERY_LIST_ITEM,
    }

    private const val INTAKE_MAX_CHARS = 1_500
    private const val FEATURES_FLOW_MAX_CHARS = 1_200

    suspend fun build(
        context: Context,
        database: AppDatabase,
        subfolderId: Long,
        mode: Mode,
        panelStateScopeKey: String? = PanelStateScope.GLOBAL,
    ): String {
        val subfolder = database.subfolderDao().getById(subfolderId) ?: return ""
        val phase = WorkshopProjectPreferences.getProjectPhase(context, subfolderId)
        if (mode == Mode.GALLERY_LIST_ITEM) {
            val launch = if (phase == WorkshopProjectPhase.COMPLETE) "launchable" else "draft → Workshop"
            return "- ${subfolder.name} (subfolderId=$subfolderId, phase=${phase.displayName}, $launch)"
        }
        return buildRunnerBlock(context, database, subfolder, phase, panelStateScopeKey)
    }

    private suspend fun buildRunnerBlock(
        context: Context,
        database: AppDatabase,
        subfolder: Subfolder,
        phase: WorkshopProjectPhase,
        panelStateScopeKey: String?,
    ): String {
        val files = database.fileReferenceDao().getBySubfolderOnce(subfolder.id)
        val intakeSummary = WorkshopProjectPreferences.getIntakeSummary(context, subfolder.id)
            .trim()
            .take(INTAKE_MAX_CHARS)
        val projectSummaryBlock = ContentSummaryService.formatWorkshopSummaryForPrompt(subfolder)
        val specFallback = if (projectSummaryBlock == null && phase != WorkshopProjectPhase.INTAKE) {
            WorkshopSpecMarkdown.loadBounded(files)
        } else {
            ""
        }
        val featuresFlow = loadFeaturesFlowExcerpt(files)
        val fileManifest = WorkshopProjectContext.formatFileManifest(files)
        val stateHint = panelStateHint(context, database, subfolder.id, panelStateScopeKey)

        return buildString {
            appendLine("Panel project (read-only context):")
            appendLine("Name: ${subfolder.name} (workshopSubfolderId=${subfolder.id})")
            appendLine("Phase: ${phase.displayName} (${phase.name})")
            appendLine()
            appendLine(fileManifest)
            if (projectSummaryBlock != null) {
                appendLine()
                append(projectSummaryBlock)
            } else if (specFallback.isNotBlank()) {
                appendLine()
                appendLine("Spec excerpt (no project summary yet):")
                appendLine(specFallback)
            }
            if (intakeSummary.isNotBlank()) {
                appendLine()
                appendLine("Intake summary:")
                appendLine(intakeSummary)
            }
            if (featuresFlow.isNotBlank()) {
                appendLine()
                append(featuresFlow)
            }
            if (stateHint.isNotBlank()) {
                appendLine()
                append(stateHint)
            }
        }.trim()
    }

    private fun loadFeaturesFlowExcerpt(files: List<FileReference>): String {
        val names = listOf("FEATURES.md", "FLOW.md")
        val builder = StringBuilder()
        var remaining = FEATURES_FLOW_MAX_CHARS
        for (name in names) {
            if (remaining <= 0) break
            val ref = files.firstOrNull { it.fileName.equals(name, ignoreCase = true) } ?: continue
            val body = runCatching { File(ref.filePath).readText() }.getOrDefault("").trim()
            if (body.isEmpty()) continue
            val capped = body.take(remaining)
            if (builder.isNotEmpty()) builder.appendLine().appendLine()
            builder.append("### ").append(name).appendLine().appendLine(capped)
            remaining -= capped.length
        }
        return builder.toString().trim()
    }

    private suspend fun panelStateHint(
        context: Context,
        database: AppDatabase,
        workshopSubfolderId: Long,
        scopeKey: String?,
    ): String {
        val key = scopeKey?.trim().orEmpty()
        if (key.isEmpty()) return ""
        val json = PanelStateRepository(database).loadStateJson(workshopSubfolderId, key)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        return if (bytes <= 2) {
            "Persisted panel state ($key): empty — use live getState when the panel is visible."
        } else {
            "Persisted panel state ($key): present (~$bytes bytes JSON). Prefer live getState over stale DB snapshot."
        }
    }
}
