# SEARCH_AND_RETRIEVAL.md

## Purpose

How **users** and **Eidos** find information in OptimalX: UI keyword search, **chunk-level semantic search** (`search_semantic`), journal/log reads, and chat keyword fallback.

Memory continuity (Daily Memory, LTM, rollover) lives in [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md) and [ROLLOVER.md](ROLLOVER.md). Per-note folder memory and body digest: [NOTE_SUMMARY.md](NOTE_SUMMARY.md).

Tool names: [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt).

---

## Core rule

The app **chunks** saved content, **embeds** each chunk on-device (MediaPipe Text Embedder), **stores** vectors + chunk text, and **`search_semantic` returns the best chunks** (`chunk_text`) for the LLM to answer from. Full notes, files, or chats are **not** dumped into every prompt.

`read_file` / `workshop_read_file` are for **expanding a file region** or **before file edits** — not a required second hop for note Q&A. Notes use inject tiers (`NotePromptContext`) plus `search_semantic` `chunk_text`; patch with `note_replace_string` using text from a search hit.

---

## Pipeline

1. **Save** — note, file import, chat message, journal/daily/LTM write, workshop file write.
2. **Chunk** — [ContentSegmentation.kt](../../src/main/java/com/example/optimalx/data/semantic/ContentSegmentation.kt): headings → paragraphs → size windows with overlap.
3. **Embed** — each chunk → vector ([EmbeddingEngine.kt](../../src/main/java/com/example/optimalx/data/semantic/EmbeddingEngine.kt)).
4. **Store** — [semantic_chunks](../../src/main/java/com/example/optimalx/data/model/SemanticChunk.kt) table: object type/id, location path, chunk text, line range, vector.
5. **Query** — user question embedded in same space.
6. **Search** — cosine similarity over chunk vectors; optional scope + `expand_if_weak`.
7. **Respond** — LLM uses returned `chunk_text` hits.

Bootstrap: full rebuild on first startup ([startup_seed]) and **Settings → Rebuild semantic index**. After saves, [SemanticSyncService.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticSyncService.kt) runs **incremental** updates per object ([SemanticSyncReason.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticSyncReason.kt)) — notes, files, and conversations are re-chunked only when their content or scope changes.

---

## What gets chunked & embedded

| Object | object_type | object_id | Notes |
|--------|-------------|-----------|--------|
| Notes (incl. journal, daily, LTM, quick notes) | `note` | subfolderId | Skips `aiBlind`; body segments + `summary_memory` / `summary_content` from [NOTE_SUMMARY.md](NOTE_SUMMARY.md) |
| Files | `file` | fileReferenceId | Text extractable types only |
| Conversations (all scopes incl. web) | `conversation` | conversationId | Thread batched into chunks |

Folder **names** are not separate vectors — location appears in chunk metadata (`location`, `parentFolderId`, `subfolderId`).

---

## `search_semantic` response (per hit)

| Field | Meaning |
|-------|---------|
| `chunk_text` | Passage to answer from |
| `object_type` | `note` \| `file` \| `conversation` |
| `object_id` | Id for read/edit tools |
| `location` | Human path or chat title |
| `chunk_type` | `summary`, `heading`, `paragraph`, `line_window`, `thread_batch` |
| `startLine` / `endLine` | Source line range when applicable |
| `score` | Similarity (debug / thresholding) |
| `subfolderId` / `fileReferenceId` / `conversationId` | Convenience ids |

### Scope params

- **`scopeType`**: `subfolder`, `parent`, `local_first`, `global`, `chat_history`
- **`scopeId`**: subfolder or parent folder id when scoped
- **`expansionPolicy`**: `expand_if_weak` (default — widen if top score &lt; 0.55) or `none`
- **`dateFrom` / `dateTo`**: optional millis filter on underlying objects

Implementation: [SemanticScopeSearch.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticScopeSearch.kt), [RoomToolExecutor.searchSemantic](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt).

**Panel Workshop:** see [SEMANTIC_SEARCH.md](../architecture/SEMANTIC_SEARCH.md) (`scopeType=local_first`, `scopeId=subfolderId`, editor-open indexing).

---

## Read tools (files / notes / conversations)

