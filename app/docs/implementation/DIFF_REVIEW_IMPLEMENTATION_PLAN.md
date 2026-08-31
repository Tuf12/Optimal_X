# DIFF_REVIEW + Patch Editing — Implementation Plan

**Status:** Shipped — 2026-05-26 (Phases 0–7 complete)
**Architecture spec:** [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) — rewritten in Phase 7 to match the shipped pipeline
**Parent plan:** [PANEL_WORKSHOP_OVERHAUL_PLAN.md](../archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md) Phase 10 (archived); active workshop work: [PANEL_WORKSHOP_RECOVERY_PLAN.md](./PANEL_WORKSHOP_RECOVERY_PLAN.md)
**Related:** [EDITOR_AND_PANELS.md](../architecture/EDITOR_AND_PANELS.md), [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md), [TOOL_FUNCTIONS.md](../reference/TOOL_FUNCTIONS.md)

Combined implementation of:

1. **DIFF_REVIEW for Panel Workshop** — user-facing accept/reject of Eidos-proposed changes (workshop scope only in v1)
2. **Patch-style editing tools** — let the LLM author targeted edits instead of rewriting whole files (new, not in original spec)

Both share infrastructure (unified diff compute + pending-change pipeline), so they ship together. The foundation is built **note-capable from day one** so the same pipeline can be reused for notes in a later phase, but no note-side tool wiring or UI is in scope here.

---

## Goal

Stop two patterns that hurt today:

| Today | After |
|-------|-------|
| `workshop_write_file` overwrites disk silently in review/edit phases | Writes in review/edit/debug/update modes queue **pending changes** routed through user review |
| LLM regenerates entire file to change two lines | LLM can call `workshop_replace_string(oldString, newString)` for targeted edits |

**Core invariant:** In **review-style** workshop modes (`DESIGN_REVIEW`, `LOGIC_REVIEW`, `EDIT`, `DEBUG`, `UPDATE`), Eidos tool calls **never touch disk directly** — they go through `PendingChangeService`. In **build-style** modes (`BUILD_DESIGN`, `BUILD_LOGIC` kickoffs), writes are auto-accepted because the user reviews via the Preview, not a per-file diff.

Note-side undo replacement and note pending review are **out of scope for this phase**. The Kotlin foundation (`PendingChangeService`, `CheckpointRepository`, `ContentDiff`) is intentionally generic over `sourceType = note | workshop_file` so a later phase can wire notes in without schema changes.

---

## Decisions locked

| Topic | Decision |
|-------|----------|
| Scope | Combined — DIFF_REVIEW pipeline **and** patch-style tools (workshop only) |
| Patch tool style | `workshop_replace_string(fileReferenceId, oldString, newString)` — Cursor-style exact match |
| Failure recovery | Patch failure → return current file snippet near intended location so LLM can retry |
| Modes requiring review | `DESIGN_REVIEW`, `LOGIC_REVIEW`, `EDIT`, `DEBUG`, `UPDATE`; `CHAT` / `PLAN` remain read-only |
| Modes that auto-accept | `BUILD_DESIGN` and `BUILD_LOGIC` kickoffs — user reviews these via the Preview, not per-file diffs |
| Trust toggle | Out of scope for v1 (review behavior is purely phase-driven) |
| Note undo replacement | **Deferred to a later phase** (workshop-first); foundation is note-capable |
| Note pending review | **Deferred to a later phase** |
| Spec `.md` writes | Same pending pipeline (where review-mode applies); existing `DESIGN_REVIEW` freeze still blocks `.md` reads/writes |

---

## Architecture summary

```
LLM tool call (workshop_write_file / workshop_replace_string / workshop_create_file)
        │
        ▼
RoomToolExecutor
        │
        ▼
WorkshopReviewPolicy.shouldReview(phase, mode)
   ├── false (BUILD_DESIGN / BUILD_LOGIC kickoff)
   │       → write directly, create checkpoint, reindex
   │
   └── true (DESIGN_REVIEW / LOGIC_REVIEW / EDIT / DEBUG / UPDATE)
           │
           ▼
   PendingChangeService.propose(...)
      • reads working copy + last checkpoint
      • computes proposedContent (full body, even for replace_string)
      • computes unifiedDiff
      • inserts pending_change_set + pending_change_item
           │
           ▼  tool returns "Proposal queued for review: foo.js"
   LLM finishes turn
           │
           ▼
   EidosBottomSheet shows "Review N changes" banner
           │
           ▼
   DiffReviewScreen → Accept / Reject (all or per-file)
           │
           ▼
   Accept:
      • write proposedContent to disk
      • reindex semantic
      • create checkpoint
      • reload open file in WorkshopEditorViewModel
```

