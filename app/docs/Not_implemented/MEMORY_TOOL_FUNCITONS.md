# TOOL_FUNCTIONS.md

## Purpose

This file defines every tool function Eidos can call inside OptimalX v2.

Each tool has a name, description, parameters, and rules about confirmation and logging.
Coding agents should use this file to build the tool calling layer between Eidos and the app.

---

## Tool Rules

### Confirmation required
Any action that is destructive or cannot be easily undone must be confirmed by the user before Eidos executes it.
Eidos presents the action clearly and waits for approval or decline.
If the user declines, nothing happens.

Actions that require confirmation:
- Moving anything to trash
- Overwriting or erasing existing note content
- Permanently removing a Long-Term Memory entry
- Any action the user cannot immediately undo

### No confirmation required
Non-destructive actions do not require confirmation.
Eidos executes them and logs them immediately.

Actions that do not require confirmation:
- Creating folders or subfolders
- Appending content to a note
- Reading any content
- Writing new notes
- Searching the system
- Writing journal, log, or memory entries
- Updating subfolder memory cache or semantic tags

### Logging
Every action Eidos takes that modifies the system must be written to the Eidos Log.
Eidos cannot act silently. If it modifies anything, it logs it.
Memory writes (Daily, Long-Term, subfolder cache) count as system modifications and are logged.

---

## Folder Tools

---

### create_parent_folder

Creates a new parent folder.
Eidos automatically generates semantic tags for the new folder based on its name.

| Parameter | Type | Description |
|---|---|---|
| name | String | Name of the new parent folder |

- Does not require confirmation
- Logs the action to Eidos Log
- Triggers semantic tag generation after creation

---

### create_subfolder

Creates a new subfolder inside a parent folder.
A note is automatically created with the subfolder.
Eidos automatically generates semantic tags for the new subfolder based on its name and parent folder context.

| Parameter | Type | Description |
|---|---|---|
| parentFolderId | Long | ID of the parent folder |
| name | String | Name of the new subfolder |

- Does not require confirmation
- Logs the action to Eidos Log
- Triggers semantic tag generation after creation

---

### rename_folder

Renames a parent folder or subfolder.

| Parameter | Type | Description |
|---|---|---|
| folderId | Long | ID of the folder to rename |
| newName | String | New name for the folder |

- Does not require confirmation
- Logs the action to Eidos Log

---

### move_to_trash

Moves a parent folder or subfolder to trash.
If a parent folder is moved to trash, all its subfolders, notes, and file references move with it.
If a subfolder is moved to trash, its note, file references, and memory cache move with it.

| Parameter | Type | Description |
|---|---|---|
| folderId | Long | ID of the folder to move to trash |

- **Requires confirmation before executing**
- Logs the action to Eidos Log

---

### list_folder_contents

Lists all subfolders inside a parent folder, or all contents of a subfolder.

| Parameter | Type | Description |
|---|---|---|
| folderId | Long | ID of the folder to list |

- Does not require confirmation
- Read only — nothing is modified

---

### update_semantic_tags

Updates the semantic tags for a parent folder or subfolder.
Called automatically after folder creation and when Eidos determines the folder's purpose has shifted meaningfully.

| Parameter | Type | Description |
|---|---|---|
| folderId | Long | ID of the folder to update tags for |
| tags | String | Comma-separated list of 3–5 descriptive tags |
| isParent | Boolean | True if updating a parent folder, false if subfolder |

- Does not require confirmation
- Logs the action to Eidos Log

---

## Note Tools

---

### read_note

Reads the full content of a note.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder whose note to read |

- Does not require confirmation
- Read only — nothing is modified
- Eidos can always read a note regardless of AI lock status

---

### write_note

Writes content to a note, replacing all existing content.
Use with caution — this overwrites everything currently in the note.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder whose note to write |
| content | String | Full content to write to the note |

- **Requires confirmation before executing**
- Cannot execute if AI lock is enabled on the note
- Logs the action to Eidos Log

---

### append_note

Adds content to the end of an existing note without touching existing content.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder whose note to append to |
| content | String | Content to add to the end of the note |

- Does not require confirmation
- Cannot execute if AI lock is enabled on the note
- Logs the action to Eidos Log

---

### edit_note_section

