package com.example.optimalx.data.eidos

import com.example.optimalx.data.revision.WorkshopReviewPolicy

/**
 * Machine-readable OptimalX panel platform contract (v1).
 *
 * Authoritative prose: [app/docs/architecture/PANEL_PLATFORM.md]
 * Kotlin runtime: PanelHtmlComposer, WorkshopPreviewPanel, PanelBridgeWebRuntime
 */
object PanelPlatformSpec {

    const val PLATFORM_VERSION: Int = 1

    val WORKSHOP_PRODUCT_STANDARD: String = """
        Panel Workshop product standard:
        - Each project is a complete, user-facing application.
        - Spec markdown and runtime code target what ships in Preview and Gallery — production quality at every phase.
        - Design build and logic build are sequential delivery phases of the same finished product, not a prototype, demo, MVP, proof-of-concept, or trial.
        - Never tell the user to "play a prototype" or treat Preview as a disposable demo — they are verifying the real panel.
    """.trimIndent()

    /** Markdown spec files (not executed). */
    val SPEC_MARKDOWN_FILES: List<String> = listOf(
        "README.md",
        "STRUCTURE.md",
        "FEATURES.md",
        "FLOW.md",
        "DESIGN.md",
    )

    /** All `.md` files Plan mode may author (spec markdown only). */
    val PLAN_MARKDOWN_FILES: List<String> = SPEC_MARKDOWN_FILES

    /** Runtime panel sources the platform expects to bundle. */
    val RUNTIME_FILES: List<String> = listOf(
        "index.html",
        "style.css",
        "bridge.js",
        "script.js",
    )

    /** Primary retrieval + file/bridge tools for BUILD/EDIT/DEBUG mode instructions. */
    val EIDOS_WORKSHOP_TOOL_NAMES: List<String> = listOf(
        "search_semantic",
        "workshop_read_file",
        "workshop_write_file",
        "workshop_edit_file",
        "workshop_append_file",
        "call_panel_function",
    )

    val EIDOS_WORKSHOP_RETRIEVAL_POLICY: String = """
        Retrieval (all workshop modes):
        - Retrieved context may already include relevant workshop file/note passages on turn 1; call search_semantic when you need more or before editing.
        - Scope search to this project (scopeType=local_first, scopeId=subfolderId). Answers from chunk_text hits.
        - Hits include lineNumbersApplyTo — use startLine/endLine from file hits only for workshop_read_file; never conversation line numbers.
        - Large files: truncated workshop_read_file is head-only (previewEndsAtLine < totalLines) — use file semantic hit + line range for tail code.
        - Use workshop_read_file(fileReferenceId, query=…) or startLine/endLine from a file hit before workshop_edit_file or workshop_write_file.
    """.trimIndent()

    /**
     * Code edits never require Preview or an active panel bridge.
     * call_panel_function is optional for live getState/runAction tests only.
     */
    val EIDOS_WORKSHOP_RUNTIME_EDIT_POLICY: String = """
        Runtime edits (all Edit/Build/Debug modes):
        - workshop_read_file / workshop_edit_file / workshop_append_file / workshop_write_file work without Preview open — use them to fix bugs directly.
        - Canvas, touch, stylus/S Pen, stroke, and pointer bugs are fixed in script.js (and html/css if needed) — not via call_panel_function.
        - call_panel_function is optional — only when Preview is open and you need live getState/runAction. Never block a code fix on Preview.
        - Never ask the user to edit source code themselves — apply fixes with workshop_edit_file or workshop_write_file.
    """.trimIndent()

    /**
     * Edit-first guidance: one line-range edit tool, plus append and full write.
     */
    val EIDOS_WORKSHOP_PATCH_POLICY: String = """
        Targeted edits (prefer over full rewrites):
        - Use workshop_edit_file(fileReferenceId, startLine, endLine, newContent) for all localized changes.
          Single-line: set startLine and endLine to the same line. Line numbers from file semantic hits or workshop_read_file.
        - Use workshop_append_file(fileReferenceId, content) to add text at EOF.
        - Use workshop_write_file only for new file scaffolds or intentional full rewrites.
        - Do NOT pseudo-rewrite via workshop_edit_file(startLine=1, endLine=EOF) — use workshop_write_file for true rewrites.
    """.trimIndent()

    /**
     * Relaxed write guidance for logic build kickoffs and LOGIC_BUILD Edit — allows full-file
     * writes when implementing behavior so the model is not forced into many tiny patches.
     */
    val EIDOS_WORKSHOP_SUBSTANTIAL_WRITE_POLICY: String = """
        Substantial runtime implementation (logic build):
        - You may use workshop_write_file on script.js, bridge.js, index.html, or style.css when implementing a behavior slice or any change over ~30 lines.
        - Prefer one complete file write per file when that is faster than many workshop_edit_file calls in one turn.
        - workshop_edit_file remains appropriate for small, localized fixes; workshop_append_file for EOF additions.
        - You may call multiple write tools in one assistant turn until the requested behavior is implemented.
    """.trimIndent()

    /**
     * Reminder injected into review-mode prompts so the model does not claim a file is
     * "updated" while the change is still queued for the user. Tools return
     * `"Proposal queued for review: <fileName>"` in these phases.
     */
    val EIDOS_WORKSHOP_REVIEW_QUEUE_NOTICE: String = """
        Diff Review (this phase queues edits — required every Edit turn):
        - Runtime edits are proposals until the user accepts them in the workshop **Diff Review** panel (top bar badge or chat banner).
        - **One Diff Review row per file** (not per tool call): many workshop_edit_file calls on script.js update the same row; bridge.js / index.html / style.css each get their own row when edited.
        - After writes, call **workshop_list_pending_review** (or read the queue line in tool results) before telling the user how many accepts are needed.
        - **workshop_read_file** and **workshop_edit_file** use the latest pending content for a file so chained patches in one turn compose correctly.
        - The diff the user sees is always **disk → your latest proposal** for that file.
        - Describe what you proposed in plain language. Do NOT claim the file is "updated", "fixed", or "live"
          until the user accepts in Diff Review.
        - If the user rejects, treat the file as unchanged and ask what to revise.
    """.trimIndent()

