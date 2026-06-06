# TOOL_FUNCTIONS.md

## Purpose

This file documents **every tool** exposed to Eidos through the local Room tool executor, matching **[EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt)** (`all`).

Coding agents should treat the Kotlin catalog as **authoritative** for names, parameter keys, `requiresConfirmation`, and `isModifying`. Implementation behavior lives in [RoomToolExecutor.kt](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt).

**Related docs:** [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md), [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md), [TAG_HINT_SYSTEM.md](../agent_loops/TAG_HINT_SYSTEM.md), [JOURNAL_SYSTEM.md](../systems/JOURNAL_SYSTEM.md).

The following are **not** model-facing catalog tools and are **not** documented here: `search_system`, `update_semantic_tags`, `list_files`, `summarize_file`.

---

## Tool rules

### Confirmation required (`requiresConfirmation = true` in catalog)

Eidos must obtain user approval before executing:

- **`move_to_trash`**
- **`edit_note_section`**
- **`prune_long_term_memory`**

If the user declines, the tool does not run.

### No confirmation in catalog (`requiresConfirmation = false`)

All other tools default to **no** confirmation step in the catalog — including **`write_note`**, which can replace full note content. The app may still apply extra UX for destructive edits; the **catalog** remains the contract for what the model is allowed to request without a confirmation flag.

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

### read_note

Reads the note for a subfolder.

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |

- Confirmation: No  
- Modifies: No  
- Respects AI lock rules in the executor (content may be withheld when locked).  

---

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

Creates, replaces, or updates note content (full write path per executor).

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |
| content | String | Content to write |

- Confirmation: No (catalog)  
- Modifies: Yes  

---

### append_note

Appends text to the note.

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |
| content | String | Text to append |

- Confirmation: No  
- Modifies: Yes  

---

### edit_note_section

Replaces or deletes a section identified by `targetText`.

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |
| targetText | String | Existing text to match |
| newContent | String | Replacement (empty to delete section) |

- Confirmation: **Yes**  
- Modifies: Yes  

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

### describe_image

Describes an attached image (vision path).

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | File reference ID |

- Confirmation: No  
- Modifies: No  

---

## Panel Workshop tools

All three tools route through `WorkshopWriteRouter`. In **build** phases the
write is auto-accepted and disk + checkpoint are updated immediately. In
**edit / update / review / debug** phases the proposed bytes are queued under
`pending_change_items` and the user reviews on `DiffReviewScreen` before they
land. In **chat / plan** modes the tools are rejected.

See [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) for the full review
pipeline and [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) for the
phase / mode matrix.

### workshop_write_file

Overwrite an existing workshop file with full new content. Prefer
`workshop_replace_string` for targeted edits.

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

### workshop_replace_string

Targeted edit: replace a unique target substring with replacement bytes.

| Parameter | Type | Description |
|-----------|------|-------------|
| fileReferenceId | String | Existing workshop file. |
| target | String | Exact target substring; must match exactly once. |
| replacement | String | Replacement bytes (may be empty for deletion). |

- Confirmation: No  
- Modifies: Yes  
- Errors: `not_found` (with snippet of nearby content) and `ambiguous` (multiple matches) when the target doesn't match exactly once.  
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

## Search and Tag & Hint

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

### read_tag_hints

Reads app-wide Tag & Hint rows with optional filters. See [TAG_HINT_SYSTEM.md](../agent_loops/TAG_HINT_SYSTEM.md).

| Parameter | Type | Description |
|-----------|------|-------------|
| scope | String | Optional filter |
| query | String | Optional substring filter |
| ref | String | Optional exact ref match |
| dateFrom | String | Optional millis |
| dateTo | String | Optional millis |
| limit | String | Optional row limit (executor clamps) |

- Confirmation: No  
- Modifies: No  

Response schema (`tag_hints_read_v1`):

