# TOOL_FUNCTIONS.md

## Purpose

This file documents **every tool** exposed to Eidos through the local Room tool executor, matching **[EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt)** (`all`).

Coding agents should treat the Kotlin catalog as **authoritative** for names, parameter keys, `requiresConfirmation`, and `isModifying`. Implementation behavior lives in [RoomToolExecutor.kt](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt).

**Related docs:** [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md), [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md), [JOURNAL_SYSTEM.md](../systems/JOURNAL_SYSTEM.md).

The following are **not** model-facing catalog tools and are **not** documented here: `search_system`, `update_semantic_tags`, `list_files`, `summarize_file`.

---

## Tool rules

### Confirmation required (`requiresConfirmation = true` in catalog)

Eidos must obtain user approval before executing:

- **`move_to_trash`**
- **`prune_long_term_memory`**

If the user declines, the tool does not run.

### No confirmation in catalog (`requiresConfirmation = false`)

All other tools default to **no** chat confirmation step in the catalog.

**Note edits** (`write_note` append/patch, `note_replace_string`) do not use the catalog confirmation handler. Instead they route through **Diff Review** when the note already has content — the user accepts or rejects on the Diff Review screen (editor badge or chat banner). **Empty notes** (no body, no pending proposal) auto-apply on first `write_note`.

**Persistence (notes):** User typing flushes to the working copy on leave — not HEAD. **Commit** (top bar) or **auto-commit before Eidos** (when dirty vs HEAD) advances the checkpoint timeline. Diff Review is **Eidos-only**. See [NOTE_PERSISTENCE_MODEL.md](../architecture/NOTE_PERSISTENCE_MODEL.md).

### Modifying tools

Any tool with **`isModifying = true`** changes persisted data. Per product rules, modifications should be **logged** via **`write_log_entry`** where applicable so Eidos does not act silently.

### Trash

- Eidos may **`move_to_trash`** (with confirmation).
- Eidos cannot permanently delete from trash; there is no empty-trash tool.

---

## Folder tools

### create_parent_folder

Creates a new parent folder.

| Parameter | Type | Description |
|-----------|------|-------------|
| name | String | Name of the new parent folder |

- Confirmation: No  
- Modifies: Yes  
- Logs to Eidos Log when implemented as a modifying action  

---

### create_subfolder

Creates a subfolder under a parent; a note row is created with the subfolder.

| Parameter | Type | Description |
|-----------|------|-------------|
| parentFolderId | String | Parent folder ID |
| name | String | Subfolder name |

- Confirmation: No  
- Modifies: Yes  

---

### rename_folder

Renames a parent folder or subfolder.

| Parameter | Type | Description |
|-----------|------|-------------|
| folderId | String | Folder to rename |
| newName | String | New name |

- Confirmation: No  
- Modifies: Yes  

---

### move_to_trash

Moves a parent folder or subfolder to trash (cascade per app rules).

| Parameter | Type | Description |
|-----------|------|-------------|
| folderId | String | Folder to trash |

- Confirmation: **Yes**  
- Modifies: Yes  

---

### list_folder_contents

Lists subfolders for a parent, or note preview + files for a subfolder.

| Parameter | Type | Description |
|-----------|------|-------------|
| folderId | String | Parent folder ID or subfolder ID depending on context |

- Confirmation: No  
- Modifies: No  

---

## Note tools

### read_dump_edit

Reads the DumpEdit scratch buffer (DataStore). Used when buffer is large, AI locked, or Eidos needs a specific section.

| Parameter | Type | Description |
|-----------|------|-------------|
| query | String | Optional — semantic section selection |
| startLine | Integer | Optional explicit range |
| endLine | Integer | Optional explicit range |

- Confirmation: No  
- Modifies: No  
- Fails when buffer is **blind from Eidos**  
- Only meaningful when user is in `dump_edit` chat scope or asks about DumpEdit  

---

### write_note

Set markdown on an **empty** note, or **append** (`\n\n` separator) when the note already has content. Prefer **`note_replace_string`** for in-place edits.

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |
| content | String | Markdown to set or append |

- Confirmation: No (catalog)  
- Modifies: Yes  
- **Routing** ([`NoteWriteRouter`](../../src/main/java/com/example/optimalx/data/revision/NoteWriteRouter.kt)): if working copy is dirty vs HEAD, **auto-commit** first; then empty stored body + no open pending proposal → auto-apply; otherwise → queued under `SCOPE_SUBFOLDER` for Diff Review.  
- See [NOTE_PERSISTENCE_MODEL.md](../architecture/NOTE_PERSISTENCE_MODEL.md), [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) (notes).  

---

### note_replace_string

