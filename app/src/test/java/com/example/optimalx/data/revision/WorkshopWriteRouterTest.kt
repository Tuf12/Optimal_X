package com.example.optimalx.data.revision

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopWriteRouterTest {

    @Test
    fun queuedReviewDescription_includesPendingCountAndSupersedeNote() {
        val msg = WorkshopWriteRouter.queuedReviewDescription(
            fileName = "script.js",
            pendingCount = 1,
            superseded = true,
        )
        assertTrue(msg.contains("1 pending item"))
        assertTrue(msg.contains("Replaced the earlier pending"))
        assertTrue(msg.contains("not how many tool calls"))
    }

    @Test
    fun queuedReviewDescription_pluralPendingCount() {
        val msg = WorkshopWriteRouter.queuedReviewDescription(
            fileName = "bridge.js",
            pendingCount = 3,
            superseded = false,
        )
        assertTrue(msg.contains("3 pending items"))
    }
}
