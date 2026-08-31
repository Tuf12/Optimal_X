package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopSpecValidationTest {

    @Test
    fun evaluateAcceptReadiness_rejectsScaffoldOnlyReadme() {
        val contents = mapOf(
            "README.md" to "# README\n\n_Short project summary — generated after you discuss intake with Eidos._\n",
            "STRUCTURE.md" to "# Structure\n\nList each project file and what it does.\n",
            "FEATURES.md" to "# Features\n\nOne section per feature.\n",
            "FLOW.md" to "# Flow\n\nUser interaction flow.\n",
            "DESIGN.md" to "# Design\n\nLayout and visual rules.\n",
        )
        assertFalse(WorkshopSpecValidation.evaluateAcceptReadiness(contents).ready)
    }

    @Test
    fun evaluateAcceptReadiness_acceptsFilledSpecs() {
        val contents = mapOf(
            "README.md" to "# Bid calculator\n\nHelps contractors estimate room bids quickly on site.",
            "STRUCTURE.md" to "# Structure\n\nREADME, FEATURES, FLOW, DESIGN, index.html, style.css, bridge.js, script.js.",
            "FEATURES.md" to "# Features\n\n- Add rooms\n- Labor rate\n- Markup\n- Running total",
            "FLOW.md" to "# Flow\n\n1. Add room\n2. Enter sqft\n3. See subtotal",
            "DESIGN.md" to "# Design\n\nPhone layout: header, room list, sticky total bar. Touch targets 56px.",
        )
        assertTrue(WorkshopSpecValidation.evaluateAcceptReadiness(contents).ready)
    }

    @Test
    fun validateCharCap_allowsSlackAboveTarget() {
        val slightlyOver = "x".repeat(650)
        assertTrue(WorkshopSpecValidation.validateCharCap("README.md", slightlyOver) == null)
    }

    @Test
    fun validateCharCap_flagsWellAboveSoftMax() {
        val farOver = "x".repeat(700)
        assertTrue(WorkshopSpecValidation.validateCharCap("README.md", farOver)?.contains("700") == true)
        assertTrue(WorkshopSpecValidation.validateCharCap("README.md", farOver)?.contains("soft max") == true)
    }
}
