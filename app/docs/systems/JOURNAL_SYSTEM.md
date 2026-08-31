# JOURNAL_SYSTEM.md

## Purpose

This file describes **Eidos Journal**, **Eidos Log**, and **Eidos Chats** in OptimalX: how they are stored, what they are for, and which Eidos tools apply to them.

It does **not** define the full continuity stack (Daily Memory, Long-Term Memory, rollover) — that lives in [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md) and [ROLLOVER.md](../systems/ROLLOVER.md).

---

## Related documentation

| Topic | File |
|------|------|
| Chat UI, scope (general / parent / subfolder), folder paths | [CHAT_UI.md](../architecture/CHAT_UI.md) |
| Rollover audit (markdown) | [ROLLOVER.md](ROLLOVER.md) |
| Memory, navigation vs reasoning vs chats, tools | [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md) |
| Data shapes | [DATA_MODEL.md](../architecture/DATA_MODEL.md) |

---

## System-level parent folders (context)

[DatabaseSeed.kt](../../src/main/java/com/example/optimalx/data/db/DatabaseSeed.kt) creates these system `ParentFolder` rows (`isSystemFolder = true`):

- **Eidos Journal** — this document  
- **Eidos Log** — this document  
- **Eidos Chats** — this document  
- **Eidos Daily** — Daily Memory (see MEMORY_SYSTEM)  
- **Eidos Memory** — Long-Term Memory (see MEMORY_SYSTEM)  
- **Eidos Reasoning** — rollover audit log (dated subfolders)  
- **Quick Notes** — voice / quick capture  

**UI:** Main list shows **Quick Notes** and **Panel Workshop** per [ParentFolderDao.kt](../../src/main/java/com/example/optimalx/data/dao/ParentFolderDao.kt). Journal, Log, Daily, Index, and Memory are **Eidos-menu-only**. Chat and provider-thinking storage is summarized in MEMORY_SYSTEM (single place of truth).

---

## Overview

| System | Purpose | Who writes | Who reads | Who deletes entries |
|--------|---------|------------|-----------|---------------------|
| Eidos Journal | Reflective record Eidos writes about its experience / continuity | Eidos | Eidos + User | User (individual entries / notes) |
| Eidos Log | Audit trail of actions that modified the app | Eidos | User (+ Eidos via tools) | User only |
| Eidos Chats | Saved conversations (general scope and scoped chats) | Eidos + User | Eidos + User | User |

Journal and Log are not interchangeable: the journal is **not** an append-only action log.

**Tools (journal / log only)** — names match [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt):

- `write_journal_entry`, `read_journal`
- `write_log_entry`, `read_log`

---

## Rules shared by protected system folders

- Created on first install when missing; **`isSystemFolder`** prevents rename, trash, and delete of the parent row.
- Users cannot rename or delete the parent folder as a whole.
- Only inner content (individual notes, lines, or subfolders as implemented) is user-manageable where the UI allows.

---

## Eidos Journal

### What it is

A durable place for **journal entries** Eidos writes (tool: `write_journal_entry`). Entries are organized **by day** under the **Eidos Journal** parent.

### Structure

- Parent folder: **Eidos Journal** (system locked)
- Subfolders: typically **one per calendar day**, named by ISO date (`YYYY-MM-DD`)
- Note inside each day subfolder: content for that day

Exact layout may evolve; the source of truth is how [RoomToolExecutor.kt](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt) reads and writes journal entries.

### What Eidos may write

Reflection on sessions — what was discussed, open threads, follow-ups, and continuity notes Eidos chooses to record. **Not** user biographical facts (location, age, preferences → Long-Term Memory via `write_long_term_memory`). Not raw duplicates of the Log.

### User access

- User can read journal content where exposed in the UI.
- User cannot author journal entries as the assistant (Eidos uses tools).
- User cannot remove system protections from the parent folder.

---

## Eidos Log

### What it is

An **append-only style** activity record: actions that **modified** stored data (folders, notes, files, memory layers, etc.), depending on what `write_log_entry` records in your build.

### Structure

- Parent folder: **Eidos Log** (system locked)
- Subfolders: commonly **one per day** (`YYYY-MM-DD`)
- Note per day subfolder: log lines for that day

### Deep links

Log entries should point to the affected object where possible (folder, subfolder, note, trash). Note-level jumps may use existing IDs plus a short text anchor for scroll position (same idea as in DATA_MODEL / editor).

### Log rules

- Append-only semantics for what Eidos already wrote (Eidos does not rewrite history).
- Read-only operations are generally **not** logged.

### User access

Read and delete individual entries where the UI allows; user does not append log lines manually via assistant tools in normal use.

---

## Eidos Chats

### What it is

Stores **saved chat conversations** only. Separate from **Journal** (reflection) and **Log** (audit). Provider thinking lives on `ChatMessage` rows, not in a Reasoning folder — see MEMORY_SYSTEM.

### Structure

- Parent folder: **Eidos Chats** (system locked)
- Subfolders: typically **one per conversation**, titled by date/time or title conventions your UI uses
- Note per subfolder: serialized conversation (roles, timestamps)

### Scoping (three surfaces)

Aligned with [CHAT_UI.md](../architecture/CHAT_UI.md) and `Conversation.scopeType` in code:

| Where the user is | Conversation scope | Where threads are listed |
|-------------------|--------------------|---------------------------|
| Widget or main folder list | **general** | **Eidos Chats** system parent + Eidos menu → Chats |
| Parent folder page **or** subfolder list (inside a parent, editor not open) | **parent** | Per-user parent → system **Chats** subfolder + Eidos menu → Chats |
| Editor open for a specific subfolder (any panel) | **subfolder** | Eidos menu → Chats for that subfolder |

### Recalling past chats

There is **no** “summarize chat into memory” tool. Past threads are found with:

- `search_chat_history`
- `read_conversation` (after you have a conversation ID)

Both are defined in **EidosToolCatalog**.

### Data model

Uses normal **ParentFolder** / **Subfolder** / **Note** / conversation tables; system rows use `isSystemFolder` / `isSystemSubfolder` as appropriate.

---

## System prompt context (journal-related slice)

Per [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md), the **target** default prompt includes **full location awareness** and **Daily Memory** only. **Long-Term Memory** and **journal** content are **not** meant to be auto-injected; Eidos uses `read_long_term_memory`, `read_journal`, and `search_semantic` when it needs more context.

**Implementation:** [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) may still inject LTM and recent journal until the client matches that contract.

---

## How Eidos searches Journal and Log

Use tools only:

- `read_journal` — query / date range parameters per catalog schema  
- `read_log` — query / date range parameters per catalog schema  

This avoids loading entire histories into context.

---

## Timestamps

Store times as Unix **Long** values in the database; display human-readable dates in the UI. Daily folder names use **ISO date**: `YYYY-MM-DD`.

---

## Data model notes

Journal and Log use the same **ParentFolder**, **Subfolder**, and **Note** entities as the rest of the app. System parents use **`isSystemFolder = true`**.

---

## Summary

| Feature | Eidos Journal | Eidos Log |
|--------|---------------|-----------|
| Purpose | Journal / reflection | Modification audit |
| Primary tools | `write_journal_entry`, `read_journal` | `write_log_entry`, `read_log` |
| Typical tone | Reflective | Factual action lines |
| System locked parent | Yes | Yes |
