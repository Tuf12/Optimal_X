# MEMORY_SYSTEM.md

## Purpose

This file describes how Eidos **continuity and context** work: which system folders exist, what should appear **in the normal chat system prompt**, what is **tool-driven only**, and how that maps to code.

**Related docs:** [JOURNAL_SYSTEM.md](../systems/JOURNAL_SYSTEM.md) (Journal / Log / Chats), [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) (semantic + chat tools), [ROLLOVER.md](../systems/ROLLOVER.md) (nightly rollover), [CHAT_UI.md](../architecture/CHAT_UI.md) (chat UI + scope rules), [WORKSHOP_MEMORY.md](WORKSHOP_MEMORY.md) (Panel Workshop categorical preferences — separate from LTM/Daily), [DATA_MODEL.md](../architecture/DATA_MODEL.md).

**Source of truth for tool names and parameters:** [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt). If a document disagrees with the catalog, the catalog wins.

---

## Navigation and chat scope

**Surfaces (top → bottom):**

1. **Main folder list** — User parent folders plus visible system parents (see [ParentFolderDao.kt](../../src/main/java/com/example/optimalx/data/dao/ParentFolderDao.kt): **Quick Notes** and **Panel Workshop** appear here alongside non-system folders).
2. **Inside a user parent folder** — **Subfolder list** (user subfolders + hidden system **Chats** / **Reasoning**, etc.).
3. **Inside a user subfolder** — **Editor** (panels + note). Opening chat from here uses **subfolder** conversation scope.

**Saved chat threads** (`scopeType` general / parent / subfolder) live in the chat UI and `chat_messages` table — not in a browsable Reasoning folder. Provider thinking for troubleshooting is stored on each assistant `ChatMessage` (`assistantReasoningContent`) and shown in the collapsible **Reasoning** section on chat bubbles. Detail: [CHAT_UI.md](../architecture/CHAT_UI.md), [CONVERSATION_DIRECTORY.md](../architecture/CONVERSATION_DIRECTORY.md).

**Chat scope nuance:** On the **subfolder list** screen (inside a parent, before opening the editor), new chat still uses **parent** scope until the user opens the editor for a specific subfolder — see scope table in [CHAT_UI.md](../architecture/CHAT_UI.md).

---

## System parent folders

Created in [DatabaseSeed.kt](../../src/main/java/com/example/optimalx/data/db/DatabaseSeed.kt) (`isSystemFolder = true` when inserted):

| Name | Role |
|------|------|
| Eidos Journal | Journal entries (see JOURNAL_SYSTEM) |
| Eidos Log | Action log (see JOURNAL_SYSTEM) |
| Eidos Chats | Saved conversations |
| Eidos Daily | Daily Memory (dated subfolder + note per day) |
| Eidos Memory | Long-Term Memory (one or more notes under the parent) |
| Eidos Reasoning | Rollover audit log (dated subfolders; markdown chunks) |
| Quick Notes | Quick / voice capture |

**UI:** [ParentFolderDao.kt](../../src/main/java/com/example/optimalx/data/dao/ParentFolderDao.kt) exposes **Quick Notes** and **Panel Workshop** on the main list. Legacy **Eidos Chats** / **Eidos Reasoning** parent rows may still exist in the DB but are hidden from folder UI. **Eidos Journal**, **Eidos Log**, **Eidos Daily**, and **Eidos Memory** are **menu-only** (Eidos section), not mixed into the main grid. Legacy **Eidos Index** parents may exist on upgraded DBs but are no longer seeded or used.

---

## Retrieval (app-wide)

Use **`search_semantic`** for meaning-based lookup across notes, files, and chats. For note edits, build `note_replace_string` `oldString` from `chunk_text` in search hits. Expand file hits with `read_file` or `workshop_read_file` when needed. See [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md).

The former **Tag & Hint / Eidos Index** system was removed from the shipping app (2026-06). Historical spec: [archive/agent_loops/TAG_HINT_SYSTEM.md](../archive/agent_loops/TAG_HINT_SYSTEM.md).

---

## What the normal chat system prompt includes

**Target contract:** only **location** and **Daily Memory** belong in the default system prompt. Eidos is expected to have **full location awareness** — the user’s current scope (general, parent folder, or subfolder) and the content needed to act there (note text or AI-lock notice, attached files, parent listing when relevant, plus base prompt / provider rules) are assembled into the prompt so Eidos always knows **where** it is working.

1. **Location awareness** — Full context for the active location (see `EidosApiClient.assembleSystemPrompt` and related helpers).  
2. **Daily Memory** — On main chat (general / parent / subfolder), today's note is **not** inlined in full. Relevant daily chunks appear in `## Retrieved context` from prefetch; if today has entries but none matched, a one-line fallback hints at `write_daily_memory` / `search_semantic`.

### Daily vs Long-Term vs folder memory vs journal

