# Pinned Row, Panel Gallery & DumpEdit — Implementation Plan

**Status:** Shipped — 2026-05-28 (Phases 0–7 complete)  
**Architecture specs:** [PANEL_GALLERY.md](../architecture/PANEL_GALLERY.md) (includes Pinned Row), [DUMPEDIT.md](../architecture/DUMPEDIT.md)  
**Related:** [APP_STRUCTURE.md](../architecture/APP_STRUCTURE.md), [UI_PRINCIPLES.md](../architecture/UI_PRINCIPLES.md), [DATA_MODEL.md](../architecture/DATA_MODEL.md), [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md), [EDITOR_AND_PANELS.md](../architecture/EDITOR_AND_PANELS.md)

Phased rollout of the **horizontal quick-access row** on the **Parent Folder Page**, plus **Panel Gallery** (launch surface), **DumpEdit** (disposable working buffer), **panel state persistence**, and **hide legacy chat/reasoning system folders**.

Each phase ships something usable on its own.

---

## Goal

Shipped. The parent folder grid shows **user folders only**. System destinations use the **pinned row** and dedicated routes:

| Before | After |
|--------|-------|
| System parents in grid | Pinned row + full-screen routes |
| No gallery launch | **Panels** → Panel Gallery → runner |
| No scratch buffer | **DumpEdit** (DataStore + Eidos) |
| No pin shortcuts | `home_pins` long-press pin/unpin |
| No panel persistence | Room `panel_state` (global + per editor tab) |

| Surface | Behavior |
|---------|----------|
| **Pinned row** | Single horizontal scroll row below search — **Parent page only** |
| **System shortcuts** | Fixed leading slots (order below); not in user folder grid |
| **User pins** | Shortcuts only — **no new directory location**; long-press to pin/unpin |
| **Panel Gallery** | Tap **Panels** in row; COMPLETE workshop projects launch full screen |
| **DumpEdit** | Scratch buffer — persists until Clear; undo Clear is session-only |
| **Panel state** | Gallery = one global save per panel; editor tab = save per host `subfolderId` |

**Core invariant:** Pins are a **shortcut system**, not a folder hierarchy. Pinning does not move or copy the underlying object.

---

## Target layout (Parent page only)

```
┌─────────────────────────────────────────────┐
│  Parent                          [Eidos][⋮] │
│  [ Search …                              ]  │
├─────────────────────────────────────────────┤
│ ◀ Panels │ DumpEdit │ Workshop │ Quick Notes │ [+] │ …pins… ▶ │
├─────────────────────────────────────────────┤
│   [ User folder grid / list only ]          │
├─────────────────────────────────────────────┤
│  [ + ]  [ Sort ]  [ Grid ]  [ Trash ]        │
└─────────────────────────────────────────────┘
```

- **Single row** — scrolls horizontally when pins overflow; never wraps to a second row.
- **[+]** at end of system slots → dialog: “Long-press a folder or panel to pin it here.”
- Row **not** on Subfolder page, editor, workshop editor, running panel, or DumpEdit.
- Panel Gallery screen includes the same pinned row (Panels entry returns to gallery).

---

## Decisions locked

