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
| deletedAt | Long? | Unix timestamp of when item was moved to trash. Null means active. |
| isSystemFolder | Boolean | If true, parent folder is a protected system folder (cannot be renamed, moved to trash, or deleted). Default is false. |

Rules:
- A parent folder contains zero or more subfolders
- A parent folder does not contain notes directly
- Deleting a parent folder moves it and all its subfolders and contents to trash (sets deletedAt)
- Permanently deleting removes the row from the database

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
| deletedAt | Long? | Unix timestamp of when item was moved to trash. Null means active. |
| isSystemSubfolder | Boolean | If true, subfolder is a protected system subfolder (cannot be renamed, moved to trash, or deleted). Default is false. |

Rules:
- A subfolder belongs to exactly one parent folder (via parentFolderId)
- A subfolder always has exactly one note (created automatically when the subfolder is created)
- A subfolder can have zero or more file attachments
- Deleting a subfolder moves it and its note and file references to trash (sets deletedAt)
- Permanently deleting removes the row from the database

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
| fileType | String | Type of file. Supported types include: pdf, docx, odt, image (jpg/png/gif/webp/heic), txt, md, xlsx, ods, pptx, odp, code (js/py/kt/ts/html/css/json/xml) |
| filePath | String | Path to the file in device storage (or cloud URL in future) |
| createdAt | Long | Unix timestamp when file was attached |

Rules:
- A file reference belongs to exactly one subfolder (via subfolderId)
- The actual file content lives in device storage, not in the database
- `filePath` points to the location of the file on the device
- When cloud storage is added, `filePath` becomes a cloud URL — no schema change required
- Supported file types in v2: pdf, docx, odt, jpg, png, gif, webp, heic, txt, md, xlsx, ods, pptx, odp, js, py, kt, ts, html, css, json, xml
- Deleting a subfolder deletes all associated file references
- Deleting a file reference does not automatically delete the file from device storage (handle separately)

 - Deleting a file reference does not automatically delete the file from device storage (handle separately)

---

## System Parent Folders (created on first install)

On first install the app creates three protected system-level parent folders. These are real ParentFolder objects with `isSystemFolder = true` and must be present so coding agents and migration scripts can rely on them:

| Folder | Purpose |
|---|---|
| Eidos Journal | Eidos private working memory — daily subfolders with journal notes for Eidos to rebuild context |
| Eidos Log | Append-only activity record — daily subfolders with log notes recording every system modification Eidos makes |
| Eidos Chats | Conversation archive — stores widget and app-level conversations (one subfolder/note per conversation) |

Rules for system parent folders:
- Created automatically on first app install
- Have `isSystemFolder = true` set on the ParentFolder record
- Cannot be renamed, moved to trash, or deleted as a whole (individual entries/conversations may be deleted)
- They do not appear in the regular folder list and are accessible from the dedicated Eidos section in the app

## Object Relationships

```
ParentFolder (1)
    └── Subfolder (many)
            ├── Note (exactly 1, auto-created)
            └── FileReference (0 or many)
```

- ParentFolder → Subfolder: one to many
- Subfolder → Note: one to one
- Subfolder → FileReference: one to many

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
- Deleting a parent folder → sets deletedAt on the parent folder, all its subfolders, their notes, and their file references
- Deleting a subfolder → sets deletedAt on the subfolder, its note, and its file references
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
