package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDocAlignGateTest {

    @Test
    fun runtimeFileNames_designExcludesBridge() {
        assertEquals(
            listOf("index.html", "style.css", "script.js"),
            WorkshopDocAlignGate.runtimeFileNames(WorkshopDocAlignScope.DESIGN),
        )
    }

    @Test
    fun runtimeFileNames_finishIncludesBridge() {
        val names = WorkshopDocAlignGate.runtimeFileNames(WorkshopDocAlignScope.FINISH)
        assertTrue(names.contains("bridge.js"))
        assertTrue(names.contains("script.js"))
    }

    @Test
    fun markdownFiles_updateUnifiedListsAllSpecs() {
        val files = WorkshopDocAlignScope.UPDATE.markdownFiles(updateSection = null)
        assertTrue(files.contains("README.md"))
        assertTrue(files.contains("DESIGN.md"))
    }
}