| Topic | Decision |
|-------|----------|
| Row placement | **Parent page + Panel Gallery only** — not Subfolder page |
| System slot order | **Panels → DumpEdit → Panel Workshop → Quick Notes** (fixed, cannot unpin) |
| Pin UX | Long-press folder/panel → Pin to home; long-press pin → Unpin; **[+]** hint dialog |
| Pin semantics | Shortcut only — underlying object stays in its real location |
| Parent grid | User folders only — system parents removed from grid query |
| Panel Gallery access | Tap **Panels** in row — **not** swipe-right (deprecated in `PANEL_GALLERY.md`) |
| Gallery eligibility | `WorkshopProjectPhase.COMPLETE` after user accepts final logic build/review |
| Workshop → Gallery sync | **No export button** — gallery loads current workshop files on disk; edits in Workshop reflect immediately |
| Panel state — gallery | `scopeKey = global` — **one save state** per panel project |
| Panel state — editor tab | `scopeKey = subfolder:{targetSubfolderId}` — isolated per job/subfolder |
| DumpEdit buffer | DataStore — survives app restart until user **Clear** |
| DumpEdit undo Clear | **Session-only (in-memory)** — does not persist across app restart |
| DumpEdit backup | Include buffer (+ flags) in `OptimalXBackupManager` export/import |
| DumpEdit Eidos — large text | Under char threshold → full context; over threshold → semantic retrieval, not full paste |
| DumpEdit Eidos privacy | Same **Lock from Eidos** / **Blind from Eidos** as notes (DataStore flags) |
| DumpEdit editor UX | Reuse note editor chrome: formatting toolbar + dropdown (see DumpEdit menu table below) |
| Legacy system folders | **Eidos Chats**, **Eidos Reasoning**, per-parent **Chats** subfolders → **invisible** (hide UI; stop new creation; data stays in DB) |
| Eidos Journal / Log / Daily / Memory / Index | Eidos section only — unchanged |

### DumpEdit vs Quick Notes (user-facing distinction)

| | Quick Notes | DumpEdit |
|---|-------------|----------|
| Purpose | Fast capture — always saved | Work messy content — may never save |
| Structure | Dated subfolders, inbox | No folders until **Promote** |
| Persistence | Note in folder system | Scratch buffer until Clear or Promote |
| Eidos | Normal note/subfolder rules | Optional blind/lock; semantic retrieval when large |

### DumpEdit dropdown menu (v1)

Reuse `EditorDropdownMenu` where it applies; DumpEdit-specific items noted.

| Item | DumpEdit |
|------|----------|
| Strikethrough | Yes |
| View / Edit mode | Yes |
| Lock from Eidos | Yes (DataStore) |
| Blind from Eidos | Yes (DataStore) |
| Generate Eidos summary | **No** — not a folder note |
| Export / Share | Yes (export/share buffer text) |
| **Clear** | Yes — DumpEdit-only (confirm dialog) |
| **Undo clear** | Yes — session-only snackbar/action |
| **Promote to folder** | Yes — shipped Phase 6 |

---

## Panel state architecture (Phase 4b — shipped)

```
Storage key = (workshopSubfolderId, scopeKey)

Gallery runner     → scopeKey = "global"
Editor custom tab  → scopeKey = "subfolder:{hostSubfolderId}"
```

- Room table `panel_state` — see [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md)
- Bridge: `loadPersistedState` / `savePersistedState` on `OptimalXPanelBridge`

---

## Legacy system folder invisibility (Phase 1b)

Hide from UI; do **not** delete rows on upgrade.

| Object | Action |
|--------|--------|
| **Eidos Chats** parent | Remove from parent grid + pinned row; hide from folder navigation |
| **Eidos Reasoning** parent | Hidden; no new writes; reasoning inbox UI removed |
| **Chats** system subfolder (per parent) | Stop creating on **new** parents; hide existing in subfolder lists |
| Reasoning folder logger | Removed — provider thinking on `ChatMessage` only |
| Chat directory | **Chat UI only** — conversations scoped via existing chat screens |

Data remains in DB for safety; invisible folders are not discoverable in normal navigation.

---

## Architecture summary

```
ParentFolderScreen / PanelGalleryScreen
        │
        ▼
   PinnedRow (LazyRow)
        │
        ├── Fixed: Panels → DumpEdit → Workshop → Quick Notes → [+]
        └── User pins (home_pins table — shortcuts only)
              parent    → subfolders(id)
              subfolder → editor(id)
              panel     → panel_runner(workshopSubfolderId)

PanelGalleryScreen
        │
        ├── COMPLETE → PanelRunnerScreen (scopeKey = global)
        └── else     → WorkshopEditorScreen

Editor custom panel tab
        └── WorkshopPreviewPanel (scopeKey = subfolder:{targetSubfolderId})

DumpEditScreen
        ├── DataStore: content, aiLocked, aiBlind
        ├── Session memory: undoClearSnapshot (not persisted)
        ├── Backup: OptimalXBackupManager includes dump_edit keys
        └── Eidos: dump_edit scope; semantic retrieval when content > threshold
```