    /** One-line mode hint for Chat prompts (no repeated Accept / mode-switch nagging). */
    val WORKSHOP_CHAT_MODE_AVAILABILITY: String =
        "Modes available: Chat (discuss only), Plan (.md specs), Edit (runtime code)."

    val WORKSHOP_CHAT_CONVERSATIONAL_RULES: String = """
        Conversational Chat rules:
        - Answer in plain language from search hits, manifest, and this thread.
        - Do NOT nag about Accept buttons, Preview, or switching modes unless the user asks how to proceed.
        - Prefer search_semantic; at most one workshop_read_file when search is insufficient, then reply.
        - $WORKSHOP_CHAT_MODE_AVAILABILITY
    """.trimIndent()

    /**
     * Returns the [EIDOS_WORKSHOP_REVIEW_QUEUE_NOTICE] when the current phase+mode would
     * route writes through the pending-change pipeline, or empty string when build modes
     * (or build-style phases) auto-accept. Lets one prompt function serve both contexts.
     */
    fun reviewNoticeFor(
        phase: WorkshopProjectPhase?,
        mode: WorkshopEidosMode?,
    ): String =
        if (WorkshopReviewPolicy.shouldReview(phase, mode)) {
            EIDOS_WORKSHOP_REVIEW_QUEUE_NOTICE
        } else {
            ""
        }

    /** Injected when Preview tab is closed; must not imply file writes are blocked. */
    fun inactivePanelBridgeContextBlock(): String = """
        Panel Bridge: inactive (Workshop Preview tab is not open).
        This affects call_panel_function only — workshop_read_file and workshop_write_file still work.
        Fix behavior by patching script.js / index.html / style.css; the user can test in Preview afterward.
        Do not ask the user to open Preview before you write a code fix, and never ask them to edit files manually.
    """.trimIndent()

    fun inactivePanelRunnerBridgeContextBlock(): String = """
        Panel Bridge: inactive (running panel WebView is not visible to Eidos).
        call_panel_function will fail until the user is on the Panel Runner screen with the panel open.
        Use workshop_read_file for script.js / bridge.js hints only — do not use workshop_write_file from Panel Runner.
    """.trimIndent()

    fun eidosPanelGalleryRules(): String = """
        Panel Gallery rules:
        - User is browsing the panel list — not inside a running panel; no Panel Bridge.
        - COMPLETE panels launch in Panel Runner; drafts open in Panel Workshop.
        - Do not use workshop_write_file or call_panel_function here.
        - Answer list questions from the project list above; suggest opening a panel or Workshop for edits.
    """.trimIndent()

    fun eidosRunnerInstructions(subfolderId: Long): String = """
        Panel Runner rules (subfolderId=$subfolderId):
        - Runtime use only — this chat is panel_runner, not panel_workshop (build/edit is a separate thread).
        - Help the user use the panel: explain controls, troubleshoot behavior, play games via the bridge when visible.
        - call_panel_function: getState with args "{}" first when you need current UI/game state; then runAction with actions from script.js.
        - workshop_read_file is read-only (script.js, bridge.js, specs) — use search_semantic with scopeType=local_first and scopeId=$subfolderId when helpful.
        - Do NOT use workshop_write_file or workshop_edit_file — direct layout/code changes to Panel Workshop.
        - ${eidosContextSummary()}
    """.trimIndent()

    data class Capability(
        val id: String,
        val summary: String,
    )

    data class ForbiddenApi(
        val id: String,
        val status: String,
        val workaround: String,
    )

    data class ProjectValidation(
        val ok: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    )

    /** Result of static checks before **Accept logic** / Finish (Phase 2). */
    data class PersistenceFinishEvaluation(
        val requiresPersistence: Boolean,
        val validation: ProjectValidation,
    ) {
        val shouldBlockFinish: Boolean
            get() = requiresPersistence && validation.warnings.isNotEmpty()

        val warningMessages: List<String>
            get() = validation.warnings
    }

    val capabilities: List<Capability> = listOf(
        Capability("html_composite_bundle", "Inlines project CSS/JS; strips external script/link src; bridge.js first"),
        Capability("webview_javascript", "JavaScript enabled in workshop/custom panel WebView"),
        Capability("dom_storage", "domStorageEnabled for localStorage when panels use it"),
        Capability("pager_vertical_scroll", "Touch listener keeps vertical scroll inside HorizontalPager tabs"),
        Capability("bridge_get_state_run_action", "OptimalXPanelBridge + global getState/runAction via bridge.js"),
        Capability("call_panel_function", "Eidos tool invokes visible panel JS when preview/tab is open"),
        Capability("console_error_capture", "Preview WebView ERROR/WARNING → workshop console buffer"),
        Capability("workshop_file_io", "workshop_read_file / workshop_write_file"),
        Capability(
            "panel_state_persistence",
            "Room panel_state + bridge load/save when panelStateScopeKey set; " +
                "panel JS must implement panelGetState + restore — Kotlin does not auto-capture DOM",
        ),
    )

    /**
     * Injected into logic-build / logic-review prompts. Panel HTML/JS owns state; Kotlin stores opaque JSON.
     * See app/docs/implementation/PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md
     */
    val EIDOS_PERSISTENCE_POLICY: String = """
        Panel persistence (user data — scores, forms, save points):
        - Kotlin does NOT read the DOM or auto-save inputs. You must implement in script.js:
          panelGetState() → JSON-serializable snapshot (include version: 1 for future migrations)
          panelRestoreState(state) OR panelHandleAction({ action: "__restoreState", state })
        - After user-mutable changes, call persistPanelStateDebounced() from bridge.js (scaffold provides this;
          default 800ms). Wire input/change listeners in script.js — do not rely on runAction per keystroke.
        - runAction still saves immediately as a safety net when panelGetState exists.
        - Test in Panel Gallery or editor custom tab — Workshop Preview does not persist.
        - Room is canonical; localStorage is optional session cache only.
    """.trimIndent()

    val forbiddenApis: List<ForbiddenApi> = listOf(
        ForbiddenApi("fetch_xhr_internet", "Out of scope (product policy)", "No panel network unless user asks and platform adds opt-in"),
        ForbiddenApi("navigator_clipboard", "Unreliable in WebView", "Platform issue: future runAction copyText"),
        ForbiddenApi("window_print_pdf", "Limited", "Platform issue: future bridge export"),
        ForbiddenApi("native_file_picker_share", "Not exposed", "Platform issue for developer"),
        ForbiddenApi("external_script_cdn", "Stripped/blocked", "Keep logic in project script.js; platform inlines files"),
        ForbiddenApi("background_tab_bridge", "Not guaranteed", "User must open preview or custom panel tab for call_panel_function"),
    )

