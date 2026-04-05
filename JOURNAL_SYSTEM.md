# JOURNAL_SYSTEM.md

## Purpose

This file defines the Eidos Journal and Eidos Log systems in OptimalX v2.

These are the two systems Eidos uses to maintain continuity across sessions and keep the user informed of its activity.

Coding agents should use this file to understand how both systems are structured, stored, and accessed.

---

## Overview

Eidos has no memory between sessions by default.
To maintain continuity it uses two separate systems:

| System | Purpose | Who writes | Who reads | Who deletes |
|---|---|---|---|---|
| Eidos Journal | Eidos working memory | Eidos only | Eidos + User | User only |
| Eidos Log | Activity record | Eidos only | User | User only |
| Eidos Chats | Conversation history | Eidos + User | Eidos + User | User only |

These are not the same thing and should never be merged.
The journal is private working memory. The log is a transparent activity record.

---

## System Level Folders

Both the Eidos Journal and Eidos Log exist as **system level parent folders** inside OptimalX.

### Rules for system folders
- They are created automatically when the app is first installed
- They cannot be renamed
- They cannot be moved to trash
- They cannot be deleted as a whole
- They do not appear in the regular folder list alongside user folders
- They are accessible from a dedicated Eidos section in the app
- Only individual entries inside them can be deleted by the user

These protections exist because destroying the journal or log would break Eidos continuity entirely.

---

## Eidos Journal

### What it is
The journal is Eidos private working memory.

Think of it like the movie Memento — Eidos wakes up each session with no memory. The journal is how it picks up where it left off. The more it writes, the more context it has for future sessions.

### Structure
- Parent folder: **Eidos Journal** (system locked)
- Subfolders: one per day, named by date (e.g. 2026-04-01)
- Note inside each subfolder: contains all journal entries for that day

### What Eidos writes
- Observations about the user's folder and note structure
- Decisions it made and why
- Context that will be useful in future sessions
- Summaries of what was discussed or built
- Anything that helps Eidos understand where things are and what has been done

### Entry format
Each entry inside the daily note should include:
- Timestamp (date and time)
- Entry content

Eidos organizes entries however is most useful for its own future retrieval.

### How Eidos uses the journal
- At the start of each session Eidos reads recent journal entries to rebuild context
- It searches the journal by date or keyword when it needs to find specific past information
- It does not read the entire journal every session — it retrieves what is relevant

### User access
- User can open and read journal entries
- User can select individual entries using checkboxes and delete them
- User cannot write to the journal
- User cannot rename or delete the folder structure

---

## Eidos Log

### What it is
The Eidos Log is a transparent activity record.

Every time Eidos modifies the app — creates a folder, writes a note, appends content, attaches a file reference — it writes a log entry. This ensures the user always knows what Eidos has done even if they were not watching.

### Structure
- Parent folder: **Eidos Log** (system locked)
- Subfolders: one per day, named by date (e.g. 2026-04-01)
- Note inside each subfolder: contains all log entries for that day

### What gets logged
Every action Eidos takes that modifies the system, with a deep link to the location:
- Created parent folder: [name] → links to folder
- Created subfolder: [name] inside [parent] → links to subfolder note
- Renamed folder: [old name] → [new name] → links to folder
- Moved to trash: [name] → links to trash bin filtered to that item
- Wrote note in: [subfolder name] → links to note
- Appended to note in: [subfolder name] → links to note, scrolls to appended content
- Edited section of note in: [subfolder name] → links to note, scrolls to edited section
- Attached file: [filename] to [subfolder name] → links to files panel of that subfolder

Read only actions are not logged — only modifications.

### Entry format
Each log entry should include:
- Timestamp (date and time)
- Action taken
- Location in the system (which folder, subfolder, or note)
- Deep link to the location where the change was made

### Deep link behavior
Every log entry links directly to where the action occurred.

| Action type | Link behavior |
|---|---|
| Folder created | Opens the new folder |
| Subfolder created | Opens the subfolder note |
| Folder renamed | Opens the renamed folder |
| Note written or appended | Opens the note and scrolls to the changed section |
| Note section edited | Opens the note and scrolls to the edited section |
| Moved to trash | Opens the trash bin filtered to that item |

To support note-level deep linking, each log entry that modifies a note stores:
- The subfolder ID (to navigate to the note)
- A short text anchor — the first line or key phrase of the added or edited content — so the app can scroll to the right position

This does not require a new ID system. It uses the existing object IDs already defined in DATA_MODEL.md combined with a text anchor for position within the note.

### Log rules
- The log is append only — Eidos never edits or deletes its own log entries
- Every system modification produces a log entry automatically
- Eidos cannot act silently — if it modifies anything, it logs it

### User access
- User can open and read log entries
- User can select individual entries using checkboxes and delete them
- User cannot write to the log
- User cannot rename or delete the folder structure

## Eidos Chats

### What it is
The Eidos Chats folder stores conversation history for widget and app-level text/voice chats. It is a system-level conversation archive separate from the Journal and the Log.

### Structure
- Parent folder: **Eidos Chats** (system locked)
- Subfolders: one per conversation, named by date and time (e.g. 2026-04-01 — 2:34 PM)
- Note inside each subfolder: contains the full conversation log (timestamped user messages and Eidos responses)

### Rules
- Created on first install and system locked (cannot be renamed, moved to trash, or deleted as a whole)
- Individual conversation notes (subfolders) can be read and deleted by the user
- Conversations started by the widget or from the app root save here; conversations scoped to a specific user subfolder save inside that subfolder's system chat subfolder

### User access
- User can read and delete individual conversation notes
- The Eidos Chats parent folder does not appear in the regular folder list and is accessible from the dedicated Eidos section in the app

### Data model
- Uses the same ParentFolder / Subfolder / Note objects defined in DATA_MODEL.md
- Parent folder should have `isSystemFolder = true`; subfolder chat entries should use `isSystemSubfolder = true` where applicable

---

## Timestamps

All journal and log entries use Unix timestamps stored as Long in the database.
Display format for the user: human readable date and time (e.g. April 1, 2026 — 2:34 PM)
Subfolders are named using ISO date format: YYYY-MM-DD

---

## How Eidos Searches the Journal and Log

Eidos does not load the entire journal or log into context every session.
It retrieves entries selectively using the read_journal and read_log tool functions.

Search parameters:
- By keyword
- By date range
- By combination of both

This keeps context usage efficient and prevents irrelevant old entries from cluttering the session.

---

## Data Model Notes

The journal and log follow the same folder and note structure as the rest of the app.
They use the same ParentFolder, Subfolder, and Note objects defined in DATA_MODEL.md.

The only difference is a system lock flag on the parent folder level that prevents deletion, renaming, and trashing.

A new field is needed in ParentFolder:

| Field | Type | Description |
|---|---|---|
| isSystemFolder | Boolean | If true, folder cannot be renamed, moved to trash, or deleted. Default is false. |

---

## Summary

| Feature | Eidos Journal | Eidos Log |
|---|---|---|
| Purpose | Working memory | Activity record |
| Written by | Eidos | Eidos |
| Read by | Eidos + User | User |
| Editable by user | No | No |
| Deletable by user | Individual entries only | Individual entries only |
| Append only | No (Eidos writes freely) | Yes (never edited after writing) |
| System locked | Yes | Yes |
| Structure | Daily subfolders + notes | Daily subfolders + notes |