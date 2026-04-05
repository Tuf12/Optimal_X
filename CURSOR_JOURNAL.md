JOURNAL.md

# OptimalX — Agent Context Journal

## System Overview (Read First)

OptimalX is an **AI-first structured knowledge system**.

It is designed so that:
- both humans and AI operate on the same data
- the AI (Eidos) can fully interact with and modify the system
- structure is simple, consistent, and predictable

Core structure:

- App opens to **parent folders**
- Parent folders contain **subfolders**
- Subfolders open directly into a **note/editor**
- The note is the central working space
- Additional data (files, tools, etc.) attaches to the subfolder

This system prioritizes:
- simplicity
- clarity
- fast navigation
- structured organization

---

## AI Role in the System

The AI is not a feature.

The AI is an **active operator inside the system**.

It can:
- create and organize folders
- write and update notes
- search and retrieve information
- build structure over time
- maintain its own understanding of the system

The AI interacts through **tool functions**.

---

## Purpose of This File

This file exists to allow the agent to:

- maintain continuity between prompts
- store useful observations about the system
- track decisions and reasoning
- improve how it approaches development over time

This is **not a rule system**.

This is a **working memory layer**.

---

## How To Use This File (Agent)

You may use this space to:

- log important changes
- store insights about the codebase
- track patterns that improve performance
- note problems and potential solutions
- refine your approach to building OptimalX

You may organize this section in any way that helps you operate better.

Keep entries:
- concise
- relevant
- useful for future steps

Avoid unnecessary repetition.

---

## Developer Visibility

This file is visible to the developer.

Entries should remain:
- clear
- grounded in the project
- aligned with the system's purpose

---

## Journal Entries

### Entry Format (suggested)

- Date:
- Change / Observation:
- Reasoning:
- Impact:

---

### Entries

---

**2026-04-05 — Phase 1 complete**
Built full project foundation: Gradle/Compose/Room setup, theme system (DarkColors/LightColors/OptimalXTheme), Google Fonts (Syne/DM Sans/DM Mono), all four Room entities and DAOs, AppDatabase, DatabaseSeed (creates Eidos Journal/Log/Chats system folders on first install).

---

**2026-04-05 — Phase 2 complete**
Built all folder screens and navigation. Key files: OptimalXApplication, FolderRepository, AppNavigation, ParentFolderViewModel, SubfolderViewModel, TrashViewModel, FolderPage (shared layout), ParentFolderScreen, SubfolderScreen, TrashScreen, EditorScreen (placeholder). All components: FolderCard, FolderTopBar, FolderBottomBar, CreateFolderDialog, RenameFolderDialog, SortBottomSheet, FolderContextMenu, MoveFolderDialog. Design decision: Move option omitted from parent folder context menu (nowhere to move them — no parent above root).

---

**2026-04-05 — Phase 3 complete**
Built full editor view. Key files: EditorRepository, EditorViewModel (undo/redo history stack + SharedFlow restore, auto-save debounce), EditorScreen (HorizontalPager with Note/Files/open file panels, back handler per panel), NotePanel (richeditor-compose RichTextEditor, formatting toolbar, dropdown menu, view/edit mode, AI lock, share/export), FilesPanel (file picker, list with badges, delete), PdfViewerPanel (Android PdfRenderer), ImageViewerPanel (pinch-to-zoom with transformable), DocxViewerPanel (ZIP+XML text extraction), TextFilePanel (plain text). Added richeditor-compose:1.0.0-rc06 to Gradle. Undo/redo is content-level (500ms snapshots), not character-level — acceptable for v2.

---

**2026-04-05 — Phase 4 complete**
Built full settings flow and navigation. Key files: SettingsScreen (new), SettingsViewModel (existing, fully consumed by UI), AppNavigation (new settings route and wired three-dot menu action). Implemented: theme segmented control (light/dark/system, instant DataStore write and live app theme update), API key fields for xAI/OpenAI/Anthropic (EncryptedSharedPreferences), provider radio selection (xAI default), wake word + handoff word text fields (DataStore), and read-aloud toggle (DataStore). Added back navigation from Settings to Parent Folder screen.

---

**2026-04-05 — Phase 5 complete**
Built provider-agnostic API layer and model clients. Key files: EidosApiClient, Eidos models (request/response/messages/tool-call types), EidosToolCatalog, provider implementations (XAIProvider/OpenAIProvider/AnthropicProvider), shared ProviderHttp helper, and DefaultToolExecutor placeholder for Phase 6. Implemented: provider selection from EncryptedSharedPreferences, API key routing per provider, OpenAI Responses API with `previous_response_id` + `reasoning.effort = none` + `text.verbosity = low`, Anthropic prompt caching block on system prompt, context assembly (current subfolder, note unless AI-locked, attached files, recent journal entries 1-3 days), history trimming, tool-calling loop with repeated round-trips until final text, confirmation gate hook for destructive tools, automatic write_log_entry calls after modifying actions, and retry-once fallback with provider-switch guidance. Also added OkHttp + kotlinx.serialization dependencies and INTERNET permission.

---

**2026-04-05 — Phase 6 mostly complete (2 open items)**
Replaced placeholder tool executor with `RoomToolExecutor` and wired it into app startup. Implemented full folder tools, note tools (with AI lock checks), search_system with date filtering, journal and log daily-subfolder append/read behavior, and voice_handoff logging. Implemented file tools with working extraction for docx/odt/ods/xlsx/text/code formats and metadata-based image description, plus summarize_file. Remaining open: `read_file` PDF extraction and true model-vision integration for `describe_image` (currently returns image metadata plus explicit not-yet-integrated status). API-layer auto-log path now safely handles non-JSON tool argument payloads.

---

**2026-04-05 — Phase 6 complete**
Finished the remaining file-tool gaps. `read_file` now supports PDF text extraction via PDFBox Android (`com.tom-roush:pdfbox-android`) in addition to existing docx/odt/ods/xlsx/txt/md/code/json/xml/html/css paths. `describe_image` now calls provider vision APIs through a new `ImageVisionService` and returns model-generated descriptions with image metadata. Provider routing follows active encrypted provider setting and uses provider-specific request formats (OpenAI Responses vision input, Anthropic image blocks, xAI multimodal chat payload). Restored downloadable Google Fonts with official cert arrays (`font_certs.xml`) and kept full compile green.

---

**2026-04-05 — Phase 7 complete**
Built and wired the in-app Eidos chat bottom sheet. Key files: `EidosBottomSheet` (new UI), `AppNavigation` (global bottom-sheet state + scope wiring), and `EidosChatViewModel` (used by sheet with message persistence). Implemented: Eidos button wiring from Parent Folder, Subfolder, and Editor top bars; swipe-to-dismiss ModalBottomSheet sized to ~65% of screen; user/right and Eidos/left bubble styling with gradient + `messageBubbleEidosBorder`; input + send controls; voice button placeholder for Phase 10; read-aloud toggle bound to DataStore from inside sheet; scoped chat storage (`__chat_{subfolderId}__` system subfolder) and root chat storage (`Eidos Chats` system folder) with appended transcript format `[HH:MM AM/PM] User/Eidos: ...`. Also fixed a chat persistence bug so successive lines append against latest note content and hardened send flow with error fallback + `isSending` reset in `finally`.

**Next: Phase 8 — Journal and Log System UI.**