    /** Short block for token-limited contexts (appended to workshop system prompt). */
    fun eidosContextSummary(): String = buildString {
        append("OptimalX panel platform v$PLATFORM_VERSION. ")
        append("Kotlin bundles index.html+CSS+JS (PanelHtmlComposer); do not rely on <script src> alone. ")
        append("bridge.js before script.js; getState/runAction in bridge.js; logic in script.js; ")
        append("panelGetState/panelRestoreState in script.js for user-data persistence (Room); Kotlin does not auto-capture DOM. ")
        append("panelHandleAction for Eidos-driven actions when Preview is open. ")
        append("File fixes use workshop_write_file — Preview/call_panel_function not required. ")
        append(WorkshopAndroidLayoutRules.EIDOS_CONTEXT_SUMMARY)
    }.trim()

    fun eidosInstructionsForMode(
        mode: WorkshopEidosMode,
        subfolderId: Long,
        phase: WorkshopProjectPhase? = null,
        docAlignScope: WorkshopDocAlignScope? = null,
        updateSection: WorkshopUpdateSection? = null,
    ): String {
        if (docAlignScope != null && mode == WorkshopEidosMode.PLAN) {
            return eidosAlignDocsFromCodeInstructions(subfolderId, docAlignScope, updateSection)
        }
        val effective = WorkshopEidosMode.effectiveForInstructions(mode, phase, docAlignScope)
        if (phase == WorkshopProjectPhase.INTAKE && effective == WorkshopEidosMode.CHAT) {
            return eidosIntakeChatInstructions(subfolderId)
        }
        if (phase == WorkshopProjectPhase.SPEC_REVIEW) {
            return when (effective) {
                WorkshopEidosMode.CHAT -> eidosSpecReviewChatInstructions(subfolderId)
                WorkshopEidosMode.PLAN -> eidosGenerateSpecsInstructions(subfolderId)
                else -> eidosPlanModeInstructions(subfolderId)
            }
        }
        if (phase == WorkshopProjectPhase.DESIGN_BUILD) {
            return when (effective) {
                WorkshopEidosMode.CHAT -> eidosDesignReviewChatInstructions(subfolderId)
                WorkshopEidosMode.BUILD_DESIGN -> eidosBuildDesignInstructions(subfolderId)
                WorkshopEidosMode.PLAN -> eidosPlanModeInstructions(subfolderId)
                else -> eidosPhaseEditInstructions(subfolderId, WorkshopProjectPhase.DESIGN_BUILD)
            }
        }
        if (phase == WorkshopProjectPhase.DESIGN_REVIEW) {
            return when (effective) {
                WorkshopEidosMode.CHAT -> eidosDesignReviewChatInstructions(subfolderId)
                WorkshopEidosMode.PLAN -> eidosPlanModeInstructions(subfolderId)
                else -> eidosPhaseEditInstructions(subfolderId, WorkshopProjectPhase.DESIGN_REVIEW)
            }
        }
        if (phase == WorkshopProjectPhase.LOGIC_BUILD) {
            return when (effective) {
                WorkshopEidosMode.CHAT -> eidosLogicReviewChatInstructions(subfolderId)
                WorkshopEidosMode.BUILD_LOGIC -> eidosBuildLogicInstructions(subfolderId)
                WorkshopEidosMode.PLAN -> eidosPlanModeInstructions(subfolderId)
                else -> logicEditOrDebugInstructions(subfolderId, phase)
            }
        }
        if (phase == WorkshopProjectPhase.LOGIC_REVIEW) {
            return when (effective) {
                WorkshopEidosMode.CHAT -> eidosLogicReviewChatInstructions(subfolderId)
                WorkshopEidosMode.PLAN -> eidosPlanModeInstructions(subfolderId)
                else -> logicEditOrDebugInstructions(subfolderId, phase)
            }
        }
        if (phase == WorkshopProjectPhase.UPDATE) {
            return when (effective) {
                WorkshopEidosMode.CHAT -> eidosUpdateChatInstructionsUnified(subfolderId)
                WorkshopEidosMode.PLAN -> eidosUpdatePlanInstructions(subfolderId)
                else -> eidosUpdateEditInstructionsUnified(subfolderId)
            }
        }
        return when (effective) {
            WorkshopEidosMode.PLAN -> eidosPlanModeInstructions(subfolderId)
            WorkshopEidosMode.BUILD -> eidosWorkshopInstructions(subfolderId)
            WorkshopEidosMode.BUILD_DESIGN -> eidosBuildDesignInstructions(subfolderId)
            WorkshopEidosMode.BUILD_LOGIC -> eidosBuildLogicInstructions(subfolderId)
            WorkshopEidosMode.CHAT -> eidosChatModeInstructions(subfolderId)
            else -> logicEditOrDebugInstructions(subfolderId, phase)
        }
    }

