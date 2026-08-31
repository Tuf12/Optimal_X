package com.example.optimalx.data.sync

/**
 * Standard Panel Workshop tree files. Used when syncing workshop files from desktop so we
 * fetch code + README even if local [file_references] rows are incomplete.
 */
object WorkshopFileManifest {
    val standardFiles: List<String> = listOf(
        "README.md",
        "STRUCTURE.md",
        "FEATURES.md",
        "FLOW.md",
        "DESIGN.md",
        "index.html",
        "style.css",
        "bridge.js",
        "script.js",
    )
}
