package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.FileReference
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopProjectContextTest {

    @Test
    fun file_manifest_includes_ids() {
        val manifest = WorkshopProjectContext.formatFileManifest(
            listOf(
                FileReference(
                    id = 42L,
                    subfolderId = 1L,
                    fileName = "index.html",
                    fileType = "html",
                    filePath = "/tmp/index.html",
                ),
            ),
        )
        assertTrue(manifest.contains("index.html"))
        assertTrue(manifest.contains("fileReferenceId=42"))
    }

    @Test
    fun open_excerpt_truncates_long_content() {
        val excerpt = WorkshopProjectContext.formatOpenFileExcerpt(
            "script.js",
            "x".repeat(WorkshopProjectContext.MAX_OPEN_FILE_EXCERPT_CHARS + 500),
        )
        requireNotNull(excerpt)
        assertTrue(excerpt.contains("truncated"))
    }
}
