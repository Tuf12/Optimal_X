# APP_STRUCTURE.md

## Purpose

This file defines the screen hierarchy and navigation structure of OptimalX v2.

Every screen, transition, and back navigation behavior is defined here.
Coding agents should use this file to understand how the app is organized and how users move through it.

---

## App Hierarchy

```
App
└── Parent Folder Page
    └── Subfolder Page
        └── Editor View
            ├── Note Panel (default)
            └── Files Panel (swipe)

System level folders (Eidos) exist at app root alongside user parent folders and are accessible from a dedicated Eidos section:
- Eidos Journal
- Eidos Log
- Eidos Chats
```

Three levels deep. No exceptions. Navigation is linear and predictable.

---

## Screen 1 — Parent Folder Page

This is the home screen of the app.

### What it shows
- All parent folders created by the user
- Displayed as rounded rectangle cards in a grid (default) or list
Note: System-level parent folders (Eidos Journal, Eidos Log, Eidos Chats) are created on install but do not appear in this regular folder list. They are available from the dedicated Eidos section.

### Top bar
- Search bar
- Eidos assistant button

### Bottom bar
- Create folder button
- Sort button
- Grid/list toggle button
- Trash icon button (opens trash screen)
- Long press → context menu: Rename / Delete / Move

### Back behavior
- This is the root screen
- Android back button exits the app from here

---

## Screen 2 — Subfolder Page

This screen is visually and functionally identical to the parent folder page.
Same layout. Same components. Same behavior.
The only difference is the contents — it shows subfolders instead of parent folders.

### What it shows
- All subfolders inside the selected parent folder
- Displayed as rounded rectangle cards in a grid (default) or list

Note: Every user-created subfolder automatically gets an internal system chat subfolder created inside it (used for subfolder-scoped text/voice conversations). This system subfolder does not appear in the regular subfolder list and is protected from rename/trash/deletion.

### Top bar
- Search bar
- Eidos assistant button

### Bottom bar
- Create folder button
- Sort button
- Grid/list toggle button

### Folder interaction
- Tap → opens the editor view for that subfolder
- Long press → context menu: Rename / Delete / Move

### Back behavior
- Android back button → returns to Parent Folder Page

---

## Screen 3 — Editor View

This is the core working screen of the app.

The editor view is a single screen with multiple panels accessed by swiping.
It is not a new screen per panel — it is one screen with swipeable content areas.

### Entry behavior
- Tapping a subfolder opens directly into the Note Panel
- No intermediate screen
- No extra taps

### Panels (v2)

| Panel | Position | Access |
|---|---|---|
| Note | Center (default) | Opens here automatically |
| Files | Right of Note | Swipe left from Note |

Future panels can be added to the swipe system without changing the screen structure.

### Note Panel
- Full screen text editor
- Formatting toolbar (fonts, text options)
- Dropdown menu for extra functions
- Full detail defined in EDITOR_AND_PANELS.md

### Files Panel
- Displays files attached to the current subfolder
- Supported types: PDF, Word (.docx), images
- Full detail defined in FILES_AND_MEDIA.md

### Back behavior
- If on the Files Panel → Android back button returns to Note Panel
- If on the Note Panel → Android back button returns to Subfolder Page

---

## Navigation Summary

| Action | Result |
|---|---|
| Open app | Parent Folder Page |
| Tap parent folder | Subfolder Page |
| Tap subfolder | Editor View — Note Panel |
| Swipe left in Note | Files Panel |
| Swipe right in Files | Note Panel |
| Back from Files Panel | Note Panel |
| Back from Note Panel | Subfolder Page |
| Back from Subfolder Page | Parent Folder Page |
| Back from Parent Folder Page | Exit app |

---

## Navigation Rules

- No custom back buttons in the UI — Android system back handles all back navigation
- No intermediate screens between any two levels
- Swipe panels are part of the same screen — they do not create new back stack entries except for returning to Note from Files
- Every screen is reachable in 3 taps or fewer from the home screen

---

## What Does Not Exist in v2

- No third folder level (no subfolders inside subfolders)
- No tab bar navigation
- No side drawer / hamburger menu
- No modal screens for folder browsing
- No calculator, math, or checklist panels (removed from v1)