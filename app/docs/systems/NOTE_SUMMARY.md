# NOTE_SUMMARY.md

## Purpose

How **per-note folder memory** works in OptimalX: storage format, prompt injection tiers, Eidos tools, and user co-op editing.

Related: [SEARCH_AND_RETRIEVAL.md](SEARCH_AND_RETRIEVAL.md) (semantic retrieval), [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md) (Daily/LTM vs folder memory), [PROMPT_SYSTEM.md](PROMPT_SYSTEM.md).

Source of truth in code: [NoteSummaryCodec.kt](../../src/main/java/com/example/optimalx/data/eidos/NoteSummaryCodec.kt), [NoteSummaryPolicy.kt](../../src/main/java/com/example/optimalx/data/eidos/NoteSummaryPolicy.kt), [NotePromptContext.kt](../../src/main/java/com/example/optimalx/data/eidos/NotePromptContext.kt).

---

## Storage (`Note.summary`)

Single text field on each note row:

```
[Memory]
- curated bullet one
- curated bullet two

[Content]
Optional leftover digest (no longer auto-maintained).
```

| Section | Who writes | Role |
|---------|------------|------|
| `[Memory]` | User (editor panel) + Eidos (`write_note_summary`) | Durable folder facts, preferences, decisions |
| `[Content]` | Unused auto-fold leftover; still stored/shown if present | Not generated anymore |

**Watermark:** `Note.summaryContentWatermark` is unused leftover from the old fold. Kept for Room/sync schema.

**Legacy:** Pre-v24 `summary` plain text and `summaryChunksJson` migrate into `[Content]` on DB upgrade (column kept nullable for rollback).

---

## Prompt injection tiers

[`NotePromptContext`](../../src/main/java/com/example/optimalx/data/eidos/NotePromptContext.kt) builds subfolder note context in [`EidosApiClient.buildSubfolderContext`](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt).

| Tier | Body size | Injected |
|------|-----------|----------|
| **EMPTY** | 0 chars | "Note: empty." |
| **INLINE_FULL** | ≤ `INLINE_NOTE_MAX_CHARS` | Full body + memory bullets |
| **LARGE** | above that cap | Memory + retrieval hints (no body inline) |

Large notes with empty `[Memory]` may get a one-turn nudge to use `write_note_summary (append)`.

Large-note body retrieval: prefetch + `search_semantic` + `read_note` / `read_note_section`.

---

## Content digest

Auto-fold of note body into `[Content]` is **removed**. Large notes inject folder memory only; body is retrieved with prefetch, `search_semantic`, `read_note`, and `read_note_section`. Existing `[Content]` text is still parsed if present.

Thresholds ([`NoteSummaryPolicy`](../../src/main/java/com/example/optimalx/data/eidos/NoteSummaryPolicy.kt)):

| Constant | Value | Meaning |
|----------|-------|---------|
| `INLINE_NOTE_MAX_CHARS` | 8,000 | Full inline inject cap |
| `FOLD_TRIGGER_CHARS` | 4,000 | Large-note `write_note_summary` nudge |
| `MAX_MEMORY_BULLETS` | 10 | Cap on `[Memory]` bullets |
| `MAX_MEMORY_CHARS` | 1,200 | Total `[Memory]` chars |
| `MAX_CONTENT_DIGEST_CHARS` | 800 | Cap if a leftover `[Content]` is still saved |

---

## Eidos tool: `write_note_summary`

Catalog: [`EidosToolCatalog`](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt). Executor: [`RoomToolExecutor`](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt).

| `mode` | Behavior |
|--------|----------|
| `append` | Add bullet (`item` required); reject duplicate |
| `replace` | Replace bullet matching `match` (index or substring) with `item` |
| `remove` | Remove bullet matching `match` |
| `set` | Replace entire `[Memory]` with `item` (newline-separated bullets) |

`[Content]` is preserved on every mode. Updates `summaryUpdatedAt` and reindexes semantic chunks.

**Edits to note body:** `search_semantic` → `note_replace_string` with `oldString` from `chunk_text`.

---

## User co-op (editor UI)

Note editor → collapsible **Folder memory & digest** panel ([`NoteSummaryPanel`](../../src/main/java/com/example/optimalx/ui/editor/components/NoteSummaryPanel.kt)):

- Edit `[Memory]` freely; optional `[Content]` corrections
- **Save summary** → [`EditorRepository.saveNoteSummary`](../../src/main/java/com/example/optimalx/data/repository/EditorRepository.kt)

Legacy **Generate Eidos summary** (one-shot LLM note summary) was removed in favor of this model.

---

## Semantic index

[`SemanticChunkBuilder.buildNoteChunks`](../../src/main/java/com/example/optimalx/data/semantic/SemanticChunkBuilder.kt) indexes:

- `summary_memory` — `[Memory]` bullets
- `summary_content` — `[Content]` digest
- Body segments as before

`search_semantic` hits include `chunk_text` for Q&A and patch prep.

---

## Migrations

| Version | Change |
|---------|--------|
| **v24** | `summaryContentWatermark`; legacy `summary` / `summaryChunksJson` → two-section format |
| **v25** | Legacy parent **memory cache** map → empty `[Memory]` bullets (best-effort) |

---

## Workshop note

**Panel Workshop** project orientation still uses `Subfolder.projectSummary` via [`ContentSummaryService.generateWorkshopProjectSummary`](../../src/main/java/com/example/optimalx/data/eidos/ContentSummaryService.kt) — separate from per-note `[Memory]`.

- **Trigger:** auto after **Generate specs** completes and after each successful **doc-align** pass when spec `.md` changed (Accept design / Accept logic / Accept update). Manual **Regenerate** remains in the workshop drawer.
- **Prompt:** with prefetch on (Phase 5+), the summary is **not** blunt-inlined every send; a short orientation hint points the model at prefetch / `search_semantic`.
- **Index (Phase 5.1):** after a successful generate, `SemanticChunkBuilder` writes a `project_summary` chunk on the workshop subfolder so prefetch can surface it on vague “what is this project?” turns.
