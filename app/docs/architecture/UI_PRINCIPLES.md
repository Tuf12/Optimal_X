# UI_PRINCIPLES.md

## Purpose

This file defines the visual and interaction rules for OptimalX v2.

Every screen, component, and navigation decision should follow these principles.
If something conflicts with what is written here, this file wins.

---

## Core Philosophy

**Big. Simple. Blunt.**

- Every screen should have one clear purpose
- No decorative elements
- No hidden complexity
- No features that aren't being used
- If it isn't needed, it isn't there

The UI must be readable by both a human and an AI agent.
Structure and layout should be consistent and predictable at every level.

---

## Folder Cards

Folders are displayed as **rounded rectangle cards**.

Rules:
- Shape: rectangle with rounded corners (consistent border radius)
- Content: folder name only — no icons, no folder graphics, no metadata visible on the card
- The name is the card
- Cards are displayed in a **grid layout** by default
- Grid layout can be toggled to list layout by the user
- Card size should be large enough to read the name clearly at a glance

Do not use:
- traditional folder icons
- file count badges on cards
- color coding (unless added intentionally later)
- shadows or heavy visual effects

---

## Screen Layout — Home and Subfolder Pages

The home page (parent folders) and the subfolder page share the same folder grid/list components. The **parent page** adds a **pinned row** below the search bar; the subfolder page does not.

### Top Bar
- Search bar (left/center)
- Eidos assistant button (right)
- Eidos section menu (⋮) on parent page

### Pinned row (parent page only)
- Single horizontal scrolling row below search
- Fixed system shortcuts first: **Panels**, **DumpEdit**, **Panel Workshop**, **Quick Notes**, then **Pin (+)**
- User pin shortcuts follow (long-press a folder or gallery panel → **Pin to home**)
- System shortcut cards may use icons; user folder grid cards remain **name only**

### Middle
- Folder card grid (or list if toggled) — **user folders / subfolders only**

### Bottom Bar
- Create folder button
- Sort button
- Grid/list toggle button
- Trash bin icon (parent page only — opens Trash screen)

---

## Screen Layout — Full-screen pinned-row routes

**DumpEdit**, **Panel Gallery**, and **Panel Runner** are full-screen routes reached from the pinned row (gallery also shows the row on its own screen). They share a compact top bar — not the parent/subfolder search + grid layout.

### Top bar (DumpEdit, Panel Gallery, Panel Runner)
- **Back** (left) — system back also works
- **Title** (center-left): “DumpEdit”, “Panels”, or running panel name
- **Eidos** button (right) — opens chat bottom sheet at the correct scope
- **No** search bar, **no** Eidos section ⋮ menu in v1 (DumpEdit may expose **Settings** via ⋯ where applicable)

### Body
- DumpEdit: note-style editor for scratch buffer
- Panel Gallery: pinned row + panel card grid/list
- Panel Runner: full-screen `WorkshopPreviewPanel`

### Bottom bar
- Panel Gallery only: sort + grid/list toggle (no create, no trash)
- DumpEdit and Panel Runner: no folder bottom bar

Eidos scoping for these surfaces: [CHAT_UI.md](CHAT_UI.md), [PANEL_GALLERY.md](PANEL_GALLERY.md).

---

## Navigation

### Folder interaction
- **Tap** a folder → opens it
- **Long press** a folder → context menu appears with options:
    - Rename
    - Delete
    - Move

### Tapping a subfolder
- Opens directly into the **note editor** for that subfolder
- No intermediate screen
- No extra taps

---

## Editor View

The editor is the core working screen of the app.

### Default state
- Opens directly into the note
- Full screen, clean, focused
- No panels visible by default
- The note panel is a text editor with a formatting toolbar (fonts, text options) and a dropdown menu for extra functions
- Full editor detail is defined in EDITOR_AND_PANELS.md

### Panel navigation (editor only)
- **Inside the editor**, panels are accessed by **swiping left or right** (Note ↔ Files ↔ Web ↔ custom panels)
- **Panel Gallery** is **not** a swipe gesture — tap **Panels** in the pinned row
- Current editor panels (v2): Note (default), Files, Web, optional custom workshop panels
- Removed from v2: calculator, math page, checklist (not needed)

### What the files panel supports
- PDFs
- Word documents (.docx)
- Images

---

## What To Avoid

- Extra buttons that serve no current purpose
- Screens that require more than one tap to reach content
- Inconsistent layouts between similar screens
- Decorative UI elements that add no function
- Features carried over from v1 that are not being used
- Clutter in the editor view

---

## Machine Readability Requirement

The UI structure must be consistent and predictable enough that an AI agent can navigate it logically.

This means:
- every screen type has the same layout
- navigation follows a fixed pattern (tap to go deeper; horizontal swipe only inside the editor)
- folder structure maps directly to what is on screen
- system tools (Panels, DumpEdit, Workshop, Quick Notes) are always visible on the parent pinned row

Note: System-level folders (Eidos Journal, Eidos Log, Eidos Daily, Eidos Memory) are accessible from the Eidos section menu. Legacy **Eidos Chats** / **Eidos Reasoning** parents are hidden from folder UI; conversations are browsed in the chat UI. Quick Notes and Panel Workshop are reached from the **pinned row**, not the folder grid.

---

## Summary of Rules

| Element | Rule |
|---|---|
| Folder cards | Rounded rectangle, name only, grid default |
| Home page | Top bar + **pinned row** + user folder grid + bottom bar |
| Subfolder page | Top bar + user subfolder grid + bottom bar (no pinned row) |
| DumpEdit / Panel Gallery / Panel Runner | Compact top bar with **Back**, title, **Eidos**; no folder grid |
| Long press | Rename / Delete / Move |
| Subfolder tap | Opens directly into note |
| Panel navigation | Swipe left/right |
| Editor default | Note panel, full screen |
| Files panel | PDF, Word, images |
| Bottom bar | Create folder, Sort, Grid/list toggle, Trash bin |
| Removed in v2 | Calculator, math page, checklist |
| Design rule | If it isn't needed, it isn't there |
