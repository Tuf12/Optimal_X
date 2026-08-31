# Panel Workshop Overhaul — Implementation Plan

**Status:** Archived — 2026-06-01  
**Reason:** Superseded by unified Update/edit flow and three Eidos modes (Chat / Plan / Edit). Phase 6 “section picker” is not target UX.  
**Active plan:** [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md)  
**Product spec:** [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) (v2)

---

**Status (at archive):** Shipped through Phases 0–5, 7–10; Phase 6 partially misaligned  
**Product spec:** [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) (v2)  
**Modes + phases:** [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) (v2)  
**Platform contract:** [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md)  
**Kimi debug tooling:** ~~[KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) Phase 3.6~~ — QuickJS **removed 2026-06-06**; see Kimi spec Phase 3.6 section
**Change review (shipped):** [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md)  
**Recovery (2026-06-01):** [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md) — canonical flow, three Eidos modes (Chat/Plan/Edit), unified Update cycle; supersedes Phase 6 section-picker UX

Tracks alignment between the **v2 phased Panel Workshop UX** and shipping Kotlin. Architecture docs are updated; this file was the **implementation checklist** for the initial v2 rollout.

---

## Goal

Replace README-first + one-shot **Start Build** + **Sync to code** with a **gated phase machine**:

1. Chat intake (What / Why / How)
2. Generate short spec `.md` files → user accepts
3. **Design build** (layout shell only) → user accepts in Preview
4. Align docs from code (short)
5. **Logic build** → user accepts; **Debug** available (console + bridge; no Kimi QuickJS)
6. Final doc align → **Complete** → **Update** mode (scoped sections)

**Core invariant:** During design/logic iteration, **code is truth**. Spec files update only on **user approval gates** — not on every edit, not via Sync to code.

---

## Current architecture (v1 in code)

| Area | Behavior today | Key files |
|------|----------------|-----------|
| New project | `README.md` pre-filled with three questions | `FolderRepository.createWorkshopProject` |
| Start gate | `isReadmeReady` → **Start Build** | `WorkshopEditorViewModel`, `WorkshopEditorScreen` |
| Build kickoff | One message: all `.md` + all runtime files | `EidosChatViewModel.sendWorkshopBuildKickoff`, `PanelPlatformSpec.workshopBuildKickoffFooter()` |
| Post-build | **Sync to code** when MD digest changes | `canSyncDocsToCode`, `applyPrimaryBuildAction` |
| Modes | Plan / **BUILD** / Edit / Debug / Chat — chips + tool gating | `WorkshopEidosMode.kt`, `WorkshopEidosModeResolver`, `EidosToolCatalog`, `RoomToolExecutor` |
| Prefs | `initial_build_sent`, `doc_digest_at_code_sync` | `WorkshopProjectPreferences` |
| Platform prompts | Single `eidosWorkshopInstructions()` for BUILD | `PanelPlatformSpec.kt` |
| Scaffolds | HTML/CSS/JS with Android layout + bridge patterns | `FolderRepository.WORKSHOP_*_SCAFFOLD`, `WorkshopAndroidLayoutRules.kt` |
| Debug | Available any time after build | Mode chip always enabled when built |
| QuickJS | Removed 2026-06-06 (was Phase 3.6 / Phase 8) |

---

## Target architecture (v2)

| Layer | New / changed |
|-------|----------------|
| **Phase** | `WorkshopProjectPhase` enum + prefs (`INTAKE` … `COMPLETE`, `UPDATE`) |
| **Modes** | Split `BUILD` → `BUILD_DESIGN`, `BUILD_LOGIC`; add `DESIGN` for design review |
| **Intake** | Chat-first; persist `intake_summary` on **Generate specs** |
| **Primary button** | Phase-specific: Generate specs / Accept specs / Build design / Accept design / Build logic / Finish |
| **Doc sync** | **Code → spec** on approval only; remove Sync to code UX + digest-driven BUILD |
| **MD freeze** | `DESIGN_REVIEW`: Eidos cannot read/write `.md` via tools |
| **Debug gate** | Enabled from `LOGIC_BUILD` onward |

See lifecycle diagram in [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md#build-lifecycle-phases).

---

## Decisions locked (product review)

| Topic | Decision |
|-------|----------|
| Intake surface | **Eidos chat**, not editable README prompts |
| Three questions | What / Why / How — Eidos script in Chat mode |
| Spec language | Summarize intent in **human language** — do not rewrite code in human language |
| Spec size (greenfield) | Per-file char caps in [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md#spec-files) |
| Design vs logic | **Two build hops** — shell first, behavior second |
| Doc authority during iteration | **Code** during design/logic review; `.md` frozen to Eidos in design review |
| Doc updates | **Approval gates only** (accept design, finish, update section accept) |
| Sync to code | **Removed** — no digest-driven primary button |
| User changes after complete | Chat with Eidos in Edit/Design/Debug; align docs on section accept |
| Platform guidance | Keep scaffolds + `WorkshopAndroidLayoutRules` + `PanelPlatformSpec`; apply full checklist only when writing runtime files |
| Debug availability | From **logic build** phase onward |
| ~~Kimi QuickJS~~ | **Removed 2026-06-06** — was DEBUG-only; see [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) Phase 3.6 |
| v1 project migration | `initial_build_sent == true` → `WorkshopProjectPhase.COMPLETE` |
| DIFF_REVIEW | Shipped — see [Phase 10](#phase-10--diff_review) and [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) |

---

## Phase checklist

### Phase 0 — Foundation (prefs + enum)

| ID | Task | Status |
|----|------|--------|
| 0a | Add `WorkshopProjectPhase` enum | ✅ |
| 0b | Extend `WorkshopProjectPreferences`: `project_phase`, `intake_summary`, `update_section` | ✅ |
| 0c | Migration: `initial_build_sent` → `COMPLETE`; new projects → `INTAKE` | ✅ |
| 0d | Extend `WorkshopEidosMode`: `BUILD_DESIGN`, `BUILD_LOGIC`, `DESIGN`; deprecate monolithic `BUILD` in new code paths | ✅ |
| 0e | Update `WorkshopEidosMode.fromStored` + chip UI for new modes | ✅ |

**Deliverable:** Phase readable/writable in prefs; existing projects still open.

---

### Phase 1 — New project scaffold + intake UX

| ID | Task | Status |
|----|------|--------|
| 1a | Stop pre-filling README with three questions in `createWorkshopProject` | ✅ |
| 1b | New project: empty/minimal README or no auto-open README; prompt user to open Eidos | ✅ |
| 1c | Intake Chat system prompt / first-message script (What / Why / How) | ✅ |
| 1d | **Generate specs** button (INTAKE → SPEC_REVIEW); store `intake_summary` | ✅ |
| 1e | Remove `isReadmeReady` gate | ✅ |

**Deliverable:** New projects start in chat; no README form.

---

### Phase 2 — Spec generation + review

| ID | Task | Status |
|----|------|--------|
| 2a | `PanelPlatformSpec.eidosGenerateSpecsInstructions()` — caps, human-language rule, layout rules in DESIGN.md | ✅ |
| 2b | Kickoff message for Generate specs (replaces `sendWorkshopBuildKickoff` for specs-only) | ✅ |
| 2c | Plan mode tool gating unchanged for `.md` writes | ✅ (v1) |
| 2d | **Accept specs** button (SPEC_REVIEW → ready for design build) | ✅ |
| 2e | Optional: validate spec char caps in `validateProjectFiles` or post-write lint | ✅ |

**Deliverable:** User can generate and accept short spec files without code writes.

---

### Phase 3 — Design build + design review

| ID | Task | Status |
|----|------|--------|
| 3a | `PanelPlatformSpec.eidosBuildDesignInstructions()` — shell only, use scaffolds | ✅ |
| 3b | **Build design** primary action → BUILD_DESIGN kickoff | ✅ |
| 3c | `PanelPlatformSpec.eidosDesignModeInstructions()` — code-only edits | ✅ |
| 3d | `RoomToolExecutor`: reject `.md` read/write when phase == `DESIGN_REVIEW` | ✅ |
| 3e | `EidosApiClient.buildWorkshopPanelContext`: omit spec bodies in DESIGN_REVIEW; use intake summary | ✅ |
| 3f | **Accept design** button → run doc align (design) → advance to LOGIC_BUILD | ✅ |
| 3g | Preview emphasized in UI during design review | ✅ |

**Deliverable:** Layout shell in Preview; iterate without touching `.md`.

---

### Phase 4 — Doc alignment (approval gates)

| ID | Task | Status |
|----|------|--------|
| 4a | `PanelPlatformSpec.eidosAlignDocsFromCodeInstructions(scope)` — short snapshots, code → spec | ✅ |
| 4b | Kotlin helper: `WorkshopEditorViewModel.alignDocsFromCode(AlignScope.DESIGN \| FINISH \| UPDATE)` | ✅ |
| 4c | Trigger align on Accept design (DESIGN + FLOW), Finish (all `.md`), Update section accept | ✅ |
| 4d | Remove `canSyncDocsToCode`, `doc_digest_at_code_sync` as **UX drivers** (may keep for telemetry only) | ✅ |
| 4e | Remove **Sync to code** button and `sendWorkshopDocsChangedToCode` | ✅ |

**Deliverable:** Docs catch up only when user approves a phase.

---

### Phase 5 — Logic build + logic review + finish

| ID | Task | Status |
|----|------|--------|
| 5a | `PanelPlatformSpec.eidosBuildLogicInstructions()` | ✅ |
| 5b | **Build logic** primary action → BUILD_LOGIC kickoff | ✅ |
| 5c | **Finish** button → final doc align → `COMPLETE` | ✅ |
| 5d | Debug mode chip **disabled** until `LOGIC_BUILD` | ✅ |
| 5e | Revise `WorkshopEidosModeResolver.suggestMode` — phase-aware; no digest → BUILD | ✅ |
| 5f | Deprecate `sendWorkshopBuildKickoff` / `workshopBuildKickoffFooter` (monolithic build) | ✅ |

**Deliverable:** Full panel with behavior; project marked complete.

---

### Phase 6 — Update mode

> **Superseded for UX:** Section picker removed in favor of unified Update/edit cycle — see [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md) Phase 1.

| ID | Task | Status |
|----|------|--------|
| 6a | Update section picker: Specs / Design / Logic | ⚠️ superseded — dead dialog; use recovery Phase 1 |
| 6b | Same chat → edit → **Accept** pattern per section | ✅ |
| 6c | Doc align on section accept (scoped) | ✅ |
| 6d | `.md` freeze during Update → Design (same as design review) | ✅ |

**Deliverable:** Post-complete maintenance without Sync to code.

---

### Phase 7 — Workshop UI + Eidos wiring

| ID | Task | Status |
|----|------|--------|
| 7a | Top bar: phase label + single primary action (replace Start Build / Sync) | ✅ |
| 7b | Eidos sheet: phase badge; mode chips filtered by phase | ✅ |
| 7c | `EidosChatViewModel`: phase-aware kickoffs (specs, design, logic, align) | ✅ |
| 7d | `EidosApiClient.buildWorkshopPanelContext(phase, mode, intakeSummary)` | ✅ |
| 7e | `EidosToolCatalog.toolsForWorkshopMode(mode, phase)` | ✅ |
| 7f | Pass `workshopProjectPhase` through `EidosChatSendWorker` | ✅ |

**Deliverable:** End-to-end UX matches [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md).

---

### Phase 8 — Kimi QuickJS ❌ Removed (2026-06-06)

Was cross-linked to [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) Phase 3.6. Shipped briefly; product removed Formula `moonshot/quickjs:latest` from `KimiFormulaToolService` and all workshop prompts.

| ID | Task | Status |
|----|------|--------|
| 8a–8c | Wire + gate + debug prompts | ~~✅~~ removed |
| 8d | Manual test | N/A |

**Deliverable (historical):** Kimi could sandbox-test JS during logic review debug — **no longer in codebase.**

---

### Phase 9 — Documentation + cleanup

| ID | Task | Status |
|----|------|--------|
| 9a | [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) v2 | ✅ |
| 9b | [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) v2 | ✅ |
| 9c | [PANEL_PLATFORM.md](../architecture/PANEL_PLATFORM.md) aligned with v2 | ✅ (this pass) |
| 9d | This plan | ✅ |
| 9e | Update [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](../implementation/PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) workshop rows (README cold-start → intake summary) | ❌ |
| 9f | Update [app/docs/README.md](../README.md) architecture blurb if needed | ❌ |

---

### Phase 10 — DIFF_REVIEW

| ID | Task | Status |
|----|------|--------|
| 10a | Pending change set for workshop writes | ✅ `pending_change_sets` + `pending_change_items` (schema v18, migration 17→18). One open set per workshop subfolder; per-source items with baseline hash / proposed hash / unified diff. |
| 10b | Accept/reject before disk write in Edit / Update / Debug / Review phases (build phases auto-accept; chat/plan rejected) | ✅ `WorkshopReviewPolicy` + `WorkshopWriteRouter` route all `workshop_write_file` / `workshop_create_file` / `workshop_replace_string` calls. Build phases auto-accept with `system`-authored checkpoints (`"Design build"` / `"Logic build"`); review phases queue. `DiffReviewScreen` (per-file accept / reject, accept all / reject all) + `Review N` badge in chat + top bar. |
| 10c | Patch-style targeted edits | ✅ `workshop_replace_string` with `not_found` / `ambiguous` errors and snippet hints; prompt prefers it for targeted edits. |
| 10d | Checkpoint history + restore | ✅ `content_checkpoints` + `content_patches` for both workshop files and notes. Shared `ContentHistorySheet` reachable from the workshop top bar (per-file) and the note editor top bar (per-note). Restore writes the blob back and appends a `user`-authored `"Restored to seq N"` checkpoint. |

Detailed task log: [DIFF_REVIEW_IMPLEMENTATION_PLAN.md](../implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md). Architecture: [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md).

**Deferred:** Eidos editing notes still applies directly (review queue is workshop-only). Hunk-level accept / reject. Multiple open pending sets per scope.

---

## Recommended implementation order

1. **Phase 0** — enums + prefs + migration (safe foundation)
2. **Phase 1 + 7a** — new project UX + primary button shell
3. **Phase 2** — Generate specs + Accept specs
4. **Phase 3 + 4** — Design build/review + align on accept
5. **Phase 5** — Logic build + Finish + remove Sync to code
6. **Phase 7** — Full Eidos wiring (can overlap 2–5)
7. ~~**Phase 8** — QuickJS (Kimi Phase 3.6)~~ removed 2026-06-06
8. **Phase 6** — Update mode
9. **Phase 10** — DIFF_REVIEW when ready

---

## Test plan (manual)

| # | Scenario | Pass |
|---|----------|------|
| 1 | Create new workshop project | Opens to chat/intake; README not a three-question form |
| 2 | Intake chat → Generate specs | Five short `.md` files; no runtime writes |
| 3 | Accept specs → Build design | HTML/CSS/stub JS only; Preview shows layout |
| 4 | Design review chat | Eidos does not read/write `.md`; layout edits work |
| 5 | Accept design | DESIGN.md updated briefly from code; no full code regen from docs |
| 6 | Build logic | Calculations/bridge behavior added |
| 7 | Debug before logic build | Debug chip disabled |
| 8 | Debug during logic review | Console + bridge tools; not in Plan/Chat |
| 9 | Finish | All `.md` short snapshots; phase COMPLETE |
| 10 | Update → Design section | Same freeze + align on accept |
| 11 | No Sync to code button | Never shown |
| 12 | Legacy project (`initial_build_sent`) | Opens as COMPLETE; Edit/Debug work |

---

## Kotlin file index

| Area | Files |
|------|-------|
| Phase enum | `data/eidos/WorkshopProjectPhase.kt` (**new**) |
| Mode enum | `data/eidos/WorkshopEidosMode.kt` |
| Resolver | `data/eidos/WorkshopEidosModeResolver.kt` |
| Prefs | `data/preferences/WorkshopProjectPreferences.kt` |
| Platform spec + prompts | `data/eidos/PanelPlatformSpec.kt` |
| Layout rules | `data/eidos/WorkshopAndroidLayoutRules.kt` |
| API context | `data/eidos/EidosApiClient.kt` |
| Tools | `data/eidos/EidosToolCatalog.kt`, `data/eidos/RoomToolExecutor.kt` |
| Send worker | `data/eidos/EidosChatSendWorker.kt` |
| Chat VM | `ui/eidos/EidosChatViewModel.kt` |
| Editor VM / screen | `ui/workshop/WorkshopEditorViewModel.kt`, `WorkshopEditorScreen.kt` |
| Mode chips | `ui/workshop/WorkshopEidosModeSelector.kt` |
| Project create | `data/repository/FolderRepository.kt` |
| ~~Kimi QuickJS~~ | `KimiFormulaToolService.kt` | **Removed 2026-06-06** |
| Composite / preview | `ui/workshop/PanelHtmlComposer.kt`, `WorkshopPreviewPanel.kt` |

---

## Open questions

1. **Intake summary generation:** Eidos writes summary on Generate specs tap, or Kotlin extracts last N turns from chat?
2. **Accept gates:** Explicit button only, or also “I’m happy with this” detected in chat?
3. **Empty spec files at create:** Keep placeholder STRUCTURE/FEATURES/FLOW/DESIGN files or create only on Generate specs?
4. **Char cap enforcement:** Soft (prompt) vs hard (reject write in executor)?
5. **Phase-specific chat threads:** One conversation per project vs new conversation per phase?

---

## Changelog

| Date | Change |
|------|--------|
| 2026-05-25 | Initial implementation plan for Panel Workshop v2 phased overhaul |
| 2026-06-01 | Archived; active work moves to PANEL_WORKSHOP_RECOVERY_PLAN.md |
