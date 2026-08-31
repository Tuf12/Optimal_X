package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EidosPromptTraceTest {

    @Test
    fun sha256_isDeterministic() {
        assertEquals(
            EidosPromptTrace.sha256("alpha"),
            EidosPromptTrace.sha256("alpha"),
        )
        assertNotEquals(
            EidosPromptTrace.sha256("alpha"),
            EidosPromptTrace.sha256("beta"),
        )
    }

    @Test
    fun toolAllowlistHash_sortsNames() {
        assertEquals(
            EidosPromptTrace.toolAllowlistHash(listOf("read_file", "search_semantic")),
            EidosPromptTrace.toolAllowlistHash(listOf("search_semantic", "read_file")),
        )
    }
}
