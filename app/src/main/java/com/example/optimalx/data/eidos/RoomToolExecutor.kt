package com.example.optimalx.data.eidos

import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.eidos.model.ToolExecutor
import com.example.optimalx.data.imagestudio.ListImagesTool
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.revision.CheckpointRepository
import com.example.optimalx.data.revision.ContentDiff
import com.example.optimalx.data.revision.DirectWriteApplier
import com.example.optimalx.data.revision.NoteIndexer
import com.example.optimalx.data.revision.NoteWriteOutcome
import com.example.optimalx.data.revision.NoteWriteRouter
import com.example.optimalx.data.revision.PendingChangeService
import com.example.optimalx.data.revision.WorkshopFileIndexer
import com.example.optimalx.data.revision.WorkshopWriteOutcome
import com.example.optimalx.data.revision.WorkshopWriteRouter
import com.example.optimalx.data.semantic.ContentSectionRetriever
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SegmentMode
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticScopeSearch
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticObjectType
import com.example.optimalx.data.semantic.SemanticSyncService
import com.example.optimalx.ui.components.NoteContentCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

class RoomToolExecutor(
    private val context: Context,
    private val db: AppDatabase,
    embeddingEngine: EmbeddingEngine = EmbeddingEngine(context),
    private val semanticIndexer: SemanticIndexer = SemanticIndexer(db, embeddingEngine),
    private val folderRepository: FolderRepository? = null,
    private val panelBridgeRegistry: PanelBridgeRegistry? = null,
    private val semanticSync: SemanticSyncService? = null,
    private val onNoteContentSaved: (subfolderId: Long) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ToolExecutor {

    private val visionService = ImageVisionService(context = context, json = json)
    private val fileTextExtractor = FileTextExtractor(context)
    private val contentSectionRetriever = ContentSectionRetriever(
        embeddingEngine = embeddingEngine,
    )
    private val semanticChunkBuilder = SemanticChunkBuilder(
        noteDao = db.noteDao(),
        subfolderDao = db.subfolderDao(),
        parentFolderDao = db.parentFolderDao(),
        conversationDao = db.conversationDao(),
        chatMessageDao = db.chatMessageDao(),
    )
    private val eidosLogWriter = EidosLogWriter(db, semanticIndexer, semanticChunkBuilder)
    private val checkpointRepository = CheckpointRepository(
        checkpointDao = db.contentCheckpointDao(),
        patchDao = db.contentPatchDao(),
        pendingChangeDao = db.pendingChangeDao(),
    )
    private val noteIndexer = NoteIndexer { subfolderId ->
        val note = db.noteDao().getBySubfolderOnce(subfolderId) ?: return@NoteIndexer
        val subfolder = db.subfolderDao().getById(subfolderId)
        val parent = subfolder?.let { db.parentFolderDao().getById(it.parentFolderId) }
        if (note.aiBlind ||
            (subfolder != null && !EidosRetrievalGuard.shouldIndexNoteForEidos(subfolder, parent))
        ) {
            semanticIndexer.deleteObject(SemanticObjectType.NOTE, subfolderId)
        } else {
            semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
        }
        semanticSync?.requestSync("note_tool:$subfolderId")
    }
    private val directWriteApplier = DirectWriteApplier(
        fileReferenceDao = db.fileReferenceDao(),
        checkpointRepository = checkpointRepository,
        indexer = WorkshopFileIndexer { ref, content ->
            semanticChunkBuilder.indexFile(semanticIndexer, ref, content)
        },
        noteDao = db.noteDao(),
        noteIndexer = noteIndexer,
        workshopFilePath = { subfolderId, fileName ->
            val dir = File(context.filesDir, "workshop/$subfolderId").also { it.mkdirs() }
            File(dir, fileName)
        },
        onNoteContentSaved = onNoteContentSaved,
    )
    private val pendingChangeService = PendingChangeService(
        pendingDao = db.pendingChangeDao(),
        fileReferenceDao = db.fileReferenceDao(),
        noteDao = db.noteDao(),
        checkpointRepository = checkpointRepository,
        directWriteApplier = directWriteApplier,
    )
    private val workshopWriteRouter = WorkshopWriteRouter(
        directWriteApplier = directWriteApplier,
        pendingChangeService = pendingChangeService,
        phaseProvider = { WorkshopEidosSession.currentPhase() },
        modeProvider = { WorkshopEidosSession.currentMode() },
        conversationIdProvider = { WorkshopEidosSession.currentConversationId() },
    )
    private val noteWriteRouter = NoteWriteRouter(
        noteDao = db.noteDao(),
        pendingDao = db.pendingChangeDao(),
        directWriteApplier = directWriteApplier,
        pendingChangeService = pendingChangeService,
        checkpointRepository = checkpointRepository,
        conversationIdProvider = { WorkshopEidosSession.currentConversationId() },
    )

    init {
        PDFBoxResourceLoader.init(context)
    }

    override suspend fun execute(toolName: String, argumentsJson: String): ToolExecutionResult {
        val args = runCatching { parseArgs(argumentsJson) }
            .getOrElse { t -> return ToolExecutionResult.Failure(t.message ?: "Invalid tool arguments") }
        val result = runCatching {
            when (toolName) {
                // 6.1 Folder tools
                "create_parent_folder" -> createParentFolder(args)
                "create_subfolder" -> createSubfolder(args)
                "rename_folder" -> renameFolder(args)
                "move_to_trash" -> moveToTrash(args)
                "list_folder_contents" -> listFolderContents(args)
                "search_folders" -> searchFolders(args)

                // 6.2 Note tools
                "read_dump_edit" -> readDumpEdit(args)
                "read_note" -> readNote(args)
                "read_note_section" -> readNoteSection(args)
                "write_note" -> writeNote(args)
                "edit_note_section" -> editNoteSection(args)
                "write_note_summary" -> writeNoteSummary(args)

                // 6.3 File tools
                "read_file" -> readFile(args)
                "workshop_create_file" -> workshopCreateFile(args)
                "workshop_write_file" -> workshopWriteFile(args)
                "workshop_edit_file" -> workshopEditFile(args)
                "workshop_append_file" -> workshopAppendFile(args)
                "workshop_read_file" -> workshopReadFile(args)
                "workshop_list_pending_review" -> workshopListPendingReview(args)
                "call_panel_function" -> callPanelFunction(args)
                "read_conversation" -> readConversation(args)
                "describe_image" -> describeImage(args)
                "list_images" -> listImages(args)

                // 6.4 Search tool
                "search_chat_history" -> searchChatHistory(args)
                "search_semantic" -> searchSemantic(args)

                // 6.5 Memory tools
                "read_daily_memory" -> readDailyMemory(args)
                "write_daily_memory" -> writeDailyMemory(args)
                "clear_daily_memory" -> clearDailyMemory(args)
                "read_long_term_memory" -> readLongTermMemory(args)
                "write_long_term_memory" -> writeLongTermMemory(args)
                "prune_long_term_memory" -> pruneLongTermMemory(args)

                // 6.6 Journal tools
                "write_journal_entry" -> writeJournalEntry(args)
                "read_journal" -> readJournal(args)

                // 6.7 Log tools
                "write_log_entry" -> writeLogEntry(args)
                "read_log" -> readLog(args)

                // Quick Notes
                "write_quick_note" -> writeQuickNote(args)

                else -> ToolExecutionResult.Failure("Unknown tool: $toolName")
            }
        }.getOrElse { t ->
            ToolExecutionResult.Failure(t.message ?: "Tool error")
        }
        if (shouldWriteAutomaticAudit(toolName)) {
            runCatching { writeAutomaticAuditEntry(toolName = toolName, args = args, result = result) }
        }
        return result
    }

    private fun shouldWriteAutomaticAudit(toolName: String): Boolean {
        return toolName != "write_log_entry"
    }

    private suspend fun writeAutomaticAuditEntry(
        toolName: String,
        args: JsonObject,
        result: ToolExecutionResult,
    ) {
        val timestamp = System.currentTimeMillis()
        val location = firstNonBlank(
            args.string("location"),
            args.string("ref"),
            args.string("subfolderId")?.let { "subfolder:$it" },
            args.string("parentFolderId")?.let { "parent:$it" },
            args.string("folderId")?.let { resolveFolderLocation(it) },
            args.string("conversationId")?.let { "conversation:$it" },
        )
        val summary = buildAuditSummary(toolName = toolName, args = args, result = result)
        writeLogEntry(
            buildJsonObject {
                put("action", JsonPrimitive(summary))
                put("timestamp", JsonPrimitive(timestamp))
                if (!location.isNullOrBlank()) put("location", JsonPrimitive(location))
            }
        )
    }

    private suspend fun resolveFolderLocation(folderIdRaw: String): String {
        val folderId = folderIdRaw.toLongOrNull() ?: return "folder:$folderIdRaw"
        val parent = db.parentFolderDao().getById(folderId)
        if (parent != null) return "parent:${parent.id}:${parent.name}"
        val subfolder = db.subfolderDao().getById(folderId)
        if (subfolder != null) return "subfolder:${subfolder.id}:${subfolder.name}"
        return "folder:$folderIdRaw"
    }

    private fun buildAuditSummary(
        toolName: String,
        args: JsonObject,
        result: ToolExecutionResult,
    ): String {
        val outcome = when (result) {
            is ToolExecutionResult.Success -> "success"
            is ToolExecutionResult.Failure -> "failure"
        }
        val details = mutableListOf<String>()
        listOf(
            "folderId",
            "parentFolderId",
            "subfolderId",
            "fileReferenceId",
            "conversationId",
            "ref",
            "name",
            "newName",
            "anchorText",
            "state",
        ).forEach { key ->
            args.string(key)?.takeIf { it.isNotBlank() }?.let { details += "$key=$it" }
        }
        if (result is ToolExecutionResult.Failure) {
            details += "error=${result.message.take(220)}"
        }
        if (details.isEmpty()) {
            val compactArgs = args.toString().replace('\n', ' ').take(220)
            if (compactArgs.isNotBlank() && compactArgs != "{}") {
                details += "args=$compactArgs"
            }
        }
        return buildString {
            append("audit tool=$toolName outcome=$outcome")
            if (details.isNotEmpty()) {
                append(" | ")
                append(details.joinToString(" | "))
            }
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private suspend fun createParentFolder(args: JsonObject): ToolExecutionResult {
        val name = args.string("name").orEmpty().trim()
        if (name.isEmpty()) return ToolExecutionResult.Failure("name is required")

        val id = db.parentFolderDao().insert(ParentFolder(name = name))
        return ToolExecutionResult.Success(
            content = "Created parent folder '$name' (id=$id)",
            modifiedSystem = true,
        )
    }

    private suspend fun createSubfolder(args: JsonObject): ToolExecutionResult {
        val parentId = args.long("parentFolderId") ?: return ToolExecutionResult.Failure("parentFolderId is required")
        val name = args.string("name").orEmpty().trim()
        if (name.isEmpty()) return ToolExecutionResult.Failure("name is required")

        val parent = db.parentFolderDao().getById(parentId)
            ?: return ToolExecutionResult.Failure("Parent folder not found")
        if (parent.deletedAt != null) return ToolExecutionResult.Failure("Parent folder is deleted")
        if (parent.name == SystemFolderNames.QUICK_NOTES) {
            return ToolExecutionResult.Failure(
                "Cannot create subfolders inside Quick Notes manually; use write_quick_note for dated captures.",
            )
        }

        val now = System.currentTimeMillis()
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = name, updatedAt = now),
        )
        db.noteDao().insert(Note(subfolderId = subfolderId, updatedAt = now))
        db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = "__chat_${subfolderId}__",
                isSystemSubfolder = true,
                updatedAt = now,
            )
        )

        return ToolExecutionResult.Success(
            content = "Created subfolder '$name' (id=$subfolderId) under parent '${parent.name}'",
            modifiedSystem = true,
        )
    }

    private suspend fun renameFolder(args: JsonObject): ToolExecutionResult {
        val folderId = args.long("folderId") ?: return ToolExecutionResult.Failure("folderId is required")
        val newName = args.string("newName").orEmpty().trim()
        if (newName.isEmpty()) return ToolExecutionResult.Failure("newName is required")

        val now = System.currentTimeMillis()

        val parent = db.parentFolderDao().getById(folderId)
        if (parent != null) {
            if (parent.isSystemFolder) return ToolExecutionResult.Failure("System folders cannot be renamed")
            db.parentFolderDao().update(parent.copy(name = newName, updatedAt = now))
            return ToolExecutionResult.Success(
                content = "Renamed parent folder '${parent.name}' to '$newName'",
                modifiedSystem = true,
            )
        }

        val subfolder = db.subfolderDao().getById(folderId)
            ?: return ToolExecutionResult.Failure("Folder not found")
        if (subfolder.isSystemSubfolder) return ToolExecutionResult.Failure("System subfolders cannot be renamed")

        db.subfolderDao().update(subfolder.copy(name = newName, updatedAt = now))
        return ToolExecutionResult.Success(
            content = "Renamed subfolder '${subfolder.name}' to '$newName'",
            modifiedSystem = true,
        )
    }

    private suspend fun moveToTrash(args: JsonObject): ToolExecutionResult {
        val folderId = args.long("folderId") ?: return ToolExecutionResult.Failure("folderId is required")
        val now = System.currentTimeMillis()

        val parent = db.parentFolderDao().getById(folderId)
        if (parent != null) {
            if (parent.isSystemFolder) return ToolExecutionResult.Failure("System folders cannot be moved to trash")
            db.parentFolderDao().softDelete(parent.id, now)
            db.subfolderDao().softDeleteByParent(parent.id, now)
            db.noteDao().softDeleteByParentFolder(parent.id, now)
            return ToolExecutionResult.Success(
                content = "Moved parent folder '${parent.name}' and descendants to trash",
                modifiedSystem = true,
            )
        }

        val subfolder = db.subfolderDao().getById(folderId)
            ?: return ToolExecutionResult.Failure("Folder not found")
        if (subfolder.isSystemSubfolder) return ToolExecutionResult.Failure("System subfolders cannot be moved to trash")

        db.subfolderDao().softDelete(subfolder.id, now)
        db.noteDao().softDeleteBySubfolder(subfolder.id, now)
        return ToolExecutionResult.Success(
            content = "Moved subfolder '${subfolder.name}' to trash",
            modifiedSystem = true,
        )
    }

    private suspend fun listFolderContents(args: JsonObject): ToolExecutionResult {
        val folderId = args.long("folderId") ?: return ToolExecutionResult.Failure("folderId is required")

        val parent = db.parentFolderDao().getById(folderId)
        if (parent != null) {
            val subfolders = db.subfolderDao().getAllByParentOnce(parent.id)
                .filter { it.deletedAt == null && !it.isSystemSubfolder }
            return ToolExecutionResult.Success(
                content = buildJsonObject {
                    put("parentFolderId", JsonPrimitive(parent.id))
                    put("parentName", JsonPrimitive(parent.name))
                    put("subfolders", buildJsonArray {
                        subfolders.forEach { sf ->
                            add(
                                buildJsonObject {
                                    put("subfolderId", JsonPrimitive(sf.id))
                                    put("name", JsonPrimitive(sf.name))
                                    put("updatedAt", JsonPrimitive(sf.updatedAt))
                                }
                            )
                        }
                    })
                }.toString(),
                modifiedSystem = false,
            )
        }

        val subfolder = db.subfolderDao().getById(folderId)
            ?: return ToolExecutionResult.Failure("Folder not found")
        val parentFolder = db.parentFolderDao().getById(subfolder.parentFolderId)
        if (!EidosRetrievalGuard.shouldExposeNoteToEidos(subfolder, parentFolder)) {
            return ToolExecutionResult.Failure("Folder not available to Eidos")
        }
        val note = db.noteDao().getBySubfolderOnce(subfolder.id)
        val files = db.fileReferenceDao().getBySubfolderOnce(subfolder.id)

        // Blinded notes must not leak any preview text through the folder listing.
        val blinded = note?.aiBlind == true
        val previewText = if (blinded) {
            ""
        } else {
            noteStorageMarkdown(note?.content.orEmpty()).take(300)
        }
        return ToolExecutionResult.Success(
            content = buildJsonObject {
                put("subfolderId", JsonPrimitive(subfolder.id))
                put("subfolderName", JsonPrimitive(subfolder.name))
                put("parentFolderId", JsonPrimitive(subfolder.parentFolderId))
                parentFolder?.name?.let { put("parentName", JsonPrimitive(it)) }
                put("notePreview", JsonPrimitive(previewText))
                if (blinded) {
                    put("noteBlind", JsonPrimitive(true))
                }
                put("fileCount", JsonPrimitive(files.size))
                put("files", buildJsonArray {
                    files.forEach { f ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(f.id))
                                put("name", JsonPrimitive(f.fileName))
                                put("type", JsonPrimitive(f.fileType))
                            }
                        )
                    }
                })
            }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun searchFolders(args: JsonObject): ToolExecutionResult {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolExecutionResult.Failure("query is required")
        val limit = (args.int("limit") ?: 20).coerceIn(1, 50)
        val parentScopeId = args.long("parentFolderId")

        val nameMatchedSubfolderIds = db.subfolderDao().searchActive(query).map { it.id }.toSet()
        val rawResults = folderRepository?.searchAll(query)
            ?: searchFoldersFallback(query)

        val matches = buildJsonArray {
            rawResults
                .asSequence()
                .filter { row ->
                    when {
                        parentScopeId == null -> true
                        row.isSubfolder -> row.parentFolderId == parentScopeId
                        else -> row.id == parentScopeId
                    }
                }
                .take(limit)
                .forEach { row ->
                    add(
                        when {
                            !row.isSubfolder -> {
                                buildJsonObject {
                                    put("matchType", JsonPrimitive("parent"))
                                    put("parentFolderId", JsonPrimitive(row.id))
                                    put("parentName", JsonPrimitive(row.name))
                                    put("location", JsonPrimitive(row.name))
                                }
                            }
                            row.id in nameMatchedSubfolderIds -> {
                                buildJsonObject {
                                    put("matchType", JsonPrimitive("subfolder"))
                                    put("subfolderId", JsonPrimitive(row.id))
                                    put("subfolderName", JsonPrimitive(row.name))
                                    put("parentFolderId", JsonPrimitive(row.parentFolderId))
                                    put("parentName", JsonPrimitive(row.parentFolderName))
                                    put(
                                        "location",
                                        JsonPrimitive(
                                            listOf(row.parentFolderName, row.name)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" / "),
                                        ),
                                    )
                                    put("optimalxUri", JsonPrimitive("optimalx://note/${row.id}"))
                                    put(
                                        "write_note",
                                        buildJsonObject { put("subfolderId", JsonPrimitive(row.id)) },
                                    )
                                }
                            }
                            else -> {
                                buildJsonObject {
                                    put("matchType", JsonPrimitive("note_content"))
                                    put("subfolderId", JsonPrimitive(row.id))
                                    put("subfolderName", JsonPrimitive(row.name))
                                    put("parentFolderId", JsonPrimitive(row.parentFolderId))
                                    put("parentName", JsonPrimitive(row.parentFolderName))
                                    put(
                                        "location",
                                        JsonPrimitive(
                                            listOf(row.parentFolderName, row.name)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" / "),
                                        ),
                                    )
                                    put("noteSnippet", JsonPrimitive(row.noteSnippet))
                                }
                            }
                        },
                    )
                }
        }

        return ToolExecutionResult.Success(
            content = buildJsonObject { put("matches", matches) }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun searchFoldersFallback(query: String): List<com.example.optimalx.data.repository.SearchResult> {
        val results = mutableListOf<com.example.optimalx.data.repository.SearchResult>()
        val seen = mutableSetOf<Long>()
        db.parentFolderDao().searchActive(query).forEach { parent ->
            results.add(
                com.example.optimalx.data.repository.SearchResult(
                    id = parent.id,
                    name = parent.name,
                    isSubfolder = false,
                    parentFolderId = 0,
                    parentFolderName = "",
                    noteSnippet = "",
                ),
            )
        }
        db.subfolderDao().searchActive(query).forEach { subfolder ->
            seen.add(subfolder.id)
            val parent = db.parentFolderDao().getById(subfolder.parentFolderId)
            results.add(
                com.example.optimalx.data.repository.SearchResult(
                    id = subfolder.id,
                    name = subfolder.name,
                    isSubfolder = true,
                    parentFolderId = subfolder.parentFolderId,
                    parentFolderName = parent?.name.orEmpty(),
                    noteSnippet = "",
                ),
            )
        }
        db.noteDao().searchContent(query).forEach { note ->
            if (note.subfolderId in seen) return@forEach
            val subfolder = db.subfolderDao().getById(note.subfolderId) ?: return@forEach
            if (subfolder.deletedAt != null || subfolder.isSystemSubfolder) return@forEach
            seen.add(subfolder.id)
            val parent = db.parentFolderDao().getById(subfolder.parentFolderId)
            val idx = note.content.indexOf(query, ignoreCase = true)
            val snippet = if (idx >= 0) {
                note.content.substring(
                    maxOf(0, idx - 20),
                    minOf(note.content.length, idx + 80),
                ).trim()
            } else {
                note.content.take(100)
            }
            results.add(
                com.example.optimalx.data.repository.SearchResult(
                    id = subfolder.id,
                    name = subfolder.name,
                    isSubfolder = true,
                    parentFolderId = subfolder.parentFolderId,
                    parentFolderName = parent?.name.orEmpty(),
                    noteSnippet = "...$snippet...",
                ),
            )
        }
        return results
    }

    private suspend fun readDumpEdit(args: JsonObject): ToolExecutionResult {
        val state = DumpEditPreferences.readState(context)
        if (state.aiBlind) {
            return ToolExecutionResult.Failure("DumpEdit buffer is blind from Eidos (content is private)")
        }
        if (state.content.isBlank()) {
            return ToolExecutionResult.Success("DumpEdit buffer is empty.", modifiedSystem = false)
        }
        return ToolExecutionResult.Success(
            content = formatScopedTextRead(
                fullText = noteStorageMarkdown(state.content),
                query = args.string("query"),
                startLine = args.int("startLine"),
                endLine = args.int("endLine"),
                mode = SegmentMode.NOTE,
                summary = null,
            ),
            modifiedSystem = false,
        )
    }

    private suspend fun readNote(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId")
            ?: return ToolExecutionResult.Failure("subfolderId is required")
        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        noteAccessGuard(note)?.let { return it }
        val content = noteStorageMarkdown(
            pendingChangeService.effectiveWorkingContentForNote(subfolderId),
        )
        if (content.isBlank()) {
            return ToolExecutionResult.Success("Note is empty.", modifiedSystem = false)
        }
        return ToolExecutionResult.Success(
            content = formatScopedTextRead(
                fullText = content,
                query = args.string("query"),
                startLine = args.int("startLine"),
                endLine = args.int("endLine"),
                mode = SegmentMode.NOTE,
                summary = NoteSummaryCodec.parse(note.summary).contentDigest.takeIf { it.isNotBlank() },
            ),
            modifiedSystem = false,
        )
    }

    private suspend fun readNoteSection(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId")
            ?: return ToolExecutionResult.Failure("subfolderId is required")
        val startLine = args.int("startLine")
            ?: return ToolExecutionResult.Failure("startLine is required")
        val endLine = args.int("endLine")
            ?: return ToolExecutionResult.Failure("endLine is required")

        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        noteAccessGuard(note)?.let { return it }

        val content = noteStorageMarkdown(
            pendingChangeService.effectiveWorkingContentForNote(subfolderId),
        )
        return ToolExecutionResult.Success(
            content = contentSectionRetriever.lineRangeJson(
                fullText = content,
                startLine = startLine,
                endLine = endLine,
                contextBefore = args.int("contextBefore") ?: 0,
                contextAfter = args.int("contextAfter") ?: 0,
            ).json,
            modifiedSystem = false,
        )
    }

    private suspend fun writeNote(args: JsonObject): ToolExecutionResult {
        when (val resolved = resolveNoteSubfolderTarget(args)) {
            is NoteSubfolderResolve.Failure -> return ToolExecutionResult.Failure(resolved.message)
            is NoteSubfolderResolve.Success -> {
                val subfolderId = resolved.subfolderId
                val content = args.resolveToolContent()
                    ?: return ToolExecutionResult.Failure(
                        "content is required — pass markdown as the \"content\" string parameter (not contentMarkdown).",
                    )
                val note = db.noteDao().getBySubfolderOnce(subfolderId)
                    ?: return ToolExecutionResult.Failure("Note not found")
                noteAccessGuard(note)?.let { return it }
                return noteWriteRouter.applyOrProposeMerge(subfolderId, noteStorageMarkdown(content)).toToolResult()
            }
        }
    }

    private sealed class NoteSubfolderResolve {
        data class Success(val subfolderId: Long) : NoteSubfolderResolve()
        data class Failure(val message: String) : NoteSubfolderResolve()
    }

    private suspend fun resolveNoteSubfolderTarget(args: JsonObject): NoteSubfolderResolve {
        args.long("subfolderId")?.let { return NoteSubfolderResolve.Success(it) }
        val name = args.string("subfolderName") ?: args.string("name")
            ?: return NoteSubfolderResolve.Failure("subfolderId or subfolderName is required")
        val parentFolderId = args.long("parentFolderId")
        val parentName = args.string("parentName")?.trim().orEmpty()
        val candidates = db.subfolderDao().searchActive(name)
            .filter { it.deletedAt == null && !it.isSystemSubfolder }
            .filter { subfolder ->
                when {
                    parentFolderId != null -> subfolder.parentFolderId == parentFolderId
                    parentName.isNotEmpty() -> {
                        db.parentFolderDao().getById(subfolder.parentFolderId)?.name
                            ?.contains(parentName, ignoreCase = true) == true
                    }
                    else -> true
                }
            }
        return when (candidates.size) {
            0 -> NoteSubfolderResolve.Failure(
                "No subfolder named \"$name\" found. Use search_folders(query=…) first.",
            )
            1 -> NoteSubfolderResolve.Success(candidates.first().id)
            else -> NoteSubfolderResolve.Failure(
                "Multiple subfolders match \"$name\" — pass parentFolderId or parentName, or use search_folders.",
            )
        }
    }

    private suspend fun editNoteSection(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId")
            ?: return ToolExecutionResult.Failure("subfolderId is required")
        val newContent = args.string("newContent")
            ?: return ToolExecutionResult.Failure("newContent is required")

        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        noteAccessGuard(note)?.let { return it }

        val current = noteStorageMarkdown(
            pendingChangeService.effectiveWorkingContentForNote(subfolderId),
        )
        when (
            val built = NoteSectionEdit.buildProposedContent(
                current = current,
                newContent = noteStorageMarkdown(newContent),
                startLine = args.int("startLine"),
                endLine = args.int("endLine"),
                oldString = args.string("oldString"),
                expectedContent = args.string("expectedContent"),
            )
        ) {
            is NoteSectionEdit.Result.Error -> return ToolExecutionResult.Failure(built.message)
            is NoteSectionEdit.Result.Ok -> {
                return noteWriteRouter.applyOrProposeContent(
                    subfolderId = subfolderId,
                    proposedContent = built.content,
                    label = "Section edit",
                    successMessage = "Note section replaced",
                ).toToolResult()
            }
        }
    }

    private suspend fun writeNoteSummary(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId")
            ?: return ToolExecutionResult.Failure("subfolderId is required")
        val mode = args.string("mode")?.trim()?.lowercase(Locale.US)
            ?: return ToolExecutionResult.Failure("mode is required")
        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        if (note.aiBlind) {
            return ToolExecutionResult.Failure("Note is blind from Eidos (content is private)")
        }

        val sections = NoteSummaryCodec.parse(note.summary)
        val editResult = when (mode) {
            "append" -> {
                val item = args.string("item")?.trim().orEmpty()
                if (item.isEmpty()) {
                    return ToolExecutionResult.Failure("item is required for append")
                }
                NoteSummaryCodec.appendMemoryBullet(sections, item)
            }
            "replace" -> {
                val match = args.string("match")?.trim().orEmpty()
                val item = args.string("item")?.trim().orEmpty()
                if (match.isEmpty()) {
                    return ToolExecutionResult.Failure("match is required for replace")
                }
                if (item.isEmpty()) {
                    return ToolExecutionResult.Failure("item is required for replace")
                }
                NoteSummaryCodec.replaceMemoryBullet(sections, match, item)
            }
            "remove" -> {
                val match = args.string("match")?.trim().orEmpty()
                if (match.isEmpty()) {
                    return ToolExecutionResult.Failure("match is required for remove")
                }
                NoteSummaryCodec.removeMemoryBullet(sections, match)
            }
            "set" -> {
                val item = args.string("item")?.trim().orEmpty()
                if (item.isEmpty()) {
                    return ToolExecutionResult.Failure("item is required for set")
                }
                NoteSummaryCodec.setMemoryBullets(sections, item)
            }
            else -> return ToolExecutionResult.Failure("Unknown mode: $mode")
        }

        return when (editResult) {
            is NoteSummaryEditResult.Success -> {
                val priorContent = sections.contentDigest
                val now = System.currentTimeMillis()
                db.noteDao().update(
                    note.copy(
                        summary = editResult.formatted.ifBlank { null },
                        summaryUpdatedAt = now,
                    ),
                )
                semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
                val preserved = NoteSummaryCodec.parse(editResult.formatted).contentDigest == priorContent
                ToolExecutionResult.Success(
                    content = buildString {
                        append("Folder memory updated (mode=$mode)")
                        if (preserved) append("; content digest preserved")
                        append(".")
                    },
                    modifiedSystem = true,
                )
            }
            is NoteSummaryEditResult.Failure -> ToolExecutionResult.Failure(editResult.message)
        }
    }

    /** Canonical markdown bytes for read/segmentation; migrates legacy HTML on the fly. */
    private fun noteStorageMarkdown(raw: String): String =
        NoteContentCodec.normalizeLegacyToMarkdown(raw)

    private fun noteWriteGuard(note: Note): ToolExecutionResult.Failure? = when {
        note.aiBlind -> ToolExecutionResult.Failure("Note is blind from Eidos (content is private)")
        note.aiLocked -> ToolExecutionResult.Failure("Note is AI locked")
        else -> null
    }

    private suspend fun noteAccessGuard(note: Note): ToolExecutionResult.Failure? {
        noteWriteGuard(note)?.let { return it }
        val subfolder = db.subfolderDao().getById(note.subfolderId) ?: return null
        val parent = db.parentFolderDao().getById(subfolder.parentFolderId)
        if (!EidosRetrievalGuard.shouldExposeNoteToEidos(subfolder, parent)) {
            return ToolExecutionResult.Failure("Note is not available to Eidos")
        }
        return null
    }

    private fun NoteWriteOutcome.toToolResult(): ToolExecutionResult = when (this) {
        is NoteWriteOutcome.Written -> ToolExecutionResult.Success(
            // subfolderId must appear in content so navigation chips resolve in general/widget
            // scope (no scoped currentSubfolderId to inject into tool args).
            content = "${description.trimEnd()} (subfolderId=$subfolderId)",
            modifiedSystem = true,
        )
        is NoteWriteOutcome.Queued -> ToolExecutionResult.Success(
            content = "${description.trimEnd()} (subfolderId=$subfolderId)",
            modifiedSystem = true,
        )
        is NoteWriteOutcome.NoChange -> ToolExecutionResult.Success(
            content = if (subfolderId != null) {
                "${description.trimEnd()} (subfolderId=$subfolderId)"
            } else {
                description
            },
            modifiedSystem = false,
        )
        is NoteWriteOutcome.Failed -> ToolExecutionResult.Failure(message)
    }

    private suspend fun readFile(args: JsonObject): ToolExecutionResult {
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val ref = db.fileReferenceDao().getById(fileReferenceId)
            ?: return ToolExecutionResult.Failure("FileReference not found")

        val file = File(ref.filePath)
        if (!file.exists()) return ToolExecutionResult.Failure("File not found on disk")

        val text = fileTextExtractor.extractText(file)
            ?: return ToolExecutionResult.Failure("Unsupported or empty file type: ${file.extension}")
        semanticChunkBuilder.indexFile(semanticIndexer, ref, text)

        return ToolExecutionResult.Success(
            content = formatScopedTextRead(
                fullText = text,
                query = args.string("query"),
                startLine = args.int("startLine"),
                endLine = args.int("endLine"),
                mode = SegmentMode.FILE,
                summary = null,
            ),
            modifiedSystem = false,
        )
    }

    private suspend fun listImages(args: JsonObject): ToolExecutionResult {
        val result = ListImagesTool.listImages(db.fileReferenceDao(), args)
        return if (result.ok) {
            ToolExecutionResult.Success(content = ListImagesTool.toJson(result), modifiedSystem = false)
        } else {
            ToolExecutionResult.Failure(result.error ?: "list_images failed")
        }
    }

    private suspend fun describeImage(args: JsonObject): ToolExecutionResult {
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val ref = db.fileReferenceDao().getById(fileReferenceId)
            ?: return ToolExecutionResult.Failure("FileReference not found")

        val file = File(ref.filePath)
        if (!file.exists()) return ToolExecutionResult.Failure("Image file not found")

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            return ToolExecutionResult.Failure("Could not decode image metadata")
        }

        val description = visionService.describeImage(file).getOrElse { t ->
            return ToolExecutionResult.Failure(t.message ?: "Image description failed")
        }

        return ToolExecutionResult.Success(
            content = buildString {
                appendLine("Image metadata")
                appendLine("- File: ${ref.fileName}")
                appendLine("- Dimensions: ${opts.outWidth}x${opts.outHeight}")
                appendLine("- Type: ${ref.fileType}")
                appendLine()
                appendLine("Vision description")
                append(description)
            }.trim(),
            modifiedSystem = false,
        )
    }

    private suspend fun readConversation(args: JsonObject): ToolExecutionResult {
        val conversationId = args.long("conversationId")
            ?: return ToolExecutionResult.Failure("conversationId is required")
        val includeMessages = args.string("includeMessages")
            ?.trim()
            ?.lowercase(Locale.US)
            ?.let { it == "true" || it == "1" || it == "yes" }
            ?: true
        val limit = args.long("limit")?.toInt()?.coerceIn(1, 500)
        val query = args.string("query")?.trim()?.takeIf { it.isNotEmpty() }

        val conversation = db.conversationDao().getById(conversationId)
            ?: return ToolExecutionResult.Failure("Conversation not found")

        val messages = if (includeMessages) {
            val all = db.chatMessageDao().getAllByConversation(conversationId)
            if (limit != null) all.takeLast(limit) else all
        } else {
            emptyList()
        }

        if (query != null && messages.isNotEmpty()) {
            val threadText = semanticChunkBuilder.conversationPlainText(conversationId).orEmpty()
            val scoped = contentSectionRetriever.retrieveByQuery(
                fullText = threadText,
                query = query,
                mode = SegmentMode.FILE,
            )
            return ToolExecutionResult.Success(
                content = buildJsonObject {
                    put("id", JsonPrimitive(conversation.id))
                    put("title", JsonPrimitive(conversation.title))
                    put("scopeType", JsonPrimitive(conversation.scopeType))
                    put("scopedRead", JsonPrimitive(scoped.json))
                }.toString(),
                modifiedSystem = false,
            )
        }

        return ToolExecutionResult.Success(
            content = buildJsonObject {
                put("id", JsonPrimitive(conversation.id))
                put("scopeType", JsonPrimitive(conversation.scopeType))
                put("parentFolderId", JsonPrimitive(conversation.parentFolderId))
                put("subfolderId", JsonPrimitive(conversation.subfolderId))
                put("title", JsonPrimitive(conversation.title))
                put("createdAt", JsonPrimitive(conversation.createdAt))
                put("updatedAt", JsonPrimitive(conversation.updatedAt))
                put("messages", buildJsonArray {
                    messages.forEach { msg ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(msg.id))
                                put("role", JsonPrimitive(msg.role))
                                put("content", JsonPrimitive(msg.content))
                                put("createdAt", JsonPrimitive(msg.createdAt))
                            }
                        )
                    }
                })
            }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun searchChatHistory(args: JsonObject): ToolExecutionResult {
        val query = args.string("query")?.trim()
        val q = query?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        val scopeType = args.string("scopeType")?.trim()?.lowercase(Locale.US)
        val scopeId = args.long("scopeId")
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        val limit = args.long("limit")?.toInt()?.coerceIn(1, 100) ?: 20

        val conversations = when (scopeType) {
            "general" -> db.conversationDao().getAllGeneral()
            "parent" -> {
                if (scopeId == null) return ToolExecutionResult.Failure("scopeId is required for scopeType=parent")
                db.conversationDao().getRecentByParentIncludingSubfolders(scopeId, 400)
            }
            "subfolder" -> {
                if (scopeId == null) return ToolExecutionResult.Failure("scopeId is required for scopeType=subfolder")
                db.conversationDao().getAllBySubfolder(scopeId)
            }
            else -> db.conversationDao().getRecentMainChat(400)
        }

        val rows = mutableListOf<JsonObject>()
        conversations
            .asSequence()
            .filter { inDateRange(it.updatedAt, dateFrom, dateTo) }
            .forEach { conv ->
                val messages = db.chatMessageDao().getAllByConversation(conv.id)
                val titleMatch = q == null || conv.title.lowercase(Locale.US).contains(q)
                val matchingMsg = if (q == null) null else messages.firstOrNull { it.content.lowercase(Locale.US).contains(q) }
                if (!titleMatch && matchingMsg == null) return@forEach

                val snippetSource = matchingMsg?.content ?: messages.firstOrNull()?.content.orEmpty()
                val snippet = if (q == null) snippetSource.take(180) else extractSnippet(snippetSource, q)
                rows += buildJsonObject {
                    put("conversationId", JsonPrimitive(conv.id))
                    put("title", JsonPrimitive(conv.title))
                    put("scopeType", JsonPrimitive(conv.scopeType))
                    put("parentFolderId", JsonPrimitive(conv.parentFolderId))
                    put("subfolderId", JsonPrimitive(conv.subfolderId))
                    put("updatedAt", JsonPrimitive(conv.updatedAt))
                    put("messageCount", JsonPrimitive(messages.size))
                    put("snippet", JsonPrimitive(snippet))
                }
            }

        return ToolExecutionResult.Success(
            content = buildJsonArray {
                rows.sortedByDescending { it["updatedAt"]?.toString()?.trim('"')?.toLongOrNull() ?: 0L }
                    .take(limit)
                    .forEach { add(it) }
            }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun searchSemantic(args: JsonObject): ToolExecutionResult {
        val query = args.string("query").orEmpty()
        if (query.isBlank()) return ToolExecutionResult.Success("[]", modifiedSystem = false)
        val limit = args.long("limit")?.toInt()?.coerceIn(1, 50) ?: 10
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        val scopeType = args.string("scopeType")?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val scopeId = args.long("scopeId")
        val expansionPolicy = args.string("expansionPolicy")

        val hits = SemanticScopeSearch.search(
            indexer = semanticIndexer,
            query = query,
            limit = limit,
            scopeMode = scopeType,
            scopeId = scopeId,
            expansionPolicy = expansionPolicy,
            resolveParentFolderId = { subfolderId ->
                db.subfolderDao().getById(subfolderId)?.parentFolderId
            },
        )

        val enriched = formatChunkHits(hits, dateFrom, dateTo)
        return ToolExecutionResult.Success(
            content = buildJsonArray { enriched.forEach(::add) }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun formatChunkHits(
        hits: List<SemanticChunkHit>,
        dateFrom: Long?,
        dateTo: Long?,
    ): List<JsonObject> {
        return hits.mapNotNull { hit ->
            if (!chunkPassesFilters(hit, dateFrom, dateTo)) return@mapNotNull null
            buildJsonObject {
                put("matchType", JsonPrimitive("semantic_chunk"))
                put("chunk_text", JsonPrimitive(hit.chunkText))
                put("object_type", JsonPrimitive(hit.objectType))
                put("object_id", JsonPrimitive(hit.objectId))
                put("location", JsonPrimitive(hit.location))
                put("chunk_type", JsonPrimitive(hit.chunkType))
                put("score", JsonPrimitive(hit.score))
                hit.startLine?.let { put("startLine", JsonPrimitive(it)) }
                hit.endLine?.let { put("endLine", JsonPrimitive(it)) }
                when (hit.objectType) {
                    SemanticObjectType.NOTE -> put("subfolderId", JsonPrimitive(hit.objectId))
                    SemanticObjectType.FILE -> put("fileReferenceId", JsonPrimitive(hit.objectId))
                    SemanticObjectType.CONVERSATION -> put("conversationId", JsonPrimitive(hit.objectId))
                }
                hit.parentFolderId?.let { put("parentFolderId", JsonPrimitive(it)) }
                if (hit.objectType != SemanticObjectType.NOTE) {
                    hit.subfolderId?.let { put("subfolderId", JsonPrimitive(it)) }
                }
            }
        }
    }

    private suspend fun chunkPassesFilters(
        hit: SemanticChunkHit,
        dateFrom: Long?,
        dateTo: Long?,
    ): Boolean {
        return when (hit.objectType) {
            SemanticObjectType.NOTE -> {
                val note = db.noteDao().getBySubfolderOnce(hit.objectId) ?: return false
                if (note.deletedAt != null || note.aiBlind) return false
                val subfolder = db.subfolderDao().getById(hit.objectId) ?: return false
                val parent = db.parentFolderDao().getById(subfolder.parentFolderId)
                if (!EidosRetrievalGuard.shouldExposeNoteToEidos(subfolder, parent)) return false
                inDateRange(note.updatedAt, dateFrom, dateTo)
            }
            SemanticObjectType.FILE -> {
                val ref = db.fileReferenceDao().getById(hit.objectId) ?: return false
                inDateRange(ref.createdAt, dateFrom, dateTo)
            }
            SemanticObjectType.CONVERSATION -> {
                val conv = db.conversationDao().getById(hit.objectId) ?: return false
                inDateRange(conv.updatedAt, dateFrom, dateTo)
            }
            else -> true
        }
    }

    private suspend fun writeJournalEntry(args: JsonObject): ToolExecutionResult {
        if (!EidosSystemFeatureFlags.JOURNAL_ENABLED) {
            return ToolExecutionResult.Failure("Eidos Journal is paused")
        }
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()

        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_JOURNAL)
            ?: return ToolExecutionResult.Failure("Eidos Journal system folder not found")

        val subfolder = getOrCreateDailySubfolder(parent.id, timestamp)
        val note = getOrCreateDailyNote(subfolder.id)
        val updated = note.content.appendEntry(timestamp, content)
        db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolder.id)

        return ToolExecutionResult.Success("Journal entry written", modifiedSystem = true)
    }

    private suspend fun readDailyMemory(args: JsonObject): ToolExecutionResult {
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()
        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)
            ?: return ToolExecutionResult.Failure("Eidos Daily system folder not found")
        val subfolder = getExistingDailySubfolder(parent.id, timestamp)
        val note = subfolder?.let { db.noteDao().getBySubfolderOnce(it.id) }
        val dateName = dateKeyFromTimestamp(timestamp)
        val blinded = note?.aiBlind == true

        return ToolExecutionResult.Success(
            content = buildJsonObject {
                put("date", JsonPrimitive(dateName))
                put("subfolderId", JsonPrimitive(subfolder?.id))
                put("content", JsonPrimitive(if (blinded) "" else note?.content.orEmpty()))
                if (blinded) put("blind", JsonPrimitive(true))
            }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun writeDailyMemory(args: JsonObject): ToolExecutionResult {
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        if (content.isBlank()) return ToolExecutionResult.Failure("content is empty")
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()

        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)
            ?: return ToolExecutionResult.Failure("Eidos Daily system folder not found")

        val subfolder = getOrCreateDailySubfolder(parent.id, timestamp)
        val note = getOrCreateDailyNote(subfolder.id)
        val updated = note.content.appendEntry(timestamp, content.trim())
        db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolder.id)

        return ToolExecutionResult.Success("Daily Memory entry written", modifiedSystem = true)
    }

    private suspend fun clearDailyMemory(args: JsonObject): ToolExecutionResult {
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()
        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_DAILY)
            ?: return ToolExecutionResult.Failure("Eidos Daily system folder not found")
        val subfolder = getExistingDailySubfolder(parent.id, timestamp)
            ?: return ToolExecutionResult.Success("Daily Memory already empty for target day", modifiedSystem = false)
        val note = db.noteDao().getBySubfolderOnce(subfolder.id)
            ?: return ToolExecutionResult.Success("Daily Memory already empty for target day", modifiedSystem = false)

        db.noteDao().update(note.copy(content = "", updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolder.id)
        return ToolExecutionResult.Success("Daily Memory cleared", modifiedSystem = true)
    }

    private suspend fun readLongTermMemory(args: JsonObject): ToolExecutionResult {
        val query = args.string("query")
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        return readSystemEntries(
            parentName = SystemFolderNames.EIDOS_MEMORY,
            query = query,
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
    }

    private suspend fun writeLongTermMemory(args: JsonObject): ToolExecutionResult {
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        if (content.isBlank()) return ToolExecutionResult.Failure("content is empty")
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()

        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_MEMORY)
            ?: return ToolExecutionResult.Failure("Eidos Memory system folder not found")

        val subfolder = getOrCreateDailySubfolder(parent.id, timestamp)
        val note = getOrCreateDailyNote(subfolder.id)
        val updated = note.content.appendEntry(timestamp, content.trim())
        db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolder.id)

        return ToolExecutionResult.Success("Long-Term Memory entry written", modifiedSystem = true)
    }

    private suspend fun pruneLongTermMemory(args: JsonObject): ToolExecutionResult {
        val anchorText = args.string("anchorText")?.trim()
            ?: return ToolExecutionResult.Failure("anchorText is required")
        if (anchorText.isBlank()) return ToolExecutionResult.Failure("anchorText is empty")

        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_MEMORY)
            ?: return ToolExecutionResult.Failure("Eidos Memory system folder not found")

        val q = anchorText.lowercase(Locale.US)
        var removedEntries = 0

        db.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
            .forEach { sf ->
                val note = db.noteDao().getBySubfolderOnce(sf.id) ?: return@forEach
                if (note.content.isBlank()) return@forEach

                val chunks = note.content
                    .split(Regex("\n\\s*\n"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val kept = chunks.filterNot { it.lowercase(Locale.US).contains(q) }
                val removed = chunks.size - kept.size
                if (removed <= 0) return@forEach

                removedEntries += removed
                val updated = kept.joinToString(separator = "\n\n")
                db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
                semanticChunkBuilder.indexNote(semanticIndexer, sf.id)
            }

        return if (removedEntries > 0) {
            ToolExecutionResult.Success(
                content = "Pruned $removedEntries Long-Term Memory entr${if (removedEntries == 1) "y" else "ies"}",
                modifiedSystem = true,
            )
        } else {
            ToolExecutionResult.Success("No matching Long-Term Memory entries found", modifiedSystem = false)
        }
    }

    private suspend fun readJournal(args: JsonObject): ToolExecutionResult {
        if (!EidosSystemFeatureFlags.JOURNAL_ENABLED) {
            return ToolExecutionResult.Failure("Eidos Journal is paused")
        }
        val query = args.string("query")
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        return readSystemEntries(
            parentName = SystemFolderNames.EIDOS_JOURNAL,
            query = query,
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
    }

    private suspend fun writeLogEntry(args: JsonObject): ToolExecutionResult {
        val action = args.string("action") ?: return ToolExecutionResult.Failure("action is required")
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()
        val location = args.string("location")
        val anchor = args.string("anchor")

        if (!eidosLogWriter.append(
                action = action,
                timestamp = timestamp,
                location = location,
                anchor = anchor,
            )
        ) {
            return ToolExecutionResult.Failure("Eidos Log system folder not found")
        }

        return ToolExecutionResult.Success("Log entry written", modifiedSystem = true)
    }

    private suspend fun readLog(args: JsonObject): ToolExecutionResult {
        val query = args.string("query")
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        return readSystemEntries(
            parentName = SystemFolderNames.EIDOS_LOG,
            query = query,
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
    }

    private suspend fun writeQuickNote(args: JsonObject): ToolExecutionResult {
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        if (content.isBlank()) return ToolExecutionResult.Failure("content is empty")
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()
        val repo = folderRepository
            ?: return ToolExecutionResult.Failure("write_quick_note requires FolderRepository wiring")
        val result = repo.appendQuickNoteFromTool(content.trim(), timestamp)
        val subfolderId = result.getOrElse { t ->
            return ToolExecutionResult.Failure(t.message ?: "write_quick_note failed")
        }
        return ToolExecutionResult.Success(
            content = "Quick note appended (subfolderId=$subfolderId)",
            modifiedSystem = true,
        )
    }

    private suspend fun readSystemEntries(
        parentName: String,
        query: String?,
        dateFrom: Long?,
        dateTo: Long?,
    ): ToolExecutionResult {
        val parent = db.parentFolderDao().getSystemFolderByName(parentName)
            ?: return ToolExecutionResult.Failure("$parentName system folder not found")

        val subfolders = db.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
            .filter { inDateRange(it.updatedAt, dateFrom, dateTo) }
            .sortedByDescending { it.updatedAt }

        val q = query?.trim()?.lowercase(Locale.US).orEmpty()
        val results = buildJsonArray {
            subfolders.forEach { sf ->
                val note = db.noteDao().getBySubfolderOnce(sf.id) ?: return@forEach
                // Skip entries the user has blinded so their content cannot enter Eidos's context.
                if (note.aiBlind) return@forEach
                if (q.isNotBlank() && !note.content.lowercase(Locale.US).contains(q)) return@forEach
                add(
                    buildJsonObject {
                        put("subfolderId", JsonPrimitive(sf.id))
                        put("subfolderName", JsonPrimitive(sf.name))
                        put("updatedAt", JsonPrimitive(sf.updatedAt))
                        put("content", JsonPrimitive(note.content))
                    }
                )
            }
        }

        return ToolExecutionResult.Success(results.toString(), modifiedSystem = false)
    }

    private suspend fun getOrCreateDailySubfolder(parentFolderId: Long, timestamp: Long): Subfolder {
        val dateName = dateKeyFromTimestamp(timestamp)

        val existing = getExistingDailySubfolder(parentFolderId, timestamp)
        if (existing != null) return existing

        val id = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentFolderId,
                name = dateName,
                isSystemSubfolder = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return db.subfolderDao().getById(id)!!
    }

    private suspend fun getExistingDailySubfolder(parentFolderId: Long, timestamp: Long): Subfolder? {
        val dateName = dateKeyFromTimestamp(timestamp)
        return db.subfolderDao().getAllByParentOnce(parentFolderId)
            .firstOrNull { it.deletedAt == null && it.name == dateName }
    }

    private suspend fun getOrCreateDailyNote(subfolderId: Long): Note {
        val existing = db.noteDao().getBySubfolderOnce(subfolderId)
        if (existing != null) return existing
        val id = db.noteDao().insert(Note(subfolderId = subfolderId, updatedAt = System.currentTimeMillis()))
        return db.noteDao().getById(id)!!
    }

    private fun extractDocxText(file: File): String {
        val sb = StringBuilder()

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().decodeToString()
                    val paragraphPattern = Regex("<w:p[ >][^/]*?</w:p>", RegexOption.DOT_MATCHES_ALL)
                    val runPattern = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

                    paragraphPattern.findAll(xml).forEach { para ->
                        val paraText = runPattern.findAll(para.value)
                            .joinToString("") { it.groupValues[1] }
                        if (paraText.isNotEmpty()) sb.appendLine(paraText) else sb.appendLine()
                    }
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return sb.toString().trim()
    }

    private fun extractXlsxText(file: File): String {
        val sharedStrings = mutableListOf<String>()
        val sheetXml = mutableListOf<String>()

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> {
                        sharedStrings += Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(zip.readBytes().decodeToString())
                            .map { it.groupValues[1] }
                            .toList()
                    }
                    entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml") -> {
                        sheetXml += zip.readBytes().decodeToString()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val cellPattern = Regex("<c[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val valuePattern = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val typePattern = Regex("t=\"(.*?)\"")

        val rows = mutableListOf<String>()
        sheetXml.forEach { xml ->
            cellPattern.findAll(xml).forEach { cell ->
                val cellXml = cell.value
                val v = valuePattern.find(cellXml)?.groupValues?.getOrNull(1).orEmpty()
                val type = typePattern.find(cellXml)?.groupValues?.getOrNull(1).orEmpty()
                val text = if (type == "s") {
                    sharedStrings.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                } else {
                    v
                }
                if (text.isNotBlank()) rows += text
            }
        }

        return rows.joinToString("\n").trim()
    }

    private fun extractOdfText(file: File): String {
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") {
                    val xml = zip.readBytes().decodeToString()
                    return Regex(">([^<>]+)<")
                        .findAll(xml)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun extractPdfText(file: File): String {
        PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper()
            return stripper.getText(document).trim()
        }
    }

    private fun inDateRange(value: Long, from: Long?, to: Long?): Boolean {
        if (from != null && value < from) return false
        if (to != null && value > to) return false
        return true
    }

    private fun extractSnippet(content: String, query: String): String {
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(120)
        val start = (idx - 24).coerceAtLeast(0)
        val end = (idx + query.length + 96).coerceAtMost(content.length)
        return content.substring(start, end).trim()
    }

    private fun parseArgs(argumentsJson: String): JsonObject {
        if (argumentsJson.isBlank()) return JsonObject(emptyMap())
        val element = json.parseToJsonElement(argumentsJson)
        return element.jsonObject
    }

    private fun JsonObject.value(key: String): JsonElement? = this[key]

    private fun JsonObject.string(key: String): String? = this[key]
        ?.let {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                else -> it.toString()
            }
        }

    private fun JsonObject.resolveToolContent(): String? {
        string("content")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (key in listOf("contentMarkdown", "markdown", "text", "body")) {
            when (val element = this[key]) {
                null -> continue
                is kotlinx.serialization.json.JsonArray -> {
                    val joined = element.mapNotNull { item ->
                        (item as? JsonPrimitive)?.contentOrNull
                    }.joinToString("\n").trim()
                    if (joined.isNotEmpty()) return joined
                }
                is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                else -> continue
            }
        }
        return null
    }

    private fun JsonObject.long(key: String): Long? {
        val v = this[key] ?: return null
        return when (v) {
            is JsonPrimitive -> v.longOrNullCompat()
            else -> v.toString().trim('"').toLongOrNull()
        }
    }

    private fun JsonObject.int(key: String): Int? {
        val v = this[key] ?: return null
        return when (v) {
            is JsonPrimitive -> v.contentOrNull?.toIntOrNull()
            else -> v.toString().trim('"').toIntOrNull()
        }
    }

    private fun formatScopedTextRead(
        fullText: String,
        query: String?,
        startLine: Int?,
        endLine: Int?,
        mode: SegmentMode,
        summary: String?,
    ): String {
        if (startLine != null && endLine != null) {
            return contentSectionRetriever.lineRangeJson(fullText, startLine, endLine).json
        }
        val trimmedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedQuery != null) {
            return contentSectionRetriever.retrieveByQuery(fullText, trimmedQuery, mode).json
        }
        if (fullText.length <= FULL_READ_CHAR_THRESHOLD) {
            return contentSectionRetriever.fullFileJson(fullText).json
        }
        return contentSectionRetriever.truncatedHintJson(
            fullText = fullText,
            summary = summary,
            hint = "Content is large (${fullText.length} chars). Pass startLine=1 and endLine=totalLines for the full file; " +
                "or use search_semantic for passages; or pass startLine/endLine to expand a region.",
        ).json
    }

    private fun JsonPrimitive.longOrNullCompat(): Long? {
        return this.contentOrNull?.toLongOrNull()
    }

    private fun String.appendEntry(timestamp: Long, line: String): String {
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
        val entry = "[$ts] $line"
        return if (isBlank()) entry else "$this\n\n$entry"
    }

    private fun dateKeyFromTimestamp(timestamp: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }

    private suspend fun requireWorkshopProjectSubfolder(subfolderId: Long): Subfolder? {
        val sf = db.subfolderDao().getById(subfolderId) ?: return null
        if (sf.deletedAt != null) return null
        val parent = db.parentFolderDao().getById(sf.parentFolderId) ?: return null
        if (parent.name != SystemFolderNames.PANEL_WORKSHOP) return null
        return sf
    }

    private fun enforceWorkshopModeForWrite(fileName: String): ToolExecutionResult.Failure? =
        WorkshopFileAccessPolicy.writeFailure(fileName)

    private suspend fun requireWorkshopFileRef(fileReferenceId: Long): Pair<Subfolder, FileReference>? {
        val ref = db.fileReferenceDao().getById(fileReferenceId) ?: return null
        val sf = requireWorkshopProjectSubfolder(ref.subfolderId) ?: return null
        return sf to ref
    }

    private fun blockPanelRuntimeScopeWrites(args: JsonObject): ToolExecutionResult.Failure? {
        val scope = args.string("currentScopeType")?.trim().orEmpty()
        if (scope == ConversationScopes.PANEL_RUNNER || scope == ConversationScopes.PANEL_GALLERY) {
            return ToolExecutionResult.Failure(
                "Workshop file writes are not allowed from Panel Runner or Panel Gallery. " +
                    "Open Panel Workshop (panel_workshop scope) to edit project files.",
            )
        }
        return null
    }

    private suspend fun workshopCreateFile(args: JsonObject): ToolExecutionResult {
        blockPanelRuntimeScopeWrites(args)?.let { return it }
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val fileName = args.string("fileName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolExecutionResult.Failure("fileName is required")
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        enforceWorkshopModeForWrite(fileName)?.let { return it }
        if (fileName.contains('/') || fileName.contains('\\')) {
            return ToolExecutionResult.Failure("Invalid fileName")
        }
        requireWorkshopProjectSubfolder(subfolderId)
            ?: return ToolExecutionResult.Failure("subfolderId is not a Panel Workshop project")
        val existing = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
            .any { it.fileName.equals(fileName, ignoreCase = true) }
        if (existing) {
            return ToolExecutionResult.Failure("File already exists: $fileName — use workshop_write_file with its fileReferenceId")
        }
        return workshopWriteRouter.applyOrProposeCreate(
            subfolderId = subfolderId,
            fileName = fileName,
            proposedContent = content,
        ).toToolResult()
    }

    private suspend fun workshopWriteFile(args: JsonObject): ToolExecutionResult {
        blockPanelRuntimeScopeWrites(args)?.let { return it }
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        val pair = requireWorkshopFileRef(fileReferenceId)
            ?: return ToolExecutionResult.Failure("File not found or not in a Panel Workshop project")
        val ref = pair.second
        enforceWorkshopModeForWrite(ref.fileName)?.let { return it }
        return workshopWriteRouter.applyOrProposeWrite(ref = ref, proposedContent = content).toToolResult()
    }

    /**
     * Line-range edit — replaces `startLine`…`endLine` (1-based inclusive) with [newContent].
     */
    private suspend fun workshopEditFile(args: JsonObject): ToolExecutionResult {
        blockPanelRuntimeScopeWrites(args)?.let { return it }
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val newContent = args.string("newContent")
            ?: return ToolExecutionResult.Failure("newContent is required")
        val startLine = args.int("startLine")
        val endLine = args.int("endLine")
        if (startLine == null || endLine == null) {
            return ToolExecutionResult.Failure(
                "startLine and endLine are required (1-based inclusive). " +
                    "For a single line, set both to the same number.",
            )
        }
        if (endLine < startLine) {
            return ToolExecutionResult.Failure("endLine must be >= startLine")
        }

        val pair = requireWorkshopFileRef(fileReferenceId)
            ?: return ToolExecutionResult.Failure("File not found or not in a Panel Workshop project")
        val ref = pair.second
        enforceWorkshopModeForWrite(ref.fileName)?.let { return it }

        if (!File(ref.filePath).exists()) {
            return ToolExecutionResult.Failure("File not found on disk: ${ref.fileName}")
        }
        val current = pendingChangeService.effectiveWorkingContentForFile(ref)

        val built = NoteSectionEdit.buildProposedContent(
            current = current,
            newContent = newContent,
            startLine = startLine,
            endLine = endLine,
            oldString = null,
            expectedContent = null,
        )
        return when (built) {
            is NoteSectionEdit.Result.Error -> ToolExecutionResult.Failure(built.message)
            is NoteSectionEdit.Result.Ok ->
                workshopWriteRouter.applyOrProposeWrite(ref = ref, proposedContent = built.content).toToolResult()
        }
    }

    /**
     * Append [content] after the last line of an existing workshop file.
     */
    private suspend fun workshopAppendFile(args: JsonObject): ToolExecutionResult {
        blockPanelRuntimeScopeWrites(args)?.let { return it }
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val appendContent = args.string("content") ?: args.string("newContent")
            ?: return ToolExecutionResult.Failure("content is required")

        val pair = requireWorkshopFileRef(fileReferenceId)
            ?: return ToolExecutionResult.Failure("File not found or not in a Panel Workshop project")
        val ref = pair.second
        enforceWorkshopModeForWrite(ref.fileName)?.let { return it }

        if (!File(ref.filePath).exists()) {
            return ToolExecutionResult.Failure("File not found on disk: ${ref.fileName}")
        }
        val current = pendingChangeService.effectiveWorkingContentForFile(ref)
        val proposed = if (current.isEmpty()) {
            appendContent
        } else {
            val needsNl = !current.endsWith("\n")
            current + (if (needsNl) "\n" else "") + appendContent
        }
        return workshopWriteRouter.applyOrProposeWrite(ref = ref, proposedContent = proposed).toToolResult()
    }

    /**
     * Targeted edit of an existing workshop file. Replaces exactly one occurrence of
     * [oldString] with [newString]; fails with a current-file snippet when [oldString]
     * is missing or appears more than once so the LLM can re-anchor without re-reading.
     *
     * Routed through [workshopWriteRouter] so the result is auto-applied in build
     * phases and queued for review elsewhere — same checkpoint behavior either way.
     */
    private suspend fun workshopReplaceString(args: JsonObject): ToolExecutionResult {
        blockPanelRuntimeScopeWrites(args)?.let { return it }
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val oldString = args.string("oldString")
            ?: return ToolExecutionResult.Failure("oldString is required")
        val newString = args.string("newString")
            ?: return ToolExecutionResult.Failure("newString is required")
        if (oldString.isEmpty()) {
            return ToolExecutionResult.Failure("oldString must be non-empty")
        }
        if (oldString == newString) {
            return ToolExecutionResult.Failure(
                "oldString and newString are identical \u2014 nothing to change.",
            )
        }

        val pair = requireWorkshopFileRef(fileReferenceId)
            ?: return ToolExecutionResult.Failure("File not found or not in a Panel Workshop project")
        val ref = pair.second
        enforceWorkshopModeForWrite(ref.fileName)?.let { return it }

        if (!File(ref.filePath).exists()) {
            return ToolExecutionResult.Failure("File not found on disk: ${ref.fileName}")
        }
        val current = pendingChangeService.effectiveWorkingContentForFile(ref)

        val firstIndex = current.indexOf(oldString)
        if (firstIndex < 0) {
            val snippet = ContentDiff.snippetAround(current, oldString, contextLines = 5)
            return ToolExecutionResult.Failure(
                buildString {
                    appendLine("oldString not found in ${ref.fileName}.")
                    appendLine("Re-read the file and include more context in oldString, then retry.")
                    append(snippet.failureRegionLabel())
                    appendLine("Line numbers below are display-only — do not include them in oldString.")
                    append(snippet.formatWithLineNumbers())
                },
            )
        }
        val secondIndex = current.indexOf(oldString, firstIndex + 1)
        if (secondIndex >= 0) {
            val total = countOccurrences(current, oldString)
            val snippet = ContentDiff.snippetAround(current, oldString, contextLines = 5)
            return ToolExecutionResult.Failure(
                buildString {
                    appendLine("oldString matched $total occurrences in ${ref.fileName} \u2014 must be unique.")
                    appendLine("Add more surrounding context to oldString so it identifies one specific occurrence, then retry.")
                    append(snippet.failureRegionLabel())
                    appendLine("Line numbers below are display-only — do not include them in oldString.")
                    append(snippet.formatWithLineNumbers())
                },
            )
        }

        val proposed = current.substring(0, firstIndex) +
            newString +
            current.substring(firstIndex + oldString.length)

        return workshopWriteRouter.applyOrProposeWrite(ref = ref, proposedContent = proposed).toToolResult()
    }

    /**
     * Line-range patch without oldString — best for single-line fixes when line number is known
     * from search_semantic (file hit).
     */
    private suspend fun workshopPatchFile(args: JsonObject): ToolExecutionResult {
        blockPanelRuntimeScopeWrites(args)?.let { return it }
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val newText = args.string("newText") ?: args.string("newContent")
            ?: return ToolExecutionResult.Failure("newText is required")

        val line = args.int("line")
        val startLine = args.int("startLine")
        val endLine = args.int("endLine")

        val rangeStart: Int
        val rangeEnd: Int
        when {
            line != null -> {
                rangeStart = line
                rangeEnd = line
            }
            startLine != null && endLine != null -> {
                rangeStart = startLine
                rangeEnd = endLine
            }
            startLine != null -> {
                rangeStart = startLine
                rangeEnd = startLine
            }
            else -> return ToolExecutionResult.Failure(
                "Provide line (single-line patch) or startLine+endLine (region patch) from a file semantic hit.",
            )
        }

        val pair = requireWorkshopFileRef(fileReferenceId)
            ?: return ToolExecutionResult.Failure("File not found or not in a Panel Workshop project")
        val ref = pair.second
        enforceWorkshopModeForWrite(ref.fileName)?.let { return it }

        if (!File(ref.filePath).exists()) {
            return ToolExecutionResult.Failure("File not found on disk: ${ref.fileName}")
        }
        val current = pendingChangeService.effectiveWorkingContentForFile(ref)

        val built = NoteSectionEdit.buildProposedContent(
            current = current,
            newContent = newText,
            startLine = rangeStart,
            endLine = rangeEnd,
            oldString = null,
            expectedContent = null,
        )
        return when (built) {
            is NoteSectionEdit.Result.Error -> ToolExecutionResult.Failure(built.message)
            is NoteSectionEdit.Result.Ok ->
                workshopWriteRouter.applyOrProposeWrite(ref = ref, proposedContent = built.content).toToolResult()
        }
    }

    /**
     * Maps a [WorkshopWriteOutcome] from [workshopWriteRouter] into the [ToolExecutionResult]
     * the LLM sees. Queued and Written both report success so the model continues its turn;
     * `modifiedSystem` is `true` in both cases so the chat UI marks the assistant turn as
     * having touched state.
     */
    private fun WorkshopWriteOutcome.toToolResult(): ToolExecutionResult = when (this) {
        is WorkshopWriteOutcome.Written -> ToolExecutionResult.Success(
            content = description,
            modifiedSystem = true,
        )
        is WorkshopWriteOutcome.Queued -> ToolExecutionResult.Success(
            content = description,
            modifiedSystem = true,
        )
        is WorkshopWriteOutcome.NoChange -> ToolExecutionResult.Success(
            content = description,
            modifiedSystem = false,
        )
        is WorkshopWriteOutcome.Failed -> ToolExecutionResult.Failure(message)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    private suspend fun workshopReadFile(args: JsonObject): ToolExecutionResult {
        val fileReferenceId = args.long("fileReferenceId")
            ?: return ToolExecutionResult.Failure("fileReferenceId is required")
        val pair = requireWorkshopFileRef(fileReferenceId)
            ?: return ToolExecutionResult.Failure("File not found or not in a Panel Workshop project")
        val ref = pair.second
        WorkshopFileAccessPolicy.markdownReadFailure(ref.fileName)?.let { return it }
        val file = File(ref.filePath)
        if (!file.exists()) return ToolExecutionResult.Failure("File not found on disk")
        val diskText = fileTextExtractor.extractText(file)
            ?: return ToolExecutionResult.Failure("Unsupported or empty file type: ${file.extension}")
        val effectiveText = pendingChangeService.effectiveWorkingContentForFile(ref)
        val includesPending = effectiveText != diskText
        semanticChunkBuilder.indexFile(semanticIndexer, ref, effectiveText)
        val body = formatScopedTextRead(
            fullText = effectiveText,
            query = args.string("query"),
            startLine = args.int("startLine"),
            endLine = args.int("endLine"),
            mode = SegmentMode.FILE,
            summary = null,
        )
        val prefix = if (includesPending) {
            "(Includes unaccepted Diff Review proposal for ${ref.fileName}; disk unchanged until user accepts.)\n\n"
        } else {
            ""
        }
        return ToolExecutionResult.Success(
            content = prefix + body,
            modifiedSystem = false,
        )
    }

    private suspend fun workshopListPendingReview(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("currentSubfolderId")
            ?: return ToolExecutionResult.Failure("currentSubfolderId is required")
        requireWorkshopProjectSubfolder(subfolderId)
            ?: return ToolExecutionResult.Failure("currentSubfolderId is not a Panel Workshop project")
        return ToolExecutionResult.Success(
            content = pendingChangeService.formatPendingQueueReport(subfolderId),
            modifiedSystem = false,
        )
    }

    private suspend fun callPanelFunction(args: JsonObject): ToolExecutionResult {
        val registry = panelBridgeRegistry
            ?: return ToolExecutionResult.Failure("Panel bridge is unavailable in this app session")
        when (WorkshopEidosMode.normalizeToUserChip(
            WorkshopEidosSession.currentMode() ?: WorkshopEidosMode.CHAT,
        )) {
            WorkshopEidosMode.CHAT,
            WorkshopEidosMode.PLAN,
            -> return ToolExecutionResult.Failure(
                "call_panel_function is not available in ${WorkshopEidosSession.currentMode()?.displayName} mode. " +
                    "Switch to Edit mode (Preview optional).",
            )
            else -> Unit
        }
        val functionName = args.string("functionName")?.trim().orEmpty()
        if (functionName.isBlank()) return ToolExecutionResult.Failure("functionName is required")
        val currentSubfolderId = args.long("currentSubfolderId")
        val currentScopeType = args.string("currentScopeType")?.trim()

        if (currentScopeType == ConversationScopes.PANEL_WORKSHOP ||
            currentScopeType == ConversationScopes.PANEL_RUNNER
        ) {
            val sid = currentSubfolderId
                ?: return ToolExecutionResult.Failure(
                    "currentSubfolderId is required for $currentScopeType bridge calls",
                )
            requireWorkshopProjectSubfolder(sid)
                ?: return ToolExecutionResult.Failure("currentSubfolderId is not a valid Panel Workshop project")
        }
        if (currentScopeType == "subfolder" && currentSubfolderId != null) {
            val hasCustomPanel = db.customPanelAssignmentDao().getByTargetSubfolderOnce(currentSubfolderId).isNotEmpty()
            if (!hasCustomPanel) {
                return ToolExecutionResult.Failure(
                    "No custom panel is assigned to this subfolder. Add a panel tab first.",
                )
            }
        }

        val argsJson = when (val raw = args.value("args")) {
            null, JsonNull -> "{}"
            is JsonPrimitive -> raw.contentOrNull ?: "{}"
            else -> raw.toString()
        }

        val call = runCatching {
            registry.call(
                functionName = functionName,
                argsJson = argsJson,
                currentSubfolderId = currentSubfolderId,
                currentScopeType = currentScopeType,
            )
        }.getOrElse { t ->
            return ToolExecutionResult.Failure(t.message ?: "Panel function call failed")
        }

        val response = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("instanceId", JsonPrimitive(call.instanceId))
            put("workshopSubfolderId", JsonPrimitive(call.workshopSubfolderId))
            put("contextType", JsonPrimitive(call.contextType))
            put("functionName", JsonPrimitive(call.functionName))
            put("result", JsonPrimitive(call.resultJson))
        }.toString()

        return ToolExecutionResult.Success(content = response, modifiedSystem = true)
    }

    private companion object {
        const val FULL_READ_CHAR_THRESHOLD = 24_000
    }

}
