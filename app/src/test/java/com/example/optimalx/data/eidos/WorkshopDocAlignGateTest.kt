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

    @Test
    fun runtimeInputsForSpec_conservativelyDependsOnAllScopeRuntimeFiles() {
        // Any spec depends on every runtime file in scope, so any code change re-reviews all specs.
        for (spec in listOf("DESIGN.md", "FLOW.md", "FEATURES.md", "STRUCTURE.md", "README.md")) {
            assertEquals(
                WorkshopDocAlignGate.runtimeFileNames(WorkshopDocAlignScope.FINISH),
                WorkshopDocAlignGate.runtimeInputsForSpec(spec, WorkshopDocAlignScope.FINISH),
            )
        }
    }

    @Test
    fun runtimeInputsForSpec_designScopeExcludesBridge() {
        val inputs = WorkshopDocAlignGate.runtimeInputsForSpec("FEATURES.md", WorkshopDocAlignScope.DESIGN)
        assertEquals(listOf("index.html", "style.css", "script.js"), inputs)
    }
}
