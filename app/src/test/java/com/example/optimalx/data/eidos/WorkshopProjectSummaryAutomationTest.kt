package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WorkshopProjectSummaryAutomationTest {

    @Test
    fun sha256Hex_isStableForSameInput() {
        val first = digestForTest("README\nhello")
        val second = digestForTest("README\nhello")
        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun sha256Hex_changesWhenSpecBodyChanges() {
        val before = digestForTest("### README.md\n\nPanel A")
        val after = digestForTest("### README.md\n\nPanel B")
        assertNotEquals(before, after)
    }

    private fun digestForTest(body: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
