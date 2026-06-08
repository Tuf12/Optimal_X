package com.example.optimalx.data.eidos

import com.example.optimalx.data.repository.FolderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPlatformSpecTest {

    @Test
    fun platformVersion_isPositive() {
        assertEquals(1, PanelPlatformSpec.PLATFORM_VERSION)
    }

    @Test
    fun validateProjectFiles_okForStandardScaffold() {
        val result = PanelPlatformSpec.validateProjectFiles(
            PanelPlatformSpec.SPEC_MARKDOWN_FILES + PanelPlatformSpec.RUNTIME_FILES,
        )
        assertTrue(result.ok)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validateProjectFiles_errorsWhenNoHtml() {
        val result = PanelPlatformSpec.validateProjectFiles(listOf("script.js", "bridge.js"))
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("HTML", ignoreCase = true) })
    }

    @Test
    fun validateProjectFiles_warnsWhenMissingBridge() {
        val result = PanelPlatformSpec.validateProjectFiles(listOf("index.html", "script.js"))
        assertTrue(result.warnings.any { it.contains("bridge.js", ignoreCase = true) })
    }

    @Test
    fun eidosInstructionsForMode_includesRetrievalPolicyOnce() {
        val instructions = PanelPlatformSpec.eidosInstructionsForMode(
            WorkshopEidosMode.BUILD_DESIGN,
            subfolderId = 1L,
            phase = WorkshopProjectPhase.DESIGN_BUILD,
            docAlignScope = null,
            updateSection = null,
        )
        val marker = "Call search_semantic(query) first"
        assertEquals(1, instructions.split(marker).size - 1)
    }

    @Test
    fun eidosContextSummary_mentionsPlatformVersion() {
        assertTrue(PanelPlatformSpec.eidosContextSummary().contains("v${PanelPlatformSpec.PLATFORM_VERSION}"))
    }

    // ── Phase 4: DIFF_REVIEW prompt wiring ──────────────────────────────────

    @Test
    fun toolNames_includeWorkshopReplaceString() {
        assertTrue(
            "EIDOS_WORKSHOP_TOOL_NAMES should advertise workshop_replace_string after Phase 4",
            PanelPlatformSpec.EIDOS_WORKSHOP_TOOL_NAMES.contains("workshop_replace_string"),
        )
    }

    @Test
    fun patchPolicy_mentionsReplaceStringAndUniqueness() {
        val policy = PanelPlatformSpec.EIDOS_WORKSHOP_PATCH_POLICY
        assertTrue(policy.contains("workshop_replace_string"))
        assertTrue(policy.contains("unique"))
    }

    @Test
    fun reviewNotice_phrasingTellsModelEditsAreQueued() {
        val notice = PanelPlatformSpec.EIDOS_WORKSHOP_REVIEW_QUEUE_NOTICE
        assertTrue(notice.contains("queued for review"))
        assertTrue(notice.contains("Do NOT claim"))
    }

    @Test
    fun reviewNoticeFor_emptyInBuildPhases() {
        assertEquals(
            "",
            PanelPlatformSpec.reviewNoticeFor(
                WorkshopProjectPhase.DESIGN_BUILD,
                WorkshopEidosMode.BUILD_DESIGN,
            ),
        )
        assertEquals(
            "",
            PanelPlatformSpec.reviewNoticeFor(
                WorkshopProjectPhase.LOGIC_BUILD,
                WorkshopEidosMode.BUILD_LOGIC,
            ),
        )
        // EDIT inside LOGIC_BUILD still auto-accepts (phase wins over mode for build phases).
        assertEquals(
            "",
            PanelPlatformSpec.reviewNoticeFor(
                WorkshopProjectPhase.LOGIC_BUILD,
                WorkshopEidosMode.EDIT,
            ),
        )
    }

    @Test
    fun reviewNoticeFor_presentInReviewPhases() {
        val designReview = PanelPlatformSpec.reviewNoticeFor(
            WorkshopProjectPhase.DESIGN_REVIEW,
            WorkshopEidosMode.DESIGN,
        )
        assertTrue(designReview.contains("queued for review"))

        val logicReview = PanelPlatformSpec.reviewNoticeFor(
            WorkshopProjectPhase.LOGIC_REVIEW,
            WorkshopEidosMode.EDIT,
        )
        assertTrue(logicReview.contains("queued for review"))

        val update = PanelPlatformSpec.reviewNoticeFor(
            WorkshopProjectPhase.UPDATE,
            WorkshopEidosMode.DEBUG,
        )
        assertTrue(update.contains("queued for review"))
    }

    @Test
    fun buildDesignPrompt_hasNoReviewQueueNotice() {
        val prompt = PanelPlatformSpec.eidosBuildDesignInstructions(subfolderId = 7L)
        assertTrue("build_design should advertise workshop_replace_string", prompt.contains("workshop_replace_string"))
        assertFalse(
            "build_design must not claim edits are queued — they auto-accept: $prompt",
            prompt.contains("queued for review"),
        )
    }

    @Test
    fun buildLogicPrompt_includesPatchPolicy_withoutQueueNotice() {
        val prompt = PanelPlatformSpec.eidosBuildLogicInstructions(subfolderId = 7L)
        assertTrue(prompt.contains("workshop_replace_string"))
        assertFalse(
            "build_logic must not claim edits are queued — they auto-accept: $prompt",
            prompt.contains("queued for review"),
        )
    }

    @Test
    fun phaseEditPrompt_designReview_includesPatchPolicyAndDiffReviewNotice() {
        val prompt = PanelPlatformSpec.eidosPhaseEditInstructions(
            subfolderId = 7L,
            phase = WorkshopProjectPhase.DESIGN_REVIEW,
        )
        assertTrue(prompt.contains("workshop_replace_string"))
        assertTrue(prompt.contains("Diff Review"))
        assertTrue(prompt.contains("queued for review"))
    }

    @Test
    fun phaseEditPrompt_queueNotice_dependsOnPhase() {
        val inLogicReview = PanelPlatformSpec.eidosPhaseEditInstructions(
            subfolderId = 7L,
            phase = WorkshopProjectPhase.LOGIC_REVIEW,
        )
        assertTrue(inLogicReview.contains("Diff Review"))

        val inLogicBuild = PanelPlatformSpec.eidosPhaseEditInstructions(
            subfolderId = 7L,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        assertFalse(
            "LOGIC_BUILD auto-accepts; Diff Review notice should be absent: $inLogicBuild",
            inLogicBuild.contains("Diff Review"),
        )
    }

    @Test
    fun planMode_allowsImplementationPlanArtifact() {
        val prompt = PanelPlatformSpec.eidosPlanModeInstructions(subfolderId = 1L)
        assertTrue(prompt.contains(PanelPlatformSpec.IMPLEMENTATION_PLAN_MD))
        assertTrue(prompt.contains(PanelPlatformSpec.EIDOS_PLAN_IMPLEMENTATION_ARTIFACT_SECTION.take(40)))
    }

    @Test
    fun chatPrompts_avoidRepeatedAcceptNagging() {
        val designChat = PanelPlatformSpec.eidosDesignReviewChatInstructions(subfolderId = 1L)
        assertFalse(designChat.contains("Accept design"))
        assertTrue(designChat.contains(PanelPlatformSpec.WORKSHOP_CHAT_MODE_AVAILABILITY))

        val updateChat = PanelPlatformSpec.eidosUpdateChatInstructionsUnified(subfolderId = 1L)
        assertFalse(updateChat.contains("Accept update"))
    }

    @Test
    fun updateLogicPrompt_includesPatchPolicyAndQueueNotice() {
        val prompt = PanelPlatformSpec.eidosUpdateLogicInstructions(subfolderId = 7L)
        assertTrue(prompt.contains("workshop_replace_string"))
        assertTrue(prompt.contains("queued for review"))
    }

    // ── Panel state persistence (Phase 1) ───────────────────────────────────

    @Test
    fun capabilities_includePanelStatePersistence() {
        assertTrue(
            PanelPlatformSpec.capabilities.any { it.id == "panel_state_persistence" },
        )
    }

    @Test
    fun eidosPersistencePolicy_requiresPanelGetStateAndRestore() {
        val policy = PanelPlatformSpec.EIDOS_PERSISTENCE_POLICY
        assertTrue(policy.contains("panelGetState"))
        assertTrue(policy.contains("panelRestoreState"))
        assertTrue(policy.contains("does NOT read the DOM"))
    }

    @Test
    fun validatePersistenceContract_warnsWhenHooksMissing() {
        val result = PanelPlatformSpec.validatePersistenceContract(
            scriptJs = "console.log('hello');",
            bridgeJs = null,
        )
        assertTrue(result.warnings.any { it.contains("panelGetState", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("panelRestoreState", ignoreCase = true) })
    }

    @Test
    fun validatePersistenceContract_okForScaffoldScript() {
        val result = PanelPlatformSpec.validatePersistenceContract(
            scriptJs = FolderRepository.WORKSHOP_SCRIPT_JS_SCAFFOLD,
            bridgeJs = FolderRepository.WORKSHOP_BRIDGE_JS_SCAFFOLD,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun bridgeScaffold_includesDebouncedPersistHelpers() {
        val bridge = FolderRepository.WORKSHOP_BRIDGE_JS_SCAFFOLD
        assertTrue(bridge.contains("persistPanelStateDebounced"))
        assertTrue(bridge.contains("flushPersistedPanelState"))
        assertTrue(bridge.contains("visibilitychange"))
    }

    @Test
    fun scriptScaffold_includesPanelRestoreStateAndDebouncedInput() {
        val script = FolderRepository.WORKSHOP_SCRIPT_JS_SCAFFOLD
        assertTrue(script.contains("panelRestoreState"))
        assertTrue(script.contains("persistPanelStateDebounced"))
    }

    @Test
    fun buildLogicPrompt_includesPersistencePolicy() {
        val prompt = PanelPlatformSpec.eidosBuildLogicInstructions(subfolderId = 1L)
        assertTrue(prompt.contains("panelGetState"))
        assertTrue(prompt.contains("persistPanelStateDebounced"))
    }

    @Test
    fun evaluatePersistenceForFinish_blocksStatefulPanelWithoutHooks() {
        val eval = PanelPlatformSpec.evaluatePersistenceForFinish(
            scriptJs = "const score = 0; document.getElementById('gameCanvas');",
            bridgeJs = null,
            featuresMd = null,
        )
        assertTrue(eval.requiresPersistence)
        assertTrue(eval.shouldBlockFinish)
    }

    @Test
    fun evaluatePersistenceForFinish_allowsDisplayOnlyScript() {
        val eval = PanelPlatformSpec.evaluatePersistenceForFinish(
            scriptJs = "function renderTitle() { document.title = 'Hello'; }",
            bridgeJs = null,
            featuresMd = null,
        )
        assertFalse(eval.requiresPersistence)
        assertFalse(eval.shouldBlockFinish)
    }

    @Test
    fun evaluatePersistenceForFinish_allowsStatefulPanelWithHooks() {
        val eval = PanelPlatformSpec.evaluatePersistenceForFinish(
            scriptJs = FolderRepository.WORKSHOP_SCRIPT_JS_SCAFFOLD,
            bridgeJs = FolderRepository.WORKSHOP_BRIDGE_JS_SCAFFOLD,
            featuresMd = "## Persistence\n\nSaves high scores.",
        )
        assertTrue(eval.requiresPersistence)
        assertFalse(eval.shouldBlockFinish)
    }

    @Test
    fun featuresMdMentionsPersistence_detectsSaveKeywords() {
        assertTrue(
            PanelPlatformSpec.featuresMdMentionsPersistence("Tracks high scores across sessions."),
        )
    }

    @Test
    fun workshopBuildLogicKickoffFooter_mentionsPersistence() {
        assertTrue(PanelPlatformSpec.workshopBuildLogicKickoffFooter().contains("panelRestoreState"))
    }

    @Test
    fun panelScriptLooksStateful_detectsInteractiveSignals() {
        assertTrue(
            PanelPlatformSpec.panelScriptLooksStateful(
                "const score = 0; document.getElementById('gameCanvas');",
            ),
        )
        assertFalse(PanelPlatformSpec.panelScriptLooksStateful("renderStaticTitle();"))
    }
}
