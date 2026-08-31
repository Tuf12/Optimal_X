package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.eidos.EidosSystemFeatureFlags
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds

object EidosRetrievalPlanner {

    private val memoryCorpora: Set<EidosMemoryCorpus>
        get() = buildSet {
            add(EidosMemoryCorpus.DAILY)
            add(EidosMemoryCorpus.LTM)
            if (EidosSystemFeatureFlags.JOURNAL_ENABLED) {
                add(EidosMemoryCorpus.JOURNAL)
            }
        }

    fun plan(
        profileId: String,
        subfolderId: Long?,
        parentFolderId: Long?,
        conversationId: Long? = null,
    ): List<EidosRetrievalPass> {
        val activePasses = if (conversationId != null) {
            listOf(
                activeConversationPass(
                    conversationId,
                    EidosPrefetchPolicy.ACTIVE_CONVERSATION_SEMANTIC_LIMIT,
                ),
            )
        } else {
            emptyList()
        }

        return when (profileId) {
        EidosScopeProfileIds.GENERAL_APP -> listOf(
            *activePasses.toTypedArray(),
            *memoryPasses().toTypedArray(),
            userNotePass(),
            conversationPass(),
        )
        EidosScopeProfileIds.PARENT -> buildList {
            activePasses.forEach { add(it) }
            if (parentFolderId != null) {
                add(
                    EidosRetrievalPass(
                        scopeMode = "parent",
                        scopeId = parentFolderId,
                        perPassLimit = 6,
                    ),
                )
            }
            addAll(memoryPasses())
            add(conversationPass())
        }
        EidosScopeProfileIds.SUBFOLDER -> buildList {
            activePasses.forEach { add(it) }
            if (subfolderId != null) {
                add(
                    EidosRetrievalPass(
                        scopeMode = "local_first",
                        scopeId = subfolderId,
                        perPassLimit = 6,
                    ),
                )
            }
            addAll(memoryPasses())
            add(conversationPass())
        }
        EidosScopeProfileIds.WIDGET_ASK,
        EidosScopeProfileIds.WIDGET_CHAT,
        -> listOf(
            *activePasses.toTypedArray(),
            *widgetMemoryPasses().toTypedArray(),
            userNotePass(perPassLimit = 2),
            conversationPass(perPassLimit = 2),
        )
        EidosScopeProfileIds.WORKSHOP_CHAT -> buildList {
            addAll(activePasses)
            addAll(workshopProjectPasses(subfolderId, perPassLimit = 4))
        }
        EidosScopeProfileIds.WORKSHOP_PLAN,
        EidosScopeProfileIds.WORKSHOP_EDIT,
        -> buildList {
            addAll(activePasses)
            addAll(workshopProjectPasses(subfolderId, perPassLimit = 6))
        }
        EidosScopeProfileIds.WORKSHOP_INTAKE -> buildList {
            addAll(activePasses)
            addAll(workshopProjectPasses(subfolderId, perPassLimit = 3))
        }
        else -> activePasses
        }
    }

    private fun memoryPasses(perPassLimit: Int = 4): List<EidosRetrievalPass> =
        memoryCorpora.map { corpus ->
            EidosRetrievalPass(
                scopeMode = "global",
                corpora = setOf(corpus),
                perPassLimit = perPassLimit,
            )
        }

    private fun widgetMemoryPasses(perPassLimit: Int = 2): List<EidosRetrievalPass> =
        memoryCorpora.map { corpus ->
            EidosRetrievalPass(
                scopeMode = "global",
                corpora = setOf(corpus),
                perPassLimit = perPassLimit,
            )
        }

    private fun workshopProjectPasses(subfolderId: Long?, perPassLimit: Int): List<EidosRetrievalPass> {
        if (subfolderId == null) return emptyList()
        return listOf(
            EidosRetrievalPass(
                scopeMode = "local_first",
                scopeId = subfolderId,
                perPassLimit = perPassLimit,
            ),
        )
    }

    private fun userNotePass(perPassLimit: Int = 3): EidosRetrievalPass =
        EidosRetrievalPass(
            scopeMode = "global",
            corpora = setOf(EidosMemoryCorpus.NOTE),
            perPassLimit = perPassLimit,
        )

    private fun conversationPass(perPassLimit: Int = 3): EidosRetrievalPass =
        EidosRetrievalPass(
            scopeMode = "chat_history",
            corpora = setOf(EidosMemoryCorpus.CHAT),
            perPassLimit = perPassLimit,
        )

    private fun activeConversationPass(conversationId: Long, perPassLimit: Int): EidosRetrievalPass =
        EidosRetrievalPass(
            scopeMode = "active_conversation",
            scopeId = conversationId,
            corpora = setOf(EidosMemoryCorpus.CHAT),
            perPassLimit = perPassLimit,
        )
}
