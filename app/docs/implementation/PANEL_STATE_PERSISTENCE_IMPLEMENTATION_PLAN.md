# Panel State Persistence — Implementation Plan

**Status:** Active — 2026-05-31  
**Audience:** Product, Kotlin implementers, Eidos prompt authors  
**Related:** [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md) (bridge contract), [DATA_MODEL.md](../architecture/DATA_MODEL.md) (`PanelState`), [EDITOR_AND_PANELS.md](../architecture/EDITOR_AND_PANELS.md), [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md), [PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md](./PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md) (Phase 4 — storage shipped)

Tracks work to make **panel user data** (form input, game scores, save points, tool state) **reliable end-to-end**: not only stored in Room, but **implemented consistently in panel JS**, **enforced during workshop build**, and **verifiable** before users depend on it in Gallery and editor tabs.

---

## Goal

Users open a panel in **Panel Gallery** or an **editor custom tab** and see the same data they left — after refresh, tab switch, or app restart.

**Core invariant:** Kotlin stores **opaque JSON** keyed by `(workshopSubfolderId, scopeKey)`. Panel JS owns the schema. Platform never parses game/form fields.

---

## What is already shipped (do not rebuild)

Foundation landed in [PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md](./PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md) **Phase 4**:

| Layer | Shipped |
|-------|---------|
| Room | `panel_state` table, migration 19→20 |
| Repository | `PanelStateRepository` load/save/delete |
| Scopes | `global` (gallery), `subfolder:{hostSubfolderId}` (editor tab) |
| Bridge | `loadPersistedState` / `savePersistedState` on `OptimalXPanelBridge` |
| Restore | Kotlin `restorePersistedPanelState` on page finish; JS `restorePersistedStateIfAvailable` in scaffold `bridge.js` |
| Surfaces | `PanelRunnerScreen`, `EditorScreen` custom panel pages |
| Cleanup | `deleteForWorkshopProject` when workshop project removed |
| Backup | Full DB export includes `panel_state` |
| Tests | `PanelStateRepositoryTest`, `AppDatabaseMigration19To20Test` |

**Intentionally not persisted today:** Workshop **Preview** (`WorkshopEditorScreen` omits `panelStateScopeKey`) — ephemeral build surface per [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md).

**Gap:** Storage works; **many panels will still lose data** if `panelGetState` / restore hooks are missing, saves only run on `runAction`, or legacy projects predate the scaffold.

---

## Decisions locked (2026-05-31)

Product review complete. Implement per **Chosen** column.

| Topic | Chosen |
|-------|--------|
| Workshop Preview persistence | **A — ephemeral** — no `panelStateScopeKey` in Preview; test saves in Gallery or editor tab |
| Save trigger | **B — debounced** `persistPanelStateDebounced` (500–1500 ms) + flush on `visibilitychange` / `pagehide` (Phase 3 scaffold) |
| `localStorage` in panels | **B — Room canonical** — `localStorage` optional for session-only cache |
| Scope model | **Keep** `global` + `subfolder:{hostSubfolderId}` |
| Clear / reset UX | **B — per-panel** “Reset saved data” (Phase 6) |
| Finish gate | **B — block Finish** for stateful panels missing hooks; override for display-only (Phase 2) |
| Eidos migration of old projects | **B + C** — audit tool (Phase 4) + persistence prompts (Phase 2) |

---

## Architecture (target end state)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Panel JS (script.js)                                                    │
│  • panelGetState() → JSON snapshot                                       │
│  • panelRestoreState(state) OR __restoreState in panelHandleAction       │
│  • persistPanelStateDebounced() — bridge.js helper                       │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ OptimalXPanelBridge
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  WorkshopPreviewPanel (when panelStateScopeKey set)                      │
│  • onPageFinished → restorePersistedPanelState                           │
│  • savePersistedState → PanelStateRepository                             │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Room panel_state (workshopSubfolderId, scopeKey) → stateJson            │
└─────────────────────────────────────────────────────────────────────────┘

Scopes:
  Gallery runner     → global
  Editor custom tab  → subfolder:{hostSubfolderId}
  Workshop preview   → (none) unless product chooses Phase 5
```

**Eidos enforcement path:**

```
LOGIC_BUILD / Finish
        │
        ▼
PanelPlatformSpec.validatePersistenceHooks(script.js, bridge.js)
        │
        ├── ok → allow Finish / mark COMPLETE
        └── warn/error → surface in workshop UI + Eidos context
```

---

## Phase checklist

### Phase 0 — Foundation ✅

**Status:** Shipped — 2026-05-28 (see PINNED_ROW plan Phase 4). **Kickoff verified 2026-05-31** — no new Kotlin storage work; proceed to Phase 1.

No further Kotlin storage work unless a bug is found. Treat Phase 0 as **frozen** unless schema change is required (unlikely).

| ID | Task | Status |
|----|------|--------|
| 0a | `PanelState` entity + DAO + migration | ✅ |
| 0b | `PanelStateRepository` + app wiring | ✅ |
| 0c | Bridge load/save + restore on load | ✅ |
| 0d | Gallery + editor `panelStateScopeKey` | ✅ |
| 0e | Scaffold `bridge.js` save-after-`runAction` + restore on ready | ✅ |
| 0f | `PANEL_PLATFORM.md` persistence section | ✅ |

---

### Phase 1 — Contract hardening (docs + static validation) ✅

**Status:** Shipped — 2026-05-31  
**Goal:** One authoritative contract every author (human or Eidos) can follow without reading Kotlin.

**Depends on:** Phase 0  
**Effort:** Small (docs + ~1 day Kotlin)

| ID | Task | Status |
|----|------|--------|
| 1a | Expand [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md) — persistence checklist, anti-patterns, what Kotlin does **not** do | ✅ |
| 1b | **Persistence** subsection in workshop `FEATURES.md` template (`FolderRepository`) | ✅ |
| 1c | `PanelPlatformSpec.capabilities` — `panel_state_persistence` entry | ✅ |
| 1d | `PanelPlatformSpec.EIDOS_PERSISTENCE_POLICY` | ✅ |
| 1e | `validatePersistenceContract` + `panelScriptLooksStateful` | ✅ |
| 1f | Cross-link [DATA_MODEL.md](../architecture/DATA_MODEL.md), [EDITOR_AND_PANELS.md](../architecture/EDITOR_AND_PANELS.md) | ✅ |
| 1g | Unit tests (`PanelPlatformSpecTest`) | ✅ |

**Acceptance:**
- New greenfield project README/FEATURES mention persistence expectations.
- `validatePersistenceContract` returns actionable warnings on empty/minimal `script.js`.

---

### Phase 2 — Eidos & workshop pipeline enforcement ✅

**Status:** Shipped — 2026-05-31  
**Goal:** Logic build and **Finish** do not ship interactive panels without persistence hooks.

**Depends on:** Phase 1  
**Effort:** Medium (~2–3 days)

| ID | Task | Status |
|----|------|--------|
| 2a | `EIDOS_PERSISTENCE_POLICY` in build logic, logic review edit/debug/chat, kickoff footer | ✅ |
| 2b | **Finish gate** — `evaluatePersistenceForFinish` + `PersistenceFinishGateDialog` on Accept logic | ✅ |
| 2c | Heuristic — `panelScriptLooksStateful` + `featuresMdMentionsPersistence` | ✅ |
| 2d | **Finish anyway** override + `Log.w` | ✅ |
| 2e | Accept logic reminders — Gallery/editor tab, not Preview-only | ✅ |
| 2f | Debug prompt — `call_panel_function` + Gallery persistence note | ✅ |
| 2g | `PanelPlatformSpecTest` finish-gate cases | ✅ |

**Acceptance:**
- Finishing a game/score panel without `panelGetState` shows blocking message with fix hints.
- Display-only panel can Finish with override.
- Eidos logic-build prompts explicitly require persistence for stateful UIs.

---

### Phase 3 — Platform scaffold (efficient save path) ✅

**Status:** Shipped — 2026-05-31  
**Goal:** Panels persist **without** routing every UI event through `runAction`.

**Depends on:** Phase 1 (contract); can parallel Phase 2  
**Effort:** Small–medium (~1–2 days)

| ID | Task | Status |
|----|------|--------|
| 3a | `persistPanelStateDebounced` / `persistPanelStateNow` / `flushPersistedPanelState` in scaffold `bridge.js` | ✅ |
| 3b | `visibilitychange` / `pagehide` → flush | ✅ |
| 3c | `runAction` → `persistPanelStateNow`; scaffold note field demos debounced input | ✅ |
| 3d | `panelRestoreState` in scaffold `script.js` | ✅ |
| 3e | `EIDOS_PERSISTENCE_POLICY` in `eidosBuildLogicInstructions`; `PANEL_PLATFORM.md` table | ✅ |
| 3f | No Kotlin polling — JS drives saves | ✅ |

**Acceptance:**
- New projects: typing in an input persists after debounce without custom `runAction` per keystroke.
- Tab away / background app triggers save flush.

**Out of scope:** Kotlin-side auto-save polling (inefficient; keep JS-owned).

---

### Phase 4 — Legacy project backfill

**Goal:** Existing COMPLETE panels gain persistence without manual file archaeology.

**Depends on:** Phases 1–3  
**Effort:** Medium (~2 days)

| ID | Task | Owner |
|----|------|-------|
| 4a | **Workshop “Panel health”** row or debug action — list persistence warnings from `validatePersistenceContract` for current project files | Kotlin + UI |
| 4b | Eidos **Update → Logic** (or one-shot system message): “Add persistence to this project” using patch policy | Prompts |
| 4c | Document manual backfill steps in [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) — open Logic mode, accept diff | Docs |
| 4d | Optional: migration script **not** for DB — only bump scaffold in **new** files; old projects via Eidos only | — |
| 4e | Gallery QA spot-check list (3 panel types: form, game, read-only) | QA doc |

**Acceptance:**
- User with pre-Phase-3 project sees health warnings and can fix via Eidos in one session.
- No automatic overwrite of `script.js` without user/Eidos review (DIFF_REVIEW applies).

---

### Phase 5 — Workshop Preview persistence (optional)

**Goal:** Decide and implement only if product rejects ephemeral preview.

**Depends on:** Product decision table above  
**Effort:** Small if deferred; medium if implemented

| ID | Task | Status |
|----|------|--------|
| 5a | **Decision record** in this file + `PANEL_PLATFORM.md` | ☐ |
| 5b | If **ephemeral (default):** add QA note — “test saves in Gallery or editor tab, not Preview” | ☐ |
| 5c | If **preview scope:** `PanelStateScope.PREVIEW = "preview"` + pass `panelStateScopeKey` from `WorkshopEditorScreen` | ☐ |
| 5d | If **preview scope:** separate clear action so preview experiments do not wipe gallery `global` | ☐ |

**Recommendation:** Skip 5c/5d unless user testing proves Preview-only verification is a blocker.

---

### Phase 6 — User-facing operations

**Goal:** Users can recover from bad state and trust what is stored.

**Depends on:** Phases 1–3 minimum  
**Effort:** Small–medium (~2 days)

| ID | Task | Owner |
|----|------|-------|
| 6a | **Reset saved data** — Panel Gallery context menu / runner overflow: clears `global` scope for that `workshopSubfolderId` | Kotlin + UI |
| 6b | Editor custom panel — long-press or panel tab menu: clear `subfolder:{hostId}` scope | Kotlin + UI |
| 6c | Confirm dialog copy — explains data loss; does not delete workshop **files** | UX |
| 6d | Optional: show `updatedAt` in debug-only workshop row (“Last saved …”) | Kotlin |
| 6e | Eidos tools must **not** clear persisted state without explicit user request (document in tool policy) | Docs |

**Acceptance:**
- User can reset high scores without deleting the panel project.
- Clear affects only the relevant scope key.

---

### Phase 7 — Testing & QA matrix

**Goal:** Regressions caught before release.

**Depends on:** Phases 1–3 (6 optional)  
**Effort:** Small (~1 day)

| ID | Task | Owner |
|----|------|-------|
| 7a | Instrumented: gallery runner save → kill process → relaunch → state restored | AndroidTest |
| 7b | Instrumented: editor tab scope isolation — same panel, two subfolders, different state | AndroidTest |
| 7c | Backup round-trip: export zip → restore → `panel_state` row intact | AndroidTest or manual |
| 7d | Manual QA script (below) in plan appendix | QA |
| 7e | Verify workshop preview **does not** write `panel_state` when ephemeral (default) | Manual |

**Manual QA script (minimum):**

1. Create panel with counter/score; increment; leave Gallery; reopen — value persists.
2. Pin panel to subfolder A and B; different scores — independent.
3. Reset saved data — score clears; panel HTML still loads.
4. Backup export → clear app data → import — score returns.
5. Finish gate: try Finish without `panelGetState` on game panel — blocked (Phase 2).

---

### Phase 8 — Advanced (defer)

Do not start until Phases 1–7 are stable and users ask for more.

| Feature | Notes |
|---------|--------|
| Multiple save slots | New table or JSON array in blob; panel-defined slot id |
| Export/import state file | SAF JSON export per panel — niche |
| `localStorage` ↔ Room sync | High complexity; prefer Room-only |
| Cross-device sync | Out of scope — Sync with Desktop |
| Eidos read/write `panel_state` tool | Security/privacy review required |

---

## Suggested rollout order

Efficient critical path — each phase ships value:

```text
Phase 0 ✅  →  Phase 1  →  Phase 3  →  Phase 2  →  Phase 4
                    ↘ Phase 7 (parallel tests after 3)
