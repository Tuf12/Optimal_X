package com.example.optimalx.data.revision

import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopReviewPolicyTest {

    @Test
    fun nullPhase_neverReviews() {
        for (mode in WorkshopEidosMode.entries) {
            assertFalse(
                "phase=null mode=$mode",
                WorkshopReviewPolicy.shouldReview(null, mode),
            )
        }
        assertFalse(WorkshopReviewPolicy.shouldReview(null, null))
    }

    @Test
    fun buildFamilyMode_neverReviews() {
        val phases = WorkshopProjectPhase.entries
        val buildModes = listOf(
            WorkshopEidosMode.BUILD,
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopEidosMode.BUILD_LOGIC,
        )
        for (phase in phases) {
            for (mode in buildModes) {
                assertFalse(
                    "phase=$phase mode=$mode should auto-accept",
                    WorkshopReviewPolicy.shouldReview(phase, mode),
                )
            }
        }
    }

    @Test
    fun buildPhases_autoAcceptEvenForNonBuildMode() {
        for (phase in listOf(WorkshopProjectPhase.DESIGN_BUILD, WorkshopProjectPhase.LOGIC_BUILD)) {
            assertFalse(
                "phase=$phase mode=EDIT should still auto-accept",
                WorkshopReviewPolicy.shouldReview(phase, WorkshopEidosMode.EDIT),
            )
            assertFalse(
                "phase=$phase mode=DEBUG should still auto-accept",
                WorkshopReviewPolicy.shouldReview(phase, WorkshopEidosMode.DEBUG),
            )
        }
    }

    @Test
    fun intakeAndSpecReview_doNotReview() {
        for (phase in listOf(WorkshopProjectPhase.INTAKE, WorkshopProjectPhase.SPEC_REVIEW)) {
            for (mode in listOf(
                WorkshopEidosMode.CHAT,
                WorkshopEidosMode.PLAN,
                WorkshopEidosMode.EDIT,
            )) {
                assertFalse(
                    "phase=$phase mode=$mode",
                    WorkshopReviewPolicy.shouldReview(phase, mode),
                )
            }
        }
    }

    @Test
    fun reviewPhases_reviewForNonBuildMode() {
        val reviewPhases = listOf(
            WorkshopProjectPhase.DESIGN_REVIEW,
            WorkshopProjectPhase.LOGIC_REVIEW,
            WorkshopProjectPhase.COMPLETE,
            WorkshopProjectPhase.UPDATE,
        )
        val modes = listOf(
            WorkshopEidosMode.EDIT,
            WorkshopEidosMode.DEBUG,
            WorkshopEidosMode.DESIGN,
            WorkshopEidosMode.CHAT,
            WorkshopEidosMode.PLAN,
            null,
        )
        for (phase in reviewPhases) {
            for (mode in modes) {
                assertTrue(
                    "phase=$phase mode=$mode should route through review",
                    WorkshopReviewPolicy.shouldReview(phase, mode),
                )
            }
        }
    }
}
