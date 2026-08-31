package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopPanelContextPrefetchTest {

    @Test
    fun formatPrefetchOrientationBlock_mentionsRetrievedContext() {
        val block = WorkshopPanelContext.formatPrefetchOrientationBlock(
            hasProjectSummary = true,
            hasSpecFiles = true,
            openFileName = "script.js",
            mode = WorkshopEidosMode.EDIT,
        )
        assertTrue(block.contains("Retrieved context"))
        assertTrue(block.contains("script.js"))
        assertTrue(block.contains("Cached project summary"))
    }

    @Test
    fun formatPrefetchOrientationBlock_noSummaryHintsSpecFiles() {
        val block = WorkshopPanelContext.formatPrefetchOrientationBlock(
            hasProjectSummary = false,
            hasSpecFiles = true,
            openFileName = null,
            mode = WorkshopEidosMode.PLAN,
        )
        assertTrue(block.contains("Spec .md files exist"))
        assertFalse(block.contains("Editor tab:"))
    }
}