Replaces a specific section of a note with new content.
Targets a section by matching existing text — only that section is changed.
The rest of the note is untouched.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder whose note to edit |
| targetText | String | The existing text to find and replace |
| newContent | String | The new content to replace it with |

- **Requires confirmation before executing**
- Cannot execute if AI lock is enabled on the note
- Logs the action to Eidos Log

---

## File Tools

---

### list_files

Lists all file references attached to a subfolder.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder to list files for |

- Does not require confirmation
- Read only — nothing is modified

---

### read_file

Reads and extracts the text content of an attached file.
Supported for PDF, Word, text, markdown, spreadsheet, and code files.

| Parameter | Type | Description |
|---|---|---|
| fileReferenceId | Long | ID of the FileReference to read |

- Does not require confirmation
- Read only — nothing is modified

---

### describe_image

Views and describes the content of an attached image file.

| Parameter | Type | Description |
|---|---|---|
| fileReferenceId | Long | ID of the image FileReference to describe |

- Does not require confirmation
- Read only — nothing is modified

---

### summarize_file

Reads a file and returns a summary of its content.
Supported for PDF, Word, text, markdown, and spreadsheet files.

| Parameter | Type | Description |
|---|---|---|
| fileReferenceId | Long | ID of the FileReference to summarize |

- Does not require confirmation
- Read only — nothing is modified

---

## Search Tools

---

### search_system

Keyword search across folder names, subfolder names, note content, and file names.
Can be filtered by date range.
Used when the user asks for something by exact name or phrase.

| Parameter | Type | Description |
|---|---|---|
| query | String | Keyword or phrase to search for |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |

- Does not require confirmation
- Read only — nothing is modified

---

### search_semantic

Two-pass semantic search across notes, folders, subfolders, logs, and chat history.

Pass 1: matches the query against semantic tags to prune irrelevant branches.
Pass 2: runs vector similarity search within matched branches only.

Use for topic or meaning-based queries where exact keywords are unlikely to match.

| Parameter | Type | Description |
|---|---|---|
| query | String | Natural language query to search by meaning |
| scope | String? | Optional — "notes", "logs", "chats", or null for all |
| scopeId | Long? | Optional — limit search to a specific parent folder or subfolder ID |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |
| limit | Int? | Optional — max results to return. Default 10, max 50 |

- Does not require confirmation
- Read only — nothing is modified

---

## Memory Tools

---

### read_daily_memory

Reads the full Daily Memory note.

| Parameter | Type | Description |
|---|---|---|
| — | — | No parameters |

- Does not require confirmation
- Read only — nothing is modified

---

### write_daily_memory

Appends a new entry to Daily Memory.
Entries should use the structured flag format defined in MEMORY_SYSTEM.md.

| Parameter | Type | Description |
|---|---|---|
| content | String | The entry content including flag, importance, confidence, and source |
| timestamp | Long | Unix timestamp of the entry |

- Does not require confirmation
- Logs the action to Eidos Log

---

### clear_daily_memory

Wipes the Daily Memory note entirely.
Only called during the midnight rollover process or a user-initiated rollover recovery.

| Parameter | Type | Description |
|---|---|---|
| — | — | No parameters |

- Does not require confirmation — only fires inside rollover
- Logs the action to Eidos Log

---

### read_long_term_memory

Reads the full Long-Term Memory note.

| Parameter | Type | Description |
|---|---|---|
| — | — | No parameters |

- Does not require confirmation
- Read only — nothing is modified

---

### write_long_term_memory

Appends a new entry to Long-Term Memory.
Used during rollover to promote high-value items from Daily Memory.
Entries should use the structured flag format defined in MEMORY_SYSTEM.md.

| Parameter | Type | Description |
|---|---|---|
| content | String | The entry content including flag, importance, confidence, and source |
| timestamp | Long | Unix timestamp of the entry |

- Does not require confirmation
- Logs the action to Eidos Log

---

### prune_long_term_memory

Permanently removes a specific entry from Long-Term Memory by matching its anchor text.
Used during rollover to remove outdated or superseded facts.

| Parameter | Type | Description |
|---|---|---|
| anchorText | String | The first line or key phrase of the entry to remove |

- **Requires confirmation before executing**
- Logs the action to Eidos Log

---

### read_subfolder_memory_cache

Reads the memory cache for a specific subfolder.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder whose memory cache to read |

- Does not require confirmation
- Read only — nothing is modified

---

### update_subfolder_memory_cache

