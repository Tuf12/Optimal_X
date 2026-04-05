# FILES_AND_MEDIA.md

## Purpose

This file defines how files and media are imported, stored, referenced, displayed, and used inside OptimalX v2.

Coding agents should use this file alongside DATA_MODEL.md and EDITOR_AND_PANELS.md to understand the full file system.

---

## Supported File Types (v2)

| Type | Extensions |
|---|---|
| PDF | .pdf |
| Word document | .docx, .odt |
| Image | .jpg, .png, .gif, .webp, .heic |
| Text file | .txt |
| Markdown | .md |
| Spreadsheet | .xlsx, .ods |
| Presentation | .pptx, .odp |
| Code files | .js, .py, .kt, .ts, .html, .css, .json, .xml |

All listed types are supported for import, storage, and Eidos reading.
No other file types are supported in v2.

---

## How Files Enter the System

### Import flow
1. User opens the Files Panel inside the editor view
2. User taps the import button
3. Android file picker opens
4. User selects a file from their device
5. The file is copied into the app's private storage
6. A FileReference record is created in the database pointing to the copied file
7. The file now appears in the Files Panel for that subfolder

### Key rules
- Files are always imported into app private storage — the original file on the device is not moved or modified
- The database stores a reference (file path) to the copied file inside app storage
- Files are scoped to a specific subfolder — there is no global file library
- A subfolder can have zero or more files attached to it

---

## Storage Location

- All imported files live in the app's private storage on the device
- The file path stored in the FileReference record points to this location
- Nothing is stored outside the app's private storage
- Nothing outside the app is ever modified or deleted by OptimalX

### Cloud storage (future)
- When cloud storage is added, the file path in FileReference becomes a cloud URL
- No schema changes are required — the pointer system handles this transition cleanly
- Full detail on cloud transition is defined in DATA_MODEL.md

---



## Files Panel Display

Each file in the Files Panel shows:
- File name
- File type (PDF, Word, image)

### Opening a file
- Tapping a file opens it as a new swipeable panel to the right of the Files Panel
- Each opened file gets its own panel
- Multiple files can be open simultaneously
- User swipes freely between open file panels
- Closing a file panel removes it from the swipe stack and returns to the Files Panel

---

## Eidos and Files

Eidos can interact with attached files to assist the user.

### What Eidos can do with files
- Read and summarize PDF and Word document content
- Answer questions about file content
- View and describe images
- Provide feedback on file content
- Reference file content when responding to user queries in the same subfolder

### Important
- Eidos accesses files through the same FileReference system
- Eidos file capabilities are fully defined in EIDOS_AGENT.md
- File reading by Eidos is scoped to the current subfolder context

---

## File Reference Connection to Notes

- Files and notes both belong to the same subfolder
- Eidos can read both the note and the attached files together as a combined context
- There is no direct link between a specific file and a specific section of a note in v2
- The connection is at the subfolder level — everything inside a subfolder belongs together