Both code paths converge on the same `CheckpointRepository` so the timeline stays continuous across build and review phases. The diff is computed by Kotlin in one place; the LLM only needs to author the change in whichever form is cheapest for its situation.

---

## Phase checklist

### Phase 0 — Foundation (data layer)

| ID | Task | Notes |
|----|------|-------|
| 0a | Bump `AppDatabase.SCHEMA_VERSION` 17 → 18 | |
| 0b | Add entities: `ContentCheckpoint`, `ContentPatch`, `PendingChangeSet`, `PendingChangeItem` | `sourceType` is `workshop_file` for v1; `note` reserved for later phase |
| 0c | DAOs: `ContentCheckpointDao`, `ContentPatchDao`, `PendingChangeDao` | |
| 0d | Migration 17 → 18 + androidTest covering schema + a basic insert | Follow existing migration test style (`AppDatabaseMigration16To17Test`) |
| 0e | Retention helpers: keep max 5 checkpoints per `(sourceType, sourceId)`, oldest dropped on insert (keep seq 0 baseline) | |
| 0f | `ContentDiff.kt` — Myers unified diff compute + apply (in-house, no native dep) | Plain-text inputs; line-based |

**Deliverable:** Schema migrated, diff utility unit-tested. No tool/UI wiring yet.

---

### Phase 1 — `PendingChangeService` + `CheckpointRepository` + review policy

| ID | Task | Notes |
|----|------|-------|
| 1a | `CheckpointRepository.baselineIfMissing(sourceType, sourceId, content)` | Lazy first-open baseline |
| 1b | `CheckpointRepository.createCheckpoint(sourceType, sourceId, content, author, label?, conversationId?)` | Inserts checkpoint, prunes oldest, inserts patch from previous |
| 1c | `WorkshopReviewPolicy.shouldReview(phase, mode)` | Returns true for review-style modes (see Decisions); false for build kickoffs |
| 1d | `PendingChangeService.proposeWorkshopFile(fileReferenceId, conversationId, proposedContent)` | Reads working copy, computes diff, inserts pending set + item; scopeType = `workshop_project` |
| 1e | `PendingChangeService.proposeWorkshopCreate(subfolderId, conversationId, fileName, content)` | Diff from empty file |
| 1f | `PendingChangeService.accept(itemId)` / `acceptAll(setId)` / `reject(itemId)` / `rejectAll(setId)` | Accept writes disk + reindex + checkpoint; reject is no-op on working copy |
| 1g | No-op detection: if `proposedContent == workingCopy`, skip insert and return synthetic success | Saves UI noise |
| 1h | Concurrency guard: on accept, recompare working copy hash to `baseCheckpointId` content hash; if user edited since, surface "Working copy changed — re-review?" instead of silent overwrite | |
| 1i | `DirectWriteApplier` shared helper used by both auto-accept (build modes) and `accept(...)` so the post-write side-effects (disk + reindex + checkpoint) live in one place | |

**Deliverable:** Service can be called by tests with no UI/tool changes yet. Build-mode and review-mode writes share one applier.

---

### Phase 2 — New patch tool: `workshop_replace_string`

| ID | Task | Notes |
|----|------|-------|
| 2a | Add to `EidosToolCatalog` (workshop write tool set), `requiresConfirmation = false`, `isModifying = true` | Review gate is the confirmation surface |
| 2b | `RoomToolExecutor.workshopReplaceString(args)` reads working copy, attempts `content.replace(oldString, newString)` (single occurrence — fail if `oldString` appears 0× or ≥2×) | Mirrors how `editNoteSection` validates `contains`, but stricter on duplicates to match Cursor semantics |
| 2c | On `oldString` not found OR multiple matches: return `ToolExecutionResult.Failure` with current-file snippet near `oldString` candidate (or `±5 lines` around first 80-char fuzzy-match line); include line numbers so LLM can re-anchor | Format: `oldString not found in foo.js. Current file (lines 23–35):\n23 \| ...` |
| 2d | On success: build `proposedContent`, call `PendingChangeService.proposeWorkshopFile(...)` | |
| 2e | Tool result: `"Proposal queued for review: foo.js (1 hunk)"` | |
| 2f | Tool description in `EidosToolCatalog`: emphasizes oldString must be **unique** and include enough surrounding context | Prevents wrong-occurrence edits |

