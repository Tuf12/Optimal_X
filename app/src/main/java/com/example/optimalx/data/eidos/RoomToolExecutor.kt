package com.example.optimalx.data.eidos

import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.agentbyte.TagHintNotifier
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.eidos.model.ToolExecutor
import com.example.optimalx.data.model.ConversationScopes
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.model.TagHintLine
import com.example.optimalx.data.revision.CheckpointRepository
import com.example.optimalx.data.revision.ContentDiff
import com.example.optimalx.data.revision.DirectWriteApplier
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
    private val tagHintNotifier: TagHintNotifier = TagHintNotifier.NoOp,
    private val panelBridgeRegistry: PanelBridgeRegistry? = null,
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
    // ON HOLD — used only when EidosIndexFeature.isActive (see eidosIndexTool guard).
    private val appIndexMaterializer = AppIndexMaterializer(db = db, tagHintLineDao = db.tagHintLineDao())

    // DIFF_REVIEW v1 pipeline. The router consults [WorkshopReviewPolicy] and dispatches
    // to direct write (build phases) or pending review (review/edit/debug/update phases).
    // See app/docs/implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md.
    private val checkpointRepository = CheckpointRepository(
        checkpointDao = db.contentCheckpointDao(),
        patchDao = db.contentPatchDao(),
    )
    private val directWriteApplier = DirectWriteApplier(
        fileReferenceDao = db.fileReferenceDao(),
        checkpointRepository = checkpointRepository,
        indexer = WorkshopFileIndexer { ref, content ->
            semanticChunkBuilder.indexFile(semanticIndexer, ref, content)
        },
        workshopFilePath = { subfolderId, fileName ->
            val dir = File(context.filesDir, "workshop/$subfolderId").also { it.mkdirs() }
            File(dir, fileName)
        },
    )
    private val pendingChangeService = PendingChangeService(
        pendingDao = db.pendingChangeDao(),
        fileReferenceDao = db.fileReferenceDao(),
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

    init {
        PDFBoxResourceLoader.init(context)
    }

    /** Blocks Eidos Index tool handlers while [EidosIndexFeature] is disabled. */
    private suspend fun eidosIndexTool(block: suspend () -> ToolExecutionResult): ToolExecutionResult {
        if (!EidosIndexFeature.isActive) {
            return ToolExecutionResult.Failure(EidosIndexFeature.ON_HOLD_MESSAGE)
        }
        return block()
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

                // 6.2 Note tools
                "read_note" -> readNote(args)
                "read_dump_edit" -> readDumpEdit(args)
                "write_note" -> writeNote(args)
                "append_note" -> appendNote(args)
                "edit_note_section" -> editNoteSection(args)

                // 6.3 File tools
                "read_file" -> readFile(args)
                "workshop_create_file" -> workshopCreateFile(args)
                "workshop_write_file" -> workshopWriteFile(args)
                "workshop_replace_string" -> workshopReplaceString(args)
                "workshop_read_file" -> workshopReadFile(args)
                "workshop_list_pending_review" -> workshopListPendingReview(args)
                "call_panel_function" -> callPanelFunction(args)
                "read_conversation" -> readConversation(args)
                "describe_image" -> describeImage(args)

                // 6.4 Search tool
                "search_chat_history" -> searchChatHistory(args)
                "search_semantic" -> searchSemantic(args)
                // ON HOLD — Eidos Index tools (see EidosIndexFeature). Use search_semantic instead.
                "read_tag_hints" -> eidosIndexTool { readTagHints(args) }
                "upsert_tag_hint" -> eidosIndexTool { upsertTagHint(args) }
                "remove_tag_hint" -> eidosIndexTool { removeTagHint(args) }
                "notify_user" -> eidosIndexTool { notifyUser(args) }

                // 6.5 Memory tools
                "read_daily_memory" -> readDailyMemory(args)
                "write_daily_memory" -> writeDailyMemory(args)
                "clear_daily_memory" -> clearDailyMemory(args)
                "read_long_term_memory" -> readLongTermMemory(args)
                "write_long_term_memory" -> writeLongTermMemory(args)
                "prune_long_term_memory" -> pruneLongTermMemory(args)
                "read_subfolder_memory_cache" -> readSubfolderMemoryCache(args)
                "update_subfolder_memory_cache" -> updateSubfolderMemoryCache(args)
                "read_reasoning_trace" -> readReasoningTrace(args)
                "append_reasoning_trace" -> appendReasoningTrace(args)

                // 6.6 Journal tools
                "write_journal_entry" -> writeJournalEntry(args)
                "read_journal" -> readJournal(args)

                // 6.7 Log tools
                "write_log_entry" -> writeLogEntry(args)
                "read_log" -> readLog(args)

                // Quick Notes
                "write_quick_note" -> writeQuickNote(args)

                // 6.8 Voice handoff
                "voice_handoff" -> voiceHandoff(args)

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
        return toolName != "voice_handoff" && toolName != "write_log_entry"
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
        val memoryCacheSubfolderId = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = id,
                name = SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
                isSystemSubfolder = true,
                sortOrder = 9998,
            )
        )
        db.noteDao().insert(Note(subfolderId = memoryCacheSubfolderId))
        val reasoningSubfolderId = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = id,
                name = SystemFolderNames.PARENT_REASONING_SUBFOLDER,
                isSystemSubfolder = true,
                sortOrder = 9997,
            )
        )
        db.noteDao().insert(Note(subfolderId = reasoningSubfolderId))
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
                content = buildJsonArray {
                    subfolders.forEach { sf ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(sf.id))
                                put("name", JsonPrimitive(sf.name))
                                put("updatedAt", JsonPrimitive(sf.updatedAt))
                            }
                        )
                    }
                }.toString(),
                modifiedSystem = false,
            )
        }

        val subfolder = db.subfolderDao().getById(folderId)
            ?: return ToolExecutionResult.Failure("Folder not found")
        val note = db.noteDao().getBySubfolderOnce(subfolder.id)
        val files = db.fileReferenceDao().getBySubfolderOnce(subfolder.id)

        // Blinded notes must not leak any preview text through the folder listing.
        val blinded = note?.aiBlind == true
        val previewText = if (blinded) "" else note?.content.orEmpty().take(300)
        return ToolExecutionResult.Success(
            content = buildJsonObject {
                put("subfolderId", JsonPrimitive(subfolder.id))
                put("subfolderName", JsonPrimitive(subfolder.name))
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

    private suspend fun readNote(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        if (note.aiBlind) return ToolExecutionResult.Failure("Note is blind from Eidos (content is private)")

        return ToolExecutionResult.Success(
            content = formatScopedTextRead(
                fullText = note.content,
                query = args.string("query"),
                startLine = args.int("startLine"),
                endLine = args.int("endLine"),
                mode = SegmentMode.NOTE,
                summary = note.summary,
            ),
            modifiedSystem = false,
        )
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
                fullText = state.content,
                query = args.string("query"),
                startLine = args.int("startLine"),
                endLine = args.int("endLine"),
                mode = SegmentMode.NOTE,
                summary = null,
            ),
            modifiedSystem = false,
        )
    }

    private suspend fun writeNote(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")

        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        if (note.aiBlind) return ToolExecutionResult.Failure("Note is blind from Eidos (content is private)")
        if (note.aiLocked) return ToolExecutionResult.Failure("Note is AI locked")

        db.noteDao().update(note.copy(content = content, updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
        return ToolExecutionResult.Success("Note overwritten", modifiedSystem = true)
    }

    private suspend fun appendNote(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")

        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        if (note.aiBlind) return ToolExecutionResult.Failure("Note is blind from Eidos (content is private)")
        if (note.aiLocked) return ToolExecutionResult.Failure("Note is AI locked")

        val separator = if (note.content.isBlank()) "" else "\n\n"
        val merged = note.content + separator + content
        db.noteDao().update(
            note.copy(
                content = merged,
                updatedAt = System.currentTimeMillis(),
            )
        )
        semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
        return ToolExecutionResult.Success("Content appended", modifiedSystem = true)
    }

    private suspend fun editNoteSection(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val targetText = args.string("targetText") ?: return ToolExecutionResult.Failure("targetText is required")
        val newContent = args.string("newContent") ?: return ToolExecutionResult.Failure("newContent is required")

        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return ToolExecutionResult.Failure("Note not found")
        if (note.aiBlind) return ToolExecutionResult.Failure("Note is blind from Eidos (content is private)")
        if (note.aiLocked) return ToolExecutionResult.Failure("Note is AI locked")
        if (!note.content.contains(targetText)) return ToolExecutionResult.Failure("targetText not found")

        val updated = note.content.replace(targetText, newContent, ignoreCase = false)
        db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
        return ToolExecutionResult.Success("Section updated", modifiedSystem = true)
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
                note.deletedAt == null && !note.aiBlind && inDateRange(note.updatedAt, dateFrom, dateTo)
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

    private suspend fun readTagHints(args: JsonObject): ToolExecutionResult {
        val scope = args.string("scope")?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        val query = args.string("query")?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        val ref = args.string("ref")?.trim()?.takeIf { it.isNotBlank() }
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        val limit = args.long("limit")?.coerceAtLeast(1)?.coerceAtMost(500)?.toInt() ?: 100
        val all = db.tagHintLineDao().getAll(limit = 1000, offset = 0)
        val filtered = all.filter { line ->
            if (scope != null && !tagHintScopeMatches(scope, line.ref, line.objectType)) return@filter false
            if (query != null && !tagHintQueryMatches(query, line)) return@filter false
            if (ref != null && line.ref != ref) return@filter false
            inDateRange(line.date, dateFrom, dateTo)
        }.take(limit)
        val payload = buildJsonObject {
            put("schema", JsonPrimitive(TAG_HINTS_READ_SCHEMA_V2))
            put("count", JsonPrimitive(filtered.size))
            put("limit", JsonPrimitive(limit))
            put(
                "filters",
                buildJsonObject {
                    put("scope", scope?.let(::JsonPrimitive) ?: JsonNull)
                    put("query", query?.let(::JsonPrimitive) ?: JsonNull)
                    put("ref", ref?.let(::JsonPrimitive) ?: JsonNull)
                    put("dateFrom", dateFrom?.let(::JsonPrimitive) ?: JsonNull)
                    put("dateTo", dateTo?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
            put("items", buildJsonArray {
                filtered.forEach { line ->
                    add(
                        buildJsonObject {
                            put("ref", JsonPrimitive(line.ref))
                            put("objectType", JsonPrimitive(line.objectType))
                            put("scopeType", JsonPrimitive(line.scopeType))
                            put("scopeId", line.scopeId?.let(::JsonPrimitive) ?: JsonNull)
                            put("parentRef", line.parentRef?.let(::JsonPrimitive) ?: JsonNull)
                            put("rootBranch", JsonPrimitive(line.rootBranch))
                            put("tag", JsonPrimitive(line.tag))
                            put("hint", JsonPrimitive(line.hint))
                            put("objectName", JsonPrimitive(line.objectName))
                            put("parentFolderName", line.parentFolderName?.let(::JsonPrimitive) ?: JsonNull)
                            put("subfolderName", line.subfolderName?.let(::JsonPrimitive) ?: JsonNull)
                            put("date", JsonPrimitive(line.date))
                            put("dateKey", JsonPrimitive(dateKeyFromTimestamp(line.date)))
                            put("createdAt", JsonPrimitive(line.createdAt))
                            put("updatedAt", JsonPrimitive(line.updatedAt))
                            put("line", JsonPrimitive(formatTagHintLine(line)))
                        }
                    )
                }
            })
        }

        return ToolExecutionResult.Success(content = payload.toString(), modifiedSystem = false)
    }

    private suspend fun upsertTagHint(args: JsonObject): ToolExecutionResult {
        val ref = args.string("ref")?.trim().orEmpty()
        val tag = args.string("tag")?.trim().orEmpty()
        val hint = args.string("hint")?.trim().orEmpty()
        if (ref.isBlank()) return ToolExecutionResult.Failure("ref is required")
        if (tag.isBlank()) return ToolExecutionResult.Failure("tag is required")
        if (hint.isBlank()) return ToolExecutionResult.Failure("hint is required")

        val meta = appIndexMaterializer.resolveRefMetadata(ref)
            ?: return ToolExecutionResult.Failure("Invalid ref format")
        val names = appIndexMaterializer.resolveNamesForRef(ref)
            ?: AppIndexNames(objectName = tag)
        val now = System.currentTimeMillis()
        val existing = db.tagHintLineDao().getByRef(ref)
        val line = TagHintLine(
            id = existing?.id ?: 0,
            ref = ref,
            objectType = meta.objectType,
            scopeType = meta.scopeType,
            scopeId = meta.scopeId,
            parentRef = meta.parentRef,
            rootBranch = meta.rootBranch,
            tag = tag,
            hint = hint,
            objectName = names.objectName,
            parentFolderName = names.parentFolderName,
            subfolderName = names.subfolderName,
            date = existing?.date ?: now,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        db.tagHintLineDao().upsertByRef(line)
        return ToolExecutionResult.Success(
            content = "Upserted Tag & Hint for ref=$ref",
            modifiedSystem = true,
        )
    }

    private suspend fun removeTagHint(args: JsonObject): ToolExecutionResult {
        val ref = args.string("ref")?.trim().orEmpty()
        if (ref.isBlank()) return ToolExecutionResult.Failure("ref is required")
        val removed = db.tagHintLineDao().deleteByRef(ref)
        return ToolExecutionResult.Success(
            content = if (removed > 0) "Removed Tag & Hint for ref=$ref" else "No Tag & Hint found for ref=$ref",
            modifiedSystem = removed > 0,
        )
    }

    private fun notifyUser(args: JsonObject): ToolExecutionResult {
        val title = args.string("title")?.trim().orEmpty()
        val message = args.string("message")?.trim().orEmpty()
        if (title.isBlank()) return ToolExecutionResult.Failure("title is required")
        if (message.isBlank()) return ToolExecutionResult.Failure("message is required")
        val ref = args.string("ref")?.trim()?.takeIf { it.isNotBlank() }
        tagHintNotifier.notify(title = title, message = message, ref = ref)
        val payload = buildJsonObject {
            put("status", JsonPrimitive("notified"))
            put("title", JsonPrimitive(title))
            put("message", JsonPrimitive(message))
            put("ref", JsonPrimitive(ref.orEmpty()))
        }
        return ToolExecutionResult.Success(payload.toString(), modifiedSystem = true)
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

    private suspend fun readSubfolderMemoryCache(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val subfolder = db.subfolderDao().getById(subfolderId)
            ?: return ToolExecutionResult.Failure("Subfolder not found")
        if (subfolder.deletedAt != null) return ToolExecutionResult.Failure("Subfolder is deleted")

        val cacheNote = getOrCreateParentMemoryCacheNote(subfolder.parentFolderId)
        if (cacheNote.aiBlind) {
            return ToolExecutionResult.Failure("Memory cache is blind from Eidos (content is private)")
        }
        val cacheMap = decodeMemoryCacheMap(cacheNote.content)
        val content = cacheMap[subfolderId].orEmpty()

        return ToolExecutionResult.Success(
            content = buildJsonObject {
                put("subfolderId", JsonPrimitive(subfolderId))
                put("parentFolderId", JsonPrimitive(subfolder.parentFolderId))
                put("content", JsonPrimitive(content))
            }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun updateSubfolderMemoryCache(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")

        val subfolder = db.subfolderDao().getById(subfolderId)
            ?: return ToolExecutionResult.Failure("Subfolder not found")
        if (subfolder.deletedAt != null) return ToolExecutionResult.Failure("Subfolder is deleted")

        val cacheNote = getOrCreateParentMemoryCacheNote(subfolder.parentFolderId)
        val cacheMap = decodeMemoryCacheMap(cacheNote.content)
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            cacheMap.remove(subfolderId)
        } else {
            val existing = cacheMap[subfolderId].orEmpty().trim()
            cacheMap[subfolderId] = if (existing.isBlank()) {
                trimmed
            } else {
                "$existing\n\n$trimmed"
            }
        }

        val now = System.currentTimeMillis()
        val encoded = encodeMemoryCacheMap(cacheMap)
        db.noteDao().update(cacheNote.copy(content = encoded, updatedAt = now))
        semanticChunkBuilder.indexNote(semanticIndexer, cacheNote.subfolderId)
        db.subfolderDao().getById(cacheNote.subfolderId)?.let { sf ->
            db.subfolderDao().update(sf.copy(updatedAt = now))
        }

        return ToolExecutionResult.Success(
            content = if (trimmed.isBlank()) {
                "Subfolder memory cache cleared (subfolderId=$subfolderId)"
            } else {
                "Subfolder memory cache appended (subfolderId=$subfolderId)"
            },
            modifiedSystem = true,
        )
    }

    private suspend fun appendReasoningTrace(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val content = args.string("content") ?: return ToolExecutionResult.Failure("content is required")
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank()) return ToolExecutionResult.Failure("content is empty")

        val subfolder = db.subfolderDao().getById(subfolderId)
            ?: return ToolExecutionResult.Failure("Subfolder not found")
        if (subfolder.deletedAt != null) return ToolExecutionResult.Failure("Subfolder is deleted")

        val piece = args.string("piece")?.trim().orEmpty()
        val situation = args.string("situation")?.trim().orEmpty()
        val step = args.string("step")?.trim().orEmpty()
        val timestamp = args.long("timestamp") ?: System.currentTimeMillis()
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
        val header = buildList {
            add("source=subfolder:$subfolderId")
            if (piece.isNotBlank()) add("piece=${piece.uppercase(Locale.US)}")
            if (situation.isNotBlank()) add("situation=$situation")
            if (step.isNotBlank()) add("step=$step")
        }.joinToString(" | ")
        val entry = "[$ts] $header\n$trimmedContent"

        val reasoningNote = getOrCreateParentReasoningNote(subfolder.parentFolderId)
        val updated = if (reasoningNote.content.isBlank()) entry else "${reasoningNote.content.trimEnd()}\n\n$entry"
        val now = System.currentTimeMillis()
        db.noteDao().update(reasoningNote.copy(content = updated, updatedAt = now))
        semanticChunkBuilder.indexNote(semanticIndexer, reasoningNote.subfolderId)
        db.subfolderDao().getById(reasoningNote.subfolderId)?.let { sf ->
            db.subfolderDao().update(sf.copy(updatedAt = now))
        }

        return ToolExecutionResult.Success(
            content = "Reasoning trace appended (subfolderId=$subfolderId)",
            modifiedSystem = true,
        )
    }

    private suspend fun readReasoningTrace(args: JsonObject): ToolExecutionResult {
        val subfolderId = args.long("subfolderId") ?: return ToolExecutionResult.Failure("subfolderId is required")
        val query = args.string("query")?.trim()?.takeIf { it.isNotBlank() }
        val dateFrom = args.long("dateFrom")
        val dateTo = args.long("dateTo")
        val limit = (args.long("limit") ?: 50L).toInt().coerceIn(1, 500)

        val subfolder = db.subfolderDao().getById(subfolderId)
            ?: return ToolExecutionResult.Failure("Subfolder not found")
        if (subfolder.deletedAt != null) return ToolExecutionResult.Failure("Subfolder is deleted")

        val note = getOrCreateParentReasoningNote(subfolder.parentFolderId)
        if (note.aiBlind) {
            return ToolExecutionResult.Failure("Reasoning trace is blind from Eidos (content is private)")
        }
        val marker = "source=subfolder:$subfolderId"
        val chunks = note.content
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(marker) }

        val filtered = buildJsonArray {
            chunks.forEach { chunk ->
                if (query != null && !chunk.contains(query, ignoreCase = true)) return@forEach
                val parsed = parseReasoningChunk(chunk)
                val parsedMillis = parsed.timestampMillis
                if ((dateFrom != null || dateTo != null) && parsedMillis == null) return@forEach
                if (parsedMillis != null && !inDateRange(parsedMillis, dateFrom, dateTo)) return@forEach
                add(
                    buildJsonObject {
                        put("raw", JsonPrimitive(parsed.raw))
                        if (parsed.timestamp != null) put("timestamp", JsonPrimitive(parsed.timestamp))
                        if (parsed.source != null) put("source", JsonPrimitive(parsed.source))
                        if (parsed.piece != null) put("piece", JsonPrimitive(parsed.piece))
                        if (parsed.situation != null) put("situation", JsonPrimitive(parsed.situation))
                        if (parsed.step != null) put("step", JsonPrimitive(parsed.step))
                        put("content", JsonPrimitive(parsed.content))
                    }
                )
            }
        }

        val items = filtered.take(limit)
        return ToolExecutionResult.Success(
            content = buildJsonObject {
                put("subfolderId", JsonPrimitive(subfolderId))
                put("count", JsonPrimitive(items.size))
                put("entries", buildJsonArray { items.forEach { add(it) } })
            }.toString(),
            modifiedSystem = false,
        )
    }

    private suspend fun readJournal(args: JsonObject): ToolExecutionResult {
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

        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_LOG)
            ?: return ToolExecutionResult.Failure("Eidos Log system folder not found")

        val subfolder = getOrCreateDailySubfolder(parent.id, timestamp)
        val note = getOrCreateDailyNote(subfolder.id)

        val line = buildString {
            append(action)
            if (!location.isNullOrBlank()) append(" | location=$location")
            if (!anchor.isNullOrBlank()) append(" | anchor=$anchor")
        }

        val updated = note.content.appendEntry(timestamp, line)
        db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
        semanticChunkBuilder.indexNote(semanticIndexer, subfolder.id)

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

    private suspend fun voiceHandoff(args: JsonObject): ToolExecutionResult {
        val state = args.string("state") ?: return ToolExecutionResult.Failure("state is required")
        return ToolExecutionResult.Success("Voice handoff updated: $state", modifiedSystem = true)
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

    private suspend fun getOrCreateParentMemoryCacheNote(parentFolderId: Long): Note {
        val existing = db.subfolderDao().getAllByParentOnce(parentFolderId)
            .firstOrNull {
                it.deletedAt == null &&
                    it.isSystemSubfolder &&
                    (it.name == SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER || it.name == "__memory_cache__")
            }
        val subfolder = if (existing != null) {
            // Canonicalize any legacy memory-cache system subfolder name to avoid split writes/reads.
            if (existing.name != SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER) {
                val now = System.currentTimeMillis()
                val renamed = existing.copy(
                    name = SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
                    updatedAt = now,
                )
                db.subfolderDao().update(renamed)
                renamed
            } else {
                existing
            }
        } else {
            val id = db.subfolderDao().insert(
                Subfolder(
                    parentFolderId = parentFolderId,
                    name = SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
                    isSystemSubfolder = true,
                    sortOrder = 9998,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            db.subfolderDao().getById(id)!!
        }
        return getOrCreateDailyNote(subfolder.id)
    }

    private suspend fun getOrCreateParentReasoningNote(parentFolderId: Long): Note {
        val existing = db.subfolderDao().getAllByParentOnce(parentFolderId)
            .firstOrNull {
                it.deletedAt == null &&
                    it.isSystemSubfolder &&
                    it.name == SystemFolderNames.PARENT_REASONING_SUBFOLDER
            }
        val subfolder = if (existing != null) {
            existing
        } else {
            val id = db.subfolderDao().insert(
                Subfolder(
                    parentFolderId = parentFolderId,
                    name = SystemFolderNames.PARENT_REASONING_SUBFOLDER,
                    isSystemSubfolder = true,
                    sortOrder = 9997,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            db.subfolderDao().getById(id)!!
        }
        return getOrCreateDailyNote(subfolder.id)
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
        val element = runCatching { json.parseToJsonElement(argumentsJson) }
            .getOrElse { return JsonObject(emptyMap()) }
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
            hint = "Content is large (${fullText.length} chars). Use search_semantic for passages; pass startLine/endLine here to expand a region.",
        ).json
    }

    private fun JsonPrimitive.longOrNullCompat(): Long? {
        return this.contentOrNull?.toLongOrNull()
    }

    private data class ParsedTagHintV2(
        val ref: String,
        val tag: String,
        val hint: String,
        val date: Long?,
        val objectType: String,
        val scopeType: String,
        val scopeId: String?,
        val parentRef: String?,
        val rootBranch: String,
    )

    private suspend fun parseTagHintLineV2(raw: String): ParsedTagHintV2? {
        val refMatch = Regex("""\|\s*ref=([^\s|]+)\s*$""").find(raw) ?: return null
        val ref = refMatch.groupValues.getOrNull(1)?.trim().orEmpty()
        if (ref.isBlank()) return null
        val withoutRef = raw.removeRange(refMatch.range).trim()
        val head = Regex("""^\[([^\]]+)]\s*(.+)$""").find(withoutRef)
        val dateText = head?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        val body = head?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { withoutRef }

        val dashIdx = body.indexOf(" — ")
        if (dashIdx < 0) return null
        val tag = body.substring(0, dashIdx).trim()
        val hint = body.substring(dashIdx + 3).trim()
        if (tag.isBlank() || hint.isBlank()) return null

        val refMeta = appIndexMaterializer.resolveRefMetadata(ref) ?: return null
        val dateMillis = dateText?.let {
            runCatching {
                LocalDate.parse(it)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }

        return ParsedTagHintV2(
            ref = ref,
            tag = tag,
            hint = hint,
            date = dateMillis,
            objectType = refMeta.objectType,
            scopeType = refMeta.scopeType,
            scopeId = refMeta.scopeId,
            parentRef = refMeta.parentRef,
            rootBranch = refMeta.rootBranch,
        )
    }

    private fun parseReasoningChunk(raw: String): ParsedReasoningChunk {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParsedReasoningChunk(raw = raw, content = raw)

        val header = lines.first().trim()
        val body = lines.drop(1).joinToString("\n").trim().ifBlank { header }

        val tsMatch = Regex("""^\[([^\]]+)]\s*(.*)$""").find(header)
        val timestamp = tsMatch?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        val metaRaw = tsMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
        val metaParts = metaRaw.split("|").map { it.trim() }.filter { it.contains("=") }
        val metaMap = metaParts.associate { part ->
            val idx = part.indexOf('=')
            val key = part.substring(0, idx).trim().lowercase(Locale.US)
            val value = part.substring(idx + 1).trim()
            key to value
        }

        val timestampMillis = timestamp?.let {
            runCatching {
                LocalDate.parse(it.substring(0, 10))
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }

        return ParsedReasoningChunk(
            raw = raw,
            timestamp = timestamp,
            timestampMillis = timestampMillis,
            source = metaMap["source"],
            piece = metaMap["piece"],
            situation = metaMap["situation"],
            step = metaMap["step"],
            content = body,
        )
    }

    private fun decodeMemoryCacheMap(content: String): MutableMap<Long, String> {
        if (content.isBlank()) return mutableMapOf()
        val parsed = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return mutableMapOf()
        val obj = parsed as? JsonObject ?: return mutableMapOf()
        val out = linkedMapOf<Long, String>()
        obj.forEach { (k, v) ->
            val id = k.toLongOrNull() ?: return@forEach
            val value = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isNotBlank()) out[id] = value
        }
        return out
    }

    private fun encodeMemoryCacheMap(map: Map<Long, String>): String {
        if (map.isEmpty()) return ""
        return buildJsonObject {
            map.toSortedMap().forEach { (subfolderId, text) ->
                put(subfolderId.toString(), JsonPrimitive(text))
            }
        }.toString()
    }

    private fun tagHintScopeMatches(scope: String, ref: String?, objectType: String? = null): Boolean {
        if (ref.isNullOrBlank()) return false
        val prefix = objectType?.trim()?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: ref.substringBefore(":").trim().lowercase(Locale.US)
        return when (scope) {
            "note", "notes" -> prefix == "note"
            "file", "files" -> prefix == "file"
            "chat", "chats" -> prefix == "chat"
            "journal", "journals" -> prefix == "journal"
            "ltm", "longterm", "long-term", "memory" -> prefix == "ltm"
            "log", "logs" -> prefix == "log"
            else -> prefix == scope
        }
    }

    private fun tagHintQueryMatches(query: String, line: TagHintLine): Boolean {
        return line.ref.lowercase(Locale.US).contains(query) ||
            line.tag.lowercase(Locale.US).contains(query) ||
            line.hint.lowercase(Locale.US).contains(query) ||
            line.objectName.lowercase(Locale.US).contains(query) ||
            line.parentFolderName.orEmpty().lowercase(Locale.US).contains(query) ||
            line.subfolderName.orEmpty().lowercase(Locale.US).contains(query) ||
            line.objectType.lowercase(Locale.US).contains(query) ||
            line.scopeType.lowercase(Locale.US).contains(query) ||
            line.rootBranch.lowercase(Locale.US).contains(query)
    }

    private fun formatTagHintLine(line: TagHintLine): String {
        val date = dateKeyFromTimestamp(line.date)
        val location = listOfNotNull(line.parentFolderName, line.subfolderName)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val atSuffix = if (location.isNotBlank()) " | at=$location" else ""
        return "[$date] ${line.tag} — ${line.hint} | ref=${line.ref}$atSuffix"
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
                    appendLine("Current file (lines ${snippet.startLine}\u2013${snippet.endLine}):")
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
                    appendLine("Current file (lines ${snippet.startLine}\u2013${snippet.endLine}):")
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
        const val TAG_HINTS_READ_SCHEMA_V2 = "tag_hints_read_v2"
        const val FULL_READ_CHAR_THRESHOLD = 2_000
    }

}

private data class ParsedReasoningChunk(
    val raw: String,
    val timestamp: String? = null,
    val timestampMillis: Long? = null,
    val source: String? = null,
    val piece: String? = null,
    val situation: String? = null,
    val step: String? = null,
    val content: String,
)