    /** Phase-aware Edit prompt (replaces legacy DESIGN / DEBUG-only blocks). */
    fun eidosPhaseEditInstructions(
        subfolderId: Long,
        phase: WorkshopProjectPhase,
    ): String {
        val phaseLabel = phase.displayName
        val scopeBlock = when (phase) {
            WorkshopProjectPhase.DESIGN_BUILD,
            WorkshopProjectPhase.DESIGN_REVIEW,
            -> """
                Scope: index.html, style.css, script.js — complete static design per accepted specs (interaction ships in logic build).
                Read spec .md with workshop_read_file; do not write spec .md in Edit until Accept design.
            """.trimIndent()
            WorkshopProjectPhase.LOGIC_BUILD,
            WorkshopProjectPhase.LOGIC_REVIEW,
            -> """
                Scope: script.js and bridge.js primary; touch index.html/style.css only when behavior requires it.
                Do NOT write any .md file (specs sync on Accept logic).
            """.trimIndent()
            WorkshopProjectPhase.UPDATE -> """
                Scope: runtime file writes (index.html, style.css, bridge.js, script.js).
                Read spec .md files with workshop_read_file anytime.
                Do not write spec .md in Edit — Plan mode or Accept update (doc align) updates specs.
            """.trimIndent()
            else -> """
                Scope: change only files required for the user's request.
            """.trimIndent()
        }
        val persistenceBlock = if (phase == WorkshopProjectPhase.LOGIC_BUILD ||
            phase == WorkshopProjectPhase.LOGIC_REVIEW ||
            phase == WorkshopProjectPhase.COMPLETE
        ) {
            EIDOS_PERSISTENCE_POLICY
        } else {
            ""
        }

        val writePolicyBlock = when (phase) {
            WorkshopProjectPhase.LOGIC_BUILD -> EIDOS_WORKSHOP_SUBSTANTIAL_WRITE_POLICY
            else -> EIDOS_WORKSHOP_PATCH_POLICY
        }

        return """
            Panel Workshop — EDIT ($phaseLabel) (subfolderId=$subfolderId)
            Active phase: $phaseLabel — apply the user's current request.

            $scopeBlock

            $EIDOS_WORKSHOP_RUNTIME_EDIT_POLICY

            $writePolicyBlock

            ${reviewNoticeFor(phase, WorkshopEidosMode.EDIT)}

            $persistenceBlock

            Tools: ${EIDOS_WORKSHOP_TOOL_NAMES.joinToString(", ")}.


            ${if (phase == WorkshopProjectPhase.DESIGN_REVIEW) WorkshopAndroidLayoutRules.EIDOS_CONTEXT_SUMMARY + "\n\n" else ""}${eidosContextSummary()}
        """.trimIndent()
    }

    /** Edit prompts; legacy DEBUG stored mode maps to [eidosPhaseEditInstructions]. */
    private fun logicEditOrDebugInstructions(
        subfolderId: Long,
        phase: WorkshopProjectPhase?,
    ): String {
        val resolvedPhase = phase ?: WorkshopProjectPhase.COMPLETE
        return eidosPhaseEditInstructions(subfolderId, resolvedPhase)
    }

    fun eidosGenerateSpecsInstructions(subfolderId: Long): String = """
        Panel Workshop — GENERATE SPECS (Plan) (subfolderId=$subfolderId)
        Active phase: SPEC_REVIEW — write or revise specification markdown only.

        Goal: Produce short human-language spec files from the intake summary in this prompt.
        Summarize intent in plain human language — do NOT rewrite HTML/JS/CSS in the specs.

        Write or update: ${SPEC_MARKDOWN_FILES.joinToString(", ")} only.
        ${WorkshopSpecValidation.specLengthGuidanceForPrompt()}

        DESIGN.md must include Android WebView layout rules:
        ${WorkshopAndroidLayoutRules.EIDOS_CONTEXT_SUMMARY}

        Do NOT workshop_write_file on index.html, style.css, bridge.js, or script.js.
        When specs match the intake, tell the user to review files in the Docs drawer and tap **Accept specs**.

        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosSpecReviewChatInstructions(subfolderId: Long): String = """
        Panel Workshop — SPEC REVIEW (Chat) (subfolderId=$subfolderId)
        Active phase: SPEC_REVIEW — discuss and compare; do NOT modify files.

        $WORKSHOP_CHAT_CONVERSATIONAL_RULES

        Tools: search_semantic, workshop_read_file (optional). No workshop_write_file.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosBuildDesignInstructions(subfolderId: Long): String = """
        Panel Workshop — BUILD DESIGN (subfolderId=$subfolderId)
        Active phase: DESIGN_BUILD — deliver the full static design (HTML/CSS + render hooks in script.js).

        $WORKSHOP_PRODUCT_STANDARD

        Goal: Every FLOW screen visible in Preview with production layout, typography, and on-screen content. Spec .md files were accepted; do not rewrite them.

        Update runtime files only: index.html, style.css, script.js.
        Start from project scaffolds in the manifest; expand structure to match accepted specs and intake summary.
        Keep bridge.js as scaffold unless bridge wiring is required for layout.
        Do NOT workshop_write_file for any .md file.

        Interaction and game rules ship in the logic build phase — deliver complete static visuals now.

        Tool choice in this phase:
        - Initial files: workshop_write_file is the right tool — full-file writes are auto-accepted to disk.
        - Targeted fixes: workshop_edit_file for one-spot edits.

        When Preview shows every FLOW screen, tell the user to refresh Preview if needed, then tap **Accept design**.

        Tools: ${EIDOS_WORKSHOP_TOOL_NAMES.joinToString(", ")}.


        ${WorkshopAndroidLayoutRules.EIDOS_CONTEXT_SUMMARY}

        ${eidosContextSummary()}
    """.trimIndent()

    /** @deprecated Use [eidosPhaseEditInstructions] for DESIGN_REVIEW / DESIGN_BUILD Edit. */
    fun eidosDesignModeInstructions(subfolderId: Long): String =
        eidosPhaseEditInstructions(subfolderId, WorkshopProjectPhase.DESIGN_REVIEW)

    fun eidosDesignReviewChatInstructions(subfolderId: Long): String = """
        Panel Workshop — DESIGN REVIEW (Chat) (subfolderId=$subfolderId)
        Active phase: DESIGN_REVIEW — discuss layout and Preview only; do NOT modify files.

        $WORKSHOP_CHAT_CONVERSATIONAL_RULES

        Tools: search_semantic (runtime passages). No workshop_write_file / call_panel_function.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosBuildLogicInstructions(subfolderId: Long): String = """
        Panel Workshop — BUILD LOGIC (subfolderId=$subfolderId)
        Active phase: LOGIC_BUILD — wire behavior, state, and bridge actions.

        $WORKSHOP_PRODUCT_STANDARD

        Goal: Implement every panel behavior from FEATURES.md — calculations, canvas/stylus input, state, panelGetState/panelRestoreState/panelHandleAction as needed.

        $EIDOS_PERSISTENCE_POLICY

        Update script.js and bridge.js as needed. Adjust index.html/style.css only when required for behavior.
        Layout shell was accepted in design review — preserve structure unless behavior requires small UI tweaks.
        Do NOT workshop_write_file for any .md file.

        $EIDOS_WORKSHOP_RUNTIME_EDIT_POLICY

