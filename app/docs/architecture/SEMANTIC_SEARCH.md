# SEMANTIC_SEARCH.md

## Purpose

How **Panel Workshop** and the rest of OptimalX use on-device semantic search (`search_semantic`). General retrieval rules live in [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md).

---

## Panel Workshop scope

When Eidos runs inside a workshop project (`scopeType = panel_workshop`), tools should search **that project first**:

| Parameter | Value |
|-----------|--------|
| `scopeType` | `local_first` |
| `scopeId` | Workshop **subfolder id** (same as `currentSubfolderId` / manifest `subfolderId`) |

`local_first` searches the subfolder’s chunks first, then the parent folder, then global — see [SemanticScopeSearch.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticScopeSearch.kt).

[EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) injects these defaults for `search_semantic` when the workshop scope is active.

---

## What gets indexed for a workshop project

On **workshop editor open**, [WorkshopEditorViewModel](../../src/main/java/com/example/optimalx/ui/workshop/WorkshopEditorViewModel.kt) requests:

```text
workshop_editor_open:<subfolderId>
```

[SemanticMaterializer.indexSubfolder](../../src/main/java/com/example/optimalx/data/semantic/SemanticMaterializer.kt) re-chunks:

- The project **note** (subfolder body, if any)
- Every **FileReference** under that subfolder (`README.md`, specs, `index.html`, `script.js`, etc.)

Incremental updates also run on file insert/share, subfolder rename, and after **Diff Review accept** (disk write via [DirectWriteApplier](../../src/main/java/com/example/optimalx/data/revision/DirectWriteApplier.kt) → [WorkshopFileIndexer](../../src/main/java/com/example/optimalx/data/revision/RevisionTypes.kt)).

---

## Recommended Eidos flow (workshop)

1. **`search_semantic(query, scopeType=local_first, scopeId=<subfolderId>)`** — find spec passages and code regions from indexed chunks (`chunk_text`, `fileReferenceId`).
2. **`workshop_read_file(fileReferenceId, query=…)`** or `startLine` / `endLine` from a hit — expand a region before edits.
3. **`workshop_write_file` / `workshop_replace_string`** — implement; review phases queue via Diff Review.

Do **not** read entire large runtime files without `query` or line range.

---

## `workshop_read_file` response shape

All reads return a **JSON object** (never raw plain text):

| Field | Meaning |
|-------|---------|
| `content` | Primary text (full file when small, query excerpts, or line range) |
| `truncated` | `true` when more text exists on disk |
| `totalLines` | Line count of the full file |
| `sections` | Optional ranked excerpts when `query` is set |
| `hint` | Present when truncated without `query` — use `search_semantic` first |

Implementation: [ContentSectionRetriever.kt](../../src/main/java/com/example/optimalx/data/semantic/ContentSectionRetriever.kt), [RoomToolExecutor.workshopReadFile](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt).

---

## Related docs

- [PANEL_WORKSHOP.md](./PANEL_WORKSHOP.md) — lifecycle and modes
- [WORKSHOP_MODES.md](./WORKSHOP_MODES.md) — Chat / Plan / Edit tool allowlists
- [DIFF_REVIEW.md](./DIFF_REVIEW.md) — queued writes and accept → disk + reindex
