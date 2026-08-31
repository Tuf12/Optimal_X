package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.Subfolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentFolderContextTest {

    @Test
    fun formatActiveParentLine_includesNameAndId() {
        val line = ParentFolderContext.formatActiveParentLine("Kitchen Reno", 42L)
        assertTrue(line.contains("Kitchen Reno"))
        assertTrue(line.contains("parentFolderId=42"))
    }

    @Test
    fun formatActiveParentLine_fallsBackToIdWhenNameMissing() {
        val line = ParentFolderContext.formatActiveParentLine(null, 7L)
        assertTrue(line.contains("parentFolderId=7"))
    }

    @Test
    fun formatSubfolderCatalog_allWhenTwentyOrFewer() {
        val subfolders = (1..15).map { id ->
            Subfolder(
                id = id.toLong(),
                parentFolderId = 99L,
                name = "Folder $id",
                createdAt = id.toLong(),
                updatedAt = id.toLong(),
                sortOrder = id,
                isSystemSubfolder = false,
                deletedAt = null,
            )
        }
        val text = ParentFolderContext.formatSubfolderCatalog(
            parentFolderId = 99L,
            total = subfolders.size,
            shown = subfolders,
        )
        assertTrue(text.contains("All subfolders in this parent (15):"))
        assertTrue(text.contains("Folder 1 (subfolderId=1)"))
        assertFalse(text.contains("search_semantic(scopeType=parent)"))
    }

    @Test
    fun formatSubfolderCatalog_cappedWithFooterWhenMoreThanTwenty() {
        val subfolders = (1..25).map { id ->
            Subfolder(
                id = id.toLong(),
                parentFolderId = 7L,
                name = "Area $id",
                createdAt = id.toLong(),
                updatedAt = id.toLong(),
                sortOrder = id,
                isSystemSubfolder = false,
                deletedAt = null,
            )
        }
        val shown = subfolders.take(ParentFolderContext.MAX_SUBFOLDER_CATALOG)
        val text = ParentFolderContext.formatSubfolderCatalog(
            parentFolderId = 7L,
            parentName = "Big Project",
            total = subfolders.size,
            shown = shown,
        )
        assertTrue(text.contains("Subfolders shown (20 of 25 total"))
        assertTrue(text.contains("search_semantic(scopeType=parent)"))
        assertTrue(text.contains("list_folder_contents(folderId=7, parent=\"Big Project\")"))
        assertTrue(text.contains("search_folders(query=…)"))
    }
}
