package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosToolDefinition
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
        "workshop_create_file",
        "workshop_replace_string",
        "call_panel_function",
    )

    /** All Panel Workshop file/bridge tools (excludes semantic search). */
    val panelWorkshop: List<EidosToolDefinition> by lazy {
        coreTools.filter { it.name in panelWorkshopFileToolNames }
    }

    private val chatToolNames = setOf(SEARCH_SEMANTIC, "workshop_read_file")

    private val panelRunnerToolNames = chatToolNames + "call_panel_function"

    private val panelGalleryToolNames = setOf(SEARCH_SEMANTIC)

    private val panelRuntimeBlockedWriteTools = setOf(
        "workshop_write_file",
        "workshop_create_file",
        "workshop_replace_string",
    )
    private val planToolNames = setOf(
        SEARCH_SEMANTIC,
        "workshop_read_file",
        "workshop_write_file",
        "workshop_create_file",
        "workshop_replace_string",
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
            mode.isBuildFamily || mode.isPlanBuildKickoff -> panelToolNames
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

    fun isPanelRuntimeWriteTool(toolName: String): Boolean =
        toolName in panelRuntimeBlockedWriteTools

    /**
     * ON HOLD — Eidos Index / Tag & Hint tools. Merged into [all] only when [EidosIndexFeature.isActive].
     * Retrieval uses [search_semantic] instead while the index is disabled.
     */
    private val eidosIndexToolsOnHold: List<EidosToolDefinition> = listOf(
        tool("read_tag_hints", "Read app-wide Tag & Hint rows with optional scope/query/ref/date/limit filters. Each row includes tag, hint, objectName, and folder names for human-readable location.", schemaString("scope", "query", "ref", "dateFrom", "dateTo", "limit")),
        tool("upsert_tag_hint", "Create or update semantic tag and hint for one indexed object by ref.", schemaString("ref", "tag", "hint"), isModifying = true),
        tool("remove_tag_hint", "Remove one Tag & Hint row by ref.", schemaString("ref"), isModifying = true),
        tool("notify_user", "Notify the user about indexing actions (stubbed in phase 2).", schemaString("title", "message", "ref"), isModifying = true),
    )

    private val coreTools: List<EidosToolDefinition> = listOf(
        tool(
            "search_semantic",
            "Primary retrieval: returns top matching text chunks (chunk_text) with object ids, location, line ranges, and score. " +
                "Use this alone to answer from notes, files, and chats — no separate read step required for Q&A. " +
                "Panel Workshop: scope to project via scopeType=local_first + subfolderId; file hits include fileReferenceId for workshop_read_file. " +
                "Use read_note/read_file/workshop_read_file only to expand a region or before edits. " +
                "scopeType: subfolder | parent | local_first | global. expansionPolicy: expand_if_weak (default) | none.",
            schemaString("query", "limit", "dateFrom", "dateTo", "scopeType", "scopeId", "expansionPolicy"),
        ),
        tool(
            "read_note",
            "Read note content by subfolder ID. Pass query (from search_semantic or user question) to get only " +
                "semantically relevant sections with line ranges. Use startLine/endLine for explicit expansion. " +
                "Large notes without query return summary + truncated hint.",
            schemaRead("subfolderId", "query", "startLine", "endLine"),
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
                "Large files without query return truncated hint — always pass query after search_semantic. " +
                "For spreadsheets (.xlsx/.xls/.csv) prefer the Kimi `excel` Formula tool — read_file flattens cells and loses structure.",
            schemaRead("fileReferenceId", "query", "startLine", "endLine"),
        ),
        tool(
            "workshop_read_file",
            "Read a Panel Workshop project file. Prefer search_semantic first; pass query for relevant sections or startLine/endLine from a search hit before edits. " +
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
        tool("write_note", "create, add or update a note.", schemaString("subfolderId", "content"), requiresConfirmation = false, isModifying = true),
        tool("append_note", "Append content to a note.", schemaString("subfolderId", "content"), isModifying = true),
        tool("edit_note_section", "Replace or delete a section inside a note.", schemaString("subfolderId", "targetText", "newContent"), requiresConfirmation = true, isModifying = true),
        tool(
            "workshop_create_file",
            "Create a new file in a Panel Workshop project. subfolderId must be a project under the Panel Workshop parent.",
            schemaString("subfolderId", "fileName", "content"),
            isModifying = true,
        ),
        tool(
            "workshop_write_file",
            "Overwrite an existing workshop project file (by fileReferenceId from list_folder_contents or system prompt). " +
                "Prefer workshop_replace_string for targeted edits; use this only for initial scaffolds or full rewrites.",
            schemaString("fileReferenceId", "content"),
            isModifying = true,
        ),
        tool(
            "workshop_replace_string",
            "Targeted edit of a workshop file: replace exactly one occurrence of oldString with newString. " +
                "oldString must be unique in the file \u2014 include at least 3 lines of surrounding context so it " +
                "identifies one specific location. On 0 or multiple matches, the tool returns an error with a " +
                "current-file snippet (with line numbers) so you can add more context and retry without re-reading. " +
                "Preferred over workshop_write_file for any change under ~30 lines.",
            schemaString("fileReferenceId", "oldString", "newString"),
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
            "search_chat_history",
            "Keyword fallback for past chat threads (title/message substring). Prefer search_semantic for meaning-based chat lookup.",
            schemaString("query", "scopeType", "scopeId", "dateFrom", "dateTo", "limit"),
        ),
        tool("read_daily_memory", "Read today's working-memory entries.", schemaString()),
        tool("write_daily_memory", "Use often to track daily activities (DECISION, PREFERENCE, ONGOING,)", schemaString("content", "timestamp"), isModifying = true),
        tool("read_long_term_memory", "Read durable user prefernces and general useful user information (decisions, stable preferences, resolved outcomes).", schemaString("query", "dateFrom", "dateTo")),
        tool("write_long_term_memory", "Use this to write user preferences and other general useful information. (environment, work, decisions, personal)", schemaString("content", "timestamp"), isModifying = true),
        tool("prune_long_term_memory", "Permanently remove Long-Term Memory entries when they are old and no longer useful.", schemaString("anchorText"), requiresConfirmation = true, isModifying = true),
        tool("read_subfolder_memory_cache", "Read subfolder memories for folder context and useful information (local personal memory) for active location.", schemaString("subfolderId")),
        tool("update_subfolder_memory_cache", "Use this to write to subfolder memory cache to provide simple folder content and useful information", schemaString("subfolderId", "content"), isModifying = true),
        tool("write_journal_entry", "Write a journal entry.Use this to write about your own expierences not about the user.", schemaString("content", "timestamp"), isModifying = true),
        tool("read_journal", "Read journal by keyword/date.", schemaString("query", "dateFrom", "dateTo")),
        tool("read_log", "Read log by keyword/date.", schemaString("query", "dateFrom", "dateTo")),
        tool(
            "write_quick_note",
            "Append a line to today's Quick Notes daily capture. Creates the dated subfolder/note if needed.",
            schemaString("content", "timestamp"),
            isModifying = true,
        ),
        tool("voice_handoff", "Update voice handoff state.", schemaString("conversationId", "state", "timestamp", "metadata"), isModifying = true),
    )

    val all: List<EidosToolDefinition> = buildList {
        addAll(coreTools)
        if (EidosIndexFeature.isActive) {
            addAll(eidosIndexToolsOnHold)
        }
    }

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
}
