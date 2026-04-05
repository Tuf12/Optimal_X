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

**Next: Phase 5 — Eidos API Layer.**
