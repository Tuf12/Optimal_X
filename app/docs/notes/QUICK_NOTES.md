# QUICK_NOTES.md

## What It Is

Quick Notes is a fast-capture inbox with a dedicated chat behavior.

No folder building required. The user captures anything (measurements, groceries, todos, thoughts, story fragments) and can keep moving.
Content is freeform. Review and sort later, either manually or by asking Eidos to extract and move relevant content into the folder system.

---

## Quick Notes System Folder

- System-locked parent folder created on install
- One subfolder per day named by ISO date (e.g. 2026-04-23)
- One note per subfolder, and the note file name is the same ISO date (e.g. `2026-04-23`)
- Daily note entries appended in order with timestamps
- No additional subfolders allowed inside Quick Notes
- User can read, write to, edit, quick copy and delete entries
- User-written entries follow the timestamp format, by using input bar.

### Chat persistence model (Quick Notes specific)

- Quick Notes chat is treated as its own domain, not generic chat behavior.
- **Quick Notes root chat** (folder-level): anchored to the Quick Notes parent folder context.
- **Quick Notes day chat** (inbox-level): anchored to a specific daily subfolder id.
- Each day subfolder keeps its own chat history so users can resume prior days.
- Widget Quick Note mic transcribes into the same day-chat thread used by the inbox chat UI.

### Daily note format
```
[HH:MM] Entry text here.

[HH:MM] Another entry.
```

---

## Quick Notes UI — Append-Only View

Quick Notes daily notes do not open in the standard note editor.

They open in a dedicated append-only view:
- Scrollable read-only display of the day's entries above
- Input bar pinned at the bottom
- Input bar supports text entry
- User types or speaks, taps send — entry is timestamped and appended automatically
- User can edit or delete individual entries by long pressing them
- Edit mode utilizes the input bar
- Delete is permanent and requires confirmation via popup before removal
- Edited entries keep their original timestamp

### Inbox chat UI behavior

- Inbox chat uses the day subfolder as its scope key.
- Toolbar options from generic chat that do not apply to Quick Notes are hidden:
  - New chat
  - Conversation browser/history switcher
  - Move conversation
- Goal: keep inbox chat focused on quick-note-day continuity, not generic thread management.

This is an exception to the standard editor behavior defined in EDITOR_AND_PANELS.md.

---

## Note Button — Widget

- Tap Note → mic activates in capture mode
- While capture is active, Note indicator is green (`Mic ON`)
- User speaks freely — speech accumulates in persistent buffer (see VOICE_SYSTEM.md)
- User taps Send from the widget to commit the captured note
- While sending, Note indicator is red (`PROCESSING/SENT`)
- On Send in Quick Note mode, Eidos writes user/assistant turns to today's Quick Notes day-chat thread
- No pause state is required for Quick Notes capture flow
- After send, mic is handed back to the user so they can continue capturing
- User can end Quick Notes capture by tapping Note again, or continue and send another entry
**This does not need to be rigid. If intent is unclear, Eidos can ask clarifying questions instead of forcing a note-write action.**
---

## `write_quick_note` Tool

Dedicated tool for appending note content to Quick Notes daily notes.

This tool is available globally, but should not be force-prompted outside Quick Notes contexts.
Inside Quick Notes contexts, Eidos may suggest using it when useful.

The tool handles:
- locating or creating today's dated subfolder
- locating or creating today's note
- appending the formatted entry with a timestamp
- enforcing folder structure rules

| Parameter | Type | Description |
|---|---|---|
| content | String | Formatted entry to append |
| timestamp | Long | Unix timestamp of the entry |

- Does not require confirmation
- Logs the action to Eidos Log

### Guidance intent

- Outside Quick Notes contexts:
  - Do not force or aggressively bias toward `write_quick_note`.
  - Use when user requests it, or when clearly helpful and suggested.
- Inside Quick Notes contexts (widget quick-note chat and inbox chat):
  - It is acceptable to nudge with a brief confirmation when uncertain:
    - "Do you want me to save this as a quick note entry?"

---

## Sorting and Extraction

Quick Notes is an inbox, not usually a final destination.

Entries worth keeping in a specific location should be moved or copied into the folder system. The user can do this manually or ask Eidos to handle it.

Examples:
- "Take anything shopping-related from my Quick Notes this week and add it to my shopping list."
- "Move the story I captured yesterday into my Journal folder."

Eidos handles extraction using existing note and folder tools. No special extraction system is required.

---

## Planned Architecture Notes

To reduce ambiguity with generic chat behavior, Quick Notes chat should be explicitly modeled as:

- `quick_notes_root` scope for folder-level Quick Notes chat
- `quick_notes_day` scope keyed by daily subfolder id

This keeps Quick Notes chat logic separate from standard `general` / `parent` / `subfolder` chat behavior while preserving persistence per day.