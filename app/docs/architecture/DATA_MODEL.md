# DATA_MODEL.md

## Purpose

This file defines every object the app stores, how those objects are structured, and how they relate to each other.

Coding agents should use this file as the source of truth for the database schema.
Nothing in the database should exist that is not defined here.

---

## Database

OptimalX v2 uses a **Room database** (built on SQLite) for all structured data.

Room is the standard Android database layer for Kotlin.
It handles object mapping, query management, and data access through DAOs (Data Access Objects).

All app data lives in this database except for file content (PDFs, images, documents) which is stored in device storage and referenced by path.

---

## Design Decisions

### IDs over names/paths
Every object has a unique auto-generated ID.
Names and paths can change (rename, move). IDs never change.
Relationships between objects are always linked by ID, never by name or path.

This prevents broken links when folders are renamed or moved.
It also makes AI navigation reliable — an agent can reference any object by ID without worrying about naming changes.

### File storage by reference
Actual file content (PDFs, images, Word documents) is stored in device storage, not in the database.
The database stores a reference (file path) pointing to where the file lives.

This approach was chosen intentionally to support future cloud storage.
When cloud is added, the file path simply becomes a cloud URL instead of a local path.
No structural changes to the database are required for that transition.

---

## Objects

---

### ParentFolder

A top-level container. Holds subfolders.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| name | String | Display name of the folder |
| createdAt | Long | Unix timestamp of creation |
| updatedAt | Long | Unix timestamp of last modification |
| sortOrder | Int | User-defined sort position |
| isSystemFolder | Boolean | If true, cannot be renamed, moved to trash, or deleted. Default false. |
| deletedAt | Long? | Unix timestamp of when item was moved to trash. Null means active. |

Rules:
- A parent folder contains zero or more subfolders
- A parent folder does not contain notes directly
- Deleting a parent folder moves it and all its subfolders and contents to trash (sets deletedAt)
- Permanently deleting removes the row from the database
- System folders (isSystemFolder = true) cannot be trashed, deleted, or renamed
- The **Eidos Chats** and **Quick Notes** system folders are visible in the main folder list alongside user folders — they are not hidden
- The **Eidos Journal** and **Eidos Log** system folders are not shown in the main folder list — they are only accessible via the Eidos menu
- **Quick Notes** is a system parent folder used as an unstructured capture inbox (dated subfolders and daily notes are created by the app / Eidos tools, not by manual “new subfolder” in the UI). Full append-only in-app editing is specified in `QUICK_NOTES.md`
- When a user creates a new parent folder, a "Chats" system subfolder is automatically created inside it (see Subfolder rules below)

---

### Subfolder

A working space inside a parent folder. Each subfolder has exactly one note.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| parentFolderId | Long | ID of the parent folder that contains this subfolder |
| name | String | Display name of the subfolder |
| createdAt | Long | Unix timestamp of creation |
| updatedAt | Long | Unix timestamp of last modification |
| sortOrder | Int | User-defined sort position |
| isSystemSubfolder | Boolean | If true, cannot be renamed, moved to trash, or deleted. Default false. |
| deletedAt | Long? | Unix timestamp of when item was moved to trash. Null means active. |

Rules:
- A subfolder belongs to exactly one parent folder (via parentFolderId)
- A subfolder always has exactly one note (created automatically when the subfolder is created)
- A subfolder can have zero or more file attachments
- Deleting a subfolder moves it and its note and file references to trash (sets deletedAt)
- Permanently deleting removes the row from the database
- System subfolders (isSystemSubfolder = true) cannot be trashed, deleted, or renamed
- When a user creates a new parent folder, a "Chats" system subfolder (isSystemSubfolder = true) is automatically inserted into that parent folder
- The "Chats" system subfolder is visible in the subfolder list but cannot be edited by the user — its note content is managed only by the Eidos chat system
- Tapping the "Chats" subfolder opens the Conversation List screen for that parent folder scope, not the note editor

---

### Note