**Deliverable:** LLM can author targeted edits. Failures give it enough context to retry without a full re-read.

---

### Phase 3 — Route workshop modifying tools through the service

All workshop write tools route through a single `applyOrPropose(...)` helper that consults `WorkshopReviewPolicy`:

| ID | Task | Notes |
|----|------|-------|
| 3a | `workshop_write_file` → `applyOrPropose` | Build-mode: direct write via `DirectWriteApplier`; review-mode: `proposeWorkshopFile` |
| 3b | `workshop_create_file` → `applyOrPropose` (create-form) | Build-mode: direct create; review-mode: `proposeWorkshopCreate` (diff from empty) |
| 3c | `workshop_replace_string` → `applyOrPropose` | Computes `proposedContent` first by string replacement, then policy decides |
| 3d | Tool result strings: `"Updated <fileName>"` for direct writes (unchanged); `"Proposal queued for review: <fileName>"` for review-mode | LLM sees same messaging it does today in build modes |
| 3e | Note tools — **shipped separately (2026-06)** | See **Note Diff Review** section below; catalog consolidated to `write_note` + `note_replace_string` |

**Deliverable:** All workshop write tools converge on one policy + one applier. Build phases still feel as fast as today; review phases queue.

---

### Phase 4 — System prompts and tool guidance

| ID | Task | Notes |
|----|------|-------|
| 4a | `PanelPlatformSpec.eidosDesignModeInstructions()` + `eidosBuildLogicInstructions()` (logic build still encourages replace_string after the first pass) + edit/debug prompts: add patch-first guidance | "Prefer `workshop_replace_string` for any targeted change. Use `workshop_write_file` only for scaffolds or rewrites > ~70% of the file." |
| 4b | `eidosBuildDesignInstructions()`: keep `workshop_create_file` + `workshop_write_file` as the primary tools (initial scaffold writes are auto-accepted) | |
| 4c | Review-mode prompts add a sentence: "Your edits are queued for the user to review and accept before they go live. Describe what you proposed; do not assume it is applied." | Stops "I have updated …" hallucinations in review phases |
| 4d | Tool description for `workshop_replace_string`: include uniqueness requirement + recommendation to grab ≥3 lines of context in `oldString` | |

**Deliverable:** Models pick the right tool per phase and stop claiming the file is updated when it's still pending.

---

### Phase 5 — Review UI (workshop only) — **COMPLETED 2026-05-26**

| ID | Task | Notes |
|----|------|-------|
| 5a | `EidosChatViewModel`: `workshopPendingChangeSetId` + `workshopPendingChangeCount` flows derived from `_workshopScopeSubfolderId` and `pendingChangeDao.observeOpenSetForScope` → `observeItems` | Uses `flatMapLatest`; class is `@OptIn(ExperimentalCoroutinesApi)` |
| 5b | `EidosChatScreen`: `DiffReviewBanner` ("Review N proposed changes") appended at the end of the message list when a workshop scope has pending items and a send isn't in flight; tap navigates to `DiffReviewScreen` | New `onOpenDiffReview` callback threaded from `AppNavigation` |
| 5c | `WorkshopEditorScreen` top bar: clickable "Review N" badge before the Eidos button when `WorkshopEditorViewModel.pendingChangeCount > 0`; tap opens `DiffReviewScreen` for the current project | |
| 5d | `DiffReviewScreen` + `DiffReviewViewModel` (under `ui/workshop/review/`): per-file cards (filename + NEW/MODIFIED/ACCEPTED/REJECTED status badge); tap to expand the unified diff; per-card Accept / Reject; toolbar Accept all / Reject all | `DiffReviewViewModel` constructs its own `PendingChangeService` from shared DAOs (state is in Room, so service instances are stateless) |
| 5e | Inline highlight: additions green, deletions red, hunk headers blue-grey, file headers dim; `DmMonoFamily` throughout; horizontal scroll for long lines | |
| 5f | Auto-reload: `WorkshopEditorViewModel` observes the accepted-status count for its open set in an `init` block and calls `reloadFromDiskAfterExternalWrite()` whenever it rises, so the in-memory buffer refreshes from disk after every accept | No new entry point on `WorkshopEditorViewModel` was needed — the existing `reloadFromDiskAfterExternalWrite()` already wires through `diskRevision` and the LaunchedEffect-based editor refresh |
| 5g | `AppNavigation`: new `WORKSHOP_DIFF_REVIEW = "workshop_diff_review/{subfolderId}"` route; both `EidosChatScreen` and `WorkshopEditorScreen` navigate via `Routes.workshopDiffReview(subfolderId)` | |

