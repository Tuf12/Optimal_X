package com.example.optimalx.data.eidos

/**
 * User-visible Eidos behavior for Panel Workshop chat.
 * See [PANEL_WORKSHOP_RECOVERY_PLAN.md] — user chips are [CHAT], [PLAN], [EDIT] only.
 */
enum class WorkshopEidosMode {
    PLAN,
    /** @deprecated Legacy monolithic build — internal kickoff only. */
    BUILD,
    /** Internal — primary **Build design** action; not a user chip. */
    BUILD_DESIGN,
    /** Internal — primary **Build logic** action; not a user chip. */
    BUILD_LOGIC,
    /** Internal — **Build plan** kickoff from UPDATE (executes [IMPLEMENTATION_PLAN_MD]). */
    BUILD_PLAN,
    /** @deprecated Maps to [EDIT] for prompts and prefs migration. */
    DESIGN,
    EDIT,
    /** @deprecated Maps to [EDIT]; QuickJS available in [EDIT] during logic-build phases. */
    DEBUG,
    CHAT,
    ;

    val displayName: String
        get() = when (this) {
            PLAN -> "Plan"
            BUILD -> "Build"
            BUILD_DESIGN -> "Build design"
            BUILD_LOGIC -> "Build logic"
            BUILD_PLAN -> "Build plan"
            DESIGN -> "Design"
            DEBUG -> "Debug"
            EDIT -> "Edit"
            CHAT -> "Chat"
        }

    /** User-selectable chips in the Eidos sheet (Chat · Plan · Edit). */
    val visibleInSelector: Boolean
        get() = this in USER_CHIP_MODES

    val isBuildFamily: Boolean
        get() = this == BUILD || this == BUILD_DESIGN || this == BUILD_LOGIC || this == BUILD_PLAN

    /** UPDATE maintenance kickoff — runtime execution from implementation plan. */
    val isPlanBuildKickoff: Boolean
        get() = this == BUILD_PLAN

  companion object {
        /** Chips shown in the workshop Eidos sheet. */
        val USER_CHIP_MODES: List<WorkshopEidosMode> = listOf(CHAT, PLAN, EDIT)

        val selectorEntries: List<WorkshopEidosMode>
            get() = USER_CHIP_MODES

        fun fromStored(value: String?): WorkshopEidosMode? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

        /**
         * Maps legacy/internal modes to a user chip for display and prefs.
         * Preserves [BUILD_DESIGN] / [BUILD_LOGIC] when still used for kickoff sends.
         */
        fun normalizeToUserChip(mode: WorkshopEidosMode): WorkshopEidosMode = when (mode) {
            CHAT, PLAN, EDIT -> mode
            BUILD_DESIGN, BUILD_LOGIC, BUILD_PLAN, BUILD, DESIGN, DEBUG -> EDIT
        }

        /** Mode used for prompt/tool routing (keeps internal build modes during kickoff). */
        fun effectiveForInstructions(
            mode: WorkshopEidosMode,
            phase: WorkshopProjectPhase?,
            docAlignScope: WorkshopDocAlignScope?,
        ): WorkshopEidosMode {
            if (docAlignScope != null && mode == PLAN) return PLAN
            if (phase == WorkshopProjectPhase.DESIGN_BUILD &&
                (mode == BUILD_DESIGN || mode == BUILD)
            ) {
                return BUILD_DESIGN
            }
            if (phase == WorkshopProjectPhase.LOGIC_BUILD &&
                (mode == BUILD_LOGIC || mode == BUILD)
            ) {
                return BUILD_LOGIC
            }
            if (phase == WorkshopProjectPhase.UPDATE && mode == BUILD_PLAN) {
                return BUILD_PLAN
            }
            return normalizeToUserChip(mode)
        }
    }
}
