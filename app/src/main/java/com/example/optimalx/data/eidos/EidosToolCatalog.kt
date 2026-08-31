package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileRegistry
import com.example.optimalx.data.model.ConversationScopes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object EidosToolCatalog {

    private const val SEARCH_SEMANTIC = "search_semantic"

    private val panelWorkshopFileToolNames = setOf(
        "workshop_read_file",
        "workshop_list_pending_review",
        "workshop_write_file",
        // workshop_create_file disabled — scaffold files exist at project creation; create always fails.
        "workshop_edit_file",
        "workshop_append_file",
        "call_panel_function",
    )

    /** All Panel Workshop file/bridge tools (excludes semantic search). */
    val panelWorkshop: List<EidosToolDefinition> by lazy {
        coreTools.filter { it.name in panelWorkshopFileToolNames }
    }

    private val chatToolNames = setOf(SEARCH_SEMANTIC, "workshop_read_file", "read_file")

    private val panelRunnerToolNames = chatToolNames + "call_panel_function"

    private val panelGalleryToolNames = setOf(SEARCH_SEMANTIC)

    private val panelRuntimeBlockedWriteTools = setOf(
        "workshop_write_file",
        "workshop_edit_file",
        "workshop_append_file",
    )
    private val planToolNames = setOf(
        SEARCH_SEMANTIC,
        "workshop_read_file",
        "workshop_write_file",
        "workshop_edit_file",
        "workshop_append_file",
    )
    private val panelToolNames = setOf(SEARCH_SEMANTIC) + panelWorkshopFileToolNames

    /**
     * Tools exposed in Panel Workshop chat, ordered for declaration (search first per PROMPT_SYSTEM).
     * [WorkshopEidosMode.CHAT] — retrieve + optional read; no writes or panel bridge.
     */
    fun toolsForWorkshopMode(
        mode: WorkshopEidosMode,
        phase: WorkshopProjectPhase? = null,
    ): List<EidosToolDefinition> {
        if (phase == WorkshopProjectPhase.INTAKE) {
            return coreTools.filter { it.name in chatToolNames }
        }
        val allowed = when {
            mode.isBuildFamily -> panelToolNames
            else -> when (WorkshopEidosMode.normalizeToUserChip(mode)) {
                WorkshopEidosMode.CHAT -> chatToolNames
                WorkshopEidosMode.PLAN -> planToolNames
                WorkshopEidosMode.EDIT -> panelToolNames
                else -> panelToolNames
            }
        }
        val gated = if (phase != null && !phase.allowsCallPanelFunction) {
            allowed - "call_panel_function"
        } else {
            allowed
        }
        return coreTools.filter { it.name in gated }
    }

    /** Panel Gallery list surface — metadata only; no bridge or file writes. */
    fun toolsForPanelGallery(): List<EidosToolDefinition> =
        coreTools.filter { it.name in panelGalleryToolNames }

    /** Panel Runner — live panel + read-only workshop files; no project writes. */
    fun toolsForPanelRunner(): List<EidosToolDefinition> =
        coreTools.filter { it.name in panelRunnerToolNames }

    /** Scopes with a subfolder note: subfolder, quick_notes day, web editor. */
    private val noteScopedChatToolNames = setOf(
        SEARCH_SEMANTIC,
        "search_folders",
        "read_note",
        "read_note_section",
        "write_note",
        "edit_note_section",
        "write_note_summary",
        "list_folder_contents",
        "read_file",
        "create_subfolder",
        "rename_folder",
        "read_conversation",
    )

    private val parentScopedChatToolNames = noteScopedChatToolNames + setOf(
        "create_parent_folder",
        "move_to_trash",
    )

    private val generalScopedChatToolNames = parentScopedChatToolNames + setOf(
        "write_daily_memory",
        "write_long_term_memory",
        "write_quick_note",
        "describe_image",
    )

    fun toolsForScopedChat(scopeType: String?): List<EidosToolDefinition> {
        val allowed = when (scopeType) {
            ConversationScopes.SUBFOLDER,
            ConversationScopes.QUICK_NOTES_DAY,
            ConversationScopes.WEB_EDITOR,
            -> noteScopedChatToolNames
            ConversationScopes.PARENT,
            ConversationScopes.QUICK_NOTES_ROOT,
            -> parentScopedChatToolNames
            ConversationScopes.GENERAL,
            null,
            -> generalScopedChatToolNames
            ConversationScopes.DUMP_EDIT -> setOf(SEARCH_SEMANTIC, "read_dump_edit", "write_quick_note")
            else -> generalScopedChatToolNames
        }
        return coreTools.filter { it.name in allowed }
    }

    /**
     * Profile-scoped tool allowlist — source of truth is [EidosScopeProfileRegistry].
     * Workshop Edit/Build uses [toolsForWorkshopMode] for phase/mode gates; internal jobs use empty lists.
     */
    fun toolsForProfile(
        profileId: String,
        scopeType: String?,
        workshopMode: WorkshopEidosMode?,
        workshopPhase: WorkshopProjectPhase?,
    ): List<EidosToolDefinition> {
        when (profileId) {
            EidosScopeProfileIds.WORKSHOP_EDIT -> return toolsForWorkshopMode(
                workshopMode ?: WorkshopEidosMode.EDIT,
                workshopPhase,
            )
            EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY,
            EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER,
            -> return emptyList()
        }

        val toolNames = EidosScopeProfileRegistry.require(profileId).toolNames
        if (toolNames.isEmpty()) return emptyList()
        return toolsByNames(*toolNames.toTypedArray())
    }

    /** Resolve catalog definitions for explicit tool names (e.g. rollover synthesis). */
    fun toolsByNames(vararg names: String): List<EidosToolDefinition> {
        if (names.isEmpty()) return emptyList()
        val wanted = names.toSet()
        return coreTools.filter { it.name in wanted }
    }

    fun isPanelRuntimeWriteTool(toolName: String): Boolean =
        toolName in panelRuntimeBlockedWriteTools

    private val coreTools: List<EidosToolDefinition> = listOf(
        tool(
            "search_semantic",
            "Primary retrieval: returns top matching text chunks (chunk_text) with object ids, location, line ranges, " +
                "lineNumbersApplyTo (file | note | conversation), filePath on workshop files, and score. " +
                "Retrieved context may already include passages — call again when editing or when you need a different file region. " +
                "For workshop_read_file, only use startLine/endLine from hits where lineNumbersApplyTo=file — never conversation chunks. " +
                "Panel Workshop: scope via scopeType=local_first + subfolderId; file hits include fileReferenceId for workshop_read_file. " +
                "scopeType: subfolder | parent | local_first | global. expansionPolicy: expand_if_weak (default) | none.",
            schemaString("query", "limit", "dateFrom", "dateTo", "scopeType", "scopeId", "expansionPolicy"),
        ),
        tool(
            "search_folders",
            "Resolve folder names to ids — same search as the app folder search bar. " +
                "Matches parent folder names, subfolder names, and note content when the folder name is unknown. " +
                "Use when the user names a folder/subfolder; then call write_note with the returned subfolderId.",
            schemaString("query", "limit", "parentFolderId"),
        ),
        tool(
            "read_dump_edit",
            "Read the DumpEdit scratch buffer. Pass query for relevant sections with line ranges; " +
                "startLine/endLine for explicit range. Use when the buffer is large or AI locked.",
            schemaRead("query", "startLine", "endLine"),
        ),
        tool(
            "read_file",
            "Extract text from a file. Pass query for relevant sections with line ranges; startLine/endLine for explicit range. " +
                "Large files without query return truncated hint — always pass query after search_semantic.",
            schemaRead("fileReferenceId", "query", "startLine", "endLine"),
        ),
        tool(
            "workshop_read_file",
            "Read a Panel Workshop project file. Large files return truncated head preview (totalLines, previewEndsAtLine) — not EOF. " +
                "Use search_semantic file hit (lineNumbersApplyTo=file) then startLine/endLine from that hit. " +
                "Never pass conversation chunk line numbers to this tool. " +
                "During Diff Review, returns the latest pending proposal content when one exists for that file.",
            schemaRead("fileReferenceId", "query", "startLine", "endLine"),
        ),
        tool(
            "workshop_list_pending_review",
            "List open Diff Review proposals for the current workshop project (pendingCount + file names). " +
                "Call after writes to see how many rows the user must accept — one row per file, not per tool call.",
            schemaString("currentSubfolderId"),
        ),
        tool(
            "read_conversation",
            "Load a saved chat by ID. Pass query to excerpt messages matching the topic; omit for metadata only when includeMessages=false.",
            schemaRead("conversationId", "query", "includeMessages", "limit"),
        ),
        tool("list_folder_contents", "List folder contents (subfolders for parent folders, note preview + files for subfolders).", schemaString("folderId")),
        tool("create_parent_folder", "Create a new parent folder.", schemaString("name"), isModifying = true),
        tool("create_subfolder", "Create a new subfolder under a parent.", schemaString("parentFolderId", "name"), isModifying = true),
        tool("rename_folder", "Rename a parent folder or subfolder.", schemaString("folderId", "newName"), isModifying = true),
        tool("move_to_trash", "Move a folder to trash.", schemaString("folderId"), requiresConfirmation = true, isModifying = true),
        tool(
            "write_note",
            "Append markdown at the END of the subfolder Note panel. One note per subfolder — subfolderId is the note scope (no separate noteId). " +
                "Use for \"add to note\" or continuing at the bottom — NOT for editing a section in the middle. " +
                "Appends when the note already has content; sets on empty notes. " +
                "Requires content (markdown string), not contentMarkdown. " +
                "Pass subfolderId from search_folders, or subfolderName (+ optional parentName).",
            schemaString("subfolderId", "content"),
            requiresConfirmation = false,
            isModifying = true,
        ),
        tool(
            "read_note",
            "Read a subfolder note as markdown. Omit query/startLine/endLine to load the full body when it is under the " +
                "size cap; otherwise pass query for relevant sections or startLine/endLine to expand a range. " +
                "Use this before answering questions about the note or before edit_note_section. " +
                "subfolderId is the note scope (no separate noteId).",
            schemaRead("subfolderId", "query", "startLine", "endLine"),
        ),
        tool(
            "read_note_section",
            "Read a line range from a subfolder note. Pass startLine/endLine from a search_semantic or read_note hit; " +
                "optional contextBefore/contextAfter add surrounding lines. Prefer read_note when you need query or a full body.",
            schemaNoteSectionRead(),
        ),
        tool(
            "edit_note_section",
            "Edit or expand part of a note in place (chapter, heading, paragraph). Use for mid-note changes — NOT write_note (that only appends at the end). " +
                "Preferred: startLine + endLine + newContent from search_semantic + read_note_section. " +
                "Fallback: oldString + newContent when lines are unreliable (oldString must match exactly once). " +
                "Optional expectedContent verifies lines before patching. Queued for Diff Review when the note has content.",
            schemaNoteSectionEdit(),
            isModifying = true,
        ),
        tool(
            "write_note_summary",
            "Update folder memory bullets for a subfolder note ([Memory] section only — not body recap). " +
                "mode: append | replace | remove | set. " +
                "append: item required. replace/remove: match required (1-based index or bullet substring). " +
                "set: item = newline-separated bullets (optional leading '- '). Content digest is auto-maintained.",
            schemaString("subfolderId", "mode", "item", "match"),
            isModifying = true,
        ),
        // workshop_create_file — disabled: FolderRepository.createWorkshopProject seeds all standard
        // files with FileReference rows; create always fails with "already exists". Re-enable when
        // panels need ad-hoc files beyond the nine scaffold files.
        tool(
            "workshop_write_file",
            "Overwrite an existing workshop project file (by fileReferenceId). Use for new scaffolds or intentional full-file rewrites. " +
                "For localized changes use workshop_edit_file (startLine/endLine). To add at EOF use workshop_append_file.",
            schemaString("fileReferenceId", "content"),
            isModifying = true,
        ),
        tool(
            "workshop_edit_file",
            "Replace a line range in a workshop file. Provide startLine + endLine (1-based, inclusive) and newContent. " +
                "Single-line edit: set startLine and endLine to the same line. " +
                "Use line numbers from search_semantic file hits (lineNumbersApplyTo=file) or workshop_read_file. " +
                "Tight ranges only — do NOT set startLine=1 through EOF (use workshop_write_file for full rewrites).",
            schemaWorkshopEdit(),
            isModifying = true,
        ),
        tool(
            "workshop_append_file",
            "Append content after the last line of an existing workshop file. " +
                "Use for adding sections at EOF. For replacing lines use workshop_edit_file; for full overwrite use workshop_write_file.",
            schemaString("fileReferenceId", "content"),
            isModifying = true,
        ),
        tool(
            "call_panel_function",
            "Optional: invoke live panel JavaScript when Workshop Preview is open. " +
                "NOT required for bug fixes — use workshop_read_file + workshop_write_file on script.js/html/css instead. " +
                "functionName: getState or runAction; args JSON string for runAction.",
            schemaString("functionName", "args"),
            isModifying = true,
        ),
        tool("describe_image", "Describe an image file.", schemaString("fileReferenceId")),
        tool(
            "list_images",
            "List image files (file_references with file_type=image). " +
                "In pinned Image Studio hub use scope=all for every image in OptimalX; " +
                "in a subfolder Image Studio tab use scope=subfolder (default) for that folder only. " +
                "Returns fileName, fileReferenceId, globalId, subfolder location, generation caption when available, and bytesOnDisk. " +
                "Pass fileReferenceId to describe_image to see image contents.",
            schemaString("scope", "subfolderId", "limit"),
        ),
        tool(
            "search_chat_history",
            "Keyword fallback for past chat threads (title/message substring). Prefer search_semantic for meaning-based chat lookup.",
            schemaString("query", "scopeType", "scopeId", "dateFrom", "dateTo", "limit"),
        ),
        tool("read_daily_memory", "Read today's working-memory entries.", schemaString()),
        tool(
            "write_daily_memory",
            "Append to today's Daily Memory — session working context only: tasks in flight, today's decisions, mood, open threads. " +
                "Do not store durable identity (location, age, sex, long-term preferences); use write_long_term_memory for those.",
            schemaString("content", "timestamp"),
            isModifying = true,
        ),
        tool(
            "read_long_term_memory",
            "Read durable Long-Term Memory by keyword/date.",
            schemaString("query", "dateFrom", "dateTo"),
        ),
        tool(
            "write_long_term_memory",
            "Append one durable user fact to Long-Term Memory: location, demographics the user chooses to share, standing preferences, " +
                "relationships, recurring constraints. One concise bullet per call — not today's session context (use write_daily_memory). " +
                "Call in the same turn when the user states a durable fact they want remembered.",
            schemaString("content", "timestamp"),
            isModifying = true,
        ),
        tool("prune_long_term_memory", "Permanently remove Long-Term Memory entries matching anchorText.", schemaString("anchorText"), requiresConfirmation = true, isModifying = true),
        tool(
            "write_journal_entry",
            "Append a journal entry — Eidos reflection on sessions your own expierences, this is your personal journal use it to form an identity. " +
                "Not user biographical facts (use write_long_term_memory).",
            schemaString("content", "timestamp"),
            isModifying = true,
        ),
        tool("read_journal", "Read journal by keyword/date.", schemaString("query", "dateFrom", "dateTo")),
        tool("read_log", "Read log by keyword/date.", schemaString("query", "dateFrom", "dateTo")),
        tool(
            "write_quick_note",
            "Append a line to today's Quick Notes daily capture. Creates the dated subfolder/note if needed.",
            schemaString("content", "timestamp"),
            isModifying = true,
        ),
    )

    val all: List<EidosToolDefinition> = coreTools

    private fun tool(
        name: String,
        description: String,
        schema: JsonObject,
        requiresConfirmation: Boolean = false,
        isModifying: Boolean = false,
    ) = EidosToolDefinition(
        name = name,
        description = description,
        parametersSchema = schema,
        requiresConfirmation = requiresConfirmation,
        isModifying = isModifying,
    )

    private fun schemaString(vararg fields: String): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { key ->
                putJsonObject(key) {
                    put("type", "string")
                }
            }
        }
    }

    private fun schemaRead(vararg fields: String): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { key ->
                putJsonObject(key) {
                    when (key) {
                        "startLine", "endLine", "limit" -> put("type", "integer")
                        else -> put("type", "string")
                    }
                }
            }
        }
    }

    private fun schemaNoteSectionRead(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("subfolderId") { put("type", "string") }
            putJsonObject("startLine") { put("type", "integer") }
            putJsonObject("endLine") { put("type", "integer") }
            putJsonObject("contextBefore") { put("type", "integer") }
            putJsonObject("contextAfter") { put("type", "integer") }
        }
    }

    private fun schemaNoteSectionEdit(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("subfolderId") { put("type", "string") }
            putJsonObject("newContent") { put("type", "string") }
            putJsonObject("startLine") { put("type", "integer") }
            putJsonObject("endLine") { put("type", "integer") }
            putJsonObject("oldString") { put("type", "string") }
            putJsonObject("expectedContent") { put("type", "string") }
        }
    }

    private fun schemaWorkshopEdit(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("fileReferenceId") { put("type", "string") }
            putJsonObject("startLine") { put("type", "integer") }
            putJsonObject("endLine") { put("type", "integer") }
            putJsonObject("newContent") { put("type", "string") }
        }
    }
}
