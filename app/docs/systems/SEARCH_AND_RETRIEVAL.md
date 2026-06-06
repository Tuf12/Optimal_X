# SEARCH_AND_RETRIEVAL.md

## Purpose

How **users** and **Eidos** find information in OptimalX: UI keyword search, **chunk-level semantic search** (`search_semantic`), journal/log reads, and chat keyword fallback.

Memory continuity (Daily Memory, LTM, rollover) lives in [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md). **Eidos Index (Tag & Hint) is on hold** — see [TAG_HINT_SYSTEM.md](../agent_loops/TAG_HINT_SYSTEM.md).

Tool names: [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt).

---

## Core rule

The app **chunks** saved content, **embeds** each chunk on-device (MediaPipe Text Embedder), **stores** vectors + chunk text, and **`search_semantic` returns the best chunks** (`chunk_text`) for the LLM to answer from. Full notes, files, or chats are **not** dumped into every prompt.

`read_note` / `read_file` / `workshop_read_file` are for **expanding a line range** or **before edits** — not a required second hop for Q&A.

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
| Notes (incl. journal, daily, LTM, quick notes) | `note` | subfolderId | Skips `aiBlind`; includes summary chunk + body segments |
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

## Read tools (expand / edit only)

| Tool | When |
|------|------|
| `read_note` | Expand lines around a hit; load full small note; edit prep |
| `read_file` / `workshop_read_file` | Expand file region; edit prep |
| `read_conversation` | Optional scoped excerpt when not using search |

Large reads without params return JSON with `truncated: true`, `content` (preview), and a `hint` to use `search_semantic` or line ranges. Small reads return `{ "content": "...", "truncated": false, "totalLines": N }`.

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
