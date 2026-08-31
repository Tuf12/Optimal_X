package com.example.optimalx.data.eidos

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WorkshopFileAccessPolicyTest {

    @Before
    fun setUp() {
        WorkshopEidosSession.end()
    }

    @After
    fun tearDown() {
        WorkshopEidosSession.end()
    }

    @Test
    fun coerceModeForPhase_docAlignForcesPlanWhenStoredEdit() {
        assertEquals(
            WorkshopEidosMode.PLAN,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.EDIT,
                WorkshopProjectPhase.LOGIC_BUILD,
                docAlignScope = WorkshopDocAlignScope.DESIGN,
            ),
        )
    }

    @Test
    fun editMode_docAlignAllowsSpecMdWritesDuringLogicBuild() {
        WorkshopEidosSession.begin(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.LOGIC_BUILD,
            docAlignScope = WorkshopDocAlignScope.DESIGN,
        )
        assertNull(WorkshopFileAccessPolicy.writeFailure("DESIGN.md"))
        assertNotNull(WorkshopFileAccessPolicy.writeFailure("script.js"))
    }

    @Test
    fun planMode_writesSpecMdDuringDesignReview() {
        WorkshopEidosSession.begin(WorkshopEidosMode.PLAN, WorkshopProjectPhase.DESIGN_REVIEW)
        assertNull(WorkshopFileAccessPolicy.writeFailure("FLOW.md"))
        assertNull(WorkshopFileAccessPolicy.writeFailure("README.md"))
    }

    @Test
    fun planMode_readsSpecMdDuringDesignReview() {
        WorkshopEidosSession.begin(WorkshopEidosMode.PLAN, WorkshopProjectPhase.DESIGN_REVIEW)
        assertNull(WorkshopFileAccessPolicy.markdownReadFailure("README.md"))
    }

    @Test
    fun planMode_rejectsRuntimeWrites() {
        WorkshopEidosSession.begin(WorkshopEidosMode.PLAN, WorkshopProjectPhase.SPEC_REVIEW)
        assertNotNull(WorkshopFileAccessPolicy.writeFailure("script.js"))
    }

    @Test
    fun editMode_blocksSpecMdWritesButAllowsReadsDuringDesignReview() {
        WorkshopEidosSession.begin(WorkshopEidosMode.EDIT, WorkshopProjectPhase.DESIGN_REVIEW)
        assertNotNull(WorkshopFileAccessPolicy.writeFailure("DESIGN.md"))
        assertNull(WorkshopFileAccessPolicy.markdownReadFailure("DESIGN.md"))
    }

    @Test
    fun editMode_readsSpecMdDuringUpdate() {
        WorkshopEidosSession.begin(WorkshopEidosMode.EDIT, WorkshopProjectPhase.UPDATE)
        assertNull(WorkshopFileAccessPolicy.markdownReadFailure("README.md"))
        assertNotNull(WorkshopFileAccessPolicy.writeFailure("README.md"))
    }

    @Test
    fun editMode_allowsRuntimeWritesDuringDesignReview() {
        WorkshopEidosSession.begin(WorkshopEidosMode.EDIT, WorkshopProjectPhase.DESIGN_REVIEW)
        assertNull(WorkshopFileAccessPolicy.writeFailure("style.css"))
    }

    @Test
    fun planMode_writesSpecMdDuringUpdate() {
        WorkshopEidosSession.begin(WorkshopEidosMode.PLAN, WorkshopProjectPhase.UPDATE)
        assertNull(WorkshopFileAccessPolicy.writeFailure("FLOW.md"))
    }

    @Test
    fun legacyDebugMode_routesLikeEditForMarkdownFreeze() {
        WorkshopEidosSession.begin(WorkshopEidosMode.DEBUG, WorkshopProjectPhase.UPDATE)
        assertNotNull(WorkshopFileAccessPolicy.writeFailure("README.md"))
        assertNull(WorkshopFileAccessPolicy.writeFailure("script.js"))
    }
}
