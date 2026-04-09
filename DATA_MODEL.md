All projects
Optimal X
OptimalX is a **simple, structured note system designed for both humans and AI agents to use directly**. It is not a traditional note app. It is a **hierarchical knowledge environment** where: - users store information in folders and notes - AI agents (Eidos) can read, write, organize, and operate inside the same system


How can I help you today?

    Market viability of custom note-taking app
    Last message 7 hours ago

Speech to text timeout issues
Last message 14 hours ago
Optimal X app architecture documentation
Last message 1 day ago
MD files prepared for coding agent project
Last message 2 days ago
GitHub repository description
Last message 2 days ago
Memory
Only you

Purpose & context Jesse is a solo developer building OptimalX v2, a Kotlin/Jetpack Compose Android note-taking and AI agent app. A previous version was built with ChatGPT; v2 is a more mature, fully architected rebuild. Jesse uses AI coding agents (Claude Code and Cursor) as the primary development mechanism, with Claude serving as architecture partner. A separate app, Stockzilla, also exists with its own Eidos AI agent implementation. Jesse's longer-term vision includes inter-agent API communication across multiple apps. Current state A full suite of architecture documentation (14 MD files) has been produced and refined, covering the complete system: UI principles, app structure, data model, editor/panels, files/media, the Eidos agent, tool functions, journal system, search/retrieval, API layer, widget system, and voice system. HTML mockups of the main app screens and widget states have also been completed. Key architectural decisions locked in: Database: Room with unique Long IDs; soft-delete via deletedAt timestamps Navigation: Swipe-based panel system in the editor; bottom sheet for Eidos AI assistant Search: Dual keyword + semantic search via TensorFlow Lite Universal Sentence Encoder API layer: Multi-provider (Grok 4.1, GPT-5.4 nano with 1M context window, Claude Haiku 4.5); GPT-5.4 nano supports tool functions but not tool search or computer use Voice: Walkie-talkie style with customizable wake word ("Hey Eidos") and handoff phrase ("Over and out") AI agent: Named Eidos (chosen for its philosophical meaning relating to essence); three-tier system folder structure (Eidos Journal, Eidos Log, Eidos Chats) mirroring the app's regular folder hierarchy UI: Dark theme, acid green (#c8fb5e) accents, Syne/DM Sans/DM Mono fonts; animated waveforms confirmed achievable in-app but not in Android home screen widgets Key learnings & principles Documentation files are machine-readable blueprints for coding agents, not human narratives — conciseness and clarity are essential Coding agents should have room to think; over-constraining prompts is counterproductive "What Does Not Exist" sections and similar explanatory bloat are unwanted and should be cut Journal.md functions as working memory for the Cursor coding agent (context across sessions); README.md is for GitHub/human readers — these serve distinct purposes Approach & patterns Jesse has strong product instincts and actively corrects errors (factual, structural, or stylistic) during collaboration — Claude should expect pushback and treat it as signal, not noise Claude Code runs with auto-accept enabled during straightforward build phases Builds are tested on a physical Android device via Android Studio before changes are accepted Large single-file outputs can fail to preview in-app; splitting into smaller files is the reliable workaround Preference for concise, direct outputs — no redundant explanations or padding Tools & resources Development: Kotlin, Jetpack Compose, Android Studio, Room database, TensorFlow Lite AI coding agents: Claude Code, Cursor APIs: Grok 4.1, GPT-5.4 nano, Claude Haiku 4.5 Testing: Physical Android device

Last updated 23 hours ago
Instructions

Add instructions to tailor Claude’s responses
Files
2% of project capacity used

Data model

10.79 KB •281 linesFormatting may be inconsistent from source
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
- The "Eidos Chats" system folder is visible in the main folder list alongside user folders — it is not hidden
- The "Eidos Journal" and "Eidos Log" system folders are not shown in the main folder list — they are only accessible via the Eidos menu
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

### Conversation

A single chat session between the user and Eidos.
Every conversation is scoped to exactly one context level.

| Field | Type | Description |
|---|---|---|
| id | Long (auto) | Unique identifier, auto-generated |
| scopeType | String | The level this conversation belongs to: "general", "parent", or "subfolder" |
| parentFolderId | Long? | Set when scopeType is "parent". ID of the parent folder. Null otherwise. |
| subfolderId | Long? | Set when scopeType is "subfolder". ID of the subfolder. Null otherwise. |
| title | String | Auto-generated title — date and time of creation (e.g. April 6, 2026 — 2:34 PM) |
| createdAt | Long | Unix timestamp of creation |
| updatedAt | Long | Unix timestamp of last message |

Scope rules:
- scopeType "general" — conversation started from widget or parent folder page. Both parentFolderId and subfolderId are null.
- scopeType "parent" — conversation started from a subfolder page. parentFolderId is set. subfolderId is null.
- scopeType "subfolder" — conversation started from inside the editor (any panel). subfolderId is set. parentFolderId may be set for reference but subfolderId is the primary key.

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
Main folder list
    ├── Eidos Chats (system ParentFolder, visible to user)
    │       └── Conversation (0 or many, scopeType "general") ← widget + root-level chats
    ├── Eidos Journal (system ParentFolder, menu-only)
    ├── Eidos Log (system ParentFolder, menu-only)
    └── UserParentFolder (many)
            ├── Chats (system Subfolder, auto-created, visible to user)
            │       └── Conversation (0 or many, scopeType "parent")
            └── UserSubfolder (many)
                    ├── Note (exactly 1, auto-created)
                    ├── FileReference (0 or many)
                    └── Conversation (0 or many, scopeType "subfolder")

Conversation (1)
    └── ChatMessage (many)
```

- ParentFolder → Subfolder: one to many (includes the auto-created "Chats" system subfolder)
- Subfolder → Note: one to one
- Subfolder → FileReference: one to many
- Conversation → ChatMessage: one to many

Chat scope to folder mapping:
- `scopeType "general"` → stored under Eidos Chats system parent folder; accessible from main list or Eidos menu on any top-level page
- `scopeType "parent"` → linked to a user ParentFolder via parentFolderId; accessible via the "Chats" system subfolder within that parent folder or via the Eidos menu on the parent folder page
- `scopeType "subfolder"` → linked to a user Subfolder via subfolderId; accessible only via the Eidos menu on the editor/panel page for that subfolder

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
