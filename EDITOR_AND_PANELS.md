# EDITOR_AND_PANELS.md

## Purpose

This file defines the editor environment in OptimalX v2.

This covers the note panel, formatting toolbar, dropdown menu, panel navigation system, and Eidos access from within the editor.

Coding agents should use this file to understand everything that happens inside the editor view.

---

## Entry Behavior

- Tapping a subfolder opens directly into the editor view
- The default panel is always the Note Panel
- No intermediate screens
- No extra taps

---

## Panel System

The editor view is a single screen with multiple panels accessed by swiping horizontally.

Panels do not create new screens. They are swipeable content areas within the same view.

### Panel order (v2)

| Position | Panel | Notes |
|---|---|---|
| Center | Note | Default, always opens here |
| Right of Note | Files | Swipe left from Note to access |
| Right of Files | Open file panels | Each opened file adds a panel to the right |

### Panel navigation rules
- Swipe left → move to the next panel to the right
- Swipe right → move back to the previous panel
- Android back button from any panel returns to the previous panel
- Android back button from the Note Panel returns to the Subfolder Page
- Panels are horizontal only — no vertical swipe navigation inside the editor

### Future panels
The swipe system is designed to accept new panels without restructuring the layout.
Future panels (calculator, contractor math tools, checklist, etc.) slot into the swipe order.
No new panels are added in v2 beyond Note and Files.

---

## Note Panel

The Note Panel is the core working space of the app.

### Layout
- Full screen text editor
- Formatting toolbar visible at the top of the editor
- Dropdown menu accessible from the toolbar

### Formatting Toolbar

The toolbar sits above the editing area and contains the most used formatting actions.

| Action | Description |
|---|---|
| Bold | Toggles bold on selected text |
| Italic | Toggles italic on selected text |
| Underline | Toggles underline on selected text |
| Font size | Cycles through sizes: Small / Medium / Large / Extra Large |
| Bullet list | Formats selected or new lines as a bulleted list |
| Numbered list | Formats selected or new lines as a numbered list |
| Undo | Undoes the last action |
| Redo | Redoes the last undone action |
| Dropdown menu | Opens the extended options menu |

Font size is not numerical input. It uses four fixed size steps: Small, Medium, Large, Extra Large.
The user highlights text and taps the size option to apply.

### Dropdown Menu

The dropdown contains less frequently used actions and system level options.

| Action | Description |
|---|---|
| Additional formatting | Less common text formatting options |
| Edit / View mode toggle | Switches between edit mode (user can type) and view mode (read only for user). Eidos can still edit in view mode. Stays in whatever mode the user sets — does not auto switch. |
| AI lock toggle | Locks or unlocks Eidos access to this note. When locked, Eidos can read the note for context but cannot write or modify it. When unlocked, Eidos has full access. User controls this manually. |
| Export note | Exports the note content as a file |
| Share note | Shares the note content via Android share sheet |

The dropdown is accessed from the toolbar. It does not clutter the main toolbar.

### Edit and AI Lock Logic

| State | User can edit | Eidos can read | Eidos can edit |
|---|---|---|---|
| Edit mode, AI unlocked | Yes | Yes | Yes |
| View mode, AI unlocked | No | Yes | Yes |
| Edit mode, AI locked | Yes | Yes | No |
| View mode, AI locked | No | Yes | No |

- View mode only restricts the user from typing — it does not affect Eidos
- AI lock only restricts Eidos from writing — it does not affect the user
- Eidos can always read a note regardless of lock status
- Both toggles are independent and stay in whatever state the user sets them

---

## Files Panel

The Files Panel shows all files attached to the current subfolder.

### Layout
- List of attached files
- Each file shows its name and type (PDF, Word, image)
- Import button to attach new files

### Supported file types
- PDF
- Word document (.docx)
- Image (jpg, png)

### Opening a file
- Tapping a file in the Files Panel opens it as a new panel to the right
- Each opened file becomes its own swipeable panel
- Multiple files can be open at the same time
- The user swipes between open file panels freely
- Closing a file panel removes it from the swipe stack and returns to the Files Panel

### File import
- User taps the import button in the Files Panel
- Device file picker opens
- Selected file is copied into app storage and a FileReference is created in the database
- Full detail on file storage is defined in FILES_AND_MEDIA.md

---

## Eidos Access — Bottom Sheet

Eidos is accessible from the editor view via the Eidos button in the top bar.

### Behavior
- Tap the Eidos button → a chat panel slides up from the bottom of the screen
- The bottom sheet covers approximately 60-70% of the screen
- The note remains partially visible above the sheet for context
- To dismiss: swipe the bottom sheet downward
- No back button required to close Eidos — swipe down returns the user directly to the editor
- The bottom sheet does not conflict with horizontal panel swipes

### Why bottom sheet
- Keeps the note visible while using Eidos
- No full screen takeover
- Swipe down to dismiss is faster than hitting back
- Standard Android pattern — familiar to users and straightforward to build