**Deliverable:** End-to-end review-mode Eidos turn → diff → accept → file updated. ✅

---

### Phase 6 — Workshop checkpoint history + restore — **COMPLETED 2026-05-26**

| ID | Task | Notes |
|----|------|-------|
| 6a | `WorkshopHistorySheet` (Modal bottom sheet) reachable from a History icon button on `WorkshopTopBar` (visible only when a file is open and not in preview mode). Lists checkpoints (newest first) with author badge (BASE / YOU / EIDOS / BUILD), label, timestamp, sequence, and per-row line-delta vs working copy. Tap a row to expand its diff inline; **Restore** writes the checkpoint blob back via `DirectWriteApplier` so a fresh `user`-authored `"Restored to seq N (<original label>)"` checkpoint is appended | `WorkshopEditorViewModel.checkpointsForCurrentFile` (flow from `contentCheckpointDao.observeForSource`) + `restoreCheckpoint(checkpointId)`; auto-reload reuses the existing `reloadOpenFileFromDisk()` path |
| 6b | `WorkshopWriteRouter.resolveAutoAcceptAttribution(...)` switches the auto-accept author/label based on `phaseProvider()`: `DESIGN_BUILD` → `system` + `"Design build"`, `LOGIC_BUILD` → `system` + `"Logic build"`. All other auto-accept paths preserve the prior `eidos` author + caller label (or fall back to a default like `"Initial create"`). | Pre-build state is preserved automatically because `DirectWriteApplier` already snapshots the prior on-disk content as the sequence-0 baseline before writing the new content |
| 6c | Note checkpoint history wired end-to-end. … | `snapshotNoteCheckpoint(...)` deduped by hash. **Note Diff Review queue** shipped 2026-06 (see Note Diff Review section). |

**Shared infrastructure introduced:** `ui/workshop/components/DiffBlock.kt` (extracted from `DiffReviewScreen`) and `ui/workshop/components/ContentHistorySheet.kt` (renamed from `WorkshopHistorySheet`, source-agnostic) — both the workshop file editor and the note editor render identical history + diff UI.

**Deliverable:** Workshop and note editors share a single checkpoint history + restore surface. ✅

---

### Phase 7 — Tests + docs cleanup — **COMPLETED 2026-05-26**

| ID | Task | Notes |
|----|------|-------|
| 7a | androidTest: `AppDatabaseMigration17To18Test` | ✅ Shipped in Phase 0. |
| 7b | androidTest: `PendingChangeServiceTest` — propose/accept/reject/no-op detection/concurrency guard | ✅ Shipped in Phase 1. |
| 7c | androidTest: `RoomToolExecutorWorkshopWriteTest` + `RoomToolExecutorWorkshopReplaceStringTest` — covers all 3 workshop write tools: auto-accept in build mode, pending in review mode. Extended in this phase to assert the Phase 6b system-authored `"Design build"` / `"Logic build"` labels. | ✅ |
| 7d | unit: `ContentDiffTest` — diff round-trip + apply + hash correctness, including empty content edge cases | ✅ Shipped in Phase 0. |
| 7e | `workshop_replace_string` — success, not-found-with-snippet, ambiguous-multiple-match | ✅ Covered end-to-end by `RoomToolExecutorWorkshopReplaceStringTest` (the closest equivalent to a unit test for a tool that depends on Room + FS). |
| 7f | **Rewrite** `app/docs/architecture/DIFF_REVIEW.md` to match shipped behavior | ✅ Status now **Shipped**. New doc covers architecture, data model, write/review flows, concurrency guard, note checkpoints, UI surfaces, tool surface, retention, deferred items, and a "where to look" map. |
| 7g | Update `app/docs/reference/TOOL_FUNCTIONS.md` for `workshop_replace_string` and pending behavior | ✅ New **Panel Workshop tools** section covering all three workshop tools + the auto-accept/review routing summary. |
| 7h | Update `PANEL_WORKSHOP_OVERHAUL_PLAN.md` Phase 10 row to ✅ | ✅ Phase 10 expanded to 4 rows (10a–10d) marking pending-set, review pipeline, patch tool, and checkpoint history all shipped. |

