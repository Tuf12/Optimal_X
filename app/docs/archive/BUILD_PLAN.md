# BUILD_PLAN.md

## How to Use This File
- Work one phase at a time. Do not start Phase N+1 until all tasks in Phase N are marked [x].
- Mark tasks [x] as you complete them.
- After every session, update CURSOR_JOURNAL.md with what was done and what is next.
- If something is not covered in the architecture files, stop and ask before inventing anything.

---

## Phase 1 — Project Foundation ✅

Set up the Android project skeleton so every later phase has a clean base to build on.

### 1.1 Gradle Dependencies
- [x] Add Jetpack Compose BOM and all Compose UI dependencies
- [x] Add Room (runtime, KTX, KSP annotation processor)
- [x] Add DataStore Preferences
- [x] Add Google Fonts (Syne, DM Sans, DM Mono via downloadable fonts)
- [x] Add Navigation Compose
- [x] Add EncryptedSharedPreferences (androidx.security.crypto)
- [ ] Add TensorFlow Lite + Universal Sentence Encoder model (for Phase 9)
- [ ] Add file-reading libraries: Apache PDFBox or iText for PDF, Apache POI for .docx/.xlsx (for Phase 3)
- [ ] Add OkHttp + kotlinx.serialization for API calls (for Phase 5)

### 1.2 Theme System
- [x] Define `OptimalXColors` data class with all tokens from THEME.md
- [x] Define `DarkColors` instance with all exact hex values from THEME.md
- [x] Define `LightColors` instance with all exact hex values from THEME.md
- [x] Create `LocalOptimalXColors = staticCompositionLocalOf { DarkColors }`
- [x] Create `OptimalXTheme` composable that reads preference string ("dark"/"light"/"system") and wraps content with `CompositionLocalProvider`
- [x] Implement DataStore helper: save/load `theme_preference` key
- [x] Wire theme preference flow into root composable so theme switches immediately on change

### 1.3 Typography
- [x] Load Syne (weight 800), DM Sans (300/400/500), DM Mono (400/500)
- [x] Define font families via Google Fonts downloadable fonts API

### 1.4 Room Database
- [x] Define `ParentFolder` entity (all fields from DATA_MODEL.md)
- [x] Define `Subfolder` entity
- [x] Define `Note` entity
- [x] Define `FileReference` entity
- [x] Write `ParentFolderDao`
- [x] Write `SubfolderDao`
- [x] Write `NoteDao`
- [x] Write `FileReferenceDao`
- [x] Write `AppDatabase` class wiring all entities and DAOs
- [x] Write `DatabaseSeed`: on first install create the three system parent folders with `isSystemFolder = true`

---

## Phase 2 — Core Navigation and Folder Screens ✅

Build the two folder screens (Parent Folder Page and Subfolder Page) and all navigation between them.

### 2.1 Navigation Graph
- [x] Set up NavHost with three destinations: ParentFolderPage, SubfolderPage, EditorView
- [x] Define argument passing: SubfolderPage receives parentFolderId, EditorView receives subfolderId
- [x] Android back behavior: SubfolderPage → ParentFolderPage → exit app

### 2.2 Parent Folder Page
- [x] Top bar: search bar (left/center) + Eidos button (right) + three-dot menu (settings)
- [x] Grid layout of folder cards (rounded rectangle, folder name only, no icons)
- [x] List layout alternative (toggled)
- [x] Bottom bar: Create folder button, Sort button, Grid/List toggle, Trash icon
- [x] Create folder: dialog to enter name → inserts ParentFolder
- [x] Tap folder card → navigate to SubfolderPage
- [x] Long press folder card → context menu: Rename / Delete
- [x] Rename: dialog to edit name → updates ParentFolder.name + updatedAt
- [x] Delete: sets deletedAt on ParentFolder + all child Subfolders + their Notes (cascade)
- [x] Sort: bottom sheet with sort options (Name A-Z, Name Z-A, Newest first, Recently updated)
- [x] System folders (isSystemFolder = true) do not appear in this list

### 2.3 Subfolder Page
- [x] Identical layout to Parent Folder Page
- [x] Shows subfolders inside the selected parent folder (isSystemSubfolder = false only)
- [x] Tap subfolder → navigate to EditorView
- [x] Long press subfolder → Rename / Delete / Move
- [x] Create subfolder: inserts Subfolder + auto-creates its Note + auto-creates system chat Subfolder
- [x] Delete subfolder: sets deletedAt on Subfolder + its Note (cascade)

