# Panel Gallery & Panel Runner — Eidos Chat Implementation Plan

**Status:** In progress — Phases 0–4 + tool policy complete (2026-05-31); Phases 5–7 open  
**Architecture specs:** [CHAT_UI.md](../architecture/CHAT_UI.md), [PANEL_GALLERY.md](../architecture/PANEL_GALLERY.md), [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md)  
**Related:** [PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md](PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md), [DATA_MODEL.md](../architecture/DATA_MODEL.md), [DUMPEDIT.md](../architecture/DUMPEDIT.md), [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md), [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md), [TOOL_FUNCTIONS.md](../reference/TOOL_FUNCTIONS.md)

Phased rollout of **Eidos Chat UI** on **Panel Gallery** and **Panel Runner**, with **dedicated conversation scopes** and a **shared read-only project knowledge layer** so runtime chat understands the panel without sharing build tools or chat threads with Workshop.

Each phase ships something usable on its own.

---

## Goal

Today, **Panel Gallery** and **Panel Runner** have no Eidos button and no scoped chat threads. Workshop build chat (`panel_workshop`) and runtime use are conflated if the user opens Eidos from the wrong surface.

After this plan:

| Surface | Eidos entry | Conversation scope | Eidos knows | Tools |
|---------|-------------|-------------------|-------------|-------|
| **Panel Gallery** | Top-bar **Eidos** | `panel_gallery` (singleton) | All panels, COMPLETE vs draft | General + optional `list_panel_projects` |
| **Panel Runner** | Top-bar **Eidos** | `panel_runner` (per `workshopSubfolderId`) | **Panel project knowledge** + live bridge + `panel_state` | `call_panel_function`, read-only `workshop_read_file` |
| **Panel Workshop** | *(unchanged)* | `panel_workshop` | Full phase/mode context, open file, build prompts | Phase-gated writes, DEBUG bridge |

### Core invariants

1. **`panel_runner` ≠ `panel_workshop`** — separate `Conversation` rows, session pointers, and chat history for the same `workshopSubfolderId`.
2. **Shared knowledge, not shared chat** — runner injects the same *stable project facts* Workshop uses (summary, bounded specs), but never workshop modes, phase actions, or write tools.
3. **Live state via bridge** — runner always enables Panel Bridge when the WebView is visible; `getState` / `runAction` are the source of truth for “what is on screen now.”
4. **Gallery has no bridge** — list surface is metadata-only.

---

## Context architecture

Three layers per send. Do **not** dump full `script.js` or DOM into every system prompt.

```text
┌─────────────────────────────────────────────────────────────────┐
│ Layer A — Scope & surface (always)                              │
│   panel_gallery | panel_runner + workshopSubfolderId            │
│   Rules: runtime vs build, tool allow/deny list                 │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│ Layer B — Panel project knowledge (read-only, shared w/ Workshop)│
│   PanelProjectKnowledge.build(subfolderId)                      │
│   • project name, phase (expect COMPLETE on runner)             │
│   • subfolder.projectSummary (preferred)                        │
│   • else bounded spec excerpt (WorkshopSpecMarkdown)          │
│   • optional intake summary (WorkshopProjectPreferences)        │
│   • compact FEATURES/FLOW bullets if within token budget        │
│   • runtime file manifest (names only — no full JS bodies)    │
│   • panel_state hint (global scope, non-empty / byte size)    │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│ Layer C — Live runtime (runner only, when WebView registered)   │
│   PanelBridgeRegistry snapshot + call_panel_function on demand │
│   • contextType=gallery, matching workshopSubfolderId          │
│   • registered functions, recent bridge events                  │
│   • getState when user question needs current UI/game state     │
└─────────────────────────────────────────────────────────────────┘
```

### What is shared with Workshop (read-only)

| Data | Source | Used in `panel_workshop` | Used in `panel_runner` | Used in `panel_gallery` |
|------|--------|--------------------------|------------------------|-------------------------|
| `subfolder.projectSummary` | `ContentSummaryService` / Room | Yes | Yes (per project on runner) | No (list-level only) |
| Bounded spec `.md` excerpt | `WorkshopSpecMarkdown.loadBounded` | Yes (fallback) | Yes (fallback if no summary) | No |
| Intake summary | `WorkshopProjectPreferences` | Yes | Yes (short, if present) | No |
| `WorkshopProjectPhase` | `WorkshopProjectPreferences` | Yes (full gating) | Yes (display only; runner expects COMPLETE) | Yes (per card in list) |
| File manifest (names + ids) | `WorkshopProjectContext.formatFileManifest` | Yes | Yes (read-only; ids for `workshop_read_file`) | Names only in gallery list |
| Open editor file excerpt | ViewModel | Yes | **No** | No |
| `PanelPlatformSpec.eidosInstructionsForMode` | Mode/phase | Yes | **No** — use `PanelPlatformSpec.eidosRunnerInstructions` instead |
| Panel Bridge | `PanelBridgeRegistry` | DEBUG only | **Always** when visible | No |
| `workshop_write_file` | Tools | Phase-gated | **Denied** | **Denied** |

**New Kotlin module:** `PanelProjectKnowledge.kt` (single builder used by gallery list context, runner context, and optionally refactored from `buildWorkshopPanelContext` later — v1 may call it only from runner/gallery paths to minimize diff).

```kotlin
// app/src/main/java/com/example/optimalx/data/eidos/PanelProjectKnowledge.kt
object PanelProjectKnowledge {
    suspend fun build(
        context: Context,
        database: AppDatabase,
        subfolderId: Long,
        mode: KnowledgeMode, // GALLERY_CARD | RUNNER | WORKSHOP (future unify)
    ): String
}
```

**Token budget (runner, Layer B):**

| Block | Cap (guideline) |
|-------|-----------------|
| `projectSummary` | Full stored summary (~800 words max from generator) |
| Spec fallback (`WorkshopSpecMarkdown`) | 6_000 chars total (existing constant) |
| Intake summary | 1_500 chars |
| FEATURES + FLOW only | 1_200 chars combined (load single files if present) |
| File manifest | No cap (typically small) |
| **Layer B total target** | ≤ ~8_000 chars before bridge block |

Do **not** inline `index.html` / `script.js` in Layer B. Use `workshop_read_file(query=…)` or `search_semantic` when the model needs implementation detail.

---

## Panel Bridge routing (runner)

**Today:** `PanelBridgeRegistry.pickTarget` filters by visibility; workshop scopes filter `workshopSubfolderId`; first focused wins.

**Required for `panel_runner`:**

```kotlin
// pickTarget when currentScopeType == "panel_runner"
instances
    .filter { it.isVisible }
    .filter { it.workshopSubfolderId == currentSubfolderId }
    .filter { it.contextType == "gallery" }  // runner WebView only
    .sortedWith(focused first, then updatedAt)
    .firstOrNull()
```

`PanelRunnerScreen` already sets `panelContextType = "gallery"` on `WorkshopPreviewPanel` — no WebView change needed.

**`EidosApiClient.buildSystemContext`:**

- For `panel_runner`: **never** set `skipPanelBridge` (unlike `panel_workshop` non-DEBUG).
- Append `buildPanelBridgeContext(subfolderId, panel_runner)` when `currentSubfolderId` is set.
- If bridge inactive (WebView destroyed): append `PanelPlatformSpec.inactivePanelBridgeContextBlock()` + remind user the panel must be visible.

**`enrichToolArguments` for `call_panel_function`:** keep existing subfolderId injection; verify scope type `panel_runner` passes through `RoomToolExecutor`.

---

## Runner prompt contract (new)

Add `PanelPlatformSpec.eidosRunnerInstructions(subfolderId)` — compact runtime rules:

- User is **using** a finished panel in Panel Gallery runner, not building.
- Answer “how do I…?” from project knowledge + live `getState`.
- Use `call_panel_function`: `getState` → plan → `runAction` loop.
- **Do not** call `workshop_write_file`, `workshop_create_file`, or Workshop build kickoffs.
- `workshop_read_file` is **read-only** for `script.js` / `bridge.js` when explaining behavior.
- For layout/code changes: direct user to **Panel Workshop** (`panel_workshop` thread).
- Persisted state: `panel_state` scope `global`; prefer live `getState` over stale DB JSON.

Document in `PROMPT_SYSTEM.md` under `panel_runner`.

---

## Current gap (code)

| Location | Today |
|----------|--------|
| `PanelGalleryScreen` | Back + title — no Eidos |
| `PanelRunnerScreen` | Back + panel name — no Eidos |
| `AppNavigation.kt` | Gallery/runner lack `eidosViewModel` / `openEidosChat` |
| `ConversationScopes` | No `panel_gallery` / `panel_runner` |
| `EidosChatViewModel` | No `setPanelGalleryScope()` / `setPanelRunnerScope()` |
| `EidosApiClient` | No gallery/runner branches; bridge skipped for non-DEBUG workshop only |
| `PanelBridgeRegistry` | No `panel_runner`-specific `pickTarget` |
| `PanelProjectKnowledge` | Does not exist |

**Reference implementations:**

