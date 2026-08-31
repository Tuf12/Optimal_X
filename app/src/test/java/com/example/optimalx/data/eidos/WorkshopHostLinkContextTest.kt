package com.example.optimalx.data.eidos

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopHostLinkContextTest {

    @Test
    fun formatPromptBlock_includesHostIdsAndCrossSourceRules() {
        val block = WorkshopHostLinkContext.formatPromptBlock(
            WorkshopHostLink(
                targetSubfolderId = 55L,
                targetSubfolderName = "Cabinets",
                parentFolderId = 12L,
                parentFolderName = "Kitchen Reno",
                panelTitle = "Budget Tracker",
            ),
        )
        assertTrue(block.contains("Budget Tracker"))
        assertTrue(block.contains("targetSubfolderId=55"))
        assertTrue(block.contains("parent=Kitchen Reno"))
        assertTrue(block.contains("parentFolderId=12"))
        assertTrue(block.contains("read_file"))
        assertTrue(block.contains(WorkshopHostLinkContext.CROSS_SOURCE_RETRIEVAL_RULES.take(40)))
    }
}
