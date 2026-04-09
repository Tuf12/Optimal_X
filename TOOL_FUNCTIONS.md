# TOOL_FUNCTIONS.md

## Purpose

This file defines every tool function Eidos can call inside OptimalX v2.

Each tool has a name, description, parameters, and rules about confirmation and logging.
Coding agents should use this file to build the tool calling layer between Eidos and the app.

---

## Tool Rules

### Confirmation required
Any action that deletes existing user data must be confirmed by the user before Eidos executes it.
Eidos presents the action clearly and waits for approval or decline.
If the user declines, nothing happens.

Actions that require confirmation:
- Moving anything to trash
- Any note operation that removes or replaces existing text (for now: `write_note` and `edit_note_section`)

### No confirmation required
Non-deleting actions do not require confirmation.
Eidos executes them and logs them immediately.

Actions that do not require confirmation:
- Creating folders or subfolders
- Appending content to a note
- Writing brand-new note content where no user text is being removed
- Reading any content
- Searching the system
- Writing journal or log entries

### Deletion approval UX
- Deletion approvals are a simple Accept / Decline prompt surfaced to the user.
- Eidos proposes the deletion action and waits for the user's choice.
- If declined, no data is changed.

### Trash permanence rule
- Eidos can move items to trash.
- Eidos cannot permanently delete items from trash.
- There is no "empty trash" tool for Eidos.

### Logging
Every action Eidos takes that modifies the system must be written to the Eidos Log.
Eidos cannot act silently. If it modifies anything, it logs it.

---

## Folder Tools

---

### create_parent_folder

Creates a new parent folder.

| Parameter | Type | Description |
|---|---|---|
| name | String | Name of the new parent folder |

- Does not require confirmation
- Logs the action to Eidos Log

---

### create_subfolder

Creates a new subfolder inside a parent folder.
A note is automatically created with the subfolder.

| Parameter | Type | Description |
|---|---|---|
| parentFolderId | Long | ID of the parent folder |
| name | String | Name of the new subfolder |

- Does not require confirmation
- Logs the action to Eidos Log

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
If a subfolder is moved to trash, its note and file references move with it.

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

Searches across folder names, subfolder names, and note content by keyword.
Can be filtered by date range.

| Parameter | Type | Description |
|---|---|---|
| query | String | Keyword or phrase to search for |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |

- Does not require confirmation
- Read only — nothing is modified

---

## Journal Tools

---

### write_journal_entry

Writes a new entry to the Eidos journal.
Entries should include observations, context, decisions, and anything useful for future sessions.

| Parameter | Type | Description |
|---|---|---|
| content | String | The journal entry content |
| timestamp | Long | Unix timestamp of the entry |

- Does not require confirmation
- Journal is Eidos only — users can read and delete but not write

---

### read_journal

Reads journal entries. Can be filtered by date range or keyword.

| Parameter | Type | Description |
|---|---|---|
| query | String? | Optional keyword to search journal entries |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |

- Does not require confirmation
- Read only — nothing is modified

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

- Does not require confirmation
- Log is append only — entries are never edited or deleted by Eidos

---

### read_log

Reads Eidos Log entries. Can be filtered by date range or keyword.

| Parameter | Type | Description |
|---|---|---|
| query | String? | Optional keyword to search log entries |
| dateFrom | Long? | Optional — Unix timestamp to search from |
| dateTo | Long? | Optional — Unix timestamp to search to |

- Does not require confirmation
- Read only — nothing is modified

---

## Voice Handoff

The voice system handoff — passing communication between Eidos (text/TTS) and the user's speech (STT) — is exposed as a tool so Eidos and the widget can coordinate turn-taking.

### voice_handoff
Signals and controls the live voice handoff state for an active conversation. Used by the widget and voice subsystem to coordinate when Eidos is speaking (TTS) and when user speech should be captured (STT).

| Parameter | Type | Description |
|---|---|---|
| conversationId | Long? | Optional ID of the conversation/subfolder being handled |
| state | String | One of: `start` (begin voice session), `eidos_speaking` (TTS active), `user_listen` (STT active), `end` (end session), `pause` |
| timestamp | Long | Unix timestamp when the signal was sent |
| metadata | JSON? | Optional additional data (e.g., audio device, language) |

- Does not require user confirmation
- Read/write depending on implementation (signals the subsystem; may result in UI state changes)
- All handoff state changes should be logged to the Eidos Log for traceability

Full implementation details live in `VOICE_SYSTEM.md`, but tool callers should treat `voice_handoff` as the canonical signal for coordinating speech <-> text transitions.

---

## Summary Table

| Tool | Confirmation Required | Modifies Data |
|---|---|---|
| create_parent_folder | No | Yes |
| create_subfolder | No | Yes |
| rename_folder | No | Yes |
| move_to_trash | Yes | Yes |
| list_folder_contents | No | No |
| read_note | No | No |
| write_note | Yes | Yes |
| append_note | No | Yes |
| edit_note_section | Yes | Yes |
| list_files | No | No |
| read_file | No | No |
| describe_image | No | No |
| summarize_file | No | No |
| search_system | No | No |
| write_journal_entry | No | Yes |
| read_journal | No | No |
| write_log_entry | No | Yes |
| read_log | No | No |