- **UI + lifecycle:** `DumpEditScreen` — Eidos button, `DisposableEffect` scope set/clear.
- **Singleton scope:** `dump_edit` — `ChatSessionPointers`, DAO, ViewModel restore.
- **Per-project scope:** `panel_workshop` — subfolder-scoped pointer (mirror for `panel_runner`).
- **Workshop knowledge:** `buildWorkshopPanelContext` + `ContentSummaryService.formatWorkshopSummaryForPrompt` — extract read-only portions into `PanelProjectKnowledge`.

---

## Decisions locked

| Topic | Decision |
|-------|----------|
| Gallery scope | `panel_gallery` — singleton thread family |
| Runner scope | `panel_runner` — one thread family **per** `workshopSubfolderId` |
| Workshop vs runner threads | **Separate** — never merge conversations |
| Workshop vs runner knowledge | **Shared read-only** via `PanelProjectKnowledge` |
| Workshop vs runner tools | **Disjoint** — runner denies writes and build kickoffs |
| UI pattern | Top-bar **Eidos** → `Routes.EIDOS_CHAT` bottom sheet (match DumpEdit) |
| Runner bridge | **Always** when runner WebView visible; `contextType == gallery` |
| Gallery bridge | **None** |
| `workshop_read_file` on runner | **Read-only** — script/bridge hints only |
| Live UI state | **`getState` on demand** — not inlined every turn |
| `panel_state` in prompt | **Hint only** (e.g. “persisted state present, N bytes”) unless product asks for excerpt |
| Editor custom panel tab | Keep **`subfolder`** scope — do not use `panel_runner` |
| Schema migration | **None** — `scopeType` is already a string on `conversations` |

---

## Scope architecture

```
PanelGalleryScreen
    DisposableEffect → setPanelGalleryScope()
    onEidosClick → openEidosChat()
    Conversation: scopeType = panel_gallery
    ChatSessionPointers: panel_gallery
    Context: PanelGalleryContext (all projects: name, phase, COMPLETE count)

PanelRunnerScreen(workshopSubfolderId)
    DisposableEffect → setPanelRunnerScope(workshopSubfolderId)
    onEidosClick → openEidosChat()
    Conversation: scopeType = panel_runner, subfolderId = workshopSubfolderId
    ChatSessionPointers: panel_runner_{subfolderId}
    Context:
        PanelProjectKnowledge (Layer B)
        + PanelPlatformSpec.eidosRunnerInstructions
        + Panel Bridge (Layer C, contextType=gallery)
    Tools: call_panel_function, workshop_read_file (read)
    Blocked: workshop_write_file, workshop_create_file, build kickoffs, WorkshopEidosModeSelector
```

### Relationship to other scopes

| User action | Scope | Same conversation as… |
|-------------|-------|------------------------|
| Parent page → Eidos | `general` | Main list |
| Gallery → Eidos | `panel_gallery` | Gallery only |
| Runner for X → Eidos | `panel_runner` + X | Runner for X only |
| Workshop editor for X → Eidos | `panel_workshop` + X | Build thread for X |
| Editor custom panel X | `subfolder` | Host job thread |

---

## Phase checklist

### Phase 0 — Architecture doc pass ✅

| ID | Task | Status |
|----|------|--------|
| 0a | `CHAT_UI.md` — gallery + runner rows | ✅ |
| 0b | `DATA_MODEL.md` — scope types | ✅ |
| 0c | `PANEL_GALLERY.md` — Eidos section | ✅ |
| 0d | `PANEL_PLATFORM.md` — runner bridge row | ✅ |
| 0e | `APP_STRUCTURE.md` | ✅ |
| 0f | `UI_PRINCIPLES.md` | ✅ |

**Shipped:** 2026-05-29

---

### Phase 1 — Eidos UI shell (gallery + runner) ✅

| ID | Task | Status |
|----|------|--------|
| 1a | `PanelGalleryScreen` — top bar: Back, “Panels”, **Eidos** (match `DumpEditScreen` / `EditorTopBar` text button) | ✅ |
| 1b | `PanelRunnerScreen` — top bar: Back, panel name, **Eidos** | ✅ |
| 1c | `AppNavigation` — pass `eidosViewModel`, `onEidosClick`, `openEidosChat` to gallery + runner routes | ✅ |
| 1d | `DisposableEffect` — `setPanelGalleryScope()` / `setPanelRunnerScope(id)` on enter; `setGeneralScope()` on dispose | ✅ |

**Files:** `PanelGalleryScreen.kt`, `PanelRunnerScreen.kt`, `AppNavigation.kt`

**Acceptance:** Chat sheet opens from gallery and runner without crash.

**Note:** Ship Phase 1 + 2 together in one PR if temporary `setGeneralScope()` would confuse testers.

---

### Phase 2 — Scope plumbing ✅

| ID | Task | Status |
|----|------|--------|
| 2a | `ConversationScopes.PANEL_GALLERY`, `PANEL_RUNNER` | ✅ |
| 2b | `ConversationScope.PanelGallery`, `ConversationScope.PanelRunner(subfolderId)` in `EidosChatViewModel` | ✅ |
| 2c | `setPanelGalleryScope()`, `setPanelRunnerScope(subfolderId)` | ✅ |
| 2d | `ChatSessionPointers` — get/set/clear for gallery + per-runner id | ✅ |
| 2e | `ConversationDao` — `getRecentPanelGallery`, `getRecentPanelRunner(subfolderId, limit)` | ✅ |
| 2f | Restore/create/persist in `EidosChatViewModel` (mirror DumpEdit + Workshop) | ✅ |
| 2g | `_chatScopeLabel` — `"Panel Gallery"`, `"Panel: {name}"` | ✅ |
| 2h | `scopeTypeToViewedScope` / `viewedScopeToScopeType` | ✅ |
| 2i | Foreground send passes scope via `scopeTypeForApi`; worker uses stored `Conversation.scopeType` | ✅ |

**Tests:** DAO query unit tests; scope round-trip in ViewModel (optional).

**Acceptance:** Gallery reopens same thread; runner A ≠ runner B ≠ workshop A.

---

### Phase 3 — Gallery context ✅

| ID | Task | Status |
|----|------|--------|
| 3a | `PanelGalleryContext.kt` — all workshop projects under Panel Workshop parent: name, phase, COMPLETE vs draft, last updated | ✅ |
| 3b | `EidosApiClient.buildSystemContext` — `panel_gallery` branch + gallery rules | ✅ |
| 3c | Prompt: launch surface only; open runner or Workshop for edits | ✅ (`PanelPlatformSpec.eidosPanelGalleryRules`) |
| 3d | `PROMPT_SYSTEM.md` — `panel_gallery` section | ☐ (follow-up) |

**Acceptance:** “Which panels are ready?” answers from injected list.

---

### Phase 4 — Panel project knowledge + runner context ✅

| ID | Task | Status |
|----|------|--------|
| 4a | **`PanelProjectKnowledge.kt`** — shared read-only builder (summary → spec fallback → intake → FEATURES/FLOW cap → file manifest → `panel_state` hint) | ✅ |
| 4b | **`PanelRunnerContext.kt`** — wraps knowledge + runner surface label + `WorkshopProjectPhase` | ✅ |
| 4c | `EidosApiClient` — `panel_runner` branch: call `PanelRunnerContext.build`; **no** `buildWorkshopPanelContext` | ✅ |
| 4d | `PanelPlatformSpec.eidosRunnerInstructions(subfolderId)` | ✅ |
| 4e | `PROMPT_SYSTEM.md` + `PANEL_GALLERY.md` — document shared knowledge vs separate threads | ☐ (follow-up) |

**Acceptance:** Runner chat explains panel purpose using `projectSummary` without user re-describing the project. Workshop thread unchanged.

**Refactor (optional, same PR or follow-up):** Have `buildWorkshopPanelContext` call `PanelProjectKnowledge` for the summary/spec portion to avoid drift.

---

### Phase 5 — Panel Bridge (runner) ✅

| ID | Task | Status |
|----|------|--------|
| 5a | `EidosApiClient` — never `skipPanelBridge` for `panel_runner`; wire `currentSubfolderId` on runner sends | ✅ |
| 5b | `PanelBridgeRegistry.pickTarget` — `panel_runner` filter: same `workshopSubfolderId` + `contextType == gallery` | ✅ |
| 5c | `EidosChatViewModel` — hide `WorkshopEidosModeSelector` for `panel_runner` and `panel_gallery` | ✅ (workshop scope cleared on leave) |
| 5d | Inactive bridge message when runner WebView not registered | ✅ (`inactivePanelRunnerBridgeContextBlock`) |

**Acceptance:** “Start a new game” on a COMPLETE panel in runner invokes `call_panel_function` on gallery instance; workshop DEBUG preview does not steal calls when runner is focused.

**Manual test matrix:**

| Active surface | Scope | Bridge target |
|----------------|-------|---------------|
| Runner for X | `panel_runner` | gallery / X |
| Workshop preview DEBUG | `panel_workshop` | workshop_preview / X |
| Editor custom tab | `subfolder` | custom_panel / X |
| Gallery list | `panel_gallery` | none |