**Pre-existing test failures** (unrelated to DIFF_REVIEW): `ContentSummaryChunksCodecTest.encode_decode_round_trip` and two `MemoryRolloverServiceAllowlistTest` rows continue to fail and are tracked separately. All DIFF_REVIEW-touching tests pass.

**Deliverable:** Green tests + docs in sync. ✅

---

## Recommended implementation order

1. **Phase 0** — schema + diff util (safe foundation, fully tested before anything depends on it)
2. **Phase 1** — `PendingChangeService` + `CheckpointRepository` + `WorkshopReviewPolicy` (no UI yet, unit-testable)
3. **Phase 2** — new `workshop_replace_string` tool (single tool, plugs into Phase 1)
4. **Phase 3** — route existing tools through one `applyOrPropose` helper
5. **Phase 4** — prompts (cheap; can land alongside Phase 3 once tools are wired)
6. **Phase 5** — review UI (the most novel surface)
7. **Phase 6** — checkpoint history drawer
8. **Phase 7** — tests + docs (continuous; final pass at the end)

---

## Kotlin file index

| Area | Files |
|------|-------|
| Schema | `data/db/AppDatabase.kt` |
| Models | `data/model/ContentCheckpoint.kt` (**new**), `ContentPatch.kt` (**new**), `PendingChangeSet.kt` (**new**), `PendingChangeItem.kt` (**new**) |
| DAOs | `data/dao/ContentCheckpointDao.kt` (**new**), `ContentPatchDao.kt` (**new**), `PendingChangeDao.kt` (**new**) |
| Diff util | `data/revision/ContentDiff.kt` (**new**) |
| Services | `data/revision/CheckpointRepository.kt` (**new**), `data/revision/PendingChangeService.kt` (**new**), `data/revision/WorkshopReviewPolicy.kt` (**new**), `data/revision/DirectWriteApplier.kt` (**new**) |
| Tool layer | `data/eidos/RoomToolExecutor.kt`, `data/eidos/EidosToolCatalog.kt` |
| Prompts | `data/eidos/PanelPlatformSpec.kt` |
| Chat VM | `ui/eidos/EidosChatViewModel.kt`, `ui/eidos/EidosBottomSheet.kt` |
| Workshop VM/UI | `ui/workshop/WorkshopEditorViewModel.kt`, `WorkshopEditorScreen.kt` |
| Review UI | `ui/revision/DiffReviewScreen.kt` (**new**), `ui/revision/HistoryDrawer.kt` (**new**) |

---

## Open questions (resolvable during Phase 1)

1. **`call_panel_function`:** runs at runtime, no disk write — skip review pipeline entirely. *Confirmed: skip.*
2. **`UPDATE` mode review scope:** review per section (specs/design/logic) or per file? *Tentative: per file; section scoping is enforced upstream by the existing Update section picker.*
3. **Build-mode checkpoint cadence:** one checkpoint per tool call, or one per build turn? *Tentative: one per accepted set / one per kickoff turn — multiple writes in a single Eidos turn collapse to one checkpoint labelled by phase.*
4. **Note pipeline:** shipped 2026-06 — markdown in DB, `NoteWriteRouter`, `SCOPE_SUBFOLDER` queue, shared `DiffReviewScreen`, editor + chat banners. See [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md).

---

## Note Diff Review — **shipped 2026-06**