The text content tied to a subfolder. One note per subfolder, always.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| subfolderId | Long | ID of the subfolder this note belongs to |
| content | String | Full text content of the note |
| createdAt | Long | Unix timestamp of creation |
| updatedAt | Long | Unix timestamp of last modification |
| deletedAt | Long? | Unix timestamp of when item was moved to trash. Null means active. |
| aiLocked | Boolean | If true, Eidos cannot write to this note. Eidos can still read it. Default is false. |
| aiBlind | Boolean | If true, Eidos cannot read or write this note. The content is fully unavailable to API calls — excluded from `read_note`, `search_semantic`, `list_folder_contents` previews, the active-subfolder system context, daily/long-term memory and journal context loaders, and the materialized Tag & Hint Index. The user's own UI (editor, search) is unaffected. Default is false. |

Rules:
- A note belongs to exactly one subfolder (via subfolderId)
- A note is created automatically when its subfolder is created
- A note is moved to trash automatically when its subfolder is moved to trash
- There is no standalone note — notes always belong to a subfolder

---

### FileReference

A pointer to a file stored in device storage (or cloud storage in the future).

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| subfolderId | Long | ID of the subfolder this file belongs to |
| fileName | String | Display name of the file |
| fileType | String | Type of file: pdf / docx / image |
| filePath | String | Path to the file in device storage (or cloud URL in future) |
| createdAt | Long | Unix timestamp when file was attached |

Rules:
- A file reference belongs to exactly one subfolder (via subfolderId)
- The actual file content lives in device storage, not in the database
- filePath points to the location of the file on the device
- When cloud storage is added, filePath becomes a cloud URL — no schema change required
- Supported file types in v2: pdf, docx, image
- Deleting a subfolder deletes all associated file references
- Deleting a file reference does not automatically delete the file from device storage (handle separately)

---

### HomePin

A shortcut on the **parent-page pinned row**. Does not move or copy the target — navigation only.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier |
| pinType | String | `parent`, `subfolder`, or `panel` |
| targetId | Long | ID of the parent folder, user subfolder, or workshop project subfolder |
| displayName | String | Snapshot at pin time; row label refreshes from live entity name |
| sortOrder | Int | Display order in the pinned row |
| createdAt | Long | Unix timestamp |

Rules:
- UNIQUE(`pinType`, `targetId`) — pinning the same target twice is idempotent
- Pins are removed when the target is trashed/deleted, or when a parent folder delete cascades to its subfolder pins
- Panel pins reference a **Panel Workshop** project subfolder (`workshopSubfolderId`)
- Pins do not appear in search or the Eidos index

---

### PanelState

Opaque JSON persisted for interactive workshop panels (gallery runner + editor custom tabs).

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier |
| workshopSubfolderId | Long | Panel Workshop project subfolder |
| scopeKey | String | `global` (gallery runner) or `subfolder:{hostSubfolderId}` (editor tab) |
| stateJson | String | Panel-owned JSON blob |
| updatedAt | Long | Unix timestamp |

Rules:
- UNIQUE(`workshopSubfolderId`, `scopeKey`)
- Kotlin stores verbatim JSON; panel JS defines schema (`panelGetState` / `panelRestoreState` in script.js — not automatic DOM capture)
- Deleted when workshop project is permanently removed
- Included in Room DB backup (not a separate files path)