        $EIDOS_WORKSHOP_SUBSTANTIAL_WRITE_POLICY

        Disk writes in this phase are applied immediately (no review queue) — keep edits focused so the user can verify behavior in Preview between turns.

        When behavior works in Preview, tell the user to iterate in Edit or Debug mode if needed, then tap **Accept logic**.

        Tools: ${EIDOS_WORKSHOP_TOOL_NAMES.joinToString(", ")}.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosLogicReviewChatInstructions(subfolderId: Long): String = """
        Panel Workshop — LOGIC REVIEW (Chat) (subfolderId=$subfolderId)
        Active phase: logic build or review — discuss behavior only; do NOT modify files.

        $WORKSHOP_CHAT_CONVERSATIONAL_RULES

        Tools: search_semantic (runtime passages). No workshop_write_file / call_panel_function.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosUpdateSpecsInstructions(subfolderId: Long): String = """
        Panel Workshop — UPDATE → Specs (Plan) (subfolderId=$subfolderId)
        Active phase: UPDATE — revise specification markdown only.

        Write or update: ${SPEC_MARKDOWN_FILES.joinToString(", ")} only.
        ${WorkshopSpecValidation.specLengthGuidanceForPrompt()}
        Do NOT change runtime files.

        When aligned, tell the user to tap **Accept changes** in the workshop top bar.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosUpdateDesignInstructions(subfolderId: Long): String = """
        Panel Workshop — UPDATE → Design (subfolderId=$subfolderId)
        Active phase: UPDATE (Design section) — layout/code iteration only.

        Rules:
        - Edit index.html, style.css, script.js (layout + stub handlers — preserve accepted behavior unless user asks).
        - Read spec .md with workshop_read_file; do not workshop_write_file on any .md file until Accept update.
        - Ask the user to keep Preview open; use call_panel_function when Preview is visible.
        - When layout matches intent, tell the user to tap **Accept changes**.

        $EIDOS_WORKSHOP_PATCH_POLICY

        $EIDOS_WORKSHOP_REVIEW_QUEUE_NOTICE

        Tools: ${EIDOS_WORKSHOP_TOOL_NAMES.joinToString(", ")}.


        ${WorkshopAndroidLayoutRules.EIDOS_CONTEXT_SUMMARY}

        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosUpdateLogicInstructions(subfolderId: Long): String = """
        Panel Workshop — UPDATE → Logic (Edit) (subfolderId=$subfolderId)
        Active phase: UPDATE (Logic section) — targeted runtime changes only.

        Rules:
        - Edit script.js and bridge.js as needed; touch HTML/CSS only when required.
        - Prefer minimal changes for the user's request; do not rewrite the whole panel.
        - Do not bulk-update .md specs — docs align on **Accept changes** only.

        $EIDOS_WORKSHOP_RUNTIME_EDIT_POLICY

        $EIDOS_WORKSHOP_PATCH_POLICY

        $EIDOS_WORKSHOP_REVIEW_QUEUE_NOTICE

        Tools: ${EIDOS_WORKSHOP_TOOL_NAMES.joinToString(", ")}.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosUpdateChatInstructionsUnified(subfolderId: Long): String = """
        Panel Workshop — UPDATE / edit (Chat) (subfolderId=$subfolderId)
        Active phase: UPDATE — discuss only; do NOT modify any project file.

        $WORKSHOP_CHAT_CONVERSATIONAL_RULES

        Tools: search_semantic, workshop_read_file (optional).


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosUpdatePlanInstructions(subfolderId: Long): String = """
        Panel Workshop — UPDATE / edit (Plan) (subfolderId=$subfolderId)
        Active phase: UPDATE — revise specification markdown only. Runtime code is edited in Edit mode.

        You may read runtime files with search_semantic / workshop_read_file to compare code to specs.
        Write or update: ${PLAN_MARKDOWN_FILES.joinToString(", ")} only.
        Do NOT workshop_write_file on index.html, style.css, bridge.js, or script.js.

        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosUpdateEditInstructionsUnified(subfolderId: Long): String =
        eidosPhaseEditInstructions(subfolderId, WorkshopProjectPhase.UPDATE)

    /** @deprecated Debug is folded into Edit; use [eidosPhaseEditInstructions]. */
    fun eidosUpdateDebugInstructions(subfolderId: Long): String =
        eidosPhaseEditInstructions(subfolderId, WorkshopProjectPhase.UPDATE)

    /** @deprecated Legacy section-scoped update; use [eidosUpdateChatInstructionsUnified]. */
    fun eidosUpdateChatInstructions(subfolderId: Long, section: WorkshopUpdateSection): String = """
        Panel Workshop — UPDATE → ${section.displayName} (Chat) (subfolderId=$subfolderId)
        Active phase: UPDATE — discuss only; do NOT modify files.

        ${when (section) {
            WorkshopUpdateSection.SPECS ->
                "For spec edits suggest **Plan** mode. Accept applies spec changes without auto-align from code."
            WorkshopUpdateSection.DESIGN ->
                "For layout changes suggest **Design** mode. Spec .md files are frozen during this section."
            WorkshopUpdateSection.LOGIC ->
                "For behavior fixes suggest **Edit** or **Debug** mode."
        }}

        When satisfied, tell the user to tap **Accept changes** in the workshop top bar.

        Tools: search_semantic only. No workshop_write_file / call_panel_function.


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosAlignDocsFromCodeInstructions(
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
    ): String {
        val targetFiles = scope.markdownFiles(updateSection)
        val filesLine = if (targetFiles.isEmpty()) {
            "any spec .md files that no longer match the code"
        } else {
            targetFiles.joinToString(", ")
        }
        val runtimeSources = when (scope) {
            WorkshopDocAlignScope.DESIGN -> "index.html, style.css, and stub script.js"
            WorkshopDocAlignScope.FINISH -> "index.html, style.css, bridge.js, and script.js"
            WorkshopDocAlignScope.UPDATE -> "index.html, style.css, bridge.js, and script.js"
        }
        val passLabel = when (scope) {
            WorkshopDocAlignScope.DESIGN -> "Accept design (doc sync)"
            WorkshopDocAlignScope.FINISH -> "Accept logic (doc sync)"
            WorkshopDocAlignScope.UPDATE -> "Accept update (doc sync)"
        }
        return """
            Panel Workshop — ALIGN DOCS FROM CODE (subfolderId=$subfolderId)
            Active pass: $passLabel — code → spec snapshots on user approval only (when Kotlin started this pass).
            This is NOT a logic-build or runtime-edit pass — do not implement panel behavior or change .html/.css/.js.

