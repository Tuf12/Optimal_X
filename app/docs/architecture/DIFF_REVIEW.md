# DIFF_REVIEW.md

**Change review, checkpoints, and patch-style edits — Panel Workshop + Notes**

| Field | Value |
|-------|-------|
| **Status** | **Shipped** — workshop Phases 0–6; note Diff Review Phases 0–3 (markdown storage + `write_note` / `note_replace_string` + review UI) |
| **Audience** | Product, Kotlin implementers, Eidos prompt authors |
| **Related** | [NOTE_PERSISTENCE_MODEL.md](NOTE_PERSISTENCE_MODEL.md) (**canonical note working copy vs HEAD**), [EDITOR_AND_PANELS.md](EDITOR_AND_PANELS.md), [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md), [WORKSHOP_MODES.md](WORKSHOP_MODES.md), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) (build run = auto-accept, no mid-loop queue), [DATA_MODEL.md](DATA_MODEL.md), [TOOL_FUNCTIONS.md](../reference/TOOL_FUNCTIONS.md) |
| **Replaces** | Eidos overwriting working copies with no user review and no rollback path |

---

## What shipped

Eidos no longer writes blindly to workshop files **or existing note bodies**. Workshop writes flow through **`WorkshopWriteRouter`**; note writes flow through **`NoteWriteRouter`**. Each router either:

- **Auto-accepts** the change — persisted content is updated, a checkpoint is
  appended, and the editor reloads. Workshop: build phases. Notes: **empty body,
  no pending proposal** (first fill only).
- **Queues the change for review** — the working copy on disk is untouched,
  proposed bytes live in `pending_change_items`, and the user accepts or rejects
  on the **Diff Review** screen.

In addition, every accepted or auto-accepted write produces a **sparse
checkpoint** with a unified-diff **patch** describing what changed since the
prior checkpoint. The user can browse history and restore any checkpoint via a
shared **Content History** bottom sheet.

Notes share the checkpoint timeline with workshop files (`sourceType = "note"`).
**Human edits do not use Diff Review** — they reach HEAD via **Commit** (manual
or auto-commit before Eidos). See [NOTE_PERSISTENCE_MODEL.md](NOTE_PERSISTENCE_MODEL.md).
Diff Review for notes is **Eidos-only** (`write_note`, `note_replace_string`).

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
| `WorkshopWriteRouter` | Single entry point for workshop file mutations from tool calls. Consults the policy and delegates. |
| `NoteWriteRouter` | Single entry point for `write_note` and `note_replace_string`. Auto-apply on empty note; queue append/patch. |
| `NoteWriteMerge` | `write_note` merge policy: set when empty, append (`\n\n`) when not. |
| `DirectWriteApplier` | Performs persisted writes (workshop disk + note Room row), reindex, and checkpoint creation. Used by auto-accept *and* by accept-from-review. |
| `PendingChangeService` | Stores proposed content under `pending_change_items`, computes the diff against the on-disk baseline, and detects concurrent manual edits. |
| `CheckpointRepository` | Appends content checkpoints and unified-diff patches. Prunes per source by retention (5/source). |
| `ContentDiff` | Pure-Kotlin LCS-based unified diff + apply + SHA-256 hash. Used everywhere a diff or hash is needed. |

---

## Data model

Three new tables (schema v18; migration `17→18` in `AppDatabase.kt`).

### `content_checkpoints`

A sparse snapshot of a file's content at a meaningful event (build kickoff,
accepted Eidos change, user Commit, auto-commit before Eidos, restore).

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
| `scope` | `workshop_project` or `subfolder`. |
| `scopeId` | `Subfolder.id` (workshop project or note host subfolder). |
| `state` | `open` / `accepted` / `rejected` / `superseded`. |

| `pending_change_items` | Notes |
|------------------------|-------|
| `setId` | FK → set. |
| `sourceType` | `workshop_file`, `workshop_new_file`, or `note`. |
| `sourceId` | `FileReference.id`, `Subfolder.id` (new workshop file), or `Subfolder.id` (note). |
| `fileName` | Workshop file name; `"Note"` for note proposals. |
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

## Note write flow

1. Tool call arrives at `RoomToolExecutor` (`write_note` or `note_replace_string`).
2. If working copy (`notes.content`) is **dirty vs HEAD**, **auto-commit** user
   edits first (`commitWorkingCopy`, `author=user`, label e.g. `"Before Eidos edit"`).
   See [NOTE_PERSISTENCE_MODEL.md](NOTE_PERSISTENCE_MODEL.md).
3. `NoteWriteRouter` reads effective working content (same path used for patch matching).
4. **Auto-apply** when stored body is blank **and** there is no open pending note item:
   `DirectWriteApplier.applyNoteWrite` → Room `notes.content` (markdown), checkpoint,
   semantic reindex.
5. **Queue** for append, patch, or any change while body exists (or while a pending
   proposal is open): `PendingChangeService.proposeNote` under `SCOPE_SUBFOLDER`.
   `ensureProposalBaseline` snapshots HEAD when disk differs from latest checkpoint.
6. `EditorViewModel` / `EidosChatViewModel` observe the open set and surface
   **Review N** badge / banner → `DiffReviewScreen` (`scopeType = subfolder`).
7. **Accept** calls `DirectWriteApplier.applyNoteWrite`; editor reloads via
   `reloadNoteAfterExternalWrite()` when not dirty.

`write_quick_note` is **not** routed through this pipeline — it appends directly to
the Quick Notes daily inbox.

---

## Note checkpoints (Commit + restore)