Find-and-replace patch on a note: exactly one occurrence of `oldString` → `newString` (markdown). Copy `oldString` from a `search_semantic` `chunk_text` hit with enough surrounding lines to match uniquely.

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |
| oldString | String | Non-empty substring to replace (must match once) |
| newString | String | Replacement text (may be empty to delete) |

- Confirmation: No  
- Modifies: Yes  
- **Routing:** if dirty vs HEAD, auto-commit first; then proposes via Diff Review when the note body is non-empty; auto-apply only when filling an empty note.  
- On `not_found` / `ambiguous`, returns a line-numbered snippet of the current note for retry.  

---

## File tools

### read_file

Extracts and returns text from an attached file.

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | File reference ID |

- Confirmation: No  
- Modifies: No  

---

### list_images

Lists image files (`file_references` with `file_type=image`). Primary gallery browse tool for **Image Studio** Eidos scope.

| Parameter | Type | Description |
|-----------|------|-------------|
| scope | `all` \| `subfolder` | Hub defaults to `all`; subfolder tab defaults to `subfolder` |
| subfolderId | Long | Required when `scope=subfolder` |
| limit | Int | Max images (default 48, max 200) |

- Confirmation: No  
- Modifies: No  
- Returns: `fileReferenceId`, `fileName`, `globalId`, folder labels, generation caption when available, `bytesOnDisk`

In `image_studio` scope, omitted `scope` / `subfolderId` are enriched from the active hub vs subfolder tab.

---

### describe_image

Describes an attached image (vision path) from a **Files** row.

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | File reference ID |

- Confirmation: No  
- Modifies: No  

**Not the same as chat composer attach.** Desktop chat vision puts pixels on the user message for one send (`models.vision`) without this tool. Mobile chat attach: [CHAT_VISION_ATTACH_PLAN.md](../implementation/CHAT_VISION_ATTACH_PLAN.md). This tool stays for “what’s in this file in Files?”  

---

## Panel Workshop tools

All write/edit tools route through `WorkshopWriteRouter`. In **build** phases the
write is auto-accepted and disk + checkpoint are updated immediately. In
**edit / update / review / debug** phases the proposed bytes are queued under
`pending_change_items` and the user reviews on `DiffReviewScreen` before they
land. In **chat / plan** modes the tools are rejected.

See [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) for the full review
pipeline and [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) for the
phase / mode matrix.

### workshop_write_file

Overwrite an existing workshop file with full new content. Prefer
`workshop_edit_file` for targeted edits; `workshop_append_file` to add at EOF.

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | ID of the existing workshop file. |
| content | String | Full replacement content. |

- Confirmation: No  
- Modifies: Yes  
- Behavior: auto-accept in build phases; queues for review elsewhere.  

### workshop_create_file

Create a brand-new file inside a workshop project subfolder.

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Workshop project subfolder. |
| fileName | String | Must be unique within the project; no slashes. |
| content | String | Initial file content (may be empty). |

- Confirmation: No  
- Modifies: Yes  
- Behavior: auto-accept creates `FileReference` + disk + baseline checkpoint. In review phases the proposal is queued under `sourceType = workshop_new_file`; on accept the `FileReference` is created.  

### workshop_edit_file

Replace a line range (`startLine`…`endLine`, 1-based inclusive) with `newContent`.

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | Existing workshop file. |
| startLine | Integer | 1-based start line (inclusive). |
| endLine | Integer | 1-based end line (inclusive). |
| newContent | String | Replacement for that range (may be multiple lines). |

- Confirmation: No  
- Modifies: Yes  
- Behavior: auto-accept in build phases; queues for review elsewhere.  

### workshop_append_file

Append content after the last line of an existing workshop file.

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | Existing workshop file. |
| content | String | Text to append at EOF. |

- Confirmation: No  
- Modifies: Yes  
- Behavior: auto-accept in build phases; queues for review elsewhere.  

---

## Chat tools

### read_conversation

Loads a saved conversation by ID (scope, title, metadata, messages). Use after **`search_chat_history`** or picking from UI lists.

| Parameter | Type | Description |
|-----------|------|-------------|
| conversationId | String | Conversation ID |
| includeMessages | String | Optional truthy string to include messages |
| limit | String | Optional max messages |

- Confirmation: No  
- Modifies: No  

---

### search_chat_history

Finds threads by **keyword** in titles or message text (substring match — not `search_semantic`). Optional scope and date filters.

| Parameter | Type | Description |
|-----------|------|-------------|
| query | String | Keyword (optional; omit for broad recent lists per executor) |
| scopeType | String | Optional: `general`, `parent`, `subfolder`, or empty |
| scopeId | String | Parent or subfolder ID when scoped |
| dateFrom | String | Millis since epoch |
| dateTo | String | Millis since epoch |
| limit | String | Max rows |

- Confirmation: No  
- Modifies: No  

