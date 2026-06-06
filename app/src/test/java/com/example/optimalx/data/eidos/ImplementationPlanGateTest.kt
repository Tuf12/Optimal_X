package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImplementationPlanGateTest {

    @Test
    fun isSubstantive_rejectsBlankAndScaffoldOnly() {
        assertFalse(ImplementationPlanGate.isSubstantive(null))
        assertFalse(ImplementationPlanGate.isSubstantive(""))
        assertFalse(
            ImplementationPlanGate.isSubstantive(
                """
                # Implementation plan
                _Optional — Plan mode may add phases here._
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun isSubstantive_acceptsRealPlanBody() {
        val body = "Phase 1: Update bridge.js\n".repeat(10)
        assertTrue(ImplementationPlanGate.isSubstantive("# Implementation plan\n\n$body"))
    }

    @Test
    fun acceptanceMatchesContent_requiresMatchingHash() {
        val content = "# Plan\n\n" + "x".repeat(100)
        val hash = ImplementationPlanGate.contentHash(content)
        assertTrue(ImplementationPlanGate.acceptanceMatchesContent(true, hash, content))
        assertFalse(ImplementationPlanGate.acceptanceMatchesContent(true, hash, content + "\nedit"))
        assertFalse(ImplementationPlanGate.acceptanceMatchesContent(false, hash, content))
    }

    @Test
    fun contentHash_changesWhenContentChanges() {
        val a = ImplementationPlanGate.contentHash("alpha")
        val b = ImplementationPlanGate.contentHash("beta")
        assertNotEquals(a, b)
    }
}
