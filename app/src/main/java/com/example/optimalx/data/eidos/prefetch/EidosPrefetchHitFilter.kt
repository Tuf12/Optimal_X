package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticObjectType

object EidosPrefetchHitFilter {

    private val SCOPE_CORPORA = setOf(EidosMemoryCorpus.NOTE, EidosMemoryCorpus.FILE)

    fun matchesPass(
        hit: SemanticChunkHit,
        pass: EidosRetrievalPass,
        folderIds: SystemMemoryFolderIds,
        excludeInlinedSubfolderId: Long?,
        excludeConversationId: Long? = null,
    ): Boolean {
        if (excludeConversationId != null &&
            pass.scopeMode == "chat_history" &&
            hit.objectType == SemanticObjectType.CONVERSATION &&
            hit.objectId == excludeConversationId
        ) {
            return false
        }
        if (pass.scopeMode == "active_conversation" &&
            hit.objectType == SemanticObjectType.CONVERSATION &&
            pass.scopeId != null &&
            hit.objectId != pass.scopeId
        ) {
            return false
        }
        if (excludeInlinedSubfolderId != null &&
            hit.objectType == SemanticObjectType.NOTE &&
            hit.subfolderId == excludeInlinedSubfolderId
        ) {
            return false
        }
        val corpus = EidosMemoryCorpusFilter.classify(hit, folderIds) ?: return false
        return if (pass.corpora.isNotEmpty()) {
            corpus in pass.corpora
        } else {
            corpus in SCOPE_CORPORA
        }
    }
}
