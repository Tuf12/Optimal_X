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
```

System-level parent folders still exist in the database for legacy data and internal tools. **Eidos Journal**, **Eidos Log**, **Eidos Daily**, **Eidos Memory**, and **Eidos Index** are reachable from the **Eidos section** (top bar menu). **Eidos Chats** and **Eidos Reasoning** parents remain in the DB but are **hidden** from folder UI — chat navigation lives in the chat UI.

**Quick Notes**, **Panel Workshop**, **Panels** (gallery), and **DumpEdit** are reached from the **pinned row** on the Parent Folder Page, not from the user folder grid.

See `DATA_MODEL.md`, `QUICK_NOTES.md`, `PANEL_GALLERY.md`, and `DUMPEDIT.md`.

Three levels deep. No exceptions. Navigation is linear and predictable.

---

## Screen 1 — Parent Folder Page

This is the home screen of the app.

### What it shows
- **Pinned row** (horizontal scroll): Panels, DumpEdit, Panel Workshop, Quick Notes, Pin (+), then user pin shortcuts
- **User folder grid** (or list): parent folders the user created — **user folders only**
- System destinations are **not** mixed into the grid

### Top bar
- Search bar
- Eidos assistant button
- Eidos section menu (Journal, Log, Daily, Memory, etc.)

### Bottom bar
- Create folder button
- Sort button
- Grid/list toggle button
- Trash icon button (opens trash screen)

### Folder interaction
- Tap user folder → Subfolder Page
- Long press user folder → Rename / Delete / Move / **Pin to home**

### Back behavior
- This is the root screen
- Android back button exits the app from here

---

## Screen 2 — Subfolder Page

Same layout components as the parent page **except there is no pinned row** on subfolder pages.

### What it shows
- User subfolders inside the selected parent folder
- System subfolders (Chats, Reasoning, Memory Cache) are **hidden** — not shown in the list

Note: Legacy per-parent **Chats** system subfolders may exist in the DB but are not displayed. New parent folders no longer receive a Chats subfolder.

### Top bar
- Search bar
- Eidos assistant button

### Bottom bar
- Create folder button (disabled on Quick Notes parent)
- Sort button
- Grid/list toggle button

### Folder interaction
- Tap subfolder → Editor View (or Quick Notes inbox / Workshop editor for special parents)
- Long press → Rename / Delete / Move

### Back behavior
- Android back button → returns to Parent Folder Page

---

## Panel Gallery, Panel Runner & DumpEdit

| Route | Entry | Top bar | Eidos scope |
|-------|--------|---------|-------------|
| **Panel Gallery** | Pinned **Panels** | Back, title, **Eidos** | `panel_gallery` |
| **Panel runner** | Tap COMPLETE panel in gallery (or pinned panel) | Back, panel name, **Eidos** | `panel_runner` (per workshop project) |
| **DumpEdit** | Pinned **DumpEdit** | Back, title, **Eidos** | `dump_edit` |

All are full-screen routes, not folder grid cards. The pinned row also appears on **Panel Gallery** (not on DumpEdit or the runner). None of these routes show the Eidos section ⋮ menu in v1 — **Eidos** chat button only (see [CHAT_UI.md](CHAT_UI.md)).

See [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](../implementation/PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md).

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

### Back behavior
- If on the Files Panel → Android back button returns to Note Panel
- If on the Note Panel → Android back button returns to Subfolder Page

---

## Navigation Summary

| Action | Result |
|---|---|
| Open app | Parent Folder Page |
| Tap pinned **Panels** | Panel Gallery |
| Tap COMPLETE panel in gallery | Panel runner (full screen) |
| Tap draft panel in gallery | Panel Workshop editor |
| Tap pinned **DumpEdit** | DumpEdit screen |
| Tap pinned **Workshop** / **Quick Notes** | That system parent's subfolder list |
| Long press folder/panel → Pin to home | Adds shortcut to pinned row |
| Tap user parent folder | Subfolder Page |
| Tap subfolder | Editor View — Note Panel |
| Swipe left in Note | Files Panel |
| Back from Subfolder Page | Parent Folder Page |
| Back from Parent Folder Page | Exit app |

---

## Navigation Rules

- No custom back buttons in the UI — Android system back handles all back navigation
- Pinned row appears on **Parent Folder Page** and **Panel Gallery** only
- Panel Gallery is opened by tapping **Panels** in the row — not by swiping on the parent page
- Swipe panels are part of the same screen — they do not create new back stack entries except for returning to Note from Files
- Chat history is navigated from the **chat UI**, not from folder cards

---

## What Does Not Exist in v2

- No third folder level (no subfolders inside subfolders)
- No tab bar navigation
- No side drawer / hamburger menu
- Eidos Chats / Eidos Reasoning as visible folder cards (legacy rows may exist in DB)
- No calculator, math, or checklist panels (removed from v1)
