package com.example.optimalx.data.eidos.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiFormulaToolServiceTest {

    @Test
    fun enrichFormulaToolOutput_prependsSourceForFetch() {
        val enriched = enrichFormulaToolOutput(
            formulaUri = KIMI_FORMULA_FETCH_URI,
            argumentsJson = """{"url":"https://example.com/article"}""",
            rawOutput = "# Title\n\nBody text",
        )
        assertTrue(enriched.startsWith("Source: https://example.com/article"))
        assertTrue(enriched.contains("# Title"))
    }

    @Test
    fun enrichFormulaToolOutput_skipsWebSearchAndErrors() {
        val webSearch = enrichFormulaToolOutput(
            formulaUri = KIMI_FORMULA_WEB_SEARCH_URI,
            argumentsJson = """{"query":"news"}""",
            rawOutput = "encrypted blob",
        )
        assertEquals("encrypted blob", webSearch)

        val error = enrichFormulaToolOutput(
            formulaUri = KIMI_FORMULA_FETCH_URI,
            argumentsJson = """{"url":"https://example.com"}""",
            rawOutput = "Error: timeout",
        )
        assertEquals("Error: timeout", error)
    }

    @Test
    fun extractUrlFromToolArguments_readsCommonKeys() {
        assertEquals(
            "https://a.test",
            extractUrlFromToolArguments("""{"url":"https://a.test"}"""),
        )
        assertEquals(
            "https://b.test",
            extractUrlFromToolArguments("""{"link":"https://b.test"}"""),
        )
        assertNull(extractUrlFromToolArguments("""{"query":"no url here"}"""))
    }
}
