package com.example.optimalx.data.eidos

/** Which spec files to refresh from code at an approval gate. */
enum class WorkshopDocAlignScope {
    DESIGN,
    FINISH,
    UPDATE,
    ;

    /** Target `.md` files Eidos may touch in this align pass (UPDATE requires [updateSection]). */
    fun markdownFiles(updateSection: WorkshopUpdateSection? = null): List<String> = when (this) {
        DESIGN -> PanelPlatformSpec.SPEC_MARKDOWN_FILES
        FINISH -> listOf(
            "README.md",
            "STRUCTURE.md",
            "FEATURES.md",
            "FLOW.md",
            "DESIGN.md",
        )
        UPDATE -> when (updateSection) {
            WorkshopUpdateSection.SPECS -> listOf(
                "README.md",
                "STRUCTURE.md",
                "FEATURES.md",
                "FLOW.md",
                "DESIGN.md",
            )
            WorkshopUpdateSection.DESIGN -> listOf("DESIGN.md", "FLOW.md")
            WorkshopUpdateSection.LOGIC -> listOf("FEATURES.md", "FLOW.md", "README.md")
            null -> listOf(
                "README.md",
                "STRUCTURE.md",
                "FEATURES.md",
                "FLOW.md",
                "DESIGN.md",
            )
        }
    }
}
