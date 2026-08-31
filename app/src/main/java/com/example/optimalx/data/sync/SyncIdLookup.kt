package com.example.optimalx.data.sync

import com.example.optimalx.data.db.AppDatabase

internal class SyncIdLookup private constructor(
    private val parentGlobal: Map<Long, String>,
    private val subfolderGlobal: Map<Long, String>,
    private val conversationGlobal: Map<Long, String>,
    private val fileGlobal: Map<Long, String>,
    /** Note checkpoints store subfolderId as sourceId; wire format uses note globalId. */
    private val noteGlobalBySubfolder: Map<Long, String>,
    private val checkpointGlobal: Map<Long, String>,
    private val changeSetGlobal: Map<Long, String>,
    private val chatMessageGlobal: Map<Long, String>,
) {
    companion object {
        suspend fun load(db: AppDatabase): SyncIdLookup {
            val notes = db.noteDao().getAllForSyncLookup()
            return SyncIdLookup(
                parentGlobal = db.parentFolderDao().getAllForSyncLookup().associate { it.id to it.globalId },
                subfolderGlobal = db.subfolderDao().getAllForSyncLookup().associate { it.id to it.globalId },
                conversationGlobal = db.conversationDao().getAllForSyncLookup().associate { it.id to it.globalId },
                fileGlobal = db.fileReferenceDao().getAllOnce().associate { it.id to it.globalId },
                noteGlobalBySubfolder = notes.associate { it.subfolderId to it.globalId },
                checkpointGlobal = db.contentCheckpointDao().getAllForSyncLookup().associate { it.id to it.globalId },
                changeSetGlobal = db.pendingChangeDao().getAllSetsForSyncLookup().associate { it.id to it.globalId },
                chatMessageGlobal = db.chatMessageDao().getAllForSyncLookup().associate { it.id to it.globalId },
            )
        }

        internal fun forTest(
            subfolderGlobal: Map<Long, String> = emptyMap(),
            fileGlobal: Map<Long, String> = emptyMap(),
            noteGlobalBySubfolder: Map<Long, String> = emptyMap(),
            checkpointGlobal: Map<Long, String> = emptyMap(),
        ): SyncIdLookup = SyncIdLookup(
            parentGlobal = emptyMap(),
            subfolderGlobal = subfolderGlobal,
            conversationGlobal = emptyMap(),
            fileGlobal = fileGlobal,
            noteGlobalBySubfolder = noteGlobalBySubfolder,
            checkpointGlobal = checkpointGlobal,
            changeSetGlobal = emptyMap(),
            chatMessageGlobal = emptyMap(),
        )
    }

    fun parentGlobalId(id: Long): String =
        parentGlobal[id] ?: error("Missing parent globalId for local id $id")

    fun subfolderGlobalId(id: Long): String =
        subfolderGlobal[id] ?: error("Missing subfolder globalId for local id $id")

    fun optionalParentGlobalId(id: Long?): String? = id?.let { parentGlobal[it] }

    fun optionalSubfolderGlobalId(id: Long?): String? = id?.let { subfolderGlobal[it] }

    fun optionalConversationGlobalId(id: Long?): String? = id?.let { conversationGlobal[it] }

    fun optionalChatMessageGlobalId(id: Long?): String? = id?.let { chatMessageGlobal[it] }

    fun conversationGlobalId(id: Long): String =
        conversationGlobal[id] ?: error("Missing conversation globalId for local id $id")

    fun changeSetGlobalId(id: Long): String =
        changeSetGlobal[id] ?: error("Missing pending change set globalId for local id $id")

    fun checkpointGlobalId(id: Long): String =
        checkpointGlobal[id] ?: error("Missing checkpoint globalId for local id $id")

    fun optionalCheckpointGlobalId(id: Long?): String? =
        id?.let { checkpointGlobal[it] }

    fun hasCheckpointId(id: Long): Boolean = id in checkpointGlobal

    fun sourceGlobalId(sourceType: String, sourceId: Long): String = when (sourceType) {
        "workshop_file" -> fileGlobal[sourceId] ?: error("Missing file globalId for source $sourceId")
        // Pending create proposals store the owning workshop subfolder id as sourceId.
        "workshop_new_file" -> subfolderGlobal[sourceId]
            ?: error("Missing subfolder globalId for workshop_new_file sourceId $sourceId")
        "note" -> noteGlobalBySubfolder[sourceId]
            ?: error("Missing note globalId for subfolder sourceId $sourceId")
        else -> error("Unknown sourceType: $sourceType")
    }

    fun scopeGlobalId(scopeType: String, scopeId: Long): String = when (scopeType) {
        "workshop_project", "note", "subfolder" -> subfolderGlobalId(scopeId)
        else -> error("Unknown scopeType: $scopeType")
    }
}
