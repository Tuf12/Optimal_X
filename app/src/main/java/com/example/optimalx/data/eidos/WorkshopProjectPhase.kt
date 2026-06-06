package com.example.optimalx.data.eidos

/**
 * Panel Workshop project lifecycle phase (gates primary actions and Eidos context).
 * See app/docs/architecture/PANEL_WORKSHOP.md and WORKSHOP_MODES.md.
 */
enum class WorkshopProjectPhase {
    INTAKE,
    SPEC_REVIEW,
    DESIGN_BUILD,
    DESIGN_REVIEW,
    LOGIC_BUILD,
    LOGIC_REVIEW,
    COMPLETE,
    UPDATE,
    ;

    val displayName: String
        get() = when (this) {
            INTAKE -> "Intake"
            SPEC_REVIEW -> "Spec review"
            DESIGN_BUILD -> "Design build"
            DESIGN_REVIEW -> "Design review"
            LOGIC_BUILD -> "Logic build"
            LOGIC_REVIEW -> "Logic review"
            COMPLETE -> "Complete"
            UPDATE -> "Update/edit"
        }

    /** Debug mode chip and QuickJS (Phase 3.6) are available from logic build onward. */
    val allowsDebugMode: Boolean
        get() = when (this) {
            LOGIC_BUILD, LOGIC_REVIEW, COMPLETE, UPDATE -> true
            else -> false
        }

    /**
     * Spec `.md` **writes** are deferred until Accept gates (doc align). Reads stay available
     * in Edit so Eidos can follow [PanelPlatformSpec.IMPLEMENTATION_PLAN_MD] and compare code.
     */
    fun freezesMarkdownWritesForEidos(updateSection: WorkshopUpdateSection? = null): Boolean =
        this == DESIGN_REVIEW || this == UPDATE

    /** @deprecated Use [freezesMarkdownWritesForEidos] — reads are no longer blocked. */
    fun freezesMarkdownForEidos(updateSection: WorkshopUpdateSection? = null): Boolean =
        freezesMarkdownWritesForEidos(updateSection)

    /** User-visible phase label (unified Update — no section suffix). */
    @Suppress("UNUSED_PARAMETER")
    fun phaseLabel(updateSection: WorkshopUpdateSection? = null): String = displayName

    /** Preview bridge calls are irrelevant before layout exists. */
    val allowsCallPanelFunction: Boolean
        get() = when (this) {
            INTAKE, SPEC_REVIEW -> false
            else -> true
        }

    /** Default chip when the user has not picked one (Chat-first except spec review → Plan). */
    fun defaultEidosMode(updateSection: WorkshopUpdateSection? = null): WorkshopEidosMode = when (this) {
        INTAKE -> WorkshopEidosMode.CHAT
        SPEC_REVIEW -> WorkshopEidosMode.PLAN
        DESIGN_BUILD,
        DESIGN_REVIEW,
        LOGIC_BUILD,
        LOGIC_REVIEW,
        COMPLETE,
        UPDATE,
        -> WorkshopEidosMode.CHAT
    }

    /** Normalize persisted mode for the current phase (user chips + internal build kickoff). */
    fun resolveStoredEidosMode(
        stored: WorkshopEidosMode?,
        updateSection: WorkshopUpdateSection? = null,
        activeBuildKickoff: WorkshopBuildKickoff? = null,
        @Suppress("UNUSED_PARAMETER") designLayoutReady: Boolean = false,
        @Suppress("UNUSED_PARAMETER") logicBehaviorReady: Boolean = false,
    ): WorkshopEidosMode {
        if (stored != null) {
            if (this == DESIGN_BUILD &&
                activeBuildKickoff == WorkshopBuildKickoff.DESIGN &&
                (stored == WorkshopEidosMode.BUILD_DESIGN || stored == WorkshopEidosMode.BUILD)
            ) {
                return WorkshopEidosMode.BUILD_DESIGN
            }
            if (this == LOGIC_BUILD &&
                activeBuildKickoff == WorkshopBuildKickoff.LOGIC &&
                (stored == WorkshopEidosMode.BUILD_LOGIC || stored == WorkshopEidosMode.BUILD)
            ) {
                return WorkshopEidosMode.BUILD_LOGIC
            }
            if (this == UPDATE &&
                activeBuildKickoff == WorkshopBuildKickoff.PLAN &&
                stored == WorkshopEidosMode.BUILD_PLAN
            ) {
                return WorkshopEidosMode.BUILD_PLAN
            }
            val chip = WorkshopEidosMode.normalizeToUserChip(stored)
            if (chip in selectorModes(updateSection)) return chip
        }
        return defaultEidosMode(updateSection)
    }

    /** User-visible mode chips (Chat · Plan · Edit). Intake is Chat-only. */
    @Suppress("UNUSED_PARAMETER")
    fun selectorModes(updateSection: WorkshopUpdateSection? = null): List<WorkshopEidosMode> = when (this) {
        INTAKE -> listOf(WorkshopEidosMode.CHAT)
        else -> WorkshopEidosMode.USER_CHIP_MODES
    }

    companion object {
        fun fromStored(value: String?): WorkshopProjectPhase? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

        /**
         * Lazy migration from v1 prefs when [project_phase] was never written.
         */
        fun resolveFromLegacy(storedPhaseName: String?, initialBuildSent: Boolean): WorkshopProjectPhase {
            fromStored(storedPhaseName)?.let { return it }
            return if (initialBuildSent) COMPLETE else INTAKE
        }
    }
}

/**
 * Legacy scoped-update enum — **not shown in UI** (unified Update/edit per recovery plan).
 * Retained for prefs migration and optional doc-align scoping only.
 */
enum class WorkshopUpdateSection {
    SPECS,
    DESIGN,
    LOGIC,
    ;

    val displayName: String
        get() = when (this) {
            SPECS -> "Specs"
            DESIGN -> "Design"
            LOGIC -> "Logic"
        }

    companion object {
        fun fromStored(value: String?): WorkshopUpdateSection? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }
}
