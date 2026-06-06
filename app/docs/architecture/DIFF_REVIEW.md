# DIFF_REVIEW.md

**Change review, checkpoints, and patch-style edits — Panel Workshop + Notes**

| Field | Value |
|-------|-------|
| **Status** | **Shipped** — Phases 0–6 of `app/docs/implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md` |
| **Audience** | Product, Kotlin implementers, Eidos prompt authors |
| **Related** | [EDITOR_AND_PANELS.md](EDITOR_AND_PANELS.md), [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md), [WORKSHOP_MODES.md](WORKSHOP_MODES.md), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) (build run = auto-accept, no mid-loop queue), [DATA_MODEL.md](DATA_MODEL.md), [TOOL_FUNCTIONS.md](../reference/TOOL_FUNCTIONS.md) |
| **Replaces** | Eidos overwriting working copies with no user review and no rollback path |

---

## What shipped

Eidos no longer writes blindly to workshop files. Every workshop write now flows
through a **policy-aware router** that either:

- **Auto-accepts** the change (build phases) — disk is updated, a checkpoint is
  appended, and the editor reloads from disk. This keeps the build loop tight:
  the visual preview *is* the review.
- **Queues the change for review** (edit / debug / update / review phases) —
  the disk is untouched, the proposed bytes live in `pending_change_items`, and
  the user accepts or rejects on the **Diff Review** screen.

In addition, every accepted or auto-accepted write produces a **sparse
checkpoint** with a unified-diff **patch** describing what changed since the
prior checkpoint. The user can browse history and restore any checkpoint via a
shared **Content History** bottom sheet.

Notes participate in the same checkpoint timeline (without a review queue —
direct saves only) via a parallel wiring in `EditorViewModel`.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Eidos tool call (workshop_write_file | workshop_create_file |          │
│                   workshop_replace_string)                              │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  ▼
                      ┌─────────────────────────┐
                      │  WorkshopWriteRouter    │   consults WorkshopReviewPolicy
                      └────────────┬────────────┘   (phase + mode → review?)
                                   │
                ┌──────────────────┴──────────────────┐
                ▼                                     ▼
       ┌────────────────┐                  ┌───────────────────┐
       │ Auto-accept    │                  │ Queue for review  │
       │ (build phases) │                  │ (edit / update)   │
       └──────┬─────────┘                  └─────────┬─────────┘
              ▼                                      ▼
   ┌──────────────────────┐               ┌────────────────────────┐
   │ DirectWriteApplier   │               │ PendingChangeService   │
   │ • write file         │               │ • snapshot baseline    │
   │ • reindex            │               │ • write proposed bytes │
   │ • baseline + patch   │               │ • surface unified diff │
   └──────────┬───────────┘               └────────────┬───────────┘
              ▼                                        ▼
   ┌──────────────────────┐               ┌────────────────────────┐
   │ content_checkpoints  │ ◀──── accept ─│ DiffReviewScreen       │
   │ content_patches      │               │ (per-file accept /     │
   └──────────────────────┘               │  reject / accept all)  │
                                          └────────────────────────┘