---

### Phase 6 — Tool policy + history UX (partial)

| ID | Task | Status |
|----|------|--------|
| 6a | `EidosToolCatalog` — runner tool set: `call_panel_function`, `workshop_read_file`; gallery: `search_semantic` only | ✅ |
| 6b | `RoomToolExecutor` — reject `workshop_write_file` / `workshop_create_file` / `workshop_replace_string` when scope is `panel_runner` or `panel_gallery` | ✅ |
| 6c | `TOOL_FUNCTIONS.md` — runner read policy | ☐ |
| 6d | History browser labels for gallery/runner threads | ☐ |
| 6e | Optional: `list_panel_projects` tool for gallery if prompt block insufficient | ☐ |

**Acceptance:** Model cannot patch `script.js` from runner; can read it for explanations.

---

### Phase 7 — Tests + doc closure ☐

| ID | Task | Status |
|----|------|--------|
| 7a | JVM: `PanelProjectKnowledge` — summary preferred over spec fallback; caps respected | ☐ |
| 7b | JVM: `PanelBridgeRegistry.pickTarget` — runner prefers gallery + subfolderId | ☐ |
| 7c | JVM: `EidosToolCatalog` runner denies writes | ☐ |
| 7d | `PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN.md` — follow-up pointer | ☐ |
| 7e | `CHAT_UI.md` — history filtering for gallery/runner | ☐ |
| 7f | This plan — mark phases ✅, **Status: Shipped** | ☐ |

---

## Suggested PR slicing

| PR | Phases | Delivers |
|----|--------|----------|
| **1** | 1 + 2 | Eidos buttons + correct conversation threads |
| **2** | 3 + 4 | Gallery list context + runner knowledge (shared summary) |
| **3** | 5 + 6 | Bridge routing + tool hardening |
| **4** | 7 | Tests + doc closure |

---

## Open questions

| # | Question | Decision |
|---|----------|----------|
| 1 | Gallery ⋮ menu with filtered Chats? | v2 — Eidos button only in v1 |
| 2 | Share chat history between runner and workshop? | **No** |
| 3 | Share project summary between runner and workshop? | **Yes** — read-only via `PanelProjectKnowledge` |
| 4 | Inline `panel_state` JSON in system prompt? | **Hint only** in v1; full JSON only via `getState` |
| 5 | Trigger summary generation from runner if missing? | **No** — direct user to Workshop or run summary there; runner uses spec fallback |
| 6 | Widget general history includes panel scopes? | **No** |

---

## Out of scope (v1)

- Merging `panel_workshop` → `panel_runner` conversations
- Eidos on draft gallery cards without opening Workshop
- Voice widget scoped to gallery/runner
- Full Eidos ⋮ menu on gallery/runner
- Auto `getState` every turn (cost + noise)
- Screenshot / vision of panel DOM

---

## File touch list

| Area | Files |
|------|--------|
| UI | `PanelGalleryScreen.kt`, `PanelRunnerScreen.kt`, `AppNavigation.kt` |
| Scope | `ConversationScopes.kt`, `ChatSessionPointers.kt`, `ConversationDao.kt`, `EidosChatViewModel.kt`, `EidosChatSendWorker.kt` |
| Context | `PanelProjectKnowledge.kt` *(new)*, `PanelGalleryContext.kt` *(new)*, `PanelRunnerContext.kt` *(new)*, `EidosApiClient.kt`, `PanelPlatformSpec.kt` |
| Bridge | `PanelBridgeRegistry.kt` |
| Tools | `EidosToolCatalog.kt`, `RoomToolExecutor.kt` |
| Tests | `PanelProjectKnowledgeTest.kt`, `PanelBridgeRegistryTest.kt`, `EidosToolCatalogPanelRunnerTest.kt` |
| Docs | `PROMPT_SYSTEM.md`, `TOOL_FUNCTIONS.md`, `PANEL_GALLERY.md`, `CHAT_UI.md` |

---

## Success criteria (end-to-end)

1. User opens COMPLETE panel in runner → taps Eidos → scope label shows panel name.
2. User asks “what does this panel do?” → answer uses `projectSummary` or bounded specs without asking them to repeat intake.
3. User asks “reset my game” → Eidos calls `getState` then `runAction` via bridge; no file writes.
4. User asks “change the button color” → Eidos explains changes belong in Panel Workshop; does not call `workshop_write_file`.
5. Same project in Workshop → different conversation thread; build tools still work there.
6. Gallery Eidos lists COMPLETE panels; does not claim live bridge access.