| Phase | Deliverable |
|-------|-------------|
| 0 | Editor sync: flush before Eidos send, reload after, guarded dispose |
| 1 | Catalog: `write_note` (set-or-append) + `note_replace_string`; removed `append_note`, `edit_note_section` |
| 2 | `NoteWriteRouter`, `DirectWriteApplier.applyNoteWrite`, `PendingChangeService.proposeNote`, `SCOPE_SUBFOLDER` |
| 3 | Shared `DiffReviewScreen` / `DiffReviewViewModel` (`scopeType`); `note_diff_review` route; editor + Eidos chat badges; reload on accept |
| 4 | Docs + `EidosSystemPromptLayers.NOTE_WRITE_RULES` |

---

## Test plan (manual)

| # | Scenario | Pass |
|---|----------|------|
| 1 | `BUILD_DESIGN` kickoff: Eidos writes shell HTML/CSS/stub JS | Direct write, no review banner, Preview updates, system checkpoint labelled "Design build" recorded |
| 2 | `BUILD_LOGIC` kickoff: Eidos writes behavior | Direct write, no review banner, system checkpoint "Logic build" recorded |
| 3 | `DESIGN_REVIEW` mode: Eidos calls `workshop_replace_string` on `script.js` | Pending banner appears, disk unchanged until Accept |
| 4 | Same, but `oldString` not found | Tool result includes 5-line snippet from current file with line numbers |
| 5 | Same, but `oldString` matches twice | Tool result rejects with "ambiguous — provide more context" + snippet |
| 6 | `EDIT` mode: `workshop_write_file` on full file | Diff queued; Accept writes; Preview refreshes |
| 7 | Concurrent edit: user types in editor, then accepts pending | Warning surfaced; user can re-review |
| 8 | Reject all | No disk changes; conversation continues |
| 9 | History drawer on workshop file | Lists checkpoints (system/eidos/user); restore creates "Restored to …" checkpoint |
| 10 | `.md` write attempt during `DESIGN_REVIEW` | Existing freeze still wins; pending pipeline never reached |
| 11 | `UPDATE` mode: Eidos proposes a logic change | Pending; section-scoped review surfaces |
| 12 | Migration: project with existing files opens in `COMPLETE` phase, edits in `EDIT` mode | Baseline checkpoint created lazily on first edit; review pipeline kicks in |

---

## Risk register

| Risk | Mitigation |
|------|-----------|
| LLM gets confused by "queued" results and resubmits | Phase 4 prompts explicitly state queued = success from the model's POV; tool result phrasing chosen to avoid retry triggers |
| `oldString` matches the wrong occurrence | Strict uniqueness check (Phase 2b); prompt guidance to include ≥3 lines of context |
| User accumulates dozens of pending sets across conversations | Phase 1: enforce **one open set per scope** (note subfolderId / workshop subfolderId); new propose merges into open set or supersedes it |
| Migration runs on huge note bodies and stalls | Baseline checkpoint is lazy (created on first open / first propose), not in migration |
| Diff compute slow on large workshop files (>200 KB) | Myers is O(ND); cap at 512 KB per `DIFF_REVIEW.md` — above that, store proposed + skip patch, show full-file replace UI |

---

## Changelog

| Date | Change |
|------|--------|
| 2026-05-26 | Initial plan combining DIFF_REVIEW spec with patch-style workshop tools |
| 2026-05-26 | Narrowed scope: workshop only in v1; notes deferred. Broadened auto-accept to all build-phase writes. Added `WorkshopReviewPolicy` + `DirectWriteApplier`. Architecture `DIFF_REVIEW.md` flagged for rewrite after Phase 7. |
| 2026-05-26 | Phases 0–5 shipped: schema + diff util, propose/accept pipeline, `workshop_replace_string`, tools routed through `WorkshopWriteRouter`, prompt updates, review UI (per-file accept/reject + accept all / reject all). |
| 2026-05-26 | Phase 6 shipped: shared `ContentHistorySheet` reachable from workshop and note editors; restore via `CheckpointRepository`; build-mode auto-accept now produces `system`-authored `"Design build"` / `"Logic build"` checkpoints. Notes participate in the timeline; review queue landed 2026-06. |
| 2026-06-20 | Note Diff Review shipped: `write_note` / `note_replace_string` through `NoteWriteRouter`; `SCOPE_SUBFOLDER` queue; `note_diff_review` route; docs + prompt layer `NOTE_WRITE_RULES`. |