Writes or updates the memory cache for a specific subfolder.
Replaces the existing cache content entirely — write the full updated cache, not just new additions.
Called when something meaningful happens in a subfolder that Eidos should remember for future sessions.

| Parameter | Type | Description |
|---|---|---|
| subfolderId | Long | ID of the subfolder to update |
| content | String | Full updated memory cache content |

- Does not require confirmation
- Logs the action to Eidos Log

---

## Journal Tools

---

### write_journal_entry

Writes a new reflective journal entry for the current day.
Called during the midnight rollover. Includes a one-line Tag & Hint line for the journal Tag & Hint index.

| Parameter | Type | Description |
|---|---|---|
| content | String | Full journal entry content |
| timestamp | Long | Unix timestamp of the entry |

- Does not require confirmation
- Logs the action to Eidos Log

---

### read_journal

Reads journal entries. Can be filtered by date range or keyword.
Used when a journal Tag & Hint index line triggers a match — fetches the full entry.

| Parameter | Type | Description |
|---|---|---|
| query | String? | Optional keyword to search journal entries |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |

- Does not require confirmation
- Read only — nothing is modified

---

### read_journal_tag_hint_index

Reads the full journal Tag & Hint index — one compact line per journal entry.
This is loaded into every prompt automatically. This tool is used when Eidos needs to explicitly search or re-read the index during a session.

| Parameter | Type | Description |
|---|---|---|
| — | — | No parameters |

- Does not require confirmation
- Read only — nothing is modified

---

### update_journal_tag_hint_index

Appends a new one-line Tag & Hint line to the journal Tag & Hint index.
Called during rollover immediately after writing the journal entry.

| Parameter | Type | Description |
|---|---|---|
| tagHintLine | String | The compact one-line Tag & Hint line for this journal entry |
| timestamp | Long | Unix timestamp matching the journal entry |

- Does not require confirmation
- Logs the action to Eidos Log

---

## Eidos Log Tools

---

### write_log_entry

Writes an entry to the Eidos Log recording an action taken.
Called automatically after every system modification.

| Parameter | Type | Description |
|---|---|---|
| action | String | Description of the action taken |
| timestamp | Long | Unix timestamp of the action |
| deepLink | String? | Optional — object ID and anchor for deep linking to the affected location |

- Does not require confirmation
- Log is append only — entries are never edited or deleted by Eidos

---

### read_log

Reads Eidos Log entries using two-pass search.
Pass 1 filters by date and action type tags.
Pass 2 runs semantic search within matched entries.

| Parameter | Type | Description |
|---|---|---|
| query | String? | Optional keyword or semantic query |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |

- Does not require confirmation
- Read only — nothing is modified

---

## Voice Handoff

The voice system handoff — passing communication back from Eidos text to speech to user speech to text — will require a signal or tool call.
Full detail is defined in VOICE_SYSTEM.md.
This tool is not fully defined here because the voice system is its own MD file.

---

## Summary Table

| Tool | Confirmation Required | Modifies Data |
|---|---|---|
| **Folder Tools** | | |
| create_parent_folder | No | Yes |
| create_subfolder | No | Yes |
| rename_folder | No | Yes |
| move_to_trash | Yes | Yes |
| list_folder_contents | No | No |
| update_semantic_tags | No | Yes |
| **Note Tools** | | |
| read_note | No | No |
| write_note | Yes | Yes |
| append_note | No | Yes |
| edit_note_section | Yes | Yes |
| **File Tools** | | |
| list_files | No | No |
| read_file | No | No |
| describe_image | No | No |
| summarize_file | No | No |
| **Search Tools** | | |
| search_system | No | No |
| search_semantic | No | No |
| **Memory Tools** | | |
| read_daily_memory | No | No |
| write_daily_memory | No | Yes |
| clear_daily_memory | No | Yes |
| read_long_term_memory | No | No |
| write_long_term_memory | No | Yes |
| prune_long_term_memory | Yes | Yes |
| read_subfolder_memory_cache | No | No |
| update_subfolder_memory_cache | No | Yes |
| **Journal Tools** | | |
| write_journal_entry | No | Yes |
| read_journal | No | No |
| read_journal_tag_hint_index | No | No |
| update_journal_tag_hint_index | No | Yes |
| **Log Tools** | | |
| write_log_entry | No | Yes |
| read_log | No | No |
