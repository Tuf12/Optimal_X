# QUICK_NOTES.md

## What It Is

Quick Notes is an unstructured inbox built into the widget.

No folder building required. The user captures anything — a measurement, a grocery item, a todo, a random thought, a story — and Eidos saves it. 
Content is completely freeform. Review and sort later, either manually or by asking Eidos to extract and move relevant content into the folder system where it belongs.

---

## Quick Notes System Folder

- System-locked parent folder created on install
- One subfolder per day named by ISO date (e.g. 2026-04-23)
- One note per subfolder — all entries appended in order with timestamps
- No additional subfolders allowed inside Quick Notes
- User can read, write to, edit, quick copy and delete entries
- User-written entries follow the timestamp format, by using input bar.

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
- Input bar supports text and voice (see VOICE_SYSTEM.md)
- User types or speaks, taps send — entry is timestamped and appended automatically
- User can edit or delete individual entries by long pressing them
- edit mode utilizes the input bar

This is an exception to the standard editor behavior defined in EDITOR_AND_PANELS.md.

---

## Note Button — Widget

- Tap Note → mic activates in capture mode
- User speaks freely — speech accumulates in persistent buffer (see VOICE_SYSTEM.md)
- Tap Note again or tap Send → Eidos receives raw text, formats it, appends to today's note with timestamp
- User responds or dismisses — capture mode ends
** This does not need to be a strict flow, if the user asks a question or makes a comment that does not seem fill the need for a note add Eidos can ask questions. so it is a quick notes first, but not a rigid strict system. **
---

## write_quick_note Tool

Dedicated tool for writing to the Quick Notes folder.

Eidos uses this tool whenever saving a quick note — from the widget, from any chat UI, or when the user asks Eidos to save something to Quick Notes.

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

---

## Sorting and Extraction

Quick Notes is an inbox, not typically a final destination.

Entries worth keeping in a specific location should be moved or copied into the folder system. The user can do this manually or ask Eidos to handle it.

Examples:
- "Take anything shopping-related from my Quick Notes this week and add it to my shopping list."
- "Move the story I captured yesterday into my Journal folder."

Eidos handles extraction using existing note and folder tools. No special extraction system required.