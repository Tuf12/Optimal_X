# Panel Workshop — Recovery Plan

**Status:** Active — canonical flow + phased fixes (2026-06-01)  
**Audience:** Product, implementers, Eidos prompt authors  
**Supersedes:** [archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md](../archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md) Phase 6 “section picker” vs [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) unified Update/edit  
**Related:** [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md), [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md)

This document is the **single source of truth** for how Panel Workshop **should** behave after recovery, what is wrong today, and **in what order** to fix it.

---

## Canonical product flow

### Greenfield (new project)

```text
NEW PROJECT
    │
    ▼
┌─────────┐   Generate specs   ┌──────────────┐   Accept specs   ┌──────────────┐
│ INTAKE  │ ─────────────────► │ SPEC REVIEW  │ ───────────────► │ DESIGN BUILD │
│ Chat    │                    │ Chat/Plan/   │                  │ (primary:    │
│ only    │                    │ Edit/Accept  │                  │ Build design)│
└─────────┘                    └──────────────┘                  └──────┬───────┘
                                                                        │
                    Doc align (specs ← code) ◄── Accept design ◄──────┘
                                                                        │
                                                                        ▼
                              ┌──────────────┐   Accept logic   ┌──────────────┐
                              │ LOGIC BUILD  │ ───────────────► │   COMPLETE   │
                              │ (primary:    │                  └──────────────┘
                              │ Build logic) │
                              └──────┬───────┘
                                     │
              Doc align (all .md ← code) ◄── Accept logic
                                     │
                                     ▼
                              ┌──────────────┐
                              │ LOGIC REVIEW │
                              │ Chat/Plan/   │
                              │ Edit/Accept  │
                              └──────────────┘
```

Between every **Accept** gate, the user may use **Chat**, **Plan**, or **Edit** (see [Eidos modes](#eidos-modes-three-chips)). **Code is truth** during review; **spec `.md` files update only on Accept** (one doc-align pass per gate).

### Maintenance (complete panel) — continuous cycle

```text
COMPLETE ──► Update (enters UPDATE) ──► Chat / Plan / Edit
                                              │
                    Plan: draft IMPLEMENTATION_PLAN.md (+ optional spec tweaks)
                                              │
                    Accept implementation plan (gate — see Phase 4.5)
                                              │
                    Build plan (chat banner or top bar — executes plan in Edit, Diff Review)
                                              │
                    … more Edit / Build plan cycles as needed …
                                              │
                    Accept update (after pending diffs == 0) ──► doc align ──► COMPLETE
    ▲                                                                              │
    └──────────────────────────────────────────────────────────────────────────────┘
```

**Update/edit is not a one-shot.** The user can repeat **COMPLETE → Update → … → Complete** indefinitely. There is **no** Specs / Design / Logic section picker in the UI—one maintenance flow only.

**Implementation plan (UPDATE):** **Plan** mode authors `IMPLEMENTATION_PLAN.md` (Phase 4). **Execution** of that plan is a separate user action (**Accept plan** then **Build plan**)—not automatic when the file exists. See [Phase 4.5](#phase-45--implementation-plan-execution-update--plan-mode).

---

## Two layers (do not mix them up)

| Layer | What it is | User sees | Drives |
|-------|------------|-----------|--------|
| **Project phase** | Lifecycle gate | Top-bar label + **one primary action** (Generate specs, Accept specs, Build design, Accept design, Build logic, Accept logic, **Update**, Accept update) | When doc align runs; which review rules apply |
| **Eidos mode** | How Eidos may act **within** the current phase | **Three chips only:** Chat · Plan · Edit | Tool allowlist + system prompt |

**Build design** and **Build logic** are **primary-button actions** (greenfield kickoffs), **not** mode chips.

**Build plan** (UPDATE only) executes an accepted `IMPLEMENTATION_PLAN.md` via a chat/top-bar kickoff (Phase 4.5)—also **not** a mode chip.

---

## Eidos modes (three chips)

| Mode | Purpose | Tools | Must not |
|------|---------|-------|----------|
| **Chat** | Discuss, explain, compare options; intake (what / why / how) | `search_semantic`, `workshop_read_file` (read-only) | Any write, `call_panel_function`, nudging user to “fix” or change code |
| **Plan** | Revise specs; optional **phased implementation plan** artifact | Read tools + **`.md` writes only** (`workshop_write_file` / `workshop_create_file` / `workshop_replace_string` on spec files) | Runtime writes (`.html`, `.css`, `.js`) |
| **Edit** | Implement layout and behavior from plan + chat | Full workshop toolkit (read, write, replace, bridge when phase allows) | Writing `.md` during review phases where specs are frozen (align runs on Accept only) |

### Chat mode — behavioral contract

- **Read-only.** No file writes, no bridge calls.
- **Conversation-first.** Answer from search hits, manifest, intake summary, and thread—not from rewriting the project.
- **No pipeline pressure.** Do not repeatedly tell the user to switch mode or tap Accept unless they ask how to proceed.
- Tool budget: search (+ optional one targeted read), then **plain-text reply**—not multi-round “investigation” loops.

### Plan mode — behavioral contract

- **Specs and plans in `.md` only.**
- May **read** runtime files (semantic search + `workshop_read_file`) to understand current code when updating docs or drafting a plan.
- May write: `README.md`, `STRUCTURE.md`, `FEATURES.md`, `FLOW.md`, `DESIGN.md`, and optionally a dedicated plan file (see Phase 4).
- **Phased implementation plan:** Plan mode creates or updates `IMPLEMENTATION_PLAN.md` (Phase 4 ✅). **Executing** the plan after user approval is Phase 4.5 (Accept plan + Build plan kickoff in UPDATE)—see below.

### Edit mode — behavioral contract

- **Code is truth.** Edit `index.html`, `style.css`, `script.js`, `bridge.js` as needed.
- **Diff review:** In review phases and UPDATE, writes go to the **pending queue**; disk updates only after user **Accept** on Diff Review ([DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md)).
- **No spec writes** during design review, logic review, or UPDATE until the user taps the phase **Accept** button (then Kotlin kicks doc align in Plan).

### Debug (optional fourth chip — logic only)

Product default above is **three chips**. If Preview/bridge debugging remains necessary, expose **Debug** only from **LOGIC_BUILD** onward as a fourth chip (console buffer + `call_panel_function`; no Kimi Formula sandbox). Otherwise fold debug behavior into **Edit** prompts for logic review. **Recovery default: three chips; Debug deferred unless manual testing demands it.**

---

## Accept gates and doc align

| User taps | Phase transition | Doc align scope | Eidos mode for align pass |
|-----------|------------------|-----------------|---------------------------|
| **Accept specs** | SPEC_REVIEW → DESIGN_BUILD | None | — |
| **Accept design** | DESIGN_REVIEW → LOGIC_BUILD | DESIGN.md (+ FLOW if needed) | Plan (automated kickoff) |
| **Accept logic** | LOGIC_REVIEW → COMPLETE | All spec `.md` | Plan (automated kickoff) |
| **Accept update** | UPDATE → COMPLETE (after diffs cleared) | All spec `.md` (or digest-skipped if unchanged) | Plan (automated kickoff) |
| **Accept implementation plan** | (stays in UPDATE) | None — marks plan approved for build | — |
| **Build plan** | (stays in UPDATE) | None — runtime via Edit kickoff + Diff Review | — |

**Invariant:** Spec files are **not** updated on every Edit turn—only after **Accept update** (and only once per gate, unless align is skipped by digest—see Phase 2).

**Plan file invariant:** `IMPLEMENTATION_PLAN.md` is edited in **Plan** mode only. **Build plan** reads it and changes **runtime** files in **Edit** (queued in UPDATE until Diff Review accept). The plan file itself is not rewritten during Build plan unless the user returns to Plan.

**Diff review invariant:** User sees proposed code changes **before** Accept; Accept on the workshop bar means “I’m done with this batch” + doc align, not “accept each file” (per-file accept is on Diff Review screen).

---

## Panel Gallery vs Panel Workshop

| Surface | Role | Eidos scope | Writes |
|---------|------|-------------|--------|
| **Panel Workshop** | Build, **Preview** (test), **Update** (maintenance) | `panel_workshop` | Phase/mode gated |
| **Panel Gallery** | Browse / **run** complete panels | `panel_gallery` | None |
| **Panel Runner** | Live runtime for one panel | `panel_runner` | Read-only workshop files + bridge |

Same files on disk; **never** the same chat thread. Workshop must not say **Use** for runtime — that is Gallery / custom tab. Recovery work stays in **Workshop** unless explicitly noted.

---

## Gap analysis (today vs canonical)

| Area | Canonical | Today (problem) |
|------|-----------|-----------------|
| **User flow** | Linear gates + continuous Update cycle | Phase machine exists but UPDATE uses vestigial `WorkshopUpdateSection`; dead `UpdateSectionDialog`; “choose a section” copy with no picker |
| **Mode chips** | Chat · Plan · Edit | Also DESIGN, DEBUG, PLAN; BUILD_* hidden but prompts still reference Design/Debug; resolver defaults COMPLETE/UPDATE to **Edit** |
| **Chat** | Read-only, no fix pressure | Writes blocked in executor but prompts/heuristics push Edit/Accept; up to 4 tool rounds |
| **Plan** | `.md` + optional implementation plan | Authoring OK; **Accept plan** + **Build plan** in UPDATE (Phase 4.5 ✅) |
| **Edit** | Full tools + diff queue | Works in principle; models don’t always surface “queued for review” |
| **Build** | Primary button only (greenfield) | BUILD_DESIGN/BUILD_LOGIC exist; **Build plan** for UPDATE not wired |
| **UPDATE + plan** | Plan → Accept plan → Build plan → Diff Review → Accept update | User must ask Eidos in chat to “follow the plan” today |
| **Accept update** | After diffs cleared → align → Complete | **Finish update** can fire full doc align with zero edits; always `FINISH` scope |
| **Doc align** | Once per Accept | Repeated full-spec reads; no digest short-circuit |
| **Semantic search** | Prefer over full reads | Often empty until read/write; `SEMANTIC_SEARCH.md` empty |
| **Docs** | One story | PANEL_WORKSHOP v2 says unified Update; overhaul Phase 6 still lists section picker ✅ |

---

## Phased recovery implementation

Work in order. Each phase should be shippable and manually testable before the next.

### Phase 0 — Align documentation (0.5 day) ✅

**Goal:** Stop implementers and agents from following conflicting specs.

| ID | Task | Status |
|----|------|--------|
| 0a | Update [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) — unified Update cycle; three mode chips; Accept update wording | ✅ |
| 0b | Update [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) — deprecate DESIGN/DEBUG as default chips; map BUILD_* to primary actions | ✅ |
| 0c | Overhaul plan archived at [archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md](../archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md); stub redirects here | ✅ |
| 0d | Architecture docs link to this file (not overhaul plan) | ✅ |
| 0e | **Preview vs use:** Workshop **Preview** (test) + **Update** (maintenance) only — no “Use / Update”; runtime use = Panel Gallery / custom tab | ✅ |

**Acceptance:** A new reader sees one flow diagram, three modes, and clear separation: workshop builds/previews/updates; Gallery runs.

---

### Phase 1 — Project phase + primary actions (1–2 days) ✅

**Goal:** Top bar and phase transitions match the canonical greenfield + Update cycle.

| ID | Task | Status |
|----|------|--------|
| 1a | **UPDATE:** Remove user-facing `WorkshopUpdateSection` (enum kept for legacy prefs / doc-align only) | ✅ |
| 1b | Delete `UpdateSectionPickerDialog` | ✅ |
| 1c | Primary action in UPDATE: **Accept update** | ✅ |
| 1d | **Accept update:** two-tap confirm; doc align only when pending diffs == 0 and user tapped twice | ✅ |
| 1e | No doc align on entering UPDATE | ✅ |
| 1f | Unified `UpdateEditPlaceholder` copy | ✅ |
| 1g | **Update** on Complete → UPDATE, default mode **Chat** (no Use/Update dialog) | ✅ |
| 1h | DESIGN_BUILD → DESIGN_REVIEW only after `pendingDesignReviewAfterBuild` + kickoff complete | ✅ |

**Acceptance:**

- New project follows intake → … → complete with correct button labels.
- Complete → Update → edit → Diff Review → Accept update → Complete, repeatable.
- No section picker anywhere.

---

### Phase 2 — Doc align efficiency (1 day) ✅

**Goal:** One meaningful align per Accept; no token burn when nothing changed.

| ID | Task | Status |
|----|------|--------|
| 2a | Before align: compare runtime+spec fingerprint to last successful align for scope; skip Eidos if unchanged | ✅ `WorkshopDocAlignGate` |
| 2b | FINISH/UPDATE align: all runtime files; prompt enforces update-only-when-diverged | ✅ |
| 2c | Update auto-finish after diffs uses same `alignDocsFromCode` (skip-aware); no duplicate kickoff while awaiting | ✅ |
| 2d | Neutral kickoff/feedback (“if needed”, “only files that diverge”) | ✅ |

**Acceptance:** Accept design/logic/update does not always spawn a long Plan thread when specs already match.

---

### Phase 3 — Three mode chips + tool gating (2–3 days)

**Goal:** UI and Kotlin enforce Chat · Plan · Edit only.

| ID | Task | Status |
|----|------|--------|
| 3a | `WorkshopProjectPhase.selectorModes()` → only `CHAT`, `PLAN`, `EDIT` (INTAKE: Chat only) | ✅ |
| 3b | `EidosToolCatalog.toolsForWorkshopMode`: CHAT = search + read; PLAN = spec writes; EDIT = full panel tools | ✅ |
| 3c | `WorkshopFileAccessPolicy`: PLAN reads/writes spec `.md` (freeze applies to **Edit** only); PLAN rejects runtime | ✅ |
| 3d | Selector = Chat/Plan/Edit only; legacy DEBUG/DESIGN/BUILD → chip via `normalizeToUserChip` (tools + prefs) | ✅ |
| 3e | `WorkshopEidosModeResolver`: Chat-first defaults; debug keywords → Edit | ✅ |
| 3f | BUILD_* kickoff internal only; chip UI via `workshopEidosModeChipSelection` | ✅ |

**Acceptance:** Eidos sheet shows three chips; tool calls match table in [Eidos modes](#eidos-modes-three-chips).

---

### Phase 4 — Prompts + Plan implementation artifact (2 days)

**Goal:** Models behave; Plan can author phased plans.

| ID | Task | Status |
|----|------|--------|
| 4a | Phase-aware **Edit** via `eidosPhaseEditInstructions` (design / logic / update) | ✅ |
| 4b | Chat: conversational rules; no Accept/mode nag; short content policy | ✅ |
| 4c | `WORKSHOP_CHAT_MAX_TOOL_ROUNDS` = **2** | ✅ |
| 4d | `IMPLEMENTATION_PLAN.md` scaffold + Plan prompts | ✅ |
| 4e | Diff Review notice in `reviewNoticeFor` + every review-phase Edit prompt | ✅ |
| 4f | Queued tool results append Diff Review hint | ✅ `WorkshopWriteRouter` |

**Acceptance:** Chat turn feels conversational; Edit proposals always mention Diff Review; Plan can write IMPLEMENTATION_PLAN.md without touching JS.

---

### Phase 4.5 — Implementation plan execution (UPDATE + Plan mode) ✅

**Goal:** In **UPDATE** (and legacy complete panels entering maintenance), the user can approve `IMPLEMENTATION_PLAN.md` and kick off execution with one button—same UX pattern as Diff Review in chat—not by re-explaining the plan in Chat every time.

**Product flow (canonical):**

```text
UPDATE phase
  1. Plan chip  → draft/revise IMPLEMENTATION_PLAN.md (+ specs if needed)
  2. User taps **Accept implementation plan** (top bar or inline when plan file exists)
  3. Chat shows **Build plan** banner (like “Review N changes”) when plan accepted + no blocking kickoff in flight
  4. **Build plan** → Eidos kickoff (internal BUILD_PLAN or EDIT + session flag) executes next plan phase(s) on runtime files
  5. Proposals → Diff Review → user accepts/rejects per file
  6. Repeat 3–5 until plan done or user switches to ad-hoc Edit
  7. **Accept update** → doc align → COMPLETE
```

**Not in scope for 4.5:** Auto-running the full plan without user tapping Build plan each batch; greenfield **Build design** / **Build logic** (remain Phase 5). Optional later: Accept plan during SPEC_REVIEW before first code build.

| ID | Task | Files (primary) |
|----|------|-----------------|
| 4.5a | **Prefs:** `implementation_plan_accepted` (bool), optional `implementation_plan_build_cursor` (phase index or last-built heading hash); reset on new plan write or entering UPDATE | `WorkshopProjectPreferences.kt` |
| 4.5b | **Detect plan ready:** non-empty `IMPLEMENTATION_PLAN.md` on disk; expose `StateFlow` in `WorkshopEditorViewModel` | `WorkshopEditorViewModel.kt`, `PanelPlatformSpec.kt` |
| 4.5c | **Accept implementation plan** — top-bar action in UPDATE when plan exists and not yet accepted; clears “needs accept” until plan file changes materially | `WorkshopEditorScreen.kt`, `WorkshopEditorViewModel.kt` |
| 4.5d | **Build plan** chat banner in workshop Eidos sheet (mirror `DiffReviewBanner`); visible when UPDATE + plan accepted + `pendingChangeCount == 0` (or allow with warning if diffs open) | `EidosChatScreen.kt`, `EidosChatViewModel.kt` |
| 4.5e | **Kickoff:** `sendWorkshopBuildFromPlanKickoff(subfolderId)` — prefill message, set internal mode `BUILD_PLAN` or `EDIT` + `WorkshopEidosSession.buildKickoff=PLAN` | `EidosChatViewModel.kt`, `WorkshopEidosSession.kt` |
| 4.5f | **Prompts:** `eidosBuildFromImplementationPlanInstructions()` — read plan, execute current phase only, runtime tools, Diff Review notice, do not rewrite plan file | `PanelPlatformSpec.kt`, `EidosApiClient.kt` (`workshopContentPolicy`) |
| 4.5g | **Tool policy:** Build-plan kickoff = runtime writes only (like BUILD_LOGIC); Plan mode unchanged | `WorkshopFileAccessPolicy.kt`, `EidosToolCatalog.kt` |
| 4.5h | **Review policy:** BUILD_PLAN / build-plan kickoff in UPDATE → queue via `WorkshopReviewPolicy` (same as Edit in UPDATE) | `WorkshopReviewPolicy.kt`, `WorkshopWriteRouter.kt` |
| 4.5i | **Optional top-bar** secondary “Build plan” when plan accepted (in addition to chat banner) for discoverability | `WorkshopEditorScreen.kt` |
| 4.5j | **Architecture docs:** maintenance cycle + Plan execution in [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) | architecture/*.md |
| 4.5k | **Tests:** prefs gate, policy allows Plan md in UPDATE, kickoff mode routing, banner visibility predicates | `*Test.kt` under `app/src/test/.../eidos`, `.../revision` |

**Acceptance:**

- Existing panel: UPDATE → Plan writes `IMPLEMENTATION_PLAN.md` → **Accept implementation plan** → **Build plan** in chat → runtime edits queued → Diff Review → Accept update → Complete.
- Plan mode never blocked from `.md` in UPDATE; Build plan never writes `.md`.
- User is not required to type “execute the implementation plan” in Chat.

**Depends on:** Phase 3 (Plan tool gating), Phase 4 (plan file + prompts). **Should ship before or with Phase 5** for maintenance panels; Phase 5 remains greenfield-only.

---

### Phase 5 — Build kickoffs (primary actions only) ✅

**Goal:** Greenfield **Build design** / **Build logic** are clearly separate from mode chips. **Does not** implement Build plan (Phase 4.5 — shipped separately).

| ID | Task |
|----|------|
| 5a | Primary button **Build design** sets session flag `buildKickoff=DESIGN`, sends kickoff, mode temporarily EDIT or internal BUILD_DESIGN (not a chip) | `WorkshopEditorViewModel`, `EidosChatViewModel` |
| 5b | Same for **Build logic** |
| 5c | `WorkshopReviewPolicy`: build kickoffs auto-accept; review phases queue | verify unchanged |
| 5d | `coerceModeForPhase`: only coerce during active kickoff flag; after kickoff, user returns to Chat/Edit | `WorkshopEidosModeResolver` |

**Acceptance:** User never needs to select “Build design” from a chip; button triggers one-shot build with Preview as review surface.

---

### Phase 6 — Semantic search + read consistency ✅

**Goal:** Eidos uses search before full-file reads.

| ID | Task |
|----|------|
| 6a | On workshop editor open: `SemanticMaterializer.indexSubfolder(subfolderId)` | `WorkshopEditorViewModel.init` |
| 6b | Populate [SEMANTIC_SEARCH.md](../architecture/SEMANTIC_SEARCH.md) — workshop `scopeType=local_first`, `scopeId=subfolderId` |
| 6c | `workshop_read_file`: always return JSON envelope `{ "content": "...", "truncated": bool, ... }` for parser consistency | `RoomToolExecutor`, `ContentSectionRetriever` |
| 6d | Reindex on diff **accept** (already on DirectWriteApplier—verify) | `DiffReviewViewModel` |

**Acceptance:** `search_semantic` returns hits for workshop files on a typical project without manual read first.

---

### Phase 7 — Regression test plan (manual)

| # | Scenario | Pass |
|---|----------|------|
| 1 | New project: intake Chat only, cannot write files | ☐ |
| 2 | Generate specs → Plan writes `.md` → Accept specs | ☐ |
| 3 | Build design → Preview updates → Edit queues diffs → accept diffs → Accept design → short doc align | ☐ |
| 4 | Build logic → Edit fixes behavior → Accept logic → Complete | ☐ |
| 5 | Complete → **Update** → Chat discusses without write tools | ☐ |
| 6 | Update → Edit changes CSS → Diff Review → Accept update → Complete | ☐ |
| 7 | Second Update cycle works | ☐ |
| 8 | Gallery Eidos cannot write workshop files | ☐ |
| 9 | Plan creates IMPLEMENTATION_PLAN.md; Edit does not modify it unless asked | ☐ |
| 10 | UPDATE: Accept implementation plan → Build plan banner → kickoff → Diff Review → Accept update | ☐ |
| 11 | Revising plan after accept resets “needs Accept plan” (or warns before Build plan) | ☐ |

---

## Mapping: earlier “recovery diagnosis” → this plan

| Prior recommendation | This plan |
|----------------------|-----------|
| P0 Stabilize UPDATE/COMPLETE | **Phase 1** |
| P1 Chat + diff cues + doc align | **Phases 2, 3, 4** |
| P1b Plan execution in UPDATE | **Phase 4.5** |
| P2 Design reconcile + semantic | **Phases 1h, 6** |
| P3 Existing panel manual fix | **Phase 7** (#10–11) + user Accept update after edits |

---

## Out of scope (this recovery)

- Panel Gallery / Runner feature work ([PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](./PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md) Phases 5–7 unless blocking)
- Chess-piece AgentByte loop
- Kotlin auto-save polling for panel state ([PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md](./PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md) Phase 4 backfill)
- Hunk-level diff accept / multiple pending sets per project

---

## Implementation order (summary)

1. **Phase 0** — docs  
2. **Phase 1** — phase/UI/UPDATE cycle  
3. **Phase 3** — three modes + tools (can overlap 1)  
4. **Phase 4** — prompts + IMPLEMENTATION_PLAN authoring  
5. **Phase 4.5** — Accept plan + Build plan (UPDATE execution) — **before relying on maintenance panels**  
6. **Phase 2** — doc align digest  
7. **Phase 5** — greenfield Build design / Build logic kickoff cleanup  
8. **Phase 6** — semantic + JSON reads  
9. **Phase 7** — manual QA  

---

## Document changelog

| Date | Notes |
|------|--------|
| 2026-06-01 | Initial recovery plan from product clarification: canonical flow, three modes, continuous Update cycle, phased fixes |
| 2026-06-01 | Phase 0 + Phase 1 shipped in Kotlin and architecture docs |
| 2026-06-01 | Phase 0e: Remove Use/Update; Preview vs Gallery terminology in docs + UI |
| 2026-06-01 | Phase 2: `WorkshopDocAlignGate` fingerprint skip + neutral align copy |
| 2026-06-01 | Phase 3: three chips + `WorkshopFileAccessPolicy` (Plan vs Edit freeze) |
| 2026-06-01 | Phase 4: conversational Chat, `eidosPhaseEditInstructions`, `IMPLEMENTATION_PLAN.md`, Diff Review hints |
| 2026-06-01 | Phase 4.5 specified: Accept implementation plan + Build plan kickoff (UPDATE); file checklist |
| 2026-06-01 | Phase 4.5 ✅: Accept plan prefs/hash, top bar + chat Build plan banner, BUILD_PLAN kickoff, tests |
| 2026-06-01 | Phase 5 ✅: `WorkshopBuildKickoff` flag; one-shot BUILD_DESIGN/BUILD_LOGIC/PLAN; coerce only during kickoff |
| 2026-06-01 | Phase 6 ✅: workshop open index, SEMANTIC_SEARCH.md, workshop_read_file JSON envelope, diff accept reindex verified |