---

## Search

### search_semantic

Semantic (embedding) search across indexed notes, folder names, and files. Parameters match the catalog only (`query`, `limit`, `dateFrom`, `dateTo`). See [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md).

| Parameter | Type | Description |
|-----------|------|-------------|
| query | String | Natural language query |
| limit | String | Max hits (executor clamps) |
| dateFrom | String | Optional millis |
| dateTo | String | Optional millis |

- Confirmation: No  
- Modifies: No  

---

## Memory tools

Memory semantics: [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md).

### read_daily_memory

Reads today’s Daily Memory content.

- Parameters: *(none in catalog schema — empty object)*  
- Confirmation: No  
- Modifies: No  

---

### write_daily_memory

Appends an entry to Daily Memory.

| Parameter | Type | Description |
|-----------|------|-------------|
| content | String | Entry text |
| timestamp | String | Millis |

- Confirmation: No  
- Modifies: Yes  

---

### read_long_term_memory

Reads Long-Term Memory with optional filters.

| Parameter | Type | Description |
|-----------|------|-------------|
| query | String | Optional keyword |
| dateFrom | String | Optional millis |
| dateTo | String | Optional millis |

- Confirmation: No  
- Modifies: No  

---

### write_long_term_memory

Appends/stores Long-Term Memory text.

| Parameter | Type | Description |
|-----------|------|-------------|
| content | String | Entry content |
| timestamp | String | Millis |

- Confirmation: No  
- Modifies: Yes  

---

### prune_long_term_memory

Removes LTM content matching anchor text.

| Parameter | Type | Description |
|-----------|------|-------------|
| anchorText | String | Text anchor to match for removal |

- Confirmation: **Yes**  
- Modifies: Yes  

---

## Journal tools

### write_journal_entry

Writes a journal entry (Eidos reflection).

| Parameter | Type | Description |
|-----------|------|-------------|
| content | String | Entry body |
| timestamp | String | Millis |

- Confirmation: No  
- Modifies: Yes  

---

### read_journal

Reads journal entries by optional keyword and date range.

| Parameter | Type | Description |
|-----------|------|-------------|
| query | String | Optional |
| dateFrom | String | Optional millis |
| dateTo | String | Optional millis |

- Confirmation: No  
- Modifies: No  

---

## Eidos Log tools

### write_log_entry

Writes one log line for an action.

| Parameter | Type | Description |
|-----------|------|-------------|
| action | String | Action description |
| timestamp | String | Millis |

- Confirmation: No  
- Modifies: Yes  

---

### read_log

Reads log entries with optional filters.

| Parameter | Type | Description |
|-----------|------|-------------|
| query | String | Optional |
| dateFrom | String | Optional millis |
| dateTo | String | Optional millis |

- Confirmation: No  
- Modifies: No  

---

## Quick Notes

### write_quick_note

Appends a line to today’s Quick Notes capture (creates dated subfolder/note as needed).

| Parameter | Type | Description |
|-----------|------|-------------|
| content | String | Line content |
| timestamp | String | Millis |

- Confirmation: No  
- Modifies: Yes  

---

## Provider-native web access

Public web search/browse is **not** implemented as Room tools. Provider adapters attach hosted tools (e.g. `web_search`, `web_fetch`) per provider. Local tools stay limited to OptimalX data and **`search_semantic`** / chat / memory / journal / log per catalog.

---

## Summary table

Catalog field `requiresConfirmation` shown as **Confirmation**; `isModifying` as **Modifies**.

| Tool | Confirmation | Modifies |
|------|--------------|----------|
| create_parent_folder | No | Yes |
| create_subfolder | No | Yes |
| rename_folder | No | Yes |
| move_to_trash | Yes | Yes |
| list_folder_contents | No | No |
| read_dump_edit | No | No |
| write_note | No | Yes |
| note_replace_string | No | Yes |
| read_file | No | No |
| list_images | No | No |
| describe_image | No | No |
| read_conversation | No | No |
| search_chat_history | No | No |
| search_semantic | No | No |
| read_daily_memory | No | No |
| write_daily_memory | No | Yes |
| read_long_term_memory | No | No |
| write_long_term_memory | No | Yes |
| prune_long_term_memory | Yes | Yes |
| write_journal_entry | No | Yes |
| read_journal | No | No |
| write_log_entry | No | Yes |
| read_log | No | No |
| write_quick_note | No | Yes |

---

## Orchestration-only tools (not in `EidosToolCatalog`)

The executor may expose additional names for **rollover orchestration** (e.g. internal synthesis passes). Those are **not** part of `EidosToolCatalog.all` and are omitted from the tables above; see [ROLLOVER.md](../systems/ROLLOVER.md) and Kotlin for availability and guards.