```json
{
  "schema": "tag_hints_read_v1",
  "count": 1,
  "limit": 10,
  "filters": {
    "scope": null,
    "query": null,
    "ref": null,
    "dateFrom": null,
    "dateTo": null
  },
  "items": [
    {
      "ref": "chat:general:221",
      "objectType": "chat",
      "scopeType": "general",
      "scopeId": null,
      "parentRef": null,
      "rootBranch": "chats",
      "piece": "KNIGHT",
      "lens": "exploratory",
      "hint": "agent training path",
      "date": 1761513600000,
      "dateKey": "2025-10-26",
      "createdAt": 1761513600000,
      "updatedAt": 1761513605000,
      "line": "[2025-10-26] KNIGHT|exploratory — agent training path | ref=chat:general:221"
    }
  ]
}
```

---

### upsert_tag_hint

Creates or updates one Tag & Hint row by `ref`.

| Parameter | Type | Description |
|-----------|------|-------------|
| ref | String | Canonical Eidos index ref |
| piece | String | Chess piece token |
| lens | String | Lens token |
| hint | String | Routing hint text |

- Confirmation: No  
- Modifies: Yes  

---

### remove_tag_hint

Deletes one Tag & Hint row by `ref`.

| Parameter | Type | Description |
|-----------|------|-------------|
| ref | String | Canonical Eidos index ref |

- Confirmation: No  
- Modifies: Yes  

---

### chess_taxonomy

Returns the piece-to-lens taxonomy used by Tag & Hint.

- Parameters: *(none)*  
- Confirmation: No  
- Modifies: No  

---

### notify_user

Sends a user-visible notification message.

| Parameter | Type | Description |
|-----------|------|-------------|
| title | String | Notification title |
| message | String | Notification body |
| ref | String | Optional related ref |

- Confirmation: No  
- Modifies: Yes  

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

### read_subfolder_memory_cache

Reads the **operating ruleset** for a subfolder (Memory Cache map — not the user’s main note).

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |

- Confirmation: No  
- Modifies: No  

---

### update_subfolder_memory_cache

Updates the subfolder ruleset (behavioral memory — not a summary of note body).

| Parameter | Type | Description |
|-----------|------|-------------|
| subfolderId | String | Subfolder ID |
| content | String | Ruleset text |

- Confirmation: No  
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

## Voice

### voice_handoff

Updates voice handoff state for widget / voice coordination.

| Parameter | Type | Description |
|-----------|------|-------------|
| conversationId | String | Optional conversation ID |
| state | String | Handoff state |
| timestamp | String | Millis |
| metadata | String | Optional JSON metadata |

- Confirmation: No  
- Modifies: Yes  
- See voice docs for state strings and UX.  

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
| read_note | No | No |
| read_dump_edit | No | No |
| write_note | No | Yes |
| append_note | No | Yes |
| edit_note_section | Yes | Yes |
| read_file | No | No |
| describe_image | No | No |
| read_conversation | No | No |
| search_chat_history | No | No |
| search_semantic | No | No |
| read_tag_hints | No | No |
| upsert_tag_hint | No | Yes |
| remove_tag_hint | No | Yes |
| chess_taxonomy | No | No |
| notify_user | No | Yes |
| read_daily_memory | No | No |
| write_daily_memory | No | Yes |
| read_long_term_memory | No | No |
| write_long_term_memory | No | Yes |
| prune_long_term_memory | Yes | Yes |
| read_subfolder_memory_cache | No | No |
| update_subfolder_memory_cache | No | Yes |
| write_journal_entry | No | Yes |
| read_journal | No | No |
| write_log_entry | No | Yes |
| read_log | No | No |
| write_quick_note | No | Yes |
| voice_handoff | No | Yes |

---

## Orchestration-only tools (not in `EidosToolCatalog`)

The executor may expose additional names for **rollover / AgentByte** (e.g. `clear_daily_memory`, reasoning traces). Those are **not** part of `EidosToolCatalog.all` and are omitted from the tables above; see Kotlin for availability and guards.