            The current code ($runtimeSources) and the specs to review are INLINED in the user's message.
            Do NOT call workshop_read_file or search_semantic — everything you need is already provided.
            Only the spec files listed as out of date need updating; if a listed spec already matches the code, leave it unchanged.
            Candidate spec files: $filesLine.
            If nothing needs changing, reply briefly that no changes were needed and do not call workshop_write_file.
            Use workshop_write_file only — do not paste tool-call JSON or fenced JSON blobs in chat.
            Summarize layout and behavior in plain human language — do NOT paste HTML/JS/CSS into specs.
            ${WorkshopSpecValidation.specLengthGuidanceForPrompt()}

            Do NOT workshop_write_file on index.html, style.css, bridge.js, or script.js in this pass.
            When done, briefly confirm which spec files you updated (if any).

            Tools: workshop_write_file.


            ${eidosContextSummary()}
        """.trimIndent()
    }

    fun eidosIntakeChatInstructions(subfolderId: Long): String = """
        Panel Workshop — INTAKE (Chat) (subfolderId=$subfolderId)
        Active phase: INTAKE — discuss only; do NOT modify any project file.

        Guide the user through these three questions in conversation (plain language):
        1. What do you want to build?
        2. Why do you want it?
        3. How should it work?

        Hard rules:
        - Do NOT call workshop_write_file or call_panel_function.
        - Do NOT ask the user to fill in README.md — intake lives in this chat.
        - Ask one topic at a time when helpful; confirm understanding before moving on.
        - When aligned on all three, mention **Generate specs** in the workshop top bar once (do not repeat every turn).

        $WORKSHOP_CHAT_CONVERSATIONAL_RULES

        Tools: search_semantic, workshop_read_file (optional).


        ${eidosContextSummary()}
    """.trimIndent()

    fun eidosPlanModeInstructions(subfolderId: Long): String = """
        Panel Workshop — PLAN mode (subfolderId=$subfolderId)
        Active Eidos mode: PLAN — discuss and update specification markdown only.

        You may read **any** project file (search_semantic, workshop_read_file on specs or runtime).
        You may write/create/replace only: ${PLAN_MARKDOWN_FILES.joinToString(", ")}.
        Do NOT workshop_write_file on .html, .css, .js, or bridge.js — use Edit mode or **Build design** / **Build logic** buttons for code.

        Do not audit every file each turn — use search_semantic for spec passages; read/write only docs you must update.
        Capture decisions in STRUCTURE.md, FEATURES.md, FLOW.md, and DESIGN.md (DESIGN.md must include Android WebView layout rules).

        ${eidosContextSummary()}
    """.trimIndent()

    /** @deprecated Use [eidosPhaseEditInstructions]. */
    fun eidosLogicReviewEditInstructions(
        subfolderId: Long,
        phase: WorkshopProjectPhase? = WorkshopProjectPhase.LOGIC_REVIEW,
    ): String = eidosPhaseEditInstructions(
        subfolderId,
        phase ?: WorkshopProjectPhase.LOGIC_REVIEW,
    )

    /** @deprecated Use [eidosPhaseEditInstructions]. */
    fun eidosLogicReviewDebugInstructions(
        subfolderId: Long,
        phase: WorkshopProjectPhase? = WorkshopProjectPhase.LOGIC_REVIEW,
    ): String = eidosPhaseEditInstructions(
        subfolderId,
        phase ?: WorkshopProjectPhase.LOGIC_REVIEW,
    )

    /** @deprecated Use [eidosPhaseEditInstructions]. */
    fun eidosEditModeInstructions(
        subfolderId: Long,
        phase: WorkshopProjectPhase? = null,
    ): String = eidosPhaseEditInstructions(
        subfolderId,
        phase ?: WorkshopProjectPhase.COMPLETE,
    )

    /** @deprecated Use [eidosPhaseEditInstructions]. */
    fun eidosDebugModeInstructions(
        subfolderId: Long,
        phase: WorkshopProjectPhase? = null,
    ): String = eidosPhaseEditInstructions(
        subfolderId,
        phase ?: WorkshopProjectPhase.COMPLETE,
    )

    fun eidosChatModeInstructions(subfolderId: Long): String = """
        Panel Workshop — CHAT mode (subfolderId=$subfolderId)
        Active Eidos mode: CHAT — discuss only; do NOT modify any project file.

        $WORKSHOP_CHAT_CONVERSATIONAL_RULES

        Hard rules:
        - Do NOT call workshop_write_file or call_panel_function.

        Tools: search_semantic, workshop_read_file (optional), read_file (host-project files from search hits when panel is linked).


        ${eidosContextSummary()}
    """.trimIndent()

    fun newChatNudge(userTurns: Int, threshold: Int = WorkshopEidosModeResolver.USER_TURN_NUDGE_THRESHOLD): String =
        """
        Workshop conversation length: $userTurns user messages in this thread (nudge threshold: $threshold).
        This thread is long. For a new unrelated task, ask the user to tap New Chat in Eidos so old build/edit instructions do not mix with the current request.
        Only continue this thread if the user is clearly continuing the same task.
        """.trimIndent()

    /** Workshop “how to work” instructions (Eidos system context). */
    fun eidosWorkshopInstructions(subfolderId: Long): String = """
        Panel Workshop — BUILD mode (subfolderId=$subfolderId)
        Active Eidos mode: BUILD — greenfield or docs→code sync.
        Panel Workshop — how to work in this project (subfolderId=$subfolderId)

        Goal: Build a self-contained HTML/CSS/JS panel that runs inside OptimalX (Android WebView host).

        Order of work:
        1. Use README/spec excerpt in this prompt for intake; search_semantic for other spec passages — avoid full-file reads of large docs.
        2. Fill in the spec files: ${SPEC_MARKDOWN_FILES.filter { it != "README.md" }.joinToString(", ")}.
        3. Update ${RUNTIME_FILES.joinToString(", ")} so the panel matches the specs (keep bridge.js before script.js in HTML).