Notes share `content_checkpoints` and `content_patches` with workshop files —
`sourceType = "note"`, `sourceId = subfolderId`.

| Checkpoint source | `author` | Trigger |
|-------------------|----------|---------|
| User **Commit** | `user` | Top bar when working copy ≠ HEAD |
| Auto-commit before Eidos | `user` | Dirty vs HEAD at Eidos tool entry |
| Diff Review Accept | `eidos` | User accepts Eidos proposal |
| Empty-note auto-apply | `eidos` | First `write_note` on blank note |
| History Restore | `user` | `"Restored to seq N (...)"` |

**User typing** flushes to the working copy on leave — **not** HEAD. Checkpoints
are created only via Commit, auto-commit, Eidos accept/auto-apply, or restore
(hash-deduped). Human edits **never** enter `pending_change_items`.

Restore writes the checkpoint blob back via `EditorRepository.saveNoteContent`,
emits to `restoreContent`, and appends a fresh `user`-authored
`"Restored to seq N (...)"` checkpoint.

Eidos **append/patch** proposals do **not** touch the working copy until accept;
`note_replace_string` matches against effective queued content (pending proposals included) so patches align with what the user will review.

---

## UI surfaces

| Surface | Where | Behavior |
|---------|-------|----------|
| **Diff Review banner (workshop)** | End of `EidosChatScreen` message list | When active workshop scope has open pending items. Tap → `workshop_diff_review/{subfolderId}`. |
| **Diff Review banner (note)** | End of `EidosChatScreen` message list | When subfolder / web / quick-notes-day scope has open note pending items. Tap → `note_diff_review/{subfolderId}`. |
| **Review N badge (workshop)** | `WorkshopTopBar` | Same target as workshop banner. |
| **Commit (note)** | `EditorTopBar` (note page) | When working copy ≠ HEAD; `commitWorkingCopy()` — not Diff Review. |
| **Review N badge (note)** | `EditorTopBar` (note page) | Eidos pending only → `note_diff_review/{subfolderId}`. |
| **DiffReviewScreen** | `workshop_diff_review/{subfolderId}` or `note_diff_review/{subfolderId}` | Shared UI; `scopeType` selects workshop vs note pending set. Per-item cards, expand diff, Accept / Reject, Accept all / Reject all. |
| **ContentHistorySheet** | History icon in `WorkshopTopBar` / `EditorTopBar` | Checkpoint timeline + restore. Expanded row shows **restore preview** (checkpoint body), not a diff vs current. |
| **DiffBlock** | Shared composable | Unified diff rendering for review + history. |

---

## Tool surface

### Workshop

| Tool | Purpose | Auto-accept | Review |
|------|---------|-------------|--------|
| `workshop_write_file` | Overwrite an existing file with the full new content. | Build phases. | Edit / update phases. |
| `workshop_create_file` | Create a brand-new file in a workshop project. | Build phases (creates `FileReference` + disk + baseline). | Edit / update phases (queues as `workshop_new_file`; on accept, the `FileReference` is created and the file is baselined). |
| `workshop_edit_file` | Replace `startLine`…`endLine` with `newContent`. | Build phases. | Edit / update phases. |
| `workshop_append_file` | Append content at EOF. | Build phases. | Edit / update phases. |

### Notes

| Tool | Purpose | Auto-accept | Review |
|------|---------|-------------|--------|
| `write_note` | Set markdown on empty note, or append when content exists. | Empty body + no pending. | Append / any change to existing body. |
| `note_replace_string` | Unique `oldString` → `newString` patch (markdown). | Empty body + no pending. | Non-empty body. |

Removed from catalog: `append_note`, `edit_note_section` (replaced by the pair above).

The workshop prompt in `PanelPlatformSpec` instructs the LLM to **prefer
`workshop_edit_file`** for targeted edits. Note scope injects parallel
guidance via `EidosSystemPromptLayers.NOTE_WRITE_RULES`.

---

## Retention

`CheckpointRepository.MAX_CHECKPOINTS_PER_SOURCE = 5` (configurable). Older
checkpoints + their connecting patches are pruned per source on every
`createCheckpoint`. The **baseline (seq 0) is preserved** so a user can always
restore to the original on-disk content.

---

## What's deferred

- **Hunk-level accept.** v1 is per-file / per-note (accept or reject a whole proposal).
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
| Router (notes) | `app/src/main/java/com/example/optimalx/data/revision/NoteWriteRouter.kt` |
| Policy | `app/src/main/java/com/example/optimalx/data/revision/WorkshopReviewPolicy.kt` |
| Router (workshop) | `app/src/main/java/com/example/optimalx/data/revision/WorkshopWriteRouter.kt` |
| Auto-accept writes | `app/src/main/java/com/example/optimalx/data/revision/DirectWriteApplier.kt` |
| Pending changes | `app/src/main/java/com/example/optimalx/data/revision/PendingChangeService.kt` |
| Checkpoints + patches | `app/src/main/java/com/example/optimalx/data/revision/CheckpointRepository.kt` |
| Diff utility | `app/src/main/java/com/example/optimalx/data/revision/ContentDiff.kt` |
| Review UI | `app/src/main/java/com/example/optimalx/ui/workshop/review/` |
| History sheet | `app/src/main/java/com/example/optimalx/ui/workshop/components/ContentHistorySheet.kt` |
| Note checkpoints + Commit | `app/src/main/java/com/example/optimalx/ui/editor/EditorViewModel.kt` |
| Persistence model | `app/docs/architecture/NOTE_PERSISTENCE_MODEL.md` |
| Implementation log | `app/docs/implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md` |
