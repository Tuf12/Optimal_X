package com.example.optimalx.data.eidos

// ON HOLD — Eidos Index materializer. Populates tag_hint_lines when EidosIndexFeature.isActive.
// Retrieval in shipping app uses semantic vector embeddings (search_semantic) instead.

import com.example.optimalx.data.dao.TagHintLineDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.TagHintLine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale

data class AppIndexRefMetadata(
    val objectType: String,
    val scopeType: String,
    val scopeId: String?,
    val parentRef: String?,
    val rootBranch: String,
)

data class AppIndexBootstrapResult(
    val upsertedCount: Int,
    val deletedCount: Int,
)

data class AppIndexNames(
    val objectName: String,
    val parentFolderName: String? = null,
    val subfolderName: String? = null,
)

class AppIndexMaterializer(
    private val db: AppDatabase,
    private val tagHintLineDao: TagHintLineDao = db.tagHintLineDao(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun bootstrapFullIndex(): AppIndexBootstrapResult {
        val specs = collectBootstrapSpecs()
        val currentRefs = linkedSetOf<String>()
        var upserted = 0

        specs.forEach { spec ->
            currentRefs += spec.ref
            upsertMaterialized(spec)
            upserted += 1
        }

        var deleted = 0
        tagHintLineDao.getAllRefs().forEach { existingRef ->
            if (existingRef !in currentRefs) {
                deleted += tagHintLineDao.deleteByRef(existingRef)
            }
        }

        return AppIndexBootstrapResult(
            upsertedCount = upserted,
            deletedCount = deleted,
        )
    }

    suspend fun resolveRefMetadata(ref: String): AppIndexRefMetadata? {
        val parts = ref.split(":")
        val prefix = parts.firstOrNull().orEmpty().lowercase(Locale.US)
        return when (prefix) {
            "parent" -> {
                if (parts.getOrNull(1)?.toLongOrNull() == null) return null
                if (parts.size != 2) return null
                AppIndexRefMetadata(
                    objectType = "parent",
                    scopeType = "none",
                    scopeId = null,
                    parentRef = null,
                    rootBranch = "hierarchy",
                )
            }

            "subfolder" -> {
                if (parts.size != 2) return null
                val subfolderId = parts.getOrNull(1)?.toLongOrNull()
                    ?: return null
                val parentRef = subfolderId
                    ?.let { db.subfolderDao().getById(it)?.parentFolderId }
                    ?.let { "parent:$it" }
                AppIndexRefMetadata(
                    objectType = "subfolder",
                    scopeType = "none",
                    scopeId = null,
                    parentRef = parentRef,
                    rootBranch = "hierarchy",
                )
            }

            "note" -> {
                if (parts.size != 2) return null
                val noteId = parts.getOrNull(1)?.toLongOrNull()
                    ?: return null
                val parentRef = noteId
                    ?.let { db.noteDao().getById(it)?.subfolderId }
                    ?.let { "subfolder:$it" }
                AppIndexRefMetadata(
                    objectType = "note",
                    scopeType = "none",
                    scopeId = null,
                    parentRef = parentRef,
                    rootBranch = "hierarchy",
                )
            }

            "file" -> {
                if (parts.size != 2) return null
                val fileId = parts.getOrNull(1)?.toLongOrNull()
                    ?: return null
                val parentRef = fileId
                    ?.let { db.fileReferenceDao().getById(it)?.subfolderId }
                    ?.let { "subfolder:$it" }
                AppIndexRefMetadata(
                    objectType = "file",
                    scopeType = "none",
                    scopeId = null,
                    parentRef = parentRef,
                    rootBranch = "hierarchy",
                )
            }

            "chat" -> {
                when (parts.getOrNull(1).orEmpty()) {
                    "general" -> {
                        if (parts.size != 3 || parts.getOrNull(2)?.toLongOrNull() == null) return null
                        AppIndexRefMetadata(
                            objectType = "chat",
                            scopeType = "general",
                            scopeId = null,
                            parentRef = null,
                            rootBranch = "chats_general",
                        )
                    }

                    "parent" -> {
                        if (parts.size != 4) return null
                        val parentId = parts.getOrNull(2)
                        if (parentId?.toLongOrNull() == null || parts.getOrNull(3)?.toLongOrNull() == null) return null
                        AppIndexRefMetadata(
                            objectType = "chat",
                            scopeType = "parent",
                            scopeId = parentId,
                            parentRef = parentId?.let { "parent:$it" },
                            rootBranch = "hierarchy",
                        )
                    }

                    "subfolder" -> {
                        if (parts.size != 4) return null
                        val subfolderId = parts.getOrNull(2)
                        if (subfolderId?.toLongOrNull() == null || parts.getOrNull(3)?.toLongOrNull() == null) return null
                        AppIndexRefMetadata(
                            objectType = "chat",
                            scopeType = "subfolder",
                            scopeId = subfolderId,
                            parentRef = subfolderId?.let { "subfolder:$it" },
                            rootBranch = "hierarchy",
                        )
                    }

                    "panel" -> {
                        if (parts.size != 4 || parts.getOrNull(2).isNullOrBlank() || parts.getOrNull(3)?.toLongOrNull() == null) return null
                        AppIndexRefMetadata(
                            objectType = "chat",
                            scopeType = "panel",
                            scopeId = parts.getOrNull(2),
                            parentRef = null,
                            rootBranch = "hierarchy",
                        )
                    }

                    else -> null
                }
            }

            "quick_note" -> {
                val date = parts.getOrNull(1).orEmpty()
                val ordinal = parts.getOrNull(2)?.toIntOrNull()
                if (parts.size != 3 || !DATE_KEY_REGEX.matches(date) || ordinal == null || ordinal <= 0) return null
                AppIndexRefMetadata(
                    objectType = "quick_note",
                    scopeType = "system",
                    scopeId = null,
                    parentRef = null,
                    rootBranch = "quick_notes",
                )
            }

            "journal" -> {
                val date = parts.getOrNull(1).orEmpty()
                if (parts.size != 2 || !DATE_KEY_REGEX.matches(date)) return null
                AppIndexRefMetadata(
                    objectType = "journal",
                    scopeType = "system",
                    scopeId = null,
                    parentRef = null,
                    rootBranch = "journal",
                )
            }

            "ltm" -> {
                if (parts.size != 2 || parts.getOrNull(1).isNullOrBlank()) return null
                AppIndexRefMetadata(
                    objectType = "ltm",
                    scopeType = "system",
                    scopeId = null,
                    parentRef = null,
                    rootBranch = "ltm",
                )
            }

            "cache" -> {
                if (parts.size != 3 || parts.getOrNull(1) != "subfolder") return null
                val subfolderId = parts.getOrNull(2)
                if (subfolderId?.toLongOrNull() == null) return null
                AppIndexRefMetadata(
                    objectType = "cache",
                    scopeType = "subfolder",
                    scopeId = subfolderId,
                    parentRef = subfolderId?.let { "subfolder:$it" },
                    rootBranch = "hierarchy",
                )
            }

            else -> null
        }
    }

    suspend fun resolveNamesForRef(ref: String): AppIndexNames? {
        val parts = ref.split(":")
        val prefix = parts.firstOrNull().orEmpty().lowercase(Locale.US)
        return when (prefix) {
            "parent" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val parent = db.parentFolderDao().getById(id) ?: return null
                AppIndexNames(objectName = parent.name)
            }

            "subfolder" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val subfolder = db.subfolderDao().getById(id) ?: return null
                val parentName = db.parentFolderDao().getById(subfolder.parentFolderId)?.name
                AppIndexNames(
                    objectName = subfolder.name,
                    parentFolderName = parentName,
                    subfolderName = subfolder.name,
                )
            }

            "note" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val note = db.noteDao().getById(id) ?: return null
                val subfolder = db.subfolderDao().getById(note.subfolderId) ?: return null
                val parentName = db.parentFolderDao().getById(subfolder.parentFolderId)?.name
                AppIndexNames(
                    objectName = subfolder.name,
                    parentFolderName = parentName,
                    subfolderName = subfolder.name,
                )
            }

            "file" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val file = db.fileReferenceDao().getById(id) ?: return null
                val subfolder = db.subfolderDao().getById(file.subfolderId) ?: return null
                val parentName = db.parentFolderDao().getById(subfolder.parentFolderId)?.name
                AppIndexNames(
                    objectName = file.fileName,
                    parentFolderName = parentName,
                    subfolderName = subfolder.name,
                )
            }

            "chat" -> {
                val convoId = when (parts.getOrNull(1).orEmpty()) {
                    "general" -> parts.getOrNull(2)?.toLongOrNull()
                    "parent", "subfolder", "panel_workshop" -> parts.getOrNull(3)?.toLongOrNull()
                    "panel" -> parts.getOrNull(3)?.toLongOrNull()
                    else -> null
                } ?: return null
                val convo = db.conversationDao().getById(convoId) ?: return null
                val title = convo.title.trim().ifBlank { "Chat $convoId" }
                when (convo.scopeType) {
                    "general" -> AppIndexNames(
                        objectName = title,
                        parentFolderName = SystemFolderNames.EIDOS_CHATS,
                    )
                    "parent" -> {
                        val parentName = convo.parentFolderId?.let { db.parentFolderDao().getById(it)?.name }
                        AppIndexNames(objectName = title, parentFolderName = parentName)
                    }
                    "subfolder", "panel_workshop" -> {
                        val subfolder = convo.subfolderId?.let { db.subfolderDao().getById(it) } ?: return null
                        val parentName = db.parentFolderDao().getById(subfolder.parentFolderId)?.name
                        AppIndexNames(
                            objectName = title,
                            parentFolderName = parentName,
                            subfolderName = subfolder.name,
                        )
                    }
                    else -> AppIndexNames(objectName = title)
                }
            }

            "quick_note" -> {
                val date = parts.getOrNull(1).orEmpty()
                val ordinal = parts.getOrNull(2)?.toIntOrNull() ?: return null
                AppIndexNames(
                    objectName = "Quick note $ordinal",
                    parentFolderName = SystemFolderNames.QUICK_NOTES,
                    subfolderName = date,
                )
            }

            "journal" -> {
                val date = parts.getOrNull(1).orEmpty()
                AppIndexNames(
                    objectName = date,
                    parentFolderName = SystemFolderNames.EIDOS_JOURNAL,
                )
            }

            "ltm" -> {
                val slug = parts.getOrNull(1).orEmpty()
                val subfolderId = slug.removePrefix("memory_").toLongOrNull()
                val subfolderName = subfolderId?.let { db.subfolderDao().getById(it)?.name }
                AppIndexNames(
                    objectName = subfolderName ?: slug,
                    parentFolderName = SystemFolderNames.EIDOS_MEMORY,
                    subfolderName = subfolderName,
                )
            }

            "cache" -> {
                val subfolderId = parts.getOrNull(2)?.toLongOrNull() ?: return null
                val subfolder = db.subfolderDao().getById(subfolderId) ?: return null
                val parentName = db.parentFolderDao().getById(subfolder.parentFolderId)?.name
                AppIndexNames(
                    objectName = "Memory cache",
                    parentFolderName = parentName,
                    subfolderName = subfolder.name,
                )
            }

            else -> null
        }
    }

    private suspend fun collectBootstrapSpecs(): List<MaterializedSpec> {
        val specs = mutableListOf<MaterializedSpec>()
        val parents = db.parentFolderDao().getAllActive().first().filter { it.deletedAt == null }
        val parentById = parents.associateBy { it.id }
        val subfoldersByParent = mutableMapOf<Long, List<com.example.optimalx.data.model.Subfolder>>()
        val userSubfolders = linkedMapOf<Long, com.example.optimalx.data.model.Subfolder>()

        parents.forEach { parent ->
            specs += MaterializedSpec(
                ref = "parent:${parent.id}",
                metadata = AppIndexRefMetadata(
                    objectType = "parent",
                    scopeType = "none",
                    scopeId = null,
                    parentRef = null,
                    rootBranch = "hierarchy",
                ),
                hintSeed = parent.name,
                sourceTimestamp = parent.updatedAt,
                names = AppIndexNames(objectName = parent.name),
            )

            val subfolders = db.subfolderDao().getAllByParentOnce(parent.id).filter { it.deletedAt == null }
            subfoldersByParent[parent.id] = subfolders
            subfolders.forEach { subfolder ->
                if (!subfolder.isSystemSubfolder) {
                    userSubfolders[subfolder.id] = subfolder
                }

                specs += MaterializedSpec(
                    ref = "subfolder:${subfolder.id}",
                    metadata = AppIndexRefMetadata(
                        objectType = "subfolder",
                        scopeType = "none",
                        scopeId = null,
                        parentRef = "parent:${subfolder.parentFolderId}",
                        rootBranch = "hierarchy",
                    ),
                    hintSeed = subfolder.name,
                    sourceTimestamp = subfolder.updatedAt,
                    names = AppIndexNames(
                        objectName = subfolder.name,
                        parentFolderName = parent.name,
                        subfolderName = subfolder.name,
                    ),
                )

                db.noteDao().getBySubfolderOnce(subfolder.id)?.let { note ->
                    // Blinded notes must not appear in the materialized Tag & Hint Index;
                    // omitting them here causes any stale tag-hint row to be dropped on the
                    // bootstrap cleanup pass.
                    if (note.deletedAt == null && note.content.isNotBlank() && !note.aiBlind) {
                        specs += MaterializedSpec(
                            ref = "note:${note.id}",
                            metadata = AppIndexRefMetadata(
                                objectType = "note",
                                scopeType = "none",
                                scopeId = null,
                                parentRef = "subfolder:${subfolder.id}",
                                rootBranch = "hierarchy",
                            ),
                            hintSeed = note.content,
                            sourceTimestamp = note.updatedAt,
                            names = AppIndexNames(
                                objectName = subfolder.name,
                                parentFolderName = parent.name,
                                subfolderName = subfolder.name,
                            ),
                        )
                    }
                }

                db.fileReferenceDao().getBySubfolderOnce(subfolder.id).forEach { file ->
                    specs += MaterializedSpec(
                        ref = "file:${file.id}",
                        metadata = AppIndexRefMetadata(
                            objectType = "file",
                            scopeType = "none",
                            scopeId = null,
                            parentRef = "subfolder:${subfolder.id}",
                            rootBranch = "hierarchy",
                        ),
                        hintSeed = listOf(file.fileName, file.fileType).joinToString(" ").trim(),
                        sourceTimestamp = file.createdAt,
                        names = AppIndexNames(
                            objectName = file.fileName,
                            parentFolderName = parent.name,
                            subfolderName = subfolder.name,
                        ),
                    )
                }
            }
        }

        db.conversationDao().getRecentAll(5000).forEach { convo ->
            if (convo.scopeType == "web_editor" || convo.scopeType == "web_widget") return@forEach
            val ref: String
            val metadata: AppIndexRefMetadata
            when (convo.scopeType) {
                "general" -> {
                    ref = "chat:general:${convo.id}"
                    metadata = AppIndexRefMetadata(
                        objectType = "chat",
                        scopeType = "general",
                        scopeId = null,
                        parentRef = null,
                        rootBranch = "chats_general",
                    )
                }

                "parent" -> {
                    val scopeId = convo.parentFolderId?.toString() ?: return@forEach
                    ref = "chat:parent:$scopeId:${convo.id}"
                    metadata = AppIndexRefMetadata(
                        objectType = "chat",
                        scopeType = "parent",
                        scopeId = scopeId,
                        parentRef = "parent:$scopeId",
                        rootBranch = "hierarchy",
                    )
                }

                "subfolder" -> {
                    val scopeId = convo.subfolderId?.toString() ?: return@forEach
                    ref = "chat:subfolder:$scopeId:${convo.id}"
                    metadata = AppIndexRefMetadata(
                        objectType = "chat",
                        scopeType = "subfolder",
                        scopeId = scopeId,
                        parentRef = "subfolder:$scopeId",
                        rootBranch = "hierarchy",
                    )
                }

                "panel_workshop" -> {
                    val scopeId = convo.subfolderId?.toString() ?: return@forEach
                    ref = "chat:panel_workshop:$scopeId:${convo.id}"
                    metadata = AppIndexRefMetadata(
                        objectType = "chat",
                        scopeType = "panel_workshop",
                        scopeId = scopeId,
                        parentRef = "subfolder:$scopeId",
                        rootBranch = "hierarchy",
                    )
                }

                else -> {
                    ref = "chat:panel:${convo.scopeType}:${convo.id}"
                    metadata = AppIndexRefMetadata(
                        objectType = "chat",
                        scopeType = "panel",
                        scopeId = convo.scopeType,
                        parentRef = null,
                        rootBranch = "hierarchy",
                    )
                }
            }

            specs += MaterializedSpec(
                ref = ref,
                metadata = metadata,
                hintSeed = convo.title,
                sourceTimestamp = convo.updatedAt,
                names = chatNames(convo),
            )
        }

        parents.firstOrNull { it.name == SystemFolderNames.QUICK_NOTES }?.let { quickNotesParent ->
            val dayFolders = subfoldersByParent[quickNotesParent.id]
                ?: db.subfolderDao().getAllByParentOnce(quickNotesParent.id).filter { it.deletedAt == null }
            dayFolders
                .filter { !it.isSystemSubfolder }
                .forEach { daySubfolder ->
                    val note = db.noteDao().getBySubfolderOnce(daySubfolder.id) ?: return@forEach
                    if (note.aiBlind) return@forEach
                    val blocks = note.content
                        .split(Regex("\n\\s*\n"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    blocks.forEachIndexed { idx, block ->
                        specs += MaterializedSpec(
                            ref = "quick_note:${daySubfolder.name}:${idx + 1}",
                            metadata = AppIndexRefMetadata(
                                objectType = "quick_note",
                                scopeType = "system",
                                scopeId = null,
                                parentRef = null,
                                rootBranch = "quick_notes",
                            ),
                            hintSeed = block,
                            sourceTimestamp = note.updatedAt,
                            names = AppIndexNames(
                                objectName = "Quick note ${idx + 1}",
                                parentFolderName = SystemFolderNames.QUICK_NOTES,
                                subfolderName = daySubfolder.name,
                            ),
                        )
                    }
                }
        }

        parents.firstOrNull { it.name == SystemFolderNames.EIDOS_JOURNAL }?.let { journalParent ->
            val journalFolders = subfoldersByParent[journalParent.id]
                ?: db.subfolderDao().getAllByParentOnce(journalParent.id).filter { it.deletedAt == null }
            journalFolders.forEach { subfolder ->
                val note = db.noteDao().getBySubfolderOnce(subfolder.id) ?: return@forEach
                if (note.aiBlind) return@forEach
                if (note.content.isBlank()) return@forEach
                specs += MaterializedSpec(
                    ref = "journal:${subfolder.name}",
                    metadata = AppIndexRefMetadata(
                        objectType = "journal",
                        scopeType = "system",
                        scopeId = null,
                        parentRef = null,
                        rootBranch = "journal",
                    ),
                    hintSeed = note.content,
                    sourceTimestamp = note.updatedAt,
                    names = AppIndexNames(
                        objectName = subfolder.name,
                        parentFolderName = SystemFolderNames.EIDOS_JOURNAL,
                    ),
                )
            }
        }

        parents.firstOrNull { it.name == SystemFolderNames.EIDOS_MEMORY }?.let { memoryParent ->
            val memoryFolders = subfoldersByParent[memoryParent.id]
                ?: db.subfolderDao().getAllByParentOnce(memoryParent.id).filter { it.deletedAt == null }
            memoryFolders.forEach { subfolder ->
                val note = db.noteDao().getBySubfolderOnce(subfolder.id) ?: return@forEach
                if (note.aiBlind) return@forEach
                if (note.content.isBlank()) return@forEach
                specs += MaterializedSpec(
                    ref = "ltm:memory_${subfolder.id}",
                    metadata = AppIndexRefMetadata(
                        objectType = "ltm",
                        scopeType = "system",
                        scopeId = null,
                        parentRef = null,
                        rootBranch = "ltm",
                    ),
                    hintSeed = note.content,
                    sourceTimestamp = note.updatedAt,
                    names = AppIndexNames(
                        objectName = subfolder.name,
                        parentFolderName = SystemFolderNames.EIDOS_MEMORY,
                        subfolderName = subfolder.name,
                    ),
                )
            }
        }

        userSubfolders.values.forEach { subfolder ->
            val parent = parentById[subfolder.parentFolderId] ?: return@forEach
            val parentSubfolders = subfoldersByParent[parent.id]
                ?: db.subfolderDao().getAllByParentOnce(parent.id).filter { it.deletedAt == null }
            val cacheSubfolder = parentSubfolders.firstOrNull {
                it.isSystemSubfolder &&
                    (it.name == SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER || it.name == "__memory_cache__")
            } ?: return@forEach
            val cacheNote = db.noteDao().getBySubfolderOnce(cacheSubfolder.id) ?: return@forEach
            if (cacheNote.aiBlind) return@forEach
            if (cacheNote.content.isBlank()) return@forEach
            val cacheObj = runCatching { json.parseToJsonElement(cacheNote.content).jsonObject }.getOrNull() ?: return@forEach
            val cacheText = (cacheObj[subfolder.id.toString()] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (cacheText.isBlank()) return@forEach

            specs += MaterializedSpec(
                ref = "cache:subfolder:${subfolder.id}",
                metadata = AppIndexRefMetadata(
                    objectType = "cache",
                    scopeType = "subfolder",
                    scopeId = subfolder.id.toString(),
                    parentRef = "subfolder:${subfolder.id}",
                    rootBranch = "hierarchy",
                ),
                hintSeed = cacheText,
                sourceTimestamp = cacheNote.updatedAt,
                names = AppIndexNames(
                    objectName = "Memory cache",
                    parentFolderName = parent.name,
                    subfolderName = subfolder.name,
                ),
            )
        }

        return specs
    }

    private suspend fun upsertMaterialized(spec: MaterializedSpec) {
        val now = clock()
        val existing = tagHintLineDao.getByRef(spec.ref)
        val (tag, hint) = if (existing == null) {
            defaultSemantics(
                hintSeed = spec.hintSeed,
                objectName = spec.names.objectName,
                ref = spec.ref,
                objectType = spec.metadata.objectType,
            )
        } else {
            existing.tag to existing.hint
        }

        val line = TagHintLine(
            id = existing?.id ?: 0,
            ref = spec.ref,
            objectType = spec.metadata.objectType,
            scopeType = spec.metadata.scopeType,
            scopeId = spec.metadata.scopeId,
            parentRef = spec.metadata.parentRef,
            rootBranch = spec.metadata.rootBranch,
            tag = tag,
            hint = hint,
            objectName = spec.names.objectName,
            parentFolderName = spec.names.parentFolderName,
            subfolderName = spec.names.subfolderName,
            date = existing?.date ?: spec.sourceTimestamp,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        tagHintLineDao.upsertByRef(line)
    }

    private suspend fun chatNames(convo: Conversation): AppIndexNames {
        val title = convo.title.trim().ifBlank { "Chat ${convo.id}" }
        return when (convo.scopeType) {
            "general" -> AppIndexNames(
                objectName = title,
                parentFolderName = SystemFolderNames.EIDOS_CHATS,
            )
            "parent" -> {
                val parentName = convo.parentFolderId?.let { db.parentFolderDao().getById(it)?.name }
                AppIndexNames(objectName = title, parentFolderName = parentName)
            }
            "subfolder", "panel_workshop" -> {
                val subfolder = convo.subfolderId?.let { db.subfolderDao().getById(it) }
                val parentName = subfolder?.parentFolderId?.let { db.parentFolderDao().getById(it)?.name }
                AppIndexNames(
                    objectName = title,
                    parentFolderName = parentName,
                    subfolderName = subfolder?.name,
                )
            }
            else -> AppIndexNames(objectName = title)
        }
    }

    private fun defaultSemantics(
        hintSeed: String,
        objectName: String,
        ref: String,
        objectType: String,
    ): Pair<String, String> {
        val normalized = normalizeHintSeed(hintSeed)
        val tag = when {
            objectName.isNotBlank() && objectName.length <= 48 -> objectName
            else -> firstWords(normalized, maxWords = 4).take(48)
        }.ifBlank { objectType.replace('_', ' ') }
        val hint = when {
            normalized.isNotBlank() -> normalized.take(180)
            objectName.isNotBlank() -> objectName.take(180)
            else -> "Indexed ${objectType.replace('_', ' ')} ($ref)"
        }
        return tag to hint
    }

    private fun firstWords(text: String, maxWords: Int): String {
        return text.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(maxWords)
            .joinToString(" ")
    }

    private fun normalizeHintSeed(raw: String): String {
        return raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
    }

    private data class MaterializedSpec(
        val ref: String,
        val metadata: AppIndexRefMetadata,
        val hintSeed: String,
        val sourceTimestamp: Long,
        val names: AppIndexNames,
    )

    private companion object {
        val DATE_KEY_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
