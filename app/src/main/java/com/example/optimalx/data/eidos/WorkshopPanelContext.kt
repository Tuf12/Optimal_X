package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT
import com.example.optimalx.data.revision.WorkshopReviewPolicy

/**
 * Volatile Panel Workshop project context for Eidos system prompts.
 */
object WorkshopPanelContext {

    suspend fun buildVolatileContext(
        context: Context,
        database: AppDatabase,
        subfolder: Subfolder,
        workshopOpenFileName: String?,
        workshopOpenFileContent: String?,
        workshopEidosMode: WorkshopEidosMode,
        workshopProjectPhase: WorkshopProjectPhase?,
        workshopDocAlignScope: WorkshopDocAlignScope?,
        workshopUpdateSection: WorkshopUpdateSection?,
        workshopUserTurns: Int,
        slimHeavyBlocks: Boolean = false,
    ): String {
        val phase = workshopProjectPhase
            ?: WorkshopProjectPreferences.getProjectPhase(context, subfolder.id)
        val updateSection = workshopUpdateSection
            ?: WorkshopProjectPreferences.getUpdateSection(context, subfolder.id)
        val intakeSummary = WorkshopProjectPreferences.getIntakeSummary(context, subfolder.id)
        val files = database.fileReferenceDao().getBySubfolderOnce(subfolder.id)
        val fileManifest = WorkshopProjectContext.formatFileManifest(files)
        val openExcerpt = if (slimHeavyBlocks || workshopEidosMode == WorkshopEidosMode.CHAT) {
            null
        } else {
            WorkshopProjectContext.formatOpenFileExcerpt(
                workshopOpenFileName,
                workshopOpenFileContent,
            )
        }

        val projectSummaryBlock = if (slimHeavyBlocks) {
            null
        } else {
            ContentSummaryService.formatWorkshopSummaryForPrompt(subfolder)
        }
        val specFallback = if (!slimHeavyBlocks &&
            projectSummaryBlock.isNullOrBlank() &&
            workshopEidosMode != WorkshopEidosMode.CHAT &&
            phase != WorkshopProjectPhase.INTAKE
        ) {
            WorkshopSpecMarkdown.loadBounded(files)
        } else {
            ""
        }
        val hasProjectSummary = !subfolder.projectSummary.isNullOrBlank()
        val hasSpecFiles = hasSpecMarkdownFiles(files)

        val diffReviewStatusBlock = if (WorkshopReviewPolicy.shouldReview(phase, workshopEidosMode)) {
            val openSet = database.pendingChangeDao().findOpenSetForScope(
                SCOPE_WORKSHOP_PROJECT,
                subfolder.id,
            )
            val pendingItems = openSet?.let { database.pendingChangeDao().countPending(it.id) } ?: 0
            WorkshopProjectContext.formatDiffReviewStatus(pendingItems)
        } else {
            null
        }

        return buildString {
            appendLine("Panel Workshop — custom HTML/JS panel project")
            appendLine("Project: ${subfolder.name} (subfolderId=${subfolder.id})")
            appendLine("Workshop phase: ${phase.displayName} (${phase.name})")
            appendLine("Active Eidos mode: ${workshopEidosMode.displayName} (${workshopEidosMode.name})")
            if (phase == WorkshopProjectPhase.UPDATE) {
                appendLine(
                    "Update/edit: Chat, Plan, or Edit — read spec .md anytime; " +
                        "spec writes and doc align run on Accept update.",
                )
            }
            appendLine()
            appendLine(fileManifest)
            if (workshopEidosMode == WorkshopEidosMode.CHAT) {
                WorkshopHostLinkContext.resolve(database, subfolder.id)?.let { link ->
                    appendLine()
                    appendLine(WorkshopHostLinkContext.formatPromptBlock(link))
                }
            }
            if (diffReviewStatusBlock != null) {
                appendLine()
                appendLine(diffReviewStatusBlock)
            }
            if (openExcerpt != null) {
                appendLine()
                append(openExcerpt)
            } else if (!slimHeavyBlocks) {
                val openName = workshopOpenFileName?.trim().orEmpty()
                appendLine()
                appendLine(
                    when {
                        workshopEidosMode == WorkshopEidosMode.CHAT && openName.isNotEmpty() ->
                            "Editor tab: $openName (Chat mode — discuss only; use search_semantic or workshop_read_file with query)"
                        openName.isNotEmpty() ->
                            "Editor tab: $openName (excerpt synced from disk before this send — still prefer workshop_read_file with query before large edits)"
                        else -> "Editor tab: (none reported)"
                    },
                )
            }
            appendLine()
            appendLine(
                PanelPlatformSpec.eidosInstructionsForMode(
                    workshopEidosMode,
                    subfolder.id,
                    phase,
                    workshopDocAlignScope,
                    updateSection,
                ),
            )
            appendLine()
            appendLine(workshopContentPolicy(workshopEidosMode, phase, workshopDocAlignScope, updateSection))
            appendLine()
            appendLine(PanelPlatformSpec.EIDOS_WORKSHOP_RETRIEVAL_POLICY)
            if (intakeSummary.isNotBlank()) {
                appendLine()
                appendLine("Intake summary (chat alignment — authoritative for spec generation):")
                appendLine(intakeSummary)
            }
            if (phase == WorkshopProjectPhase.SPEC_REVIEW) {
                val specContents = loadWorkshopSpecContents(files)
                appendLine()
                appendLine(WorkshopSpecValidation.formatCapReport(specContents))
                val readiness = WorkshopSpecValidation.evaluateAcceptReadiness(specContents)
                appendLine()
                appendLine(
                    if (readiness.ready) {
                        "Spec accept gate: ready — user may tap Accept specs after reviewing Docs."
                    } else {
                        "Spec accept gate: not ready — ${readiness.message}"
                    },
                )
            }
            if (phase == WorkshopProjectPhase.DESIGN_REVIEW) {
                appendLine()
                appendLine(
                    "Design review: read spec .md with workshop_read_file; do not write spec .md in Edit until Accept design (doc align). " +
                        "User validates layout in Preview.",
                )
            }
            if (phase == WorkshopProjectPhase.UPDATE) {
                appendLine()
                appendLine(
                    "Update/edit: runtime/code is truth for Preview; read spec .md in Edit. " +
                        "Spec .md writes and sync to code happen on Accept update (doc align), not per edit.",
                )
            }
            if (!projectSummaryBlock.isNullOrBlank()) {
                appendLine()
                append(projectSummaryBlock)
            } else if (specFallback.isNotBlank()) {
                appendLine()
                appendLine("Spec markdown (bounded; regenerate project summary to cache a stable version):")
                appendLine(specFallback)
            } else if (slimHeavyBlocks) {
                appendLine()
                appendLine(
                    formatPrefetchOrientationBlock(
                        hasProjectSummary = hasProjectSummary,
                        hasSpecFiles = hasSpecFiles,
                        openFileName = workshopOpenFileName,
                        mode = workshopEidosMode,
                    ),
                )
            } else {
                appendLine()
                appendLine(
                    "No project summary yet. Use spec .md via workshop_read_file or ask the user to Generate Project Summary.",
                )
            }
            if (WorkshopEidosModeResolver.shouldNudgeNewChat(workshopUserTurns)) {
                appendLine()
                appendLine(PanelPlatformSpec.newChatNudge(workshopUserTurns))
            }
            appendLine()
            appendLine("User's current request is authoritative for this turn.")
        }.trim()
    }