### 2.4 Trash Screen
- [x] Accessible from trash icon in bottom bar of Parent Folder Page
- [x] Shows all soft-deleted parent folders and subfolders
- [x] Restore: clears deletedAt on item and all its children
- [x] Permanent delete: removes rows from database; deletes actual files from storage

### 2.5 Keyword Search (User-Facing)
- [x] Search bar on both folder pages filters results in real time (debounced 300ms)
- [x] Searches folder names + note content simultaneously
- [x] Results show folder/subfolder name + note content snippet
- [x] Tapping a result navigates to that EditorView

---

## Phase 3 — Editor View ✅

Build the core working screen: note editor with formatting, panels, and back stack behavior.

### 3.1 Panel System
- [x] Editor view is a single screen with a horizontal `HorizontalPager` (Note → Files → open file panels)
- [x] Back from Files Panel → Note Panel
- [x] Back from Note Panel → SubfolderPage

### 3.2 Note Panel
- [x] Full screen rich text editor (richeditor-compose RichTextEditor)
- [x] Formatting toolbar: Bold, Italic, Underline, Font size (S/M/L/XL cycle), Bullet list, Numbered list, Undo, Redo, Dropdown trigger
- [x] Dropdown: Strikethrough, Edit/View mode toggle, AI lock toggle, Export note, Share note
- [x] Edit/View mode toggle (readOnly = isViewMode)
- [x] AI lock toggle (writes Note.aiLocked to DB)
- [x] Auto-save with 500ms debounce via snapshotFlow
- [x] Top bar: subfolder name + Eidos button

### 3.3 Files Panel
- [x] List of FileReferences with fileName + FileTypeBadge
- [x] File type badges (PDF red, DOC blue, IMG lime)
- [x] Import via OpenDocument file picker → copy to app private storage → insert FileReference
- [x] Tap file row → open as new panel

### 3.4 Open File Panels
- [x] PDF viewer (Android PdfRenderer, page-by-page render)
- [x] Image viewer (pinch-to-zoom with transformable)
- [x] DOCX viewer (ZIP+XML text extraction)
- [x] Text/other file viewer (plain text)
- [x] Closing a file panel removes it from the pager

---

## Phase 4 — Settings Screen ✅

Build the settings screen accessed from the three-dot menu on the Parent Folder Page top bar.

- [x] Theme section: segmented control with Light / Dark / System default options; writes to DataStore immediately; live theme update with no restart
- [x] API Keys section: one entry field per provider (xAI, OpenAI, Anthropic); stored in EncryptedSharedPreferences
- [x] Provider selection: radio group selecting active provider; default is xAI (Grok)
- [x] Wake word setting: text field to customize wake phrase (default: "Hey Eidos")
- [x] Handoff word setting: text field to customize handoff phrase (default: "Over and out")
- [x] Read aloud toggle: on/off for TTS; persists via DataStore

---

## Phase 5 — Eidos API Layer ✅

Build the abstraction layer that connects Eidos to the three AI providers.

### 5.1 EidosApiClient
- [x] Define a common `EidosRequest` data class (systemPrompt, conversationHistory, toolDefinitions, userMessage)
- [x] Define a common `EidosResponse` data class (textResponse, toolCalls list)
- [x] Define `EidosProvider` interface with a single `send(request)` method
- [x] Implement `XAIProvider` for Grok 4.1 (grok-4.1) — standard chat completions API format with tool calling
- [x] Implement `OpenAIProvider` for GPT-5.4 nano (gpt-5.4-nano-2026-03-17) — uses Responses API, pass `previous_response_id`, reasoning effort = `none`, verbosity = `low`
- [x] Implement `AnthropicProvider` for Claude Haiku 4.5 (claude-haiku-4-5) — enable prompt caching on system prompt and note content
- [x] `EidosApiClient` reads active provider from EncryptedSharedPreferences and delegates to correct provider

### 5.2 Context Assembly
- [x] Assemble system prompt: Eidos identity + current subfolder name/ID + note content (if not AI locked) + list of attached files
- [x] Load recent journal entries (last 1-3 days) and append to system prompt selectively
- [x] Pass all tool definitions (from Phase 6) on every request
- [x] Trim oldest conversation messages if context grows very large; never trim system prompt or current note