| Layer | Store | Who writes | Typical content |
|-------|-------|------------|-----------------|
| **Daily Memory** | Eidos Daily (dated subfolders) | Eidos (`write_daily_memory`) | Today's tasks, decisions in flight, session mood — **not** durable identity |
| **Long-Term Memory** | Eidos Memory (dated subfolders) | Eidos (`write_long_term_memory`) | Durable user facts: location, preferences, relationships, standing constraints |
| **Folder `[Memory]`** | `Note.summary` on user subfolders | User + Eidos (`write_note_summary`) | Project/folder-specific rules and facts |
| **Journal** | Eidos Journal | Eidos (`write_journal_entry`) | Eidos session reflections — **not** user biography (that belongs in LTM) |

**User UI:** Eidos menu → **Eidos Daily** / **Eidos Memory** shows timestamped entries. **Long-press** an entry → confirm → delete (removes that chunk from the note and re-indexes semantic search).

**Not** injected into the system prompt (Eidos reads these with tools when needed):

- **Long-Term Memory** — Use `read_long_term_memory` (writes: `write_long_term_memory`, `prune_long_term_memory`).  
- **Eidos Journal** — Use `read_journal` (writes: `write_journal_entry`). Do not rely on an automatic “recent journal” excerpt in the prompt.

A future **chat loop** may describe when and how to open LTM and journal more clearly; until then, use memory tools and `search_semantic` when the default prompt is not enough.

**Also not** inlined in full: the full **past** chat transcript — current-session messages are attached on the request with trimming (`trimHistoryIfNeeded`).

**Implementation note:** [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) may still call `loadLongTermMemoryContext` and `loadRecentJournalContext` inside `assembleSystemPrompt` until that pipeline is updated to match this contract (remove LTM and recent journal from automatic injection).

---

## Per-note folder memory ([Memory] in `Note.summary`)

Curated bullets for each subfolder note live in **`Note.summary`** under the `[Memory]` section. Full spec: [NOTE_SUMMARY.md](../systems/NOTE_SUMMARY.md). Eidos updates them via **`write_note_summary`**; users edit them in the note editor **Folder memory & digest** panel.

Legacy **subfolder memory cache** (parent-level JSON map in a hidden system subfolder) was **removed in 2026-06**. v25 migration copies cache entries into empty `[Memory]` sections when possible.

---

## Chat recall

There is **no** tool that summarizes chats into memory for you.

To find or reopen history:

- `search_chat_history`
- `read_conversation`

---

## Semantic search

Catalog tool: `search_semantic`. Primary retrieval path for notes, files, and chats.

---

## Midnight rollover (behavioral overview)

Nightly rollover is orchestrated in application code. See [ROLLOVER.md](../systems/ROLLOVER.md) and [`MemoryRolloverService.kt`](../../src/main/java/com/example/optimalx/data/eidos/MemoryRolloverService.kt). It uses tools via `RoomToolExecutor`; some executor actions exist **only** for orchestration and may **not** appear in `EidosToolCatalog`.

High-level intent: read daily snapshot → synthesis passes → write journal / LTM → **clear Daily Memory in code after success** (`ROLLOVER_OK` verification).

---

## Data model (current Room)

Match the entities in `data/model`:

- **ParentFolder** — includes `isSystemFolder`.  
- **Subfolder** — includes `isSystemSubfolder`.  
- **Note** — `content`, `aiLocked`, etc.

Do not document unimplemented columns (e.g. `memoryCache` on `Subfolder`, `semanticTags`) as if they already exist in the database.

---

## Eidos tools (must match `EidosToolCatalog.all`)

| Tool | Modifying | Requires confirmation |
|------|-----------|------------------------|
| `create_parent_folder` | Yes | No |
| `create_subfolder` | Yes | No |
| `rename_folder` | Yes | No |
| `move_to_trash` | Yes | Yes |
| `list_folder_contents` | No | No |
| `write_note` | Yes | No |
| `note_replace_string` | Yes | No |
| `write_note_summary` | Yes | No |
| `read_file` | No | No |
| `describe_image` | No | No |
| `read_conversation` | No | No |
| `search_chat_history` | No | No |
| `search_semantic` | No | No |
| `read_daily_memory` | No | No |
| `write_daily_memory` | Yes | No |
| `read_long_term_memory` | No | No |
| `write_long_term_memory` | Yes | No |
| `prune_long_term_memory` | Yes | Yes |
| `write_journal_entry` | Yes | No |
| `read_journal` | No | No |
| `write_log_entry` | Yes | No |
| `read_log` | No | No |
| `write_quick_note` | Yes | No |

Parameter schemas are defined only in code (`parametersSchema` per tool).

---

## Summary

| Concern | In default system prompt | On demand (tools) |
|--------|---------------------------|-------------------|
| Location awareness | Yes (full) | — |
| Semantic retrieval | No | `search_semantic` |
| Folder memory ([Memory]) | Inject in subfolder scope (`NotePromptContext`) | `write_note_summary` / editor panel |
| Daily Memory | Yes (today) | `read_daily_memory` / `write_daily_memory` |
| Long-Term Memory | No | `read_long_term_memory` / `write_long_term_memory` / `prune_long_term_memory` |
| Eidos Journal | No | `read_journal` / `write_journal_entry` |
| Log | No | `read_log` / `write_log_entry` |
| Past chats | No (session history separate) | `search_chat_history` / `read_conversation` |
