package com.example.optimalx.data.revision

import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase

/**
 * Decides whether a workshop file write should be routed through the pending review
 * pipeline or applied directly to disk.
 *
 * Rules (mirrors `app/docs/implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md` Phase 1c):
 *
 * - **Build kickoffs** (`BUILD_DESIGN` / `BUILD_LOGIC`, or `DESIGN_BUILD` /
 *   `LOGIC_BUILD` phases) auto-accept. The user reviews via Preview, not per-file diffs.
 * - **Intake / Spec review** never produce runtime file writes; if they ever did,
 *   no review baseline exists, so we let them through.
 * - **Everything else** — `DESIGN_REVIEW`, `LOGIC_REVIEW`, `COMPLETE`, `UPDATE` —
 *   routes through review.
 */
object WorkshopReviewPolicy {

    fun shouldReview(phase: WorkshopProjectPhase?, mode: WorkshopEidosMode?): Boolean {
        if (phase == null) return false
        if (mode?.isBuildFamily == true) return false
        return when (phase) {
            WorkshopProjectPhase.INTAKE,
            WorkshopProjectPhase.SPEC_REVIEW,
            WorkshopProjectPhase.DESIGN_BUILD,
            WorkshopProjectPhase.LOGIC_BUILD,
            -> false

            WorkshopProjectPhase.DESIGN_REVIEW,
            WorkshopProjectPhase.LOGIC_REVIEW,
            WorkshopProjectPhase.COMPLETE,
            WorkshopProjectPhase.UPDATE,
            -> true
        }
    }
}