        Available tools: ${EIDOS_WORKSHOP_TOOL_NAMES.joinToString(", ")}. Use fileReferenceId from the project file list.


        Platform (Kotlin provides — panel code must not reimplement):
        - PanelHtmlComposer: inlines all .css/.js; removes external script/link href; loads composite HTML in WebView.
        - WorkshopPreviewPanel: JS on, scroll/touch safe inside editor pager, OptimalXPanelBridge injected on load.
        - bridge.js: local WebView↔Android only (not network). Defines getState/runAction; forwards to panelGetState/panelHandleAction in script.js.
        - script.js: all UI, math, game rules, DOM listeners. Do not redefine global getState/runAction here.

        Panel authoring rules:
        - Stay offline — no fetch/XHR/external APIs unless the user explicitly asked.
        - Use event delegation for dynamic UI; avoid calculateAll→updateRates→calculateAll recursion.
        - Do not use body { overflow-y: auto } or touchmove stopPropagation for scroll (platform handles pager).
        - runAction args shape: { action: "name", ... }; panelHandleAction reads args.action.
        - To play/test games with Eidos: user opens Preview; you call call_panel_function(getState) then runAction with actions defined in panelHandleAction.

        ${eidosContextSummary()}

        Unsupported without platform change: ${forbiddenApis.joinToString("; ") { "${it.id} (${it.status})" }}.
        If the panel needs one of these, tell the user and describe a platform issue — do not hack around silently.
    """.trimIndent()

    /** @deprecated Legacy monolithic greenfield build — use [workshopBuildDesignKickoffFooter] + [workshopBuildLogicKickoffFooter]. */
    @Deprecated("Removed in Panel Workshop v2 phased flow")
    fun workshopBuildKickoffFooter(): String = """
        Write STRUCTURE.md, FEATURES.md, FLOW.md, and DESIGN.md from the intake above (DESIGN.md must include Android WebView layout rules from platform spec). Then update index.html, style.css, bridge.js, and script.js to match DESIGN.md and PANEL_PLATFORM v$PLATFORM_VERSION: phone touch layout, bridge.js before script.js, platform bundles all JS (no CDN). Use workshop_write_file. No network/external APIs unless the user asked.
    """.trimIndent()

    /** Footer for DESIGN_BUILD → Build design (runtime shell only). System block: [eidosBuildDesignInstructions]. */
    fun workshopBuildDesignKickoffFooter(): String = """
        Build the full static design from accepted specs and the intake summary in system context.
        Update index.html, style.css, and script.js — use project scaffolds only as a starting point.
        Do NOT write any .md files. Interaction and game rules ship in the logic build phase.
        When done, tell the user to refresh Preview, verify every FLOW screen, then tap Accept design.
    """.trimIndent()

    /** Footer for LOGIC_BUILD → Build logic. System block: [eidosBuildLogicInstructions]. */
    fun workshopBuildLogicKickoffFooter(): String = """
        Wire full panel behavior from accepted design and specs in system context.
        Update script.js and bridge.js (and HTML/CSS only if needed for behavior). Do NOT write any .md files.
        Implement panelGetState, panelRestoreState (or __restoreState), and panelHandleAction as needed.
        Wire user edits to persistPanelStateDebounced() when the scaffold provides it.
        Test behavior in Preview; verify save/restore in Panel Gallery or an editor tab before Accept logic.
        $EIDOS_PERSISTENCE_POLICY
        When done, tell the user to test Preview, then tap Accept logic.
    """.trimIndent()

    fun workshopAlignDocsKickoffFooter(
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
    ): String = when (scope) {
        WorkshopDocAlignScope.DESIGN ->
            "Using the inlined code + specs below, update any spec .md that is out of date; skip files that already match. " +
                ".md only — no runtime edits, no file reads."
        WorkshopDocAlignScope.FINISH ->
            "Using the inlined code + specs below, update only the spec .md files that are out of date. " +
                ".md only — no file reads."
        WorkshopDocAlignScope.UPDATE ->
            "Using the inlined code + specs below, update only the spec .md files that are out of date. " +
                ".md only — no file reads."
    }

    /** Full one-shot Align user message: intent line, out-of-date spec list, and the inlined payload. */
    fun workshopAlignDocsInlineMessage(
        scope: WorkshopDocAlignScope,
        staleSpecs: List<String>,
        inlinePayload: String,
        updateSection: WorkshopUpdateSection? = null,
    ): String {
        val header = when (scope) {
            WorkshopDocAlignScope.DESIGN ->
                "Accept design — sync spec docs from the code below (no file reads):"
            WorkshopDocAlignScope.FINISH ->
                "Accept logic — sync spec docs from the code below (no file reads):"
            WorkshopDocAlignScope.UPDATE ->
                "Accept update — sync spec docs from the code below (no file reads):"
        }
        val staleLine = if (staleSpecs.isEmpty()) {
            "Out of date: none detected — reply that no changes were needed."
        } else {
            "Out of date (update only these): ${staleSpecs.joinToString(", ")}."
        }
        return buildString {
            append(header)
            append("\n")
            append(staleLine)
            append("\n")
            append(workshopAlignDocsKickoffFooter(scope, updateSection))
            append("\n\n")
            append(inlinePayload)
        }
    }

    /** Footer for INTAKE → Generate specs (spec .md only). System block: [eidosGenerateSpecsInstructions]. */
    fun workshopGenerateSpecsKickoffFooter(): String = """
        Write all spec files listed in your GENERATE SPECS instructions from the intake above.
        Use workshop_write_file for .md only — no runtime files yet.
        When done, briefly tell the user to open Docs to review and tap Accept specs.
    """.trimIndent()

    /** Reminder when user message asks to sync docs → code. */
    fun workshopDocsChangedReminder(): String =
        "Follow OptimalX panel platform v$PLATFORM_VERSION: keep bridge.js + script.js split; " +
            "logic in script.js; composite bundling assumes project-local files only."

    /** DESIGN.md seed section (layout + visual). */
    fun designMarkdownSection(): String = WorkshopAndroidLayoutRules.MARKDOWN_SECTION

    fun platformIssueTemplate(): String = """
        ## Panel platform issue