    internal fun workshopContentPolicy(
        mode: WorkshopEidosMode,
        phase: WorkshopProjectPhase,
        docAlignScope: WorkshopDocAlignScope? = null,
        updateSection: WorkshopUpdateSection? = null,
    ): String {
        val chip = WorkshopEidosMode.normalizeToUserChip(mode)
        return when {
            docAlignScope != null && mode == WorkshopEidosMode.PLAN ->
                "Workshop content policy (Align docs / Plan): read runtime code and existing spec .md as needed; " +
                    "write spec .md only where out of date — no HTML/CSS/JS changes."
            phase == WorkshopProjectPhase.INTAKE ->
                "Workshop content policy (Intake): chat only — no file writes. Spec files are not generated until the user taps Generate specs."
            phase == WorkshopProjectPhase.SPEC_REVIEW && chip == WorkshopEidosMode.CHAT ->
                "Workshop content policy (Spec review / Chat): discuss specs only — no file writes. Plan mode for doc edits; user taps Accept specs when aligned."
            phase == WorkshopProjectPhase.SPEC_REVIEW && chip == WorkshopEidosMode.PLAN ->
                "Workshop content policy (Spec review / Plan): .md spec files only — no runtime writes until Accept specs and Build design."
            phase == WorkshopProjectPhase.SPEC_REVIEW && chip == WorkshopEidosMode.EDIT ->
                "Workshop content policy (Spec review / Edit): prefer Plan for .md; runtime edits only if user explicitly needs a scaffold tweak."
            mode == WorkshopEidosMode.BUILD_DESIGN && phase == WorkshopProjectPhase.DESIGN_BUILD ->
                "Workshop content policy (Design build / Build design): runtime shell only (index.html, style.css, stub script.js) — no .md read/write."
            phase == WorkshopProjectPhase.DESIGN_BUILD && chip == WorkshopEidosMode.PLAN ->
                "Workshop content policy (Design build / Plan): .md spec files only — use Build design for the static design build."
            phase == WorkshopProjectPhase.DESIGN_BUILD && chip == WorkshopEidosMode.EDIT ->
                "Workshop content policy (Design build / Edit): HTML/CSS/stub JS — no .md writes until Accept design."
            phase == WorkshopProjectPhase.DESIGN_REVIEW && chip == WorkshopEidosMode.CHAT ->
                "Workshop content policy (Design review / Chat): discuss only — no file writes."
            phase == WorkshopProjectPhase.DESIGN_REVIEW && chip == WorkshopEidosMode.PLAN ->
                "Workshop content policy (Design review / Plan): read any file; write spec .md only (runtime edits in Edit mode)."
            phase == WorkshopProjectPhase.DESIGN_REVIEW ->
                "Workshop content policy (Design review / Edit): HTML/CSS/stub JS only — .md files frozen until Accept design."
            mode == WorkshopEidosMode.BUILD_LOGIC && phase == WorkshopProjectPhase.LOGIC_BUILD ->
                "Workshop content policy (Logic build / Build logic): script.js and bridge.js primary; no .md read/write."
            phase == WorkshopProjectPhase.LOGIC_BUILD && chip == WorkshopEidosMode.PLAN ->
                "Workshop content policy (Logic build / Plan): .md spec files only — use Build logic for behavior."
            phase == WorkshopProjectPhase.LOGIC_BUILD ->
                "Workshop content policy (Logic build / Edit): edit runtime files directly — Preview not required for workshop_write_file."
            phase == WorkshopProjectPhase.LOGIC_REVIEW && chip == WorkshopEidosMode.CHAT ->
                "Workshop content policy (Logic review / Chat): discuss only — no file writes."
            phase == WorkshopProjectPhase.LOGIC_REVIEW ->
                "Workshop content policy (Logic review / Edit): edit runtime files directly — no .md writes until Accept logic."
            phase == WorkshopProjectPhase.UPDATE && chip == WorkshopEidosMode.CHAT ->
                "Workshop content policy (Update / Chat): discuss only — no file writes."
            phase == WorkshopProjectPhase.UPDATE && chip == WorkshopEidosMode.PLAN ->
                "Workshop content policy (Update / Plan): .md spec files only — runtime edits in Edit mode."
            phase == WorkshopProjectPhase.UPDATE ->
                "Workshop content policy (Update / Edit): runtime writes in Edit; read any spec .md; spec writes on Accept update only."
            chip == WorkshopEidosMode.CHAT ->
                "Workshop content policy (Chat): discuss only — no file writes. " +
                    "Use search_semantic for workshop and (when linked) host-project content; " +
                    "workshop_read_file for workshop files; read_file for host-project file hits; " +
                    "optional workshop_read_file with query when search is insufficient."
            chip == WorkshopEidosMode.PLAN ->
                "Workshop content policy: search_semantic first for spec passages; writes limited to .md spec files (fileReferenceId from manifest)."
            else ->
                "Workshop content policy: search_semantic → workshop_read_file(query or line range) → workshop_write_file. " +
                    "Do not read full large files without query; use Retrieved context or workshop_read_file — excerpts may be stale after edits."
        }
    }