```

### Key components

| Component | Role |
|-----------|------|
| `WorkshopReviewPolicy` | Pure function: `(phase, mode) → ReviewMode` (`AUTO_ACCEPT`, `REVIEW`, or `REJECT`). |
| `WorkshopWriteRouter` | Single entry point for all workshop file mutations from tool calls. Consults the policy and delegates. |
| `DirectWriteApplier` | Performs the physical disk write, reindex, and checkpoint creation. Used by auto-accept *and* by accept-from-review. |
| `PendingChangeService` | Stores proposed content under `pending_change_items`, computes the diff against the on-disk baseline, and detects concurrent manual edits. |
| `CheckpointRepository` | Appends content checkpoints and unified-diff patches. Prunes per source by retention (5/source). |
| `ContentDiff` | Pure-Kotlin LCS-based unified diff + apply + SHA-256 hash. Used everywhere a diff or hash is needed. |

---

## Data model

Three new tables (schema v18; migration `17→18` in `AppDatabase.kt`).

### `content_checkpoints`

A sparse snapshot of a file's content at a meaningful event (build kickoff,
accepted change, restore, manual save).

| Column | Type | Notes |
|--------|------|-------|
| `id` | INTEGER PK | |
| `sourceType` | TEXT | `workshop_file`, `note`, ... |
| `sourceId` | INTEGER | `FileReference.id` for workshop files; `Subfolder.id` for notes. |
| `sequence` | INTEGER | Monotonic, `0` = baseline. |
| `author` | TEXT | `system` (build), `eidos`, `user`. |
| `label` | TEXT? | Short human-readable tag (`Design build`, `Accepted`, `Restored to seq 2`, ...). |
| `conversationId` | INTEGER? | Eidos conversation that produced the change. |
| `contentBlob` | TEXT | Full content at this checkpoint. |
| `contentHash` | TEXT | SHA-256 of `contentBlob`. |
| `createdAt` | INTEGER | Epoch ms. |

Indexed on `(sourceType, sourceId, sequence)`.

### `content_patches`

A unified-diff patch from checkpoint `N` → `N+1` for the same source. Allows
lightweight history without storing every full snapshot.

| Column | Type | Notes |
|--------|------|-------|
| `id` | INTEGER PK | |
| `sourceType` / `sourceId` | TEXT / INTEGER | Same as checkpoint. |
| `fromCheckpointId` / `toCheckpointId` | INTEGER FK | |
| `unifiedDiff` | TEXT | Computed by `ContentDiff.unifiedDiff`. |
| `createdAt` | INTEGER | Epoch ms. |

Cascading delete from `content_checkpoints`.

### `pending_change_sets` + `pending_change_items`

Open proposals awaiting user review. A **set** groups all proposals for a
scope (one open set per workshop subfolder at a time); each **item** is one
file's proposed content.

| `pending_change_sets` | Notes |
|-----------------------|-------|
| `scope` | `workshop_project` today. |
| `scopeId` | `Subfolder.id` for workshop projects. |
| `state` | `open` / `accepted` / `rejected` / `superseded`. |

| `pending_change_items` | Notes |
|------------------------|-------|
| `setId` | FK → set. |
| `sourceType` | `workshop_file` (existing file) or `workshop_new_file` (no FileReference yet). |
| `sourceId` | `FileReference.id` or `Subfolder.id` (for new files). |
| `fileName` | Required for `workshop_new_file`; redundant-but-cached for existing files. |
| `baselineHash` | SHA-256 of the on-disk content at propose time; used by the **concurrency guard**. |
| `proposedBlob` | The bytes Eidos wants to write. |
| `proposedHash` | SHA-256 of `proposedBlob`. |
| `unifiedDiff` | Diff baseline → proposed, computed once at propose time. |
| `status` | `pending` / `accepted` / `rejected` / `conflict`. |
| `isNewFile` | Convenience flag for the UI badge. |

---

## Write flow — auto-accept (build phases)

1. Tool call arrives at `RoomToolExecutor` (`workshop_write_file`,
   `workshop_create_file`, or `workshop_replace_string`).
2. The executor builds a `WorkshopWriteRouter` and calls
   `applyOrProposeWrite(...)` / `applyOrProposeCreate(...)`.
3. `WorkshopReviewPolicy.shouldReview(phase, mode)` returns **false** for
   `DESIGN_BUILD` / `LOGIC_BUILD`.
4. The router does a no-op check (`current == proposed` → return early without
   churning the timeline).
5. `resolveAutoAcceptAttribution(...)` assigns the checkpoint:
   - `DESIGN_BUILD` → `author = system`, `label = "Design build"`.
   - `LOGIC_BUILD` → `author = system`, `label = "Logic build"`.
   - All other auto-accept entrypoints fall back to `eidos` + the caller label.
6. `DirectWriteApplier`:
   - Snapshots the prior on-disk bytes as the **baseline** if none exists.
   - Writes the new bytes to disk.
   - Re-runs the semantic indexer.
   - Calls `CheckpointRepository.createCheckpoint(...)` which appends a new
     checkpoint *and* stores the unified diff from the previous checkpoint.
7. The editor's `diskRevision` ticks and `WorkshopEditorViewModel` reloads its
   in-memory buffer from disk.

## Write flow — review (edit / update / debug / review phases)

1. Steps 1–2 above.
2. The policy returns **true**.
3. The router calls `PendingChangeService.proposeWorkshopFile(...)` (or
   `proposeWorkshopCreate(...)` for brand-new files).
4. The service:
   - Reads the on-disk content (or empty string for a new file).
   - If proposed == current, returns `NoChange` (no row written).
   - Otherwise computes `baselineHash`, `proposedHash`, and the unified diff.
   - Finds-or-creates the open `pending_change_sets` row for the scope.
   - Either inserts a new `pending_change_items` row or **supersedes** any
     existing pending item for the same source (the LLM can iterate within one
     turn; the last proposed bytes win).
5. The tool returns a success result with a message like
   `"Proposal queued for review: index.html"`. The router does **not** touch
   disk.
6. `EidosChatViewModel` and `WorkshopEditorViewModel` both observe the open
   pending set and surface a **"Review N proposed changes"** banner / badge.
7. The user taps through to `DiffReviewScreen`.

### Accept

`PendingChangeService.acceptItem(itemId)`:

- Re-reads the on-disk bytes and compares against `baselineHash`. If the file
  changed manually since propose time, the item is marked **conflict** and the
  user sees a "manual edit detected" message instead of an overwrite.
- For an existing file: calls `DirectWriteApplier.applyWorkshopWrite(...)` with
  `author = eidos`, `label = "Accepted"`. A new checkpoint + patch are
  appended.
- For a new file (`workshop_new_file`): calls
  `DirectWriteApplier.applyWorkshopCreate(...)` which inserts the
  `FileReference`, writes to disk, and creates the baseline checkpoint under
  `workshop_file` (the `workshop_new_file` row becomes irrelevant once
  accepted).
- Marks the item `accepted`. When all items in the set are resolved, the set's
  `state` advances to `accepted`.

### Reject

`PendingChangeService.rejectItem(itemId)`:

- No disk write; no checkpoint.
- Marks the item `rejected`. Same set-resolution logic.

`DiffReviewScreen` also supports **Accept all** and **Reject all** toolbar
actions that loop over the open set.

---

## Concurrency guard

`PendingChangeService` re-hashes the on-disk content on accept and compares
against the `baselineHash` snapshot taken at propose time. If the user edits
the file manually after Eidos proposes, the item flips to **conflict** and the
accept is refused. This prevents silent overwrites without sacrificing the
inline-review UX.

---

## Note checkpoints

Notes share `content_checkpoints` and `content_patches` with workshop files —
the only difference is `sourceType = "note"`, `sourceId = subfolderId`. The
**review queue is not used for notes** (yet): saves and `undo` / `redo` write
directly to the working copy, and `EditorViewModel.snapshotNoteCheckpoint(...)`
appends a checkpoint at each save (deduped by hash so identical save events
don't churn the timeline).

Restore writes the checkpoint blob back via `EditorRepository.saveNoteContent`,
emits to the existing `restoreContent` `SharedFlow` so the rich-text editor
replaces its buffer, and appends a fresh `user`-authored
`"Restored to seq N (...)"` checkpoint so the timeline shows the restore as a
new event.

Pending-review for notes (Eidos editing a note → user reviews diff before it
lands) is **deferred** — the data layer is ready, but `write_note` and
`append_note` still apply directly.

---

## UI surfaces

| Surface | Where | Behavior |
|---------|-------|----------|
| **Diff Review banner** | End of `EidosChatScreen` message list | Visible when the active workshop scope has open pending items. Tap → `DiffReviewScreen`. |
| **Review N badge** | `WorkshopTopBar` | Clickable badge next to the Eidos button; same target as the banner. |
| **DiffReviewScreen** | Standalone route `workshop_diff_review/{subfolderId}` | Per-file cards (NEW / MODIFIED / ACCEPTED / REJECTED), tap to expand the unified diff, per-card Accept / Reject, toolbar Accept all / Reject all. |
| **ContentHistorySheet** | Modal bottom sheet, opened from the History icon in `WorkshopTopBar` (workshop) and `EditorTopBar` (note page) | Newest-first checkpoint timeline with author badge (BASE / YOU / EIDOS / BUILD), label, timestamp, sequence, line-delta vs working copy. Tap to expand the diff; **Restore** writes the blob back and appends a new `user`-authored checkpoint. |
| **DiffBlock** | Shared composable | Renders a unified diff string with addition (green), deletion (red), hunk header (blue-grey), file header (dim) syntax highlighting. Used by both `DiffReviewScreen` and `ContentHistorySheet`. |

---

## Tool surface

| Tool | Purpose | Auto-accept | Review |
|------|---------|-------------|--------|
| `workshop_write_file` | Overwrite an existing file with the full new content. | Build phases. | Edit / update phases. |
| `workshop_create_file` | Create a brand-new file in a workshop project. | Build phases (creates `FileReference` + disk + baseline). | Edit / update phases (queues as `workshop_new_file`; on accept, the `FileReference` is created and the file is baselined). |
| `workshop_replace_string` | Replace a unique target substring with a replacement. Returns a `not_found` / `ambiguous` error with a snippet when the target doesn't match exactly once. | Build phases. | Edit / update phases. |

The prompt in `PanelPlatformSpec` instructs the LLM to **prefer
`workshop_replace_string`** for targeted edits and only fall back to the
full-content tools when rewriting more than ~60% of a file.

---

## Retention

`CheckpointRepository.MAX_CHECKPOINTS_PER_SOURCE = 5` (configurable). Older
checkpoints + their connecting patches are pruned per source on every
`createCheckpoint`. The **baseline (seq 0) is preserved** so a user can always
restore to the original on-disk content.

---

## What's deferred

- **Note pending-review.** Notes use the timeline for history but Eidos still
  edits notes directly (no diff queue).
- **Hunk-level accept.** v1 is per-file (accept or reject a whole proposal).
- **Multiple open pending sets per scope.** v1 enforces one open set per
  workshop project. If Eidos issues a second batch before the first is
  resolved, the new items supersede prior pending items for the same files
  rather than splitting into a parallel set.
- **Branch / merge.** Out of scope. The system is a single-linear history.
- **Binary file diff.** PDFs / images are tracked by checkpoint blobs but not
  visualized.

---

## Where to look

| Concern | File |
|---------|------|
| Tool entry points | `app/src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt` |
| Policy | `app/src/main/java/com/example/optimalx/data/revision/WorkshopReviewPolicy.kt` |
| Router | `app/src/main/java/com/example/optimalx/data/revision/WorkshopWriteRouter.kt` |
| Auto-accept writes | `app/src/main/java/com/example/optimalx/data/revision/DirectWriteApplier.kt` |
| Pending changes | `app/src/main/java/com/example/optimalx/data/revision/PendingChangeService.kt` |
| Checkpoints + patches | `app/src/main/java/com/example/optimalx/data/revision/CheckpointRepository.kt` |
| Diff utility | `app/src/main/java/com/example/optimalx/data/revision/ContentDiff.kt` |
| Review UI | `app/src/main/java/com/example/optimalx/ui/workshop/review/` |
| History sheet | `app/src/main/java/com/example/optimalx/ui/workshop/components/ContentHistorySheet.kt` |
| Note checkpoints | `app/src/main/java/com/example/optimalx/ui/editor/EditorViewModel.kt` |
| Implementation log | `app/docs/implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md` |