        **Platform version:** $PLATFORM_VERSION
        **Category:** bridge | webview | composer | layout-rules | security | new-capability
        **Requested capability:**
        **Why panel JS is insufficient:**
        **Workshop subfolderId:**
        **Repro steps:**
        **Console / verify output:**
        **Minimal panel snippet:**
        **Suggested Kotlin / bridge API:**
    """.trimIndent()

    /**
     * Static project file checks for Eidos or future panel_verify tooling.
     * [fileNames] should be basenames only (e.g. from FileReference.fileName).
     */
    fun validateProjectFiles(fileNames: List<String>): ProjectValidation {
        val names = fileNames.map { it.trim() }.filter { it.isNotEmpty() }
        val lower = names.map { it.lowercase() }.toSet()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val hasHtml = lower.any { it.endsWith(".html") }
        if (!hasHtml) {
            errors += "Missing HTML entry file (expected index.html or another .html file)."
        }
        if (!lower.contains("script.js")) {
            warnings += "Missing script.js — panel app logic will not run."
        }
        if (!lower.contains("bridge.js")) {
            warnings += "Missing bridge.js — Eidos call_panel_function may not work (add scaffold bridge.js)."
        }
        if (!lower.contains("index.html") && hasHtml) {
            warnings += "No index.html; platform will use first .html file in project."
        }
        SPEC_MARKDOWN_FILES.forEach { spec ->
            if (!lower.contains(spec.lowercase())) {
                warnings += "Missing spec file $spec (recommended for workshop projects)."
            }
        }

        return ProjectValidation(
            ok = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
        )
    }

    /**
     * Static checks for panel-authored persistence hooks in script.js (and optional bridge.js).
     * Warnings only — display-only panels may omit hooks until Finish gate (Phase 2).
     */
    fun validatePersistenceContract(
        scriptJs: String?,
        bridgeJs: String? = null,
    ): ProjectValidation {
        val warnings = mutableListOf<String>()
        val script = scriptJs?.trim().orEmpty()
        if (script.isBlank()) {
            warnings += "script.js is empty or missing — panel cannot persist user data."
            return ProjectValidation(ok = true, errors = emptyList(), warnings = warnings)
        }
        if (!script.contains("panelGetState")) {
            warnings +=
                "Missing panelGetState in script.js — OptimalX will not save user state " +
                "(scores, form fields, game progress)."
        }
        val hasRestore = script.contains("panelRestoreState") ||
            (script.contains("__restoreState") && script.contains("panelHandleAction"))
        if (!hasRestore) {
            warnings +=
                "Missing panelRestoreState (or __restoreState in panelHandleAction) — " +
                "saved state will not reload after app restart."
        }
        val bridge = bridgeJs?.trim().orEmpty()
        if (bridge.isNotBlank() &&
            !bridge.contains("savePersistedState") &&
            !bridge.contains("loadPersistedState")
        ) {
            warnings +=
                "bridge.js lacks savePersistedState/loadPersistedState helpers — " +
                "use platform scaffold bridge.js or call OptimalXPanelBridge from script.js."
        }
        return ProjectValidation(ok = true, errors = emptyList(), warnings = warnings)
    }

    /**
     * Heuristic for Phase 2 Finish gate: panel likely needs persistence hooks.
     */
    fun panelScriptLooksStateful(scriptJs: String?): Boolean {
        val lower = scriptJs?.trim()?.lowercase().orEmpty()
        if (lower.isBlank()) return false
        val signals = listOf(
            "highscore",
            "high_score",
            "savepoint",
            "save_point",
            "localstorage",
            "<input",
            "<textarea",
            "contenteditable",
            "gamecanvas",
            "getelementbyid",
            "panelstate",
            "counter",
            "score",
            "persistpanelstate",
        )
        return signals.any { lower.contains(it) }
    }

    fun featuresMdMentionsPersistence(featuresMd: String?): Boolean {
        val lower = featuresMd?.trim()?.lowercase().orEmpty()
        if (lower.isBlank()) return false
        return listOf(
            "persist",
            "persistence",
            "high score",
            "highscore",
            "save point",
            "savepoint",
            "saved state",
            "auto-save",
            "autosave",
        ).any { lower.contains(it) }
    }

    /**
     * Finish gate: block Accept logic when the panel looks stateful but lacks persistence hooks.
     */
    fun evaluatePersistenceForFinish(
        scriptJs: String?,
        bridgeJs: String? = null,
        featuresMd: String? = null,
    ): PersistenceFinishEvaluation {
        val requiresPersistence =
            panelScriptLooksStateful(scriptJs) || featuresMdMentionsPersistence(featuresMd)
        val validation = validatePersistenceContract(scriptJs, bridgeJs)
        return PersistenceFinishEvaluation(
            requiresPersistence = requiresPersistence,
            validation = validation,
        )
    }

    fun formatPersistenceValidationReport(validation: ProjectValidation): String = buildString {
        appendLine("Panel persistence contract (platform v$PLATFORM_VERSION):")
        if (validation.warnings.isEmpty()) {
            append("ok — panelGetState and restore hooks look present.")
            return@buildString
        }
        validation.warnings.forEach { appendLine("WARNING: $it") }
    }.trim()

    /**
     * Optional spec length warnings when [specContents] is available (fileName → text).
     */
    fun validateSpecMarkdownCaps(specContents: Map<String, String>): List<String> =
        specContents.mapNotNull { (name, text) -> WorkshopSpecValidation.validateCharCap(name, text) }

    /** Human-readable validation report for Eidos context. */
    fun isMarkdownWorkshopFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".md") ||
            PLAN_MARKDOWN_FILES.any { it.equals(fileName, ignoreCase = true) }
    }

    /** `.md` files Plan mode may create, read, or write. */
    fun isPlanMarkdownFile(fileName: String): Boolean =
        PLAN_MARKDOWN_FILES.any { it.equals(fileName, ignoreCase = true) }

    fun formatValidationReport(validation: ProjectValidation): String = buildString {
        appendLine("Panel project validation (platform v$PLATFORM_VERSION):")
        if (validation.errors.isEmpty() && validation.warnings.isEmpty()) {
            append("ok — runtime files look present.")
            return@buildString
        }
        validation.errors.forEach { appendLine("ERROR: $it") }
        validation.warnings.forEach { appendLine("WARNING: $it") }
    }.trim()
}