### 5.3 Tool Calling Loop
- [x] After each API response check for tool calls in the response
- [x] Execute the tool function locally (Phase 6)
- [x] Send tool result back to the model
- [x] Repeat until the model returns a final text response
- [x] Auto-call `write_log_entry` after every modifying tool call at this layer (not left to the model)

### 5.4 Confirmation Flow
- [x] For tools requiring confirmation (move_to_trash, write_note, edit_note_section): surface the pending action in the chat UI before executing
- [x] Wait for user approval or decline
- [x] Only execute the tool if approved; log the outcome either way

### 5.5 Fallback
- [x] Retry failed requests once after a short delay
- [x] On second failure, show user an error message and offer provider switch option

---

## Phase 6 — Tool Functions ✅

Implement every tool defined in TOOL_FUNCTIONS.md as callable Kotlin functions that the API layer invokes.

### 6.1 Folder Tools
- [x] `create_parent_folder(name)` → insert ParentFolder; log action
- [x] `create_subfolder(parentFolderId, name)` → insert Subfolder + auto-create Note + auto-create system chat Subfolder; log action
- [x] `rename_folder(folderId, newName)` → update name + updatedAt on ParentFolder or Subfolder; log action
- [x] `move_to_trash(folderId)` → requires confirmation; cascade deletedAt; log action
- [x] `list_folder_contents(folderId)` → return list of active subfolders or contents; no modification

### 6.2 Note Tools
- [x] `read_note(subfolderId)` → return note content; ignore AI lock (Eidos can always read)
- [x] `write_note(subfolderId, content)` → requires confirmation; check aiLocked; replace content; update updatedAt; log action; re-embed (Phase 9)
- [x] `append_note(subfolderId, content)` → check aiLocked; append to content; update updatedAt; log action; re-embed (Phase 9)
- [x] `edit_note_section(subfolderId, targetText, newContent)` → requires confirmation; check aiLocked; find and replace targetText; update updatedAt; log action; re-embed (Phase 9)

### 6.3 File Tools
- [x] `list_files(subfolderId)` → return FileReferences list; no modification
- [x] `read_file(fileReferenceId)` → extract text from PDF/docx/odt/txt/md/xlsx/ods/js/py/kt/ts/html/css/json/xml; return text
- [x] `describe_image(fileReferenceId)` → pass image to model vision capability; return description
- [x] `summarize_file(fileReferenceId)` → read_file then summarize; no modification

### 6.4 Search Tool
- [x] `search_system(query, dateFrom?, dateTo?)` → SQL LIKE search across ParentFolder.name, Subfolder.name, Note.content; apply date filters on updatedAt; return results

### 6.5 Journal Tools
- [x] `write_journal_entry(content, timestamp)` → get or create today's daily subfolder in Eidos Journal; append entry to its note with timestamp header
- [x] `read_journal(query?, dateFrom?, dateTo?)` → search journal entries by keyword and/or date range

### 6.6 Log Tools
- [x] `write_log_entry(action, timestamp)` → get or create today's daily subfolder in Eidos Log; append entry to its note with timestamp, action, location, and deep link anchor
- [x] `read_log(query?, dateFrom?, dateTo?)` → search log entries by keyword and/or date range

### 6.7 Voice Handoff Tool
- [x] `voice_handoff(conversationId?, state, timestamp, metadata?)` → update voice session state (start / eidos_speaking / user_listen / end / pause); log state change to Eidos Log

---

## Phase 7 — Eidos Chat UI ✅

Build the Eidos bottom sheet and conversation UI inside the app.

- [x] Eidos button in top bar of all screens opens the bottom sheet
- [x] Bottom sheet covers ~65% of screen; note remains partially visible above
- [x] Swipe down to dismiss; no back button needed
- [x] Message list: user bubbles (right, surface2 background) vs Eidos bubbles (left, gradient messageBubbleEidos with messageBubbleEidosBorder)
- [x] Text input field at bottom of sheet; send button
- [x] Voice button in sheet to switch to voice input (Phase 10)
- [x] Read aloud toggle in sheet
- [x] Conversations scoped to current subfolder save to that subfolder's system chat subfolder; root-level conversations save to Eidos Chats system folder
- [x] Each conversation is one Note; messages appended in `[HH:MM AM/PM] User: ...` / `[HH:MM AM/PM] Eidos: ...` format

