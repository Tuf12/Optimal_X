package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticObjectType

data class SystemMemoryFolderIds(
    val dailyParentId: Long?,
    val ltmParentId: Long?,
    val journalParentId: Long?,
) {
    companion object {
        suspend fun load(database: AppDatabase): SystemMemoryFolderIds {
            val parentDao = database.parentFolderDao()
            return SystemMemoryFolderIds(
                dailyParentId = parentDao.getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)?.id,
                ltmParentId = parentDao.getSystemFolderByName(SystemFolderNames.EIDOS_MEMORY)?.id,
                journalParentId = parentDao.getSystemFolderByName(SystemFolderNames.EIDOS_JOURNAL)?.id,
            )
        }
    }
}

object EidosMemoryCorpusFilter {

    fun classify(hit: SemanticChunkHit, folderIds: SystemMemoryFolderIds): EidosMemoryCorpus? =
        when (hit.objectType) {
            SemanticObjectType.CONVERSATION -> EidosMemoryCorpus.CHAT
            SemanticObjectType.FILE -> EidosMemoryCorpus.FILE
            SemanticObjectType.NOTE -> classifyNote(hit.parentFolderId, folderIds)
            else -> null
        }

    fun sourceTag(hit: SemanticChunkHit, folderIds: SystemMemoryFolderIds): String {
        if (hit.chunkType == SemanticChunkBuilder.CHUNK_TYPE_PROJECT_SUMMARY) {
            return "Project summary"
        }
        return classify(hit, folderIds)?.displayTag ?: "Note"
    }

    private fun classifyNote(parentFolderId: Long?, folderIds: SystemMemoryFolderIds): EidosMemoryCorpus {
        return when (parentFolderId) {
            folderIds.dailyParentId -> EidosMemoryCorpus.DAILY
            folderIds.ltmParentId -> EidosMemoryCorpus.LTM
            folderIds.journalParentId -> EidosMemoryCorpus.JOURNAL
            else -> EidosMemoryCorpus.NOTE
        }
    }
}
