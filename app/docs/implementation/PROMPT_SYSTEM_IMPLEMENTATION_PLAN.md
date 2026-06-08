# PROMPT_SYSTEM — Implementation Plan

**Status:** Active — updated 2026-05-21  
**Product spec:** [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md)  
**Semantic retrieval:** [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md)  
**Provider transport:** [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md)  
**Panel Workshop Auto-Continue:** [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md)  
**Memory contract:** [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md)

Tracks alignment between **PROMPT_SYSTEM** (behavior + prompt contents) and **shipping Kotlin**. Semantic chunk indexing is a **separate major track** that largely landed first — it powers the core “search before read” loop.

---

## Current architecture (as configured in code)

### Retrieval stack ✅ (chunk embeddings)

| Layer | Implementation |
|-------|----------------|
| Embedder | [EmbeddingEngine.kt](../../src/main/java/com/example/optimalx/data/semantic/EmbeddingEngine.kt) — MediaPipe Text Embedder on-device |
| Chunking | [ContentSegmentation.kt](../../src/main/java/com/example/optimalx/data/semantic/ContentSegmentation.kt), [SemanticChunkBuilder.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticChunkBuilder.kt) |
| Storage | `semantic_chunks` table via [SemanticIndexer.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticIndexer.kt) (legacy `semantic_vectors` cleaned on re-index) |
| Bootstrap | [SemanticMaterializer.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticMaterializer.kt) — notes, files, conversations |
| Incremental sync | [SemanticSyncService.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticSyncService.kt) + [SemanticSyncReason.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticSyncReason.kt) |
| Scoped search | [SemanticScopeSearch.kt](../../src/main/java/com/example/optimalx/data/semantic/SemanticScopeSearch.kt) — `local_first`, `expand_if_weak` (threshold 0.55) |
| Tool | `search_semantic` — returns `chunk_text` + ids + line ranges ([RoomToolExecutor.searchSemantic](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt)) |
| UI | Settings → **Rebuild semantic index**; first startup `startup_seed` full bootstrap ([OptimalXApplication.kt](../../src/main/java/com/example/optimalx/OptimalXApplication.kt)) |

**Indexed object types:** `note` (subfolderId), `file` (fileReferenceId), `conversation` (conversationId). Includes journal/daily/LTM/quick notes as notes; skips `aiBlind`.

**Eidos Index (Tag & Hint):** ON HOLD — [EidosIndexFeature.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosIndexFeature.kt). Index tools merged into catalog only when `isActive`. Retrieval doc: use `search_semantic`.

### Tool catalog ✅ (reordered + semantic reads)

[EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt):

1. `search_semantic` (primary retrieval — chunk passages)
2. `read_note`, `read_file`, `workshop_read_file`, `read_conversation` — optional `query`, `startLine`, `endLine`
3. `list_folder_contents`, writes, workshop tools, memory tools, etc.

Read tools: expand a region or prep edits; **not** required second hop for Q&A (see catalog descriptions + [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md)).

### Prompt policy (partial) 🟡

| Source | What it injects today |
|--------|---------------------|
| [EidosChatViewModel.buildBasePrompt](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt) | Short Eidos identity (~4 lines) |
| [EidosContextLimits.TOOL_FIRST_CONTEXT_RULES](../../src/main/java/com/example/optimalx/data/eidos/EidosContextLimits.kt) | Tool-first + **semantic chunk** policy (`search_semantic`, `local_first`, `expand_if_weak`) |
| [EidosApiClient.assembleSystemPrompt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) | Location rules, provider web note, scope, subfolder/parent/workshop context, memory depth label |

**Not yet in prompt:** verbatim PROMPT_SYSTEM core rule line; memory tool pointers; inline parent/subfolder inventories; tightened web-search-only-when-needed wording.

### Provider transport ✅ (tool loops)

| Item | Status | Code |
|------|--------|------|
| Kimi `reasoning_content` parse + replay | ✅ | [KimiProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/KimiProvider.kt), [EidosModels.kt](../../src/main/java/com/example/optimalx/data/eidos/model/EidosModels.kt) |
| Kimi `thinking: { type: enabled, keep: all }` | ✅ | KimiProvider payload |
| OpenAI/xAI reasoning in response | ✅ | [OpenAIProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/OpenAIProvider.kt), [XAIProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/XAIProvider.kt) |
| History trim disabled | ✅ | `EidosApiClient.send()` — full history (`// History trimming disabled until tool-safe trimming exists`) |
| MESSAGES_CACHED full history on tool hops | ✅ | Anthropic + Kimi |
| RESPONSES_CHAINED incremental continuations | ✅ | OpenAI + xAI |
| Kimi workshop write replay redaction | ✅ | `redactToolCallForKimiReplay()` |
| LLM reasoning → chat bubble preview | ✅ | [ReasoningPersistPolicy.kt](../../src/main/java/com/example/optimalx/data/eidos/ReasoningPersistPolicy.kt) — final-hop only on `ChatMessage` |

