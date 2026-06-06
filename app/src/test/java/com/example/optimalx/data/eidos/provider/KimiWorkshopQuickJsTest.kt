package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiWorkshopQuickJsTest {

    @Test
    fun workshopQuickJsExposureAllowed_editDuringLogicBuildOnward() {
        assertFalse(
            KimiFormulaToolService.workshopQuickJsExposureAllowed(
                isPanelWorkshop = true,
                mode = WorkshopEidosMode.EDIT,
                phase = WorkshopProjectPhase.DESIGN_REVIEW,
            ),
        )
        assertTrue(
            KimiFormulaToolService.workshopQuickJsExposureAllowed(
                isPanelWorkshop = true,
                mode = WorkshopEidosMode.EDIT,
                phase = WorkshopProjectPhase.LOGIC_REVIEW,
            ),
        )
        assertFalse(
            KimiFormulaToolService.workshopQuickJsExposureAllowed(
                isPanelWorkshop = true,
                mode = WorkshopEidosMode.EDIT,
                phase = WorkshopProjectPhase.SPEC_REVIEW,
            ),
        )
        assertTrue(
            KimiFormulaToolService.workshopQuickJsExposureAllowed(
                isPanelWorkshop = true,
                mode = WorkshopEidosMode.DEBUG,
                phase = WorkshopProjectPhase.LOGIC_REVIEW,
            ),
        )
        assertTrue(
            KimiFormulaToolService.workshopQuickJsExposureAllowed(
                isPanelWorkshop = true,
                mode = WorkshopEidosMode.EDIT,
                phase = WorkshopProjectPhase.COMPLETE,
            ),
        )
        assertFalse(
            KimiFormulaToolService.workshopQuickJsExposureAllowed(
                isPanelWorkshop = false,
                mode = WorkshopEidosMode.EDIT,
                phase = WorkshopProjectPhase.LOGIC_REVIEW,
            ),
        )
    }
}
