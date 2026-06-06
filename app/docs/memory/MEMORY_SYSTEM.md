# MEMORY_SYSTEM.md

## Purpose

This file describes how Eidos **continuity and context** work: which system folders exist, what should appear **in the normal chat system prompt**, what is **tool-driven only**, and how that maps to code.

**Related docs:** [JOURNAL_SYSTEM.md](../systems/JOURNAL_SYSTEM.md) (Journal / Log / Chats), [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) (keyword, semantic, Tag & Hint, chat tools), [CHAT_UI.md](../architecture/CHAT_UI.md) (chat UI + scope rules), [TAG_HINT_SYSTEM.md](../agent_loops/TAG_HINT_SYSTEM.md) (Tag & Hint — not a summary system), [WORKSHOP_MEMORY.md](WORKSHOP_MEMORY.md) (Panel Workshop categorical preferences — separate from LTM/Daily), [DATA_MODEL.md](../architecture/DATA_MODEL.md).

**Source of truth for tool names and parameters:** [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt). If a document disagrees with the catalog, the catalog wins.

---

## Navigation, chat scope, and reasoning (don’t conflate folders)

**Surfaces (top → bottom):**

1. **Main folder list** — User parent folders plus visible system parents (see [ParentFolderDao.kt](../../src/main/java/com/example/optimalx/data/dao/ParentFolderDao.kt): **Eidos Chats**, **Quick Notes**, **Eidos Reasoning** appear here alongside non-system folders).
2. **Inside a user parent folder** — **Subfolder list** (user subfolders + system **Chats** + system **Reasoning** + **Memory Cache**, etc.).
3. **Inside a user subfolder** — **Editor** (panels + note). Opening chat from here uses **subfolder** conversation scope.

**Conversations vs reasoning:**

| Role | System locations |
|------|------------------|
| **Saved chat threads** (`scopeType` general / parent / subfolder) | **Eidos Chats** (general) → per-parent **Chats** system subfolder → subfolder-scoped lists from editor. Detail: [CHAT_UI.md](../architecture/CHAT_UI.md), [CONVERSATION_DIRECTORY.md](../architecture/CONVERSATION_DIRECTORY.md). |
| **AgentByte reasoning traces** (`ABR1|` lines) | **Eidos Reasoning** (general / no parent) → dated notes under that parent; **Reasoning** system subfolder under each user parent for parent- or subfolder-derived scope. Implementation: [AgentByteReasoningLogger.kt](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteReasoningLogger.kt). |

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
| Eidos Index | **App-wide Tag & Hint** index storage (not inside Journal) |
| Eidos Reasoning | AgentByte reasoning artifacts |
| Quick Notes | Quick / voice capture |

**UI:** [ParentFolderDao.kt](../../src/main/java/com/example/optimalx/data/dao/ParentFolderDao.kt) exposes **Eidos Chats**, **Quick Notes**, and **Eidos Reasoning** on the main list. [DatabaseSeed.kt](../../src/main/java/com/example/optimalx/data/db/DatabaseSeed.kt) comments mention Chats + Quick Notes; Reasoning is included in the same DAO filter. **Eidos Journal**, **Eidos Log**, **Eidos Daily**, **Eidos Index**, and **Eidos Memory** are **menu-only** (Eidos section), not mixed into the main grid.

---

## Tag & Hint (app-wide)

Tag & Hint is a **routing** layer for the whole app: short lines with a reference to an object to open if the line matches. It is **not** a summary system. Full behavior and line shape: [TAG_HINT_SYSTEM.md](../agent_loops/TAG_HINT_SYSTEM.md).

- **Storage:** `tag_hint_lines` table (Room), with rows materialized from app objects and optional enrichment overlays.  
- **Tools (catalog):** `read_tag_hints`, `upsert_tag_hint`, `remove_tag_hint`, `notify_user`.  
- **Not injected in full** into the system prompt — the model fetches the index when needed via tools (see AgentByte opening tool lists).

---

## What the normal chat system prompt includes

**Target contract:** only **location** and **Daily Memory** belong in the default system prompt. Eidos is expected to have **full location awareness** — the user’s current scope (general, parent folder, or subfolder) and the content needed to act there (note text or AI-lock notice, attached files, parent listing when relevant, plus base prompt / provider rules) are assembled into the prompt so Eidos always knows **where** it is working.

1. **Location awareness** — Full context for the active location (see `EidosApiClient.assembleSystemPrompt` and related helpers).  
2. **Daily Memory** — Today’s working-memory note under **Eidos Daily** (dated subfolder name `yyyy-MM-dd` in local zone). Shown as empty if missing.