    internal fun formatPrefetchOrientationBlock(
        hasProjectSummary: Boolean,
        hasSpecFiles: Boolean,
        openFileName: String?,
        mode: WorkshopEidosMode,
    ): String = buildString {
        appendLine("Workshop orientation:")
        appendLine("- Project files are listed above; relevant excerpts may appear in Retrieved context.")
        when {
            hasProjectSummary ->
                appendLine("- Cached project summary exists — use search_semantic or workshop_read_file if you need more.")
            hasSpecFiles ->
                appendLine("- Spec .md files exist — use search_semantic or workshop_read_file; Generate Project Summary caches orientation.")
            else ->
                appendLine("- No project summary yet — use workshop_read_file on spec .md or ask the user to Generate Project Summary.")
        }
        val openName = openFileName?.trim().orEmpty()
        if (openName.isNotEmpty()) {
            appendLine(
                when (mode) {
                    WorkshopEidosMode.CHAT ->
                        "- Editor tab: $openName (discuss only — use Retrieved context or workshop_read_file with query)"
                    else ->
                        "- Editor tab: $openName — use workshop_read_file with query before large edits."
                },
            )
        }
    }.trim()

    private fun hasSpecMarkdownFiles(files: List<FileReference>): Boolean {
        val specNames = PanelPlatformSpec.SPEC_MARKDOWN_FILES.map { it.lowercase() }.toSet()
        return files.any { ref ->
            ref.fileType.equals("md", ignoreCase = true) &&
                ref.fileName.lowercase() in specNames
        }
    }

    private fun loadWorkshopSpecContents(files: List<FileReference>): Map<String, String> =
        files
            .filter { it.fileType.equals("md", ignoreCase = true) }
            .associate { ref ->
                ref.fileName to runCatching {
                    java.io.File(ref.filePath).readText()
                }.getOrDefault("")
            }
}
