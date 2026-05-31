# PANEL_GALLERY.md

## What It Is

The Panel Gallery is where the user browses and launches panels they have built in Panel Workshop.

**Access (v1):** Tap **Panels** in the pinned row on the Parent Folder Page (and on the gallery screen itself — **Panels** returns to this list).

**Not supported:** Swiping right on the parent folder page (or any gesture) to open the gallery. Do not reintroduce swipe-to-gallery without an explicit product decision.

**Implementation plan:** [PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md](../implementation/PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md) (shipped 2026-05-28). Eidos Chat: [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](../implementation/PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md).

---

## Purpose

Panel Workshop is the builder. The Panel Gallery is where you go to use what you built. Users should not have to navigate into Workshop and hunt through subfolders just to launch a finished panel.

---

## What It Shows

All Panel Workshop projects the user has created, regardless of which workshop subfolder they live in.

Panels appear in two states:

| State | Badge | Tap behavior |
|-------|-------|--------------|
| **COMPLETE** | (none) | Full-screen **Panel runner** (`PanelRunnerScreen`, `scopeKey = global`) |
| **Draft** (any other phase) | Draft | Opens **Panel Workshop** editor for that project |

`WorkshopProjectPhase.COMPLETE` is set after the user accepts the final logic build/review in Workshop.

---

## Behavior

- Tap a finished panel → launches full screen with live workshop HTML/JS/CSS from disk (no export-to-gallery step)
- Tap a draft panel → opens in Panel Workshop editor
- Long press any panel → context menu includes **Pin to home** (shortcut on pinned row; does not move the project)

Panel state for the gallery runner persists in Room `panel_state` with `scopeKey = global`. See [PANEL_PLATFORM.md](PANEL_PLATFORM.md).

---

## Eidos Chat

Both **Panel Gallery** and **Panel Runner** expose a top-bar **Eidos** button (same pattern as [DUMPEDIT.md](DUMPEDIT.md)). Tapping it opens the standard in-app chat bottom sheet. Scope is set automatically — the user does nothing.

| Surface | Conversation scope | What Eidos knows | Panel Bridge |
|---------|-------------------|------------------|--------------|
| **Panel Gallery** | `panel_gallery` (singleton) | Panel names, COMPLETE vs draft, gallery purpose | **None** — no WebView on the list |
| **Panel Runner** | `panel_runner` (per `workshopSubfolderId`) | Which panel is running, global `panel_state`, live JS via bridge | **Active** — `panelContextType = gallery` |

### Runner vs Workshop chat

Runtime chat on the runner is **`panel_runner`**. Build/edit chat in Panel Workshop is **`panel_workshop`**. They are **separate conversation threads** even for the same project — e.g. “how do I use this?” on the runner vs “change the layout” in Workshop.

On the runner, Eidos can use **`call_panel_function`** (`getState` / `runAction`) against the live panel. It must **not** use **`workshop_write_file`** or Workshop build kickoffs from runner scope.

Draft panels opened from the gallery still route to **Panel Workshop**, which already has `panel_workshop` Eidos — the gallery list itself does not embed a panel WebView.

See [CHAT_UI.md](CHAT_UI.md) and [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](../implementation/PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md).

---

## Adding a Panel to a Subfolder

A panel can be added directly to a subfolder so it is accessible from within that subfolder's editor (custom panel tab). This is distinct from the gallery:

| Surface | Scope | Purpose |
|---------|-------|---------|
| **Panel Gallery** | Global launch | Run panel full screen |
| **Editor custom tab** | `subfolder:{hostSubfolderId}` | Panel beside a job’s note |

---

## Pinned Row

The pinned row appears on the **Parent Folder Page** and **Panel Gallery**. See **PINNED_ROW** section below.

---

## Relationship to Panel Workshop

The gallery does not replace Workshop. Workshop is still where panels are created and edited. The gallery is a launch surface only — it reads the same on-disk workshop files Workshop manages.



# PINNED_ROW.md

## What It Is

The Pinned row sits at the top of the **Parent Folder Page** and the **Panel Gallery**. It gives the user one-tap access to frequently used destinations and system tools without navigating the folder structure.

The Pinned row does **not** appear on subfolder pages, inside the editor, inside a running panel, inside Panel Workshop, or inside DumpEdit.

---

## Permanent Items

Four system shortcuts are always present in the pinned row (cannot be unpinned), in this order:

1. **Panels** — Panel Gallery
2. **DumpEdit** — disposable scratch buffer ([DUMPEDIT.md](DUMPEDIT.md))
3. **Panel Workshop** — workshop project list (builder)
4. **Quick Notes** — Quick Notes parent subfolder list

A **Pin (+)** control at the end opens a hint: long-press a folder or panel to pin it here.

---

## User Pins

Users can pin any parent folder, subfolder, or panel to the Pinned row.

- **Pin:** long press a folder card or gallery panel → **Pin to home**
- **Unpin:** long press the pinned shortcut → **Unpin**

There is no limit on the number of pins. Pins are stored in Room `home_pins` — shortcuts only; targets stay in their real location.

### What a pin tap does

| Pin type | Opens |
|----------|--------|
| Parent folder | Subfolder page |
| Subfolder | Editor (note) |
| Panel | Panel runner (COMPLETE projects only) |

---

## Overflow

If the pinned row exceeds the screen width it scrolls horizontally. No wrapping, no second row.

---

## Display

Pinned items display as compact cards showing the item name. System shortcuts (Panels, DumpEdit, Workshop, Quick Notes) are visually distinct from user pins.