**Not** injected into the system prompt (Eidos reads these with tools when needed):

- **Long-Term Memory** — Use `read_long_term_memory` (writes: `write_long_term_memory`, `prune_long_term_memory`).  
- **Eidos Journal** — Use `read_journal` (writes: `write_journal_entry`). Do not rely on an automatic “recent journal” excerpt in the prompt.

A future **chat loop** will describe when and how to open LTM and journal more clearly; until then, AgentByte policies and Tag & Hint routing guide retrieval.

**Also not** inlined in full: the Tag & Hint index (use `read_tag_hints`), subfolder memory cache (use `read_subfolder_memory_cache`), and the full **past** chat transcript — current-session messages are attached on the request with trimming (`trimHistoryIfNeeded`).

**Implementation note:** [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) may still call `loadLongTermMemoryContext` and `loadRecentJournalContext` inside `assembleSystemPrompt` until that pipeline is updated to match this contract (remove LTM and recent journal from automatic injection).

---

## Subfolder memory cache (operating ruleset)

**Not** a `String` field on `Subfolder` in the Room schema. The executor stores a **map** of `subfolderId → ruleset text` inside a dedicated **system subfolder** named **Memory Cache** under the **parent folder**, in one note (`getOrCreateParentMemoryCacheNote` in [RoomToolExecutor.kt](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt)).

**Tools:** `read_subfolder_memory_cache`, `update_subfolder_memory_cache` (catalog).

**Prompt:** Not automatically merged into the system prompt; AgentByte often recommends `read_subfolder_memory_cache` when scope is a subfolder.

---

## Chat recall

There is **no** tool that summarizes chats into memory for you.

To find or reopen history:

- `search_chat_history`
- `read_conversation`

---

## Semantic search

Catalog tool: `search_semantic`. Use for broader retrieval when Tag & Hint + targeted reads are not enough.

---

## Midnight rollover (behavioral overview)

Nightly rollover is orchestrated in application code (see [MemoryRolloverService.kt](../../src/main/java/com/example/optimalx/data/eidos/MemoryRolloverService.kt)). It uses tools via `RoomToolExecutor`; some executor actions exist **only** for orchestration and may **not** appear in `EidosToolCatalog`. Do not treat markdown here as replacing the Kotlin prompts and guards — **finish and validate behavior in code.**

High-level intent: read daily snapshot → ensure journal / continuity steps → write journal / LTM / Tag & Hint as appropriate → **clear Daily Memory in code after success** (the exact sequence is defined by `MemoryRolloverService` + AgentByte rollover phases, not by this file alone).

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
| `read_note` | No | No |
| `write_note` | Yes | No |
| `append_note` | Yes | No |
| `edit_note_section` | Yes | Yes |
| `read_file` | No | No |
| `describe_image` | No | No |
| `read_conversation` | No | No |
| `search_chat_history` | No | No |
| `search_semantic` | No | No |
| `read_tag_hints` | No | No |
| `upsert_tag_hint` | Yes | No |
| `remove_tag_hint` | Yes | No |
| `chess_taxonomy` | No | No |
| `notify_user` | Yes | No |
| `read_daily_memory` | No | No |
| `write_daily_memory` | Yes | No |
| `read_long_term_memory` | No | No |
| `write_long_term_memory` | Yes | No |
| `prune_long_term_memory` | Yes | Yes |
| `read_subfolder_memory_cache` | No | No |
| `update_subfolder_memory_cache` | Yes | No |
| `write_journal_entry` | Yes | No |
| `read_journal` | No | No |
| `write_log_entry` | Yes | No |
| `read_log` | No | No |
| `write_quick_note` | Yes | No |
| `voice_handoff` | Yes | No |

Parameter schemas are defined only in code (`parametersSchema` per tool).

---

## Summary

| Concern | In default system prompt | On demand (tools) |
|--------|---------------------------|-------------------|
| Location awareness | Yes (full) | — |
| Tag & Hint index | No (full index) | `read_tag_hints` / `upsert_tag_hint` / `remove_tag_hint` |
| Subfolder ruleset | No | `read_subfolder_memory_cache` / `update_subfolder_memory_cache` |
| Daily Memory | Yes (today) | `read_daily_memory` / `write_daily_memory` |
| Long-Term Memory | No | `read_long_term_memory` / `write_long_term_memory` / `prune_long_term_memory` |
| Eidos Journal | No | `read_journal` / `write_journal_entry` |
| Log | No | `read_log` / `write_log_entry` |
| Past chats | No (session history separate) | `search_chat_history` / `read_conversation` |