| Tool | When |
|------|------|
| `read_file` / `workshop_read_file` | Expand file region; edit prep |
| `read_note` / `read_note_section` | Current subfolder note (full/query or a line range) when body is not inlined |
| `read_conversation` | Optional scoped excerpt when not using search |

Note bodies: small notes are inlined in the subfolder prompt; large notes inject `[Memory]` only. Use prefetch, `search_semantic`, `read_note`, and `read_note_section` for body text; copy `oldString` for `edit_note_section` from a search or read hit.

---

## Prefetch vs `search_semantic`

**Prefetch** runs automatically in `EidosPromptComposer` before the first provider hop when the profile has `prefetchPolicy.profileEnabled` and the user message passes the substantive-message gate ([EidosRetrievalQuery.kt](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosRetrievalQuery.kt)).

| Mechanism | When | Query source | Output |
|-----------|------|--------------|--------|
| **Prefetch** | Turn 1, before API call | User message + optional thread-summary tail (~500 chars) — **never** system prompt | `## Retrieved context` in system prompt (volatile) |
| **`search_semantic` tool** | Model chooses during tool loop | Model-supplied `query` + optional scope | Tool result JSON with `chunk_text` hits |

### What prefetch searches (by profile)

| Profile group | Planner passes |
|---------------|----------------|
| Main chat (`general.app`, `parent`, `subfolder`) | Global Daily/LTM/Journal; parent or `local_first` scope notes; global user notes; past chats (`chat_history`, excludes active thread) |
| Widget (`widget.ask`, `widget.chat`) | Memory corpora + global user notes + chats (tighter caps) |
| Workshop | `local_first` on workshop `subfolderId` — files, notes, indexed `project_summary` |

### Gating and caps

- **Skip:** empty message, greetings (“hi”, “thanks”), messages &lt; ~12 chars ([EidosRetrievalQuery.shouldPrefetch](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosRetrievalQuery.kt))
- **Score gate:** top hit must meet `WEAK_SCORE_THRESHOLD` (0.55) — same as `expand_if_weak`
- **Budget:** per-profile `maxChunks` / `maxChars` ([EidosPrefetchPolicy.kt](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosPrefetchPolicy.kt))
- **Reserved slots:** main chat/widget may reserve note and chat slots in the merged block ([EidosPrefetchChunkSelector.kt](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosPrefetchChunkSelector.kt))

### When to use which

| Need | Use |
|------|-----|
| Answer from memory / notes / past chat on turn 1 | Prefetch (automatic) |
| More hits, different query, or after prefetch | `search_semantic` |
| Expand file region before edit | `read_file` / `workshop_read_file` with query or line range |
| Patch note or file | Copy `chunk_text` from prefetch or search hit into `oldString` |

Prefetch does **not** replace the tool loop — it reduces turn-1 tool hops for orientation. Workshop edits still require `workshop_read_file` before `workshop_replace_string` for exact text.

Implementation: [EidosPrefetchService.kt](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosPrefetchService.kt), [EidosRetrievalPlanner.kt](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosRetrievalPlanner.kt). Plan: [EIDOS_PREFETCH_RAG_PLAN.md](../implementation/EIDOS_PREFETCH_RAG_PLAN.md).

---

## Other retrieval paths

| Path | Role |
|------|------|
| **UI keyword search** | [FolderRepository.kt](../../src/main/java/com/example/optimalx/data/repository/FolderRepository.kt) — SQL `LIKE` on names/content |
| **`read_journal` / `read_log`** | Keyword + date on journal/log notes |
| **`search_chat_history`** | Exact title/message substring — fallback only |
| **`list_folder_contents`** | Explore folder tree |

---

## Privacy

- `aiBlind` notes: chunks deleted; excluded from search results.
- Embedding runs on **save** events, not every keystroke.

---

## Performance note

Search scans all chunk vectors (brute-force cosine). Revisit ANN / hybrid FTS when chunk count grows large (>5k).

---

## Related code

- [SemanticChunkBuilder.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticChunkBuilder.kt) — chunk drafts per object
- [SemanticIndexer.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticIndexer.kt) — store & search chunks
- [EidosContextLimits.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosContextLimits.kt) — prompt policy
