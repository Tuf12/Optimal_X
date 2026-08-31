package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.model.ConversationScopes

/**
 * Pure helpers extracted from [com.example.optimalx.data.eidos.EidosApiClient.assembleSystemPrompt].
 * No behavior change — keeps universal prompt blocks in one place for future scope-router work.
 */
object EidosSystemPromptLayers {

  val DUMP_EDIT_RULES: String = """
      DumpEdit rules:
      - The user is in the DumpEdit scratch buffer.
      - Use read_dump_edit to read buffer sections when content is large.
      - Work with user to edit and write notes in DumpEdit.
  """.trimIndent()

  val PARENT_SCOPE_RULES: String = """
      Parent folder rules:
      - Default folder and note actions to subfolders under this parent unless the user names another destination.
      - The subfolder catalog below pairs each visible name with subfolderId — use those ids for write_note and related tools.
      - For content questions across the project, use search_semantic scoped to this parent.
      - list_folder_contents and search_folders resolve structure — not for reading note content.
  """.trimIndent()

  val GENERAL_SCOPE_RULES: String = """
      General chat rules:
      - You are not anchored to one folder — the user may name any destination by its visible folder name.
      - search_folders(query=…) resolves parentFolderId and subfolderId from folder names (same search as the app UI).
      - search_semantic finds note/file content — not folder names. Use search_folders first when the user names a folder.
  """.trimIndent()

  val QUICK_NOTES_DAY_RULES: String = """
      Quick Notes capture rules:
      - Append-only capture for this calendar day — use write_quick_note for each new item the user wants saved.
      - Do not use write_note, edit_note_section, or folder create/rename tools here.
      - read_conversation is available for same-day chat thread context when helpful.
  """.trimIndent()

  val QUICK_NOTES_ROOT_RULES: String = """
      Quick Notes root rules:
      - Browse dated day folders — write_quick_note is not available here; open a day inbox to capture.
      - Use list_folder_contents to list day folders; read_conversation for thread context when helpful.
  """.trimIndent()

  val NOTE_WRITE_RULES: String = """
      Note write rules (Diff Review):
      - Notes are stored as markdown. The user sees a rendered preview and edits the markdown source.
      - One note per subfolder: subfolderId is the note scope — there is no separate noteId in tools.
      - write_note: appends at the END of the note only ("add to the story"). Sets body on an empty note. Never use for mid-note edits.
      - edit_note_section: expand or replace a chapter, heading, or paragraph in place — use after read_note or search_semantic for line numbers.
      - write_note requires subfolderId (or subfolderName) and content (markdown string) — not contentMarkdown.
      - read_note: read the current note (full body when small; query or startLine/endLine when large). Use this before answering questions about the note.
      - read_note_section: read startLine..endLine from a read_note or search_semantic hit before editing.
      - edit_note_section: preferred startLine+endLine+newContent; fallback oldString+newContent (unique match).
      - Empty note with no pending proposal: write applies immediately. Append or section edit on existing content: queued for Diff Review — tell the user they must Accept in the editor Review badge or chat banner before it saves.
      - write_note_summary: append/replace/remove folder-memory bullets (durable prefs and conventions — not body recap).
      - write_quick_note is for today's Quick Notes capture only — do not use write_note for that.
  """.trimIndent()

  fun isNoteEditScope(activeScope: String): Boolean =
      activeScope == ConversationScopes.SUBFOLDER ||
          activeScope == ConversationScopes.WEB_EDITOR

  val ACTIVE_LOCATION_RULE: String = """
      Active location rule:
      - Default all folder/note create/write actions to the current in-app location.
      - Do not create or write in other folders unless the user explicitly names a different destination.
  """.trimIndent()

  fun resolveActiveScope(
      currentScopeType: String?,
      currentSubfolderId: Long?,
      currentParentFolderId: Long?,
  ): String = currentScopeType ?: when {
      currentSubfolderId != null -> ConversationScopes.SUBFOLDER
      currentParentFolderId != null -> ConversationScopes.PARENT
      else -> ConversationScopes.GENERAL
  }

  fun universalIdentityAndRetrieval(
      baseSystemPrompt: String,
      activeScope: String,
  ): List<String> = listOf(baseSystemPrompt, EidosIdentityPrompt.APP_MODEL)

  fun activeScopeLine(activeScope: String): String = "Active scope: $activeScope"

  fun isWebChatScope(activeScope: String): Boolean = ConversationScopes.isWebScope(activeScope)

  fun webPanelUrlBlock(loadedWebUrl: String): String? {
      val url = loadedWebUrl.trim()
      if (url.isEmpty()) return null
      return """
          In-app web browser (OptimalX Web panel):
          Loaded page URL: $url
          The WebView renders this page for the user, but page HTML/text is NOT in this prompt.
          When they refer to "this page", "this site", "the article", "here", or visible page content, they mean this URL — retrieve page text via tools before answering; do not guess page content.
      """.trimIndent()
  }

  fun imageStudioRulesBlock(hub: Boolean, saveSubfolderId: Long): String {
    val lines = mutableListOf(
      "Image Studio rules:",
      "- Clarify subject, style, composition, lighting, materials, camera, and mood.",
      "- Expand wording into a detailed visual prompt (cloud models still need concrete visuals).",
      "- Browse existing images with list_images — never assume the gallery is in context.",
      "- Do not trigger image generation from chat.",
    )
    if (hub) {
      lines += "- Pinned Image Studio hub: All Images across OptimalX."
      lines += "- list_images default scope=all."
      lines += "Save target subfolderId (General): $saveSubfolderId"
    } else {
      lines += "- Image Studio subfolder tab — images in this folder only."
      lines += "Active save target subfolderId: $saveSubfolderId"
    }
    lines += ""
    lines += "### Image Studio draft"
    lines += "When the user is ready to generate, include a draft the UI can apply with Use in Image Studio."
    return lines.joinToString("\n")
  }

  fun webScopeRulesBlock(): String = """
      Web-scoped chat rules:
      - The loaded tab URL (when present) is focus metadata only — you cannot see the WebView; retrieve page text via tools.
      - Use prior messages in this same search thread only when they help answer about the current page or the active search topic.
      - Do not assume context from other search threads unless the user explicitly refers to earlier research outside this search.
  """.trimIndent()
}