Phase 6 (after 3)     Phase 5 (product decision; likely skip)
Phase 8 (defer)
```

**Rationale:**
1. **Contract first** (Phase 1) — stops new broken panels immediately.
2. **Scaffold debounce** (Phase 3) — fixes save efficiency without more Room work.
3. **Eidos + Finish gate** (Phase 2) — prevents shipping stateful panels without hooks.
4. **Legacy backfill** (Phase 4) — fixes existing catalog.
5. **UX reset** (Phase 6) — operational safety net.

---

## File touch list (by phase)

| Phase | Primary files |
|-------|----------------|
| 1 | `PanelPlatformSpec.kt`, `PANEL_PLATFORM.md`, `DATA_MODEL.md`, `FolderRepository.kt` (FEATURES template) |
| 2 | `WorkshopEditorViewModel.kt`, `WorkshopEditorScreen.kt`, `PanelPlatformSpec.kt` (prompts) |
| 3 | `FolderRepository.kt` (`WORKSHOP_BRIDGE_JS_SCAFFOLD`, `WORKSHOP_SCRIPT_JS_SCAFFOLD`) |
| 4 | `WorkshopEditorScreen.kt` or debug sheet, `PANEL_WORKSHOP.md` |
| 5 | `PanelStateScope.kt`, `WorkshopEditorScreen.kt` |
| 6 | `PanelGalleryScreen.kt`, `PanelRunnerScreen.kt`, `EditorScreen.kt`, `PanelStateRepository.kt` |
| 7 | `app/src/androidTest/...` |

---

## Success metrics

| Metric | Target |
|--------|--------|
| New COMPLETE panels with user input | 100% pass `validatePersistenceContract` at Finish |
| Save without `runAction` | Debounced persist works on scaffold panels |
| Gallery restart | User-visible state restored in &lt; 2s of panel load |
| Support burden | “Lost my high score” reports traceable to missing hooks vs platform bug |

---

## Appendix — persistence contract (authoritative snippet)

Panel authors implement:

```javascript
// script.js — required for stateful panels
window.panelGetState = function () {
  return { version: 1, /* your fields */ };
};

window.panelRestoreState = function (state) {
  // apply state to DOM / game engine
};

// On meaningful UI changes:
// persistPanelStateDebounced();  // from bridge.js scaffold (Phase 3)
```

Kotlin never interprets `version` or inner fields — optional for panel migrations only.

---

## Changelog

| Date | Change |
|------|--------|
| 2026-05-31 | Initial plan — foundation marked shipped; Phases 1–8 defined |
| 2026-05-31 | Decisions locked; Phase 0 kickoff verified; Phase 1 shipped |
| 2026-05-31 | Phase 3 — debounced persist scaffold + demo note field + logic-build prompt |
| 2026-05-31 | Phase 2 — Finish gate, Eidos prompts, persistence evaluation |