---

## Phase checklist

### Phase 0 — Pinned row UI shell (Parent page only) ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 0a | `PinnedRowItem` sealed type | ✅ |
| 0b | `PinnedRow` — single `LazyRow`, system slots in locked order | ✅ |
| 0c | `[+]` → pin hint dialog | ✅ |
| 0d | `FolderPage` — `showPinnedRow` on Parent only | ✅ |
| 0e | Panels/DumpEdit → `ComingSoonScreen` routes | ✅ |

**Files:** `PinnedRowItem.kt`, `PinnedRow.kt`, `ComingSoonScreen.kt`, `FolderPage.kt`, `ParentFolderScreen.kt`, `ParentFolderViewModel.kt`, `AppNavigation.kt`, `FolderRepository.getQuickNotesParentId()`

---

### Phase 1 — Grid cleanup + invisible legacy folders ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 1a | Parent list → user folders only (`getActiveUserFolders`) | ✅ |
| 1b | Hide Chats/Reasoning system subfolders; stop Chats subfolder on new parents | ✅ |
| 1c | Remove chat/reasoning grid navigation; chat directory uses user folders only | ✅ |
| 1d | Update `APP_STRUCTURE.md`, `UI_PRINCIPLES.md`, `PANEL_GALLERY.md` | ✅ |

**Tests:** `ParentFolderListQueryTest`

---

### Phase 2 — Pin storage and pin/unpin ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 2a | `HomePin` entity + DAO + migration 18→19 | ✅ |
| 2b | Long-press folder → Pin; long-press pin → Unpin | ✅ |
| 2c | Auto-remove pins when target trashed/deleted | ✅ |
| 2d | Document in `DATA_MODEL.md` | ✅ |

**Tests:** `HomePinRepositoryTest`, `AppDatabaseMigration18To19Test`

---

### Phase 3 — Panel Gallery ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 3a | `PanelGalleryScreen` — lists workshop subfolders; Draft badge when not COMPLETE | ✅ |
| 3b | Tap COMPLETE → `panel_runner` placeholder; tap draft → workshop editor | ✅ |
| 3c | Long-press → Pin to home | ✅ |
| 3d | Pinned row on gallery screen | ✅ |

**Files:** `PanelGalleryItem.kt`, `PanelGalleryViewModel.kt`, `PanelGalleryScreen.kt`, `PanelGalleryCard.kt`, `PanelGalleryContextMenu.kt`, `AppNavigation.kt` (`PANEL_RUNNER` placeholder)

---

### Phase 4 — Panel runner + panel state persistence ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 4a | `PanelRunnerScreen` — full-screen `WorkshopPreviewPanel`, `panelContextType = gallery` | ✅ |
| 4b | **Panel state store** — Room `panel_state`, global scope for gallery | ✅ |
| 4c | Editor embedded panels — `subfolder:{id}` scoped state | ✅ |
| 4d | Update `PANEL_PLATFORM.md` — bridge persistence contract | ✅ |
| 4e | No export-to-gallery step — live workshop files | ✅ |

**Files:** `PanelState.kt`, `PanelStateDao.kt`, `PanelStateRepository.kt`, `PanelStateScope.kt`, `PanelRunnerScreen.kt`, `PanelRunnerViewModel.kt`, `WorkshopPreviewPanel.kt`, `PanelBridgeWebRuntime.kt`, migration 19→20

**Tests:** `PanelStateRepositoryTest`, `AppDatabaseMigration19To20Test`

---