### Location context (partial) 🟡

| Context | PROMPT_SYSTEM target | Code today |
|---------|---------------------|------------|
| Subfolder note | Summary only | ✅ [buildSubfolderContext](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) + [ContentSummaryService](../../src/main/java/com/example/optimalx/data/eidos/ContentSummaryService.kt) |
| Subfolder files | Names + types inline | ❌ “use list_folder_contents / read_file — not listed inline” |
| Parent folder | Subfolder list inline | ❌ “use list_folder_contents — not listed inline” |
| Workshop cold start | README ≤2k | 🟡 [WorkshopSpecMarkdown](../../src/main/java/com/example/optimalx/data/eidos/WorkshopSpecMarkdown.kt) bounded spec `.md` (README first in list, 2k/file, 6k total) when no project summary |
| Workshop steady state | Manifest + search/read | ✅ file manifest + bounded spec fallback; **no** project summary inject (Phase 1); Chat mode skips open excerpt |
| Memory bodies in prompt | Tool-driven (decided) | ❌ not inlined; ❌ no memory pointer lines yet |

---

## Decisions locked (product review)

| Topic | Decision |
|-------|----------|
| Note body in prompt | **Summary only** — orientation; facts from `search_semantic` chunks |
| Parent / subfolder chat | **Inline inventory** (names, ids, types) — **not implemented yet** |
| Workshop | Startup spec/README bounded; steady state manifest + search → read → write (no project summary inject — Phase 1) |
| Daily memory | **Inject** (bounded excerpt + “only when relevant” guidance) — at-hand for the day; not implemented in `assembleSystemPrompt` yet |
| Long-term memory | **Search / tool only** — embedded in semantic index; do not inject |
| Journal | **Search / tool only** — embedded in semantic index; no prompt inject until write quality fixed |
| Subfolder memory cache | **Inject per scope** when populated (parent/subfolder chat) — not implemented yet |
| Core behavioral rule | PROMPT_SYSTEM: *Search before reading. Read before writing. Never assume content — retrieve it.* |
| Eidos Index | **On hold** — semantic chunks replace Tag & Hint routing for shipping |
| History trimming | **Disabled** until atomic tool-round trimmer exists |
| Summaries | Manual/editor **Generate Summary** — no auto-summary on first `read_note` |

---

## Phase checklist

### Phase 0 — Tool loop reliability

| ID | Task | Status |
|----|------|--------|
| 0a | Kimi `reasoning_content` round-trip | ✅ Done |
| 0b | Disable `trimHistoryIfNeeded` | ✅ Done |
| 0c | Anthropic multi-tool HTTP 400 | 🟡 Unverified — retest Sonnet 4.6 with 2+ parallel local tools |
| 0d | Global max iterations 13 / workshop 18 | ❌ Not done — only `WORKSHOP_CHAT_MAX_TOOL_ROUNDS = 4` for Chat mode |
| 0d | Duplicate tool failure guard (same call fails twice) | ❌ Not done |
| — | Kimi invalid JSON args replay guard | ✅ Done (`redactToolCallForKimiReplay`) |
| — | Persist provider thinking on chat rows | ✅ Done ([ReasoningPersistPolicy.kt](../../src/main/java/com/example/optimalx/data/eidos/ReasoningPersistPolicy.kt)) |

### Phase 1 — Prompt policy text

| ID | Task | Status |
|----|------|--------|
| 1a | Merge PROMPT_SYSTEM core rule into `TOOL_FIRST_CONTEXT_RULES` | 🟡 Partial — semantic policy present; verbatim rule not injected |
| 1b | Web search: only when user needs current/external facts | ❌ Still generic “when available” in `assembleSystemPrompt` |
| 1c | General chat tone (conversational, don’t push tools) | ❌ `buildBasePrompt` unchanged |
| 1d | Sync `LLM_API_REFERENCE.md` with chunk search + reasoning | 🟡 Partial — see [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) |

### Phase 2 — Tool catalog & semantic reads

| ID | Task | Status |
|----|------|--------|
| 2a | `search_semantic` first in catalog | ✅ Done |
| 2b | `query` / line range on read tools | ✅ Catalog + executor (verify edge cases in manual test) |
| 2c | Chunk-level index + bootstrap + scoped search | ✅ Done — see **Current architecture** above |
| 2d | Per-scope tool declaration order | ❌ Optional later — single global order today |
| 2e | Settings rebuild + startup bootstrap | ✅ Done |
| 2f | Panel Workshop tools include `search_semantic` (all modes) | ✅ Done |

### Phase 3 — Location-aware prompt content