**Contract and rollout:** [PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md](../implementation/PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md). Platform behavior: [PANEL_PLATFORM.md](PANEL_PLATFORM.md#platform-capability-panel-state-persistence).

---

### Conversation

A single chat session between the user and Eidos.
Every conversation is scoped to exactly one context level.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| scopeType | String | `general`, `parent`, `subfolder`, `quick_notes_root`, `quick_notes_day`, `panel_workshop`, `panel_gallery`, `panel_runner`, `dump_edit`, `web_editor`, `web_widget` |
| parentFolderId | Long? | Set when scopeType is `parent` or `quick_notes_root`. Null otherwise. |
| subfolderId | Long? | Set when scopeType is `subfolder`, `quick_notes_day`, `panel_workshop`, or `panel_runner` (workshop project id). Null otherwise. |
| title | String | Auto-generated title — date and time of creation (e.g. April 6, 2026 — 2:34 PM) |
| createdAt | Long | Unix timestamp of creation |
| updatedAt | Long | Unix timestamp of last message |

Scope rules (summary):
- `general` — widget or parent-level chat; no folder IDs
- `parent` / `subfolder` — folder-scoped chat from subfolder page or editor
- `quick_notes_root` / `quick_notes_day` — Quick Notes surfaces
- `panel_workshop` — Panel Workshop editor chat (`subfolderId` = workshop project)
- `panel_gallery` — Panel Gallery browse/launch surface; no folder IDs (singleton scope, like `dump_edit`)
- `panel_runner` — Panel Runner full-screen chat (`subfolderId` = workshop project); **not** the same thread as `panel_workshop` for that project
- `dump_edit` — DumpEdit scratch buffer chat (no folder IDs)
- `web_editor` / `web_widget` — in-app or widget web search threads

Rules:
- A conversation is created automatically when the user opens Eidos in any context
- A conversation is never reused across sessions — each time Eidos is opened a new conversation starts unless the user explicitly continues a previous one
- Conversations are never moved to trash — the user deletes them directly from the history browser
- Deleting a parent folder deletes all conversations scoped to it
- Deleting a subfolder deletes all conversations scoped to it

---

### ChatMessage

A single message inside a conversation.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| conversationId | Long | ID of the conversation this message belongs to |
| role | String | Who sent the message: "user" or "eidos" |
| content | String | Full text content of the message |
| createdAt | Long | Unix timestamp of the message |

Rules:
- A message belongs to exactly one conversation (via conversationId)
- Messages are append only — they are never edited after being written
- Deleting a conversation deletes all its messages

---

## Object Relationships

```
Parent page (user grid only)
    └── UserParentFolder (many) → UserSubfolder → Note, FileReference

Pinned row (shortcuts, not in grid)
    ├── Panels → Panel Gallery → PanelState (global) per workshop project
    ├── DumpEdit → DataStore buffer (not Room note)
    ├── Panel Workshop / Quick Notes → system parents (not in user grid)

Eidos menu-only parents: Journal, Log, Daily, Memory, Index
Hidden legacy (DB only): Eidos Chats, Eidos Reasoning (no longer written), per-parent Chats/Reasoning subfolders

HomePin → parent | subfolder | panel (shortcut targets)

Conversation (scoped) → ChatMessage (many)
```

- ParentFolder → Subfolder: one to many (legacy Chats subfolders may exist but are hidden in UI)
- Subfolder → Note: one to one
- Workshop project → PanelState: one to many (per scopeKey)
- Conversation → ChatMessage: one to many

Chat UI holds conversation history; folder UI does not list chat system folders (see `APP_STRUCTURE.md`).

---

## ID and Timestamp Rules

- All IDs are Long type, auto-generated by Room (autoGenerate = true)
- All timestamps are stored as Unix time in milliseconds (Long)
- createdAt is set once at creation and never changed
- updatedAt is updated every time the object is modified
- IDs are never reused after deletion

---

## DAO Layer

Each object has its own DAO (Data Access Object) in Kotlin.
DAOs define all database operations for that object type.

Standard operations per object:
- insert
- update
- delete
- getById
- getAll (filtered by parent where applicable)

DAOs are the only layer that touches the database directly.
App logic and AI tool functions call DAOs — they do not write raw SQL.

---

## Trash System

OptimalX uses a soft delete system. Nothing is permanently deleted immediately.

### How it works
- Every object has a `deletedAt` field (Long?, nullable)
- When a user deletes something, `deletedAt` is set to the current timestamp
- The item disappears from the active app but remains in the database
- Items where `deletedAt` is null are active
- Items where `deletedAt` has a value are in the trash

### Cascade behavior
- Deleting a parent folder → sets deletedAt on the parent folder, all its subfolders, their notes, file references, and all scoped conversations and messages
- Deleting a subfolder → sets deletedAt on the subfolder, its note, file references, and all scoped conversations and messages
- Everything moves together

### Restoring
- Restoring any item clears its deletedAt field (sets back to null)
- Restoring a subfolder also restores its note and file references
- Restoring a subfolder does not automatically restore its parent folder if the parent was also deleted — handle parent restore separately

### Permanent deletion
- Permanent deletion removes the row from the database entirely
- File content in device storage must be deleted separately when a FileReference is permanently deleted

### Trash UI access
- A trash icon button sits in the bottom bar of the Parent Folder Page
- The trash screen shows all soft-deleted items
- From the trash the user can restore or permanently delete items

---

## What Does Not Exist in v2

- No global file library (files are always scoped to a subfolder)
- No multiple notes per subfolder
- No third folder level (no subfolders inside subfolders)
- No user accounts or authentication (local only in v2)
- No sync system (cloud storage is a future addition, structure is ready for it)