### Phase 5 — DumpEdit screen ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 5a | DataStore: `dump_edit_content`, `dump_edit_ai_locked`, `dump_edit_ai_blind` | ✅ |
| 5b | Reuse note editor toolbar + dropdown (per menu table above) | ✅ |
| 5c | **Clear** + confirm; **Undo clear** in-memory only (lost on process death — intentional) | ✅ |
| 5d | Include DumpEdit keys in `OptimalXBackupManager` | ✅ |
| 5e | Distinct screen title/copy vs Quick Notes | ✅ |

**Files:** `DumpEditPreferences.kt`, `DumpEditViewModel.kt`, `DumpEditScreen.kt`, `DumpEditPanel.kt`, `DumpEditDropdownMenu.kt`, `DumpEditBackupPayload.kt`, `BackupArchive.kt`, `OptimalXBackupManager.kt`, `AppNavigation.kt`

---

### Phase 6 — DumpEdit Eidos + promote ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 6a | `dump_edit` Eidos scope | ✅ |
| 6b | Context: full buffer if under threshold; else semantic chunks / `read_dump_edit` tool | ✅ |
| 6c | Respect aiLocked / aiBlind in tools and context injection | ✅ |
| 6d | **Promote to folder** — create subfolder + note; buffer not auto-cleared | ✅ |

**Files:** `DumpEditContext.kt`, `ConversationScopes.kt`, `ConversationDao.kt`, `ChatSessionPointers.kt`, `EidosToolCatalog.kt`, `RoomToolExecutor.kt`, `EidosApiClient.kt`, `EidosChatViewModel.kt`, `PromoteDumpEditDialog.kt`, `DumpEditScreen.kt`, `DumpEditViewModel.kt`, `DumpEditDropdownMenu.kt`, `AppNavigation.kt`

**Threshold:** 8K chars (`DumpEditContextLimits.FULL_CONTEXT_CHAR_THRESHOLD`)

---

### Phase 7 — Doc polish ✅

**Status:** Shipped — 2026-05-28

| ID | Task | Status |
|----|------|--------|
| 7a | `PANEL_GALLERY.md` — tap-only access; no swipe-right gallery | ✅ |
| 7b | `DUMPEDIT.md` — DataStore, Eidos, promote, backup | ✅ |
| 7c | `APP_STRUCTURE.md`, `UI_PRINCIPLES.md` — pinned row + gallery/runner | ✅ |
| 7d | `DATA_MODEL.md` — `HomePin`, `PanelState`; fix header corruption | ✅ |
| 7e | `PROMPT_SYSTEM.md`, `TOOL_FUNCTIONS.md` — DumpEdit scope + `read_dump_edit` | ✅ |
| 7f | This plan — status + shipped goal | ✅ |

---

## Open questions

| # | Question | Status |
|---|----------|--------|
| 1 | Panel state storage — Room table vs JSON files | **Room** (`panel_state`) — backup coherency |
| 2 | DumpEdit rich text vs plain | Reuse rich text editor like notes (matches dropdown/toolbar decision) |
| 3 | Semantic threshold for DumpEdit context | **8K chars** — `DumpEditContextLimits` |
| 4 | Show draft panels in gallery or COMPLETE-only list | Tentative: show all, badge drafts |

---

## Out of scope (v1)

- Pin reorder drag-and-drop
- Persist undo-clear across app restart
- Multiple gallery save slots per panel (only one global state)
- Delete/migrate legacy chat/reasoning DB rows (invisible only)
- Swipe-right to Panel Gallery
- Eidos Chat on Panel Gallery / Panel Runner (see follow-up plan below)

---

## Follow-up — Eidos on gallery & runner

Phases 0–7 shipped gallery and runner **without** Eidos Chat UI or dedicated scopes. Gallery and runner top bars have no Eidos button; conversations opened elsewhere do not match panel launch context.

**Next plan:** [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md) — scopes `panel_gallery` (singleton) and `panel_runner` (per workshop project), top-bar Eidos entry, runner Panel Bridge integration, architecture doc alignment (`CHAT_UI.md`, `DATA_MODEL.md`, `PANEL_GALLERY.md`, etc.).
