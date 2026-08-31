package com.example.optimalx.data.sync

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.PendingChangeSet
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.preferences.DumpEditPreferences

class SyncPushBuilder(
    private val context: Context,
    private val db: AppDatabase,
) {

    suspend fun buildTier1(lastSyncAt: Long): SyncTablesPayload =
        buildTier1(lastSyncAt, Tier1Dependencies())

    private suspend fun buildTier2(lastSyncAt: Long): SyncTablesPayload {
        val since = if (lastSyncAt == 0L) -1L else lastSyncAt
        val lookup = SyncIdLookup.load(db)

        val contentPatches = db.contentPatchDao().getChangedSince(since)
            .filter { patch ->
                lookup.hasCheckpointId(patch.fromCheckpointId) &&
                    lookup.hasCheckpointId(patch.toCheckpointId)
            }
            .map { SyncMappers.contentPatch(it, lookup) }
        val pendingChangeItems = db.pendingChangeDao().getChangedItemsSince(since)
            .map { SyncMappers.pendingChangeItem(it, lookup) }

        val contentCheckpoints = includeCheckpointDependencies(
            changed = db.contentCheckpointDao().getChangedSince(since),
            patchRows = contentPatches,
            pendingItemRows = pendingChangeItems,
            lookup = lookup,
        )

        val pendingChangeSets = includePendingChangeSetDependencies(
            changed = db.pendingChangeDao().getChangedSetsSince(since),
            pendingItemRows = pendingChangeItems,
            lookup = lookup,
        )

        return SyncTablesPayload(
            customPanelAssignments = db.customPanelAssignmentDao().getChangedSince(since)
                .map { SyncMappers.customPanelAssignment(it, lookup) },
            conversations = db.conversationDao().getChangedSince(since)
                .map { SyncMappers.conversation(it, lookup) },
            chatMessages = db.chatMessageDao().getChangedSince(since)
                .map { SyncMappers.chatMessage(it, lookup) },
            contentCheckpoints = contentCheckpoints,
            contentPatches = contentPatches,
            pendingChangeSets = pendingChangeSets,
            pendingChangeItems = pendingChangeItems,
            panelState = db.panelStateDao().getChangedSince(since)
                .map { SyncMappers.panelState(it, lookup) },
        )
    }

    /**
     * Patches and pending items may change without their anchor checkpoints changing.
     * Desktop apply needs every referenced checkpoint globalId in the same push batch.
     */
    private suspend fun includeCheckpointDependencies(
        changed: List<ContentCheckpoint>,
        patchRows: List<SyncContentPatchRow>,
        pendingItemRows: List<SyncPendingChangeItemRow>,
        lookup: SyncIdLookup,
    ): List<SyncContentCheckpointRow> {
        val neededGlobals = mutableSetOf<String>()
        patchRows.forEach { patch ->
            neededGlobals.add(patch.fromCheckpointGlobalId)
            neededGlobals.add(patch.toCheckpointGlobalId)
        }
        pendingItemRows.forEach { item ->
            item.baseCheckpointGlobalId?.let { neededGlobals.add(it) }
        }

        val includedGlobals = changed.map { it.globalId }.toMutableSet()
        val merged = changed.toMutableList()
        if (neededGlobals.isNotEmpty()) {
            val byGlobal = db.contentCheckpointDao().getAllForSyncLookup().associateBy { it.globalId }
            for (globalId in neededGlobals) {
                if (globalId in includedGlobals) continue
                val row = byGlobal[globalId] ?: continue
                merged.add(row)
                includedGlobals.add(globalId)
            }
        }
        return merged.map { SyncMappers.contentCheckpoint(it, lookup) }
    }

    private suspend fun includePendingChangeSetDependencies(
        changed: List<PendingChangeSet>,
        pendingItemRows: List<SyncPendingChangeItemRow>,
        lookup: SyncIdLookup,
    ): List<SyncPendingChangeSetRow> {
        val includedGlobals = changed.map { it.globalId }.toMutableSet()
        val merged = changed.toMutableList()
        val neededSetGlobals = pendingItemRows.map { it.changeSetGlobalId }.toSet()
        if (neededSetGlobals.isNotEmpty()) {
            val byGlobal = db.pendingChangeDao().getAllSetsForSyncLookup().associateBy { it.globalId }
            for (globalId in neededSetGlobals) {
                if (globalId in includedGlobals) continue
                val row = byGlobal[globalId] ?: continue
                merged.add(row)
                includedGlobals.add(globalId)
            }
        }
        return merged.map { SyncMappers.pendingChangeSet(it, lookup) }
    }

    suspend fun buildTier1ForFiles(fileGlobalIds: Collection<String>): SyncTablesPayload =
        buildTier1(
            lastSyncAt = 1L,
            Tier1Dependencies(fileGlobalIds = fileGlobalIds.toSet()),
        )

    suspend fun buildPushPayload(lastSyncAt: Long): SyncTablesPayload {
        val tier2 = buildTier2(lastSyncAt)
        val lookups = loadTier1Lookups()
        val tier1Deps = collectTier2Tier1Dependencies(tier2, lookups)
        val tier1 = buildTier1(lastSyncAt, tier1Deps)
        return tier1.copy(
            customPanelAssignments = tier2.customPanelAssignments,
            conversations = tier2.conversations,
            chatMessages = tier2.chatMessages,
            contentCheckpoints = tier2.contentCheckpoints,
            contentPatches = tier2.contentPatches,
            pendingChangeSets = tier2.pendingChangeSets,
            pendingChangeItems = tier2.pendingChangeItems,
            panelState = tier2.panelState,
        )
    }

    private suspend fun buildTier1(
        lastSyncAt: Long,
        extra: Tier1Dependencies,
    ): SyncTablesPayload {
        val since = if (lastSyncAt == 0L) -1L else lastSyncAt
        val lookups = loadTier1Lookups()
        val subfolderGlobalById = lookups.subfolders.values.associate { it.id to it.globalId }

        val parentIds = db.parentFolderDao().getChangedSince(since).map { it.id }.toMutableSet()
        val subfolderIds = db.subfolderDao().getChangedSince(since).map { it.id }.toMutableSet()
        val noteIds = db.noteDao().getChangedSince(since).map { it.id }.toMutableSet()
        val fileIds = db.fileReferenceDao().getChangedSince(since).map { it.id }.toMutableSet()

        noteIds.mapNotNull { lookups.notes[it]?.subfolderId }.forEach { subfolderIds.add(it) }
        fileIds.mapNotNull { lookups.files[it]?.subfolderId }.forEach { subfolderIds.add(it) }

        extra.subfolderGlobalIds.forEach { globalId ->
            lookups.subfolderByGlobalId[globalId]?.id?.let { subfolderIds.add(it) }
        }
        extra.parentGlobalIds.forEach { globalId ->
            lookups.parentByGlobalId[globalId]?.id?.let { parentIds.add(it) }
        }
        extra.noteGlobalIds.forEach { globalId ->
            lookups.noteByGlobalId[globalId]?.id?.let { noteId ->
                noteIds.add(noteId)
                lookups.notes[noteId]?.subfolderId?.let { subfolderIds.add(it) }
            }
        }
        extra.fileGlobalIds.forEach { globalId ->
            lookups.fileByGlobalId[globalId]?.id?.let { fileId ->
                fileIds.add(fileId)
                lookups.files[fileId]?.subfolderId?.let { subfolderIds.add(it) }
            }
        }

        includeParentFoldersForSubfolders(subfolderIds, parentIds, lookups)

        val parents = parentIds.mapNotNull { lookups.parents[it] }
        val subfolders = subfolderIds.mapNotNull { lookups.subfolders[it] }
        val notes = noteIds.mapNotNull { lookups.notes[it] }
        val files = fileIds.mapNotNull { lookups.files[it] }

        val dumpEdit = DumpEditPreferences.readState(context)
        val dumpEditRow = if (since < 0L || dumpEdit.updatedAt > lastSyncAt) {
            SyncMappers.dumpEdit(dumpEdit)
        } else {
            null
        }

        return SyncTablesPayload(
            parentFolders = parents.map(SyncMappers::parentFolder),
            subfolders = subfolders.map { sf ->
                val parentGlobal = lookups.parents[sf.parentFolderId]?.globalId
                    ?: error("Missing parent globalId for subfolder ${sf.globalId}")
                SyncMappers.subfolder(sf, parentGlobal)
            },
            notes = notes.map { note ->
                val subGlobal = subfolderGlobalById[note.subfolderId]
                    ?: error("Missing subfolder globalId for note ${note.globalId}")
                SyncMappers.note(note, subGlobal)
            },
            fileReferences = files.map { ref ->
                val subGlobal = subfolderGlobalById[ref.subfolderId]
                    ?: error("Missing subfolder globalId for file ${ref.globalId}")
                SyncMappers.fileReference(ref, subGlobal)
            },
            dumpEdit = dumpEditRow,
        )
    }

    private suspend fun loadTier1Lookups(): Tier1Lookups {
        val parents = db.parentFolderDao().getAllForSyncLookup().associateBy { it.id }
        val subfolders = db.subfolderDao().getAllForSyncLookup().associateBy { it.id }
        val notes = db.noteDao().getAllForSyncLookup().associateBy { it.id }
        val files = db.fileReferenceDao().getAllOnce().associateBy { it.id }
        return Tier1Lookups(
            parents = parents,
            subfolders = subfolders,
            notes = notes,
            files = files,
            parentByGlobalId = parents.values.associateBy { it.globalId },
            subfolderByGlobalId = subfolders.values.associateBy { it.globalId },
            noteByGlobalId = notes.values.associateBy { it.globalId },
            fileByGlobalId = files.values.associateBy { it.globalId },
        )
    }

    private fun collectTier2Tier1Dependencies(
        tier2: SyncTablesPayload,
        lookups: Tier1Lookups,
    ): Tier1Dependencies {
        val subfolderGlobals = mutableSetOf<String>()
        val parentGlobals = mutableSetOf<String>()
        val noteGlobals = mutableSetOf<String>()
        val fileGlobals = mutableSetOf<String>()

        fun requireSubfolder(globalId: String?) {
            if (globalId.isNullOrBlank()) return
            subfolderGlobals.add(globalId)
            lookups.subfolderByGlobalId[globalId]?.let { sf ->
                lookups.parents[sf.parentFolderId]?.globalId?.let { parentGlobals.add(it) }
            }
        }

        fun requireNoteSource(noteGlobalId: String) {
            noteGlobals.add(noteGlobalId)
            val note = lookups.noteByGlobalId[noteGlobalId] ?: return
            lookups.subfolders[note.subfolderId]?.let { sf ->
                subfolderGlobals.add(sf.globalId)
                lookups.parents[sf.parentFolderId]?.globalId?.let { parentGlobals.add(it) }
            }
        }

        fun requireFileSource(fileGlobalId: String) {
            fileGlobals.add(fileGlobalId)
            val file = lookups.fileByGlobalId[fileGlobalId] ?: return
            lookups.subfolders[file.subfolderId]?.let { sf ->
                subfolderGlobals.add(sf.globalId)
                lookups.parents[sf.parentFolderId]?.globalId?.let { parentGlobals.add(it) }
            }
        }

        fun requireSource(sourceType: String, sourceGlobalId: String) {
            when (sourceType) {
                "note" -> requireNoteSource(sourceGlobalId)
                "workshop_file" -> requireFileSource(sourceGlobalId)
                "workshop_new_file" -> requireSubfolder(sourceGlobalId)
            }
        }

        tier2.customPanelAssignments.forEach {
            requireSubfolder(it.workshopSubfolderGlobalId)
            requireSubfolder(it.targetSubfolderGlobalId)
        }
        tier2.conversations.forEach {
            it.parentFolderGlobalId?.let { parentGlobals.add(it) }
            requireSubfolder(it.subfolderGlobalId)
        }
        tier2.contentCheckpoints.forEach { requireSource(it.sourceType, it.sourceGlobalId) }
        tier2.contentPatches.forEach { requireSource(it.sourceType, it.sourceGlobalId) }
        tier2.pendingChangeSets.forEach { requireSubfolder(it.scopeGlobalId) }
        tier2.pendingChangeItems.forEach { requireSource(it.sourceType, it.sourceGlobalId) }
        tier2.panelState.forEach { requireSubfolder(it.workshopSubfolderGlobalId) }

        return Tier1Dependencies(
            parentGlobalIds = parentGlobals,
            subfolderGlobalIds = subfolderGlobals,
            noteGlobalIds = noteGlobals,
            fileGlobalIds = fileGlobals,
        )
    }

    private fun includeParentFoldersForSubfolders(
        subfolderIds: Set<Long>,
        parentIds: MutableSet<Long>,
        lookups: Tier1Lookups,
    ) {
        subfolderIds.forEach { subfolderId ->
            lookups.subfolders[subfolderId]?.parentFolderId?.let { parentIds.add(it) }
        }
    }

    private data class Tier1Dependencies(
        val parentGlobalIds: Set<String> = emptySet(),
        val subfolderGlobalIds: Set<String> = emptySet(),
        val noteGlobalIds: Set<String> = emptySet(),
        val fileGlobalIds: Set<String> = emptySet(),
    )

    private data class Tier1Lookups(
        val parents: Map<Long, ParentFolder>,
        val subfolders: Map<Long, Subfolder>,
        val notes: Map<Long, Note>,
        val files: Map<Long, FileReference>,
        val parentByGlobalId: Map<String, ParentFolder>,
        val subfolderByGlobalId: Map<String, Subfolder>,
        val noteByGlobalId: Map<String, Note>,
        val fileByGlobalId: Map<String, FileReference>,
    )
}
