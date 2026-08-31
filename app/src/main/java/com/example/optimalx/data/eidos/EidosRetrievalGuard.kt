package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder

/** Blocks retired or paused system notes from Eidos search, prefetch, and semantic indexing. */
object EidosRetrievalGuard {

    fun shouldIndexNoteForEidos(subfolder: Subfolder, parent: ParentFolder?): Boolean =
        shouldExposeNoteToEidos(subfolder, parent)

    fun shouldExposeNoteToEidos(subfolder: Subfolder, parent: ParentFolder?): Boolean {
        if (isLegacyReasoningSubfolder(subfolder, parent)) return false
        if (!EidosSystemFeatureFlags.JOURNAL_ENABLED &&
            parent?.name == SystemFolderNames.EIDOS_JOURNAL
        ) {
            return false
        }
        return true
    }

    /** Legacy per-parent Reasoning subfolder and Eidos Reasoning system parent. */
    fun isLegacyReasoningSubfolder(subfolder: Subfolder, parent: ParentFolder?): Boolean {
        if (subfolder.isSystemSubfolder &&
            subfolder.name == SystemFolderNames.PARENT_REASONING_SUBFOLDER
        ) {
            return true
        }
        return parent?.name == SystemFolderNames.EIDOS_REASONING
    }
}