---

## Phase 8 — Journal and Log System UI ✅

Build the UI for the user to read the Eidos Journal, Eidos Log, and Eidos Chats.

- [x] Dedicated Eidos section accessible from the app (button in Parent Folder Page top bar area or within three-dot menu)
- [x] Three entries: Eidos Journal, Eidos Log, Eidos Chats (system folders — not shown in regular folder list)
- [x] Each opens like a folder page showing daily subfolders (or conversation subfolders for Chats)
- [x] Tapping a daily subfolder opens the note (read only for user in Journal and Log)
- [x] Checkbox selection for individual entries → delete selected entries
- [x] Deep links in Log entries: tapping a log entry navigates to the referenced folder/subfolder/note and scrolls to the text anchor

---

## Phase 9 — Semantic Search (On-Device) ✅

Build the on-device vector search system for Eidos.

- [x] Integrate TensorFlow Lite with the Universal Sentence Encoder model (bundled in assets)
- [x] Define a local vector store: a simple SQLite table (or separate Room entity) storing (id, sourceType, sourceId, vector BLOB)
- [x] Write `EmbeddingEngine`: takes a text string, runs it through USE, returns a float array
- [x] Write `VectorStore`: insert vector, search by cosine similarity, delete by sourceId
- [x] Embedding pipeline: when a Note is created or updated → embed Note.content → upsert vector in VectorStore
- [x] Embedding pipeline: when Eidos reads a file (read_file tool) → embed extracted text → upsert vector
- [x] Embed ParentFolder and Subfolder names on creation/rename
- [x] Add `search_semantic(query, limit)` tool function: embed query → find top-N closest vectors → return matching notes/folders
- [x] Eidos search priority: semantic first, keyword fallback if semantic confidence is low, date filtering applied on top

---

## Phase 10 — Voice System

Build the voice interaction layer used by both the Eidos bottom sheet and the widget.

- [x] Implement `SpeechToText` wrapper around Android SpeechRecognizer: real-time text display as user speaks
- [x] Implement `TextToSpeech` wrapper around Android TextToSpeech engine
- [x] Implement wake word detection: lightweight always-on listener for "Hey Eidos" (only active when widget is enabled)
- [x] Walkie-talkie handoff: detect handoff word ("Over and out") in speech stream to trigger send; Eidos says handoff word at end of TTS response
- [x] Wire STT → message send → Eidos API call → TTS response loop
- [x] Read aloud toggle: persists via DataStore; when off, TTS skipped but text still shown
- [x] Warn user in settings if they set a very common wake/handoff word
- [x] Voice processing happens on device; no external voice API calls

---

## Phase 11 — Home Screen Widget

Build the Android home screen widget.

- [x] Widget layout: Talk button, End button, Conversation picker dropdown (last 10 conversations), Text chat button
- [x] Widget is minimal — no notes, no folder content, no previews
- [x] Talk button → start STT session → walkie-talkie loop with Eidos
- [x] End button → finalize and save conversation note to Eidos Chats system folder
- [x] Conversation picker: shows last 10 conversations from entire system (query by most recent updatedAt on chat notes); tap to continue selected conversation
- [x] Text chat button → opens a full text chat screen (standard Eidos chat UI, not voice)
- [x] All widget conversations saved to Eidos Chats system folder (one subfolder per conversation, named YYYY-MM-DD — HH:MM AM/PM)
- [x] Wake word detection runs only while widget is active and enabled

---

## Build Order Summary

| Phase | Depends On |
|---|---|
| 1 — Foundation | Nothing |
| 2 — Folder Screens | Phase 1 |
| 3 — Editor View | Phase 2 |
| 4 — Settings | Phase 1 |
| 5 — API Layer | Phase 4 |
| 6 — Tool Functions | Phase 3, Phase 5 |
| 7 — Eidos Chat UI | Phase 5, Phase 6 |
| 8 — Journal/Log UI | Phase 6, Phase 7 |
| 9 — Semantic Search | Phase 6 |
| 10 — Voice System | Phase 7 |
| 11 — Widget | Phase 10 |
