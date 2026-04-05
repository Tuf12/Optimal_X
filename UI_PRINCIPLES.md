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

The home page (parent folders) and the subfolder page are **visually identical**.
Same layout. Same components. Same behavior. Different folder contents.

### Top Bar
- Search bar (left/center)
- Eidos assistant button (right)

### Middle
- Folder card grid (or list if toggled)

### Bottom Bar
- Create folder button
- Sort button
- Grid/list toggle button
 - Trash bin icon (opens Trash screen)

No other buttons belong on these screens.

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

### Panel navigation
- Panels are accessed by **swiping left or right**
- Current panels (v2):
    - Note (default, center)
    - Files (swipe to access)
- Future panels can be added to the swipe system without restructuring the layout
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
- navigation follows a fixed pattern (tap to go deeper, swipe for panels)
- folder structure maps directly to what is on screen
- nothing is hidden behind non-obvious gestures except the panel swipe system

Note: System-level folders (Eidos Journal, Eidos Log, Eidos Chats) do not appear in the regular folder lists. They are accessible from a dedicated Eidos section in the app and are protected from rename/trash/deletion.

---

## Summary of Rules

| Element | Rule |
|---|---|
| Folder cards | Rounded rectangle, name only, grid default |
| Home page | Top bar + grid + bottom bar |
| Subfolder page | Identical to home page |
| Long press | Rename / Delete / Move |
| Subfolder tap | Opens directly into note |
| Panel navigation | Swipe left/right |
| Editor default | Note panel, full screen |
| Files panel | PDF, Word, images |
| Bottom bar | Create folder, Sort, Grid/list toggle, Trash bin |
| Removed in v2 | Calculator, math page, checklist |
| Design rule | If it isn't needed, it isn't there |