| ID | Task | Status |
|----|------|--------|
| 3a | Parent folder: inline subfolder list | ❌ |
| 3b | Subfolder: inline file list (name, id, type) | ❌ |
| 3c | Subfolder memory cache one-liner if populated | ❌ |
| 3d | Workshop README cold-start (≤2k dedicated block) | 🟡 Via `WorkshopSpecMarkdown` fallback, not separate README-only path |
| 3e | Memory tool pointers (no bodies) in system prompt | ❌ |

**Suggested helpers:** reuse `listFolderContents` JSON shaping from [RoomToolExecutor.kt](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt) inside `buildParentFolderContext` / `buildSubfolderContext`.

### Phase 4 — Provider reasoning knobs

| Provider | Status |
|----------|--------|
| xAI `reasoning_effort: medium` | ✅ |
| OpenAI `reasoning.effort: medium` | ✅ |
| Kimi `thinking` enabled + `keep: all` | ✅ |
| Anthropic explicit thinking level | 🟡 Model default (Sonnet 4.6) — no extra knob wired |

### Phase 5 — Journal quality gate

| ID | Task | Status |
|----|------|--------|
| 5a | Audit journal writers (rollover, tools, AgentByte) | ❌ |
| 5b | Dedupe / substance rules on `write_journal_entry` | ❌ |
| 5c | Optional recent journal excerpt in prompt | ❌ Blocked on 5b |

### Phase 6 — Documentation

| Doc | Status |
|-----|--------|
| [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) | ✅ Updated for chunk pipeline |
| [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) | 🟡 Stale vs code (memory inject table, `NOTE_AND_FILE_SUMMARY.md` ref, inline note rules) |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md) | 🟡 Cross-link semantic track |
| This plan | ✅ This file |

---

## Recommended next work (priority order)

1. **Phase 3a + 3b** — Inline parent/subfolder inventories (small, high UX value; aligns with PROMPT_SYSTEM).
2. **Phase 0d** — Global tool loop cap (13/18) + duplicate-failure guard.
3. **Phase 1** — Prompt text polish (core rule verbatim, web rule, base tone, memory pointers in 3e).
4. **Phase 0c** — Confirm Anthropic multi-tool after any remaining 400 reports.
5. **Phase 5** — Journal spam root cause before any journal prompt inject.
6. **Phase 6** — Refresh `PROMPT_SYSTEM.md` product table to match decisions (summary-only notes, tool-driven memory, semantic-first).

**Do not** re-build semantic indexing inside this plan — it is largely complete. Extend it only when adding new source types (e.g. finer workshop indexing policy) or performance (ANN when chunk count > ~5k per SEARCH_AND_RETRIEVAL).

---

## Test plan (manual regression)

| # | Scenario | Expect |
|---|----------|--------|
| 1 | Kimi subfolder chat, 3+ tool hops | No HTTP 400; `reasoning_content` in replay |
| 2 | Anthropic same | Multi-tool turn completes |
| 3 | `search_semantic` in parent scope with `scopeType=local_first` | Scoped chunks + weak expansion |
| 4 | `read_note` with `query` after search hit | Relevant sections + line ranges |
| 5 | Settings → Rebuild semantic index | `full_bootstrap` log; chunks > 0 |
| 6 | Chat bubble Reasoning expand | Final-hop preview visible after kimi/openai/xai turns |
| 7 | Parent folder chat (after 3a) | Subfolder names/ids visible in system prompt |
| 8 | Eidos Index menu | Hidden; `read_tag_hints` fails with on-hold message |

---

## Open questions

1. **Workshop README:** keep `WorkshopSpecMarkdown` multi-file fallback or PROMPT_SYSTEM’s README-only ≤2k block when no project summary?
2. **Memory tier chip:** hide or label “history not trimmed” while trim is disabled?
3. **Tool loop caps:** adopt PROMPT_SYSTEM 13/18 globally or keep workshop Chat mode’s separate 4-round read cap?
4. **Anthropic reasoning preview:** persist thinking text on `ChatMessage` when API exposes it?

---

## File index (prompt + retrieval)

| Area | Files |
|------|-------|
| Prompt assembly | `EidosApiClient.kt`, `EidosContextLimits.kt`, `EidosChatViewModel.kt` |
| Tools | `EidosToolCatalog.kt`, `RoomToolExecutor.kt` |
| Semantic index | `SemanticIndexer.kt`, `SemanticChunkBuilder.kt`, `SemanticMaterializer.kt`, `SemanticSyncService.kt`, `SemanticScopeSearch.kt`, `EmbeddingEngine.kt` |
| Providers | `KimiProvider.kt`, `AnthropicProvider.kt`, `OpenAIProvider.kt`, `XAIProvider.kt` |
| Reasoning preview | `ReasoningPersistPolicy.kt`, `ReasoningTrace.kt`, `EidosChatScreen.kt` |
| Summaries | `ContentSummaryService.kt`, `WorkshopSpecMarkdown.kt` |
| Index on hold | `EidosIndexFeature.kt`, `AppIndexMaterializer.kt` |
