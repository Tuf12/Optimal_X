# PROMPT_SYSTEM — Implementation Plan

> **Active implementation track (2026-06-22):** [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) — **Phases 0–6 complete.** This file tracks retrieval, transport, and historical pre-router work.
>
> **Prefetch RAG (2026-07):** [EIDOS_PREFETCH_RAG_PLAN.md](./EIDOS_PREFETCH_RAG_PLAN.md) — **complete** — proactive semantic retrieval before hop 1 + memory-writing hygiene.

**Status:** Maintenance — router shipped; new prompt/routing work → router plan or product spec only  
**Product spec:** [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) — verified 2026-06-22  
**Scope router:** [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md)  
**Transport:** [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md)  
**Memory contract:** [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md)

Tracks alignment between **PROMPT_SYSTEM** (behavior + prompt contents) and **shipping Kotlin**. Semantic chunk indexing landed first. **Prompt assembly/routing** now uses `EidosScopeRouter` + `EidosPromptComposer` (verified 2026-06-22).

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

**Eidos Index (Tag & Hint):** **Removed** from shipping app (2026-06). Retrieval: `search_semantic` only. Historical spec: [archive/agent_loops/TAG_HINT_SYSTEM.md](../archive/agent_loops/TAG_HINT_SYSTEM.md).

### Tool catalog ✅ (reordered + semantic reads)

[EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt):

1. `search_semantic` (primary retrieval — chunk passages)
2. `read_note`, `read_file`, `workshop_read_file`, `read_conversation` — optional `query`, `startLine`, `endLine`
3. `list_folder_contents`, writes, workshop tools, memory tools, etc.

Read tools: expand a region or prep edits; **not** required second hop for Q&A (see catalog descriptions + [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md)).

### Prompt policy ✅ (scope router — shipped 2026-06-22)

| Source | What it produces |
|--------|------------------|
| [EidosScopeRouter](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosScopeRouter.kt) | Resolves `EidosScopeProfile` from `scopeType` + `entrySurface` + workshop mode/phase |
| [EidosScopeProfileRegistry](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosScopeProfileRegistry.kt) | Per-profile ontology, location policy, tool allowlist, `contextPolicy` |
| [EidosPromptComposer](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosPromptComposer.kt) | Sectioned system prompt (`## Identity & rules` … `## This turn`) |
| [EidosIdentityPrompt](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosIdentityPrompt.kt) | Shared identity text (replaces ViewModel `buildBasePrompt`) |
| [EidosContextLimits.TOOL_FIRST_CONTEXT_RULES](../../src/main/java/com/example/optimalx/data/eidos/EidosContextLimits.kt) | Tool-first rules — appended by composer for non-workshop, non-internal profiles |
| [DailyMemoryContext.kt](../../src/main/java/com/example/optimalx/data/eidos/DailyMemoryContext.kt) | Bounded daily note inject (`general.app`, `parent`, `subfolder`) |
| [ParentFolderContext.kt](../../src/main/java/com/example/optimalx/data/eidos/ParentFolderContext.kt) | Bounded subfolder catalog (≤20) for parent scope |
| [WorkshopHostLinkContext.kt](../../src/main/java/com/example/optimalx/data/eidos/WorkshopHostLinkContext.kt) | Workshop Chat host-link block + semantic enrich |
| [EidosInternalPromptBlocks.kt](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosInternalPromptBlocks.kt) | Internal background prompts (no tool-first rules) |
| [EidosApiClient.assembleSystemPrompt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) | Legacy thin passthrough fallback only |
| [EidosChatViewModel](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt) | Passes `EidosIdentityPrompt.TEXT` as `baseSystemPrompt` hint; composer is authoritative |

**Superseded:** monolithic `assembleSystemPrompt` branches, `EidosPromptSections` as primary path, `BASE_SYSTEM_PROMPT` in `EidosContextLimits` (identity now in `EidosIdentityPrompt`).

### Provider transport 🟡 (tool loops)

| Item | Status | Code |
|------|--------|------|
| Kimi `reasoning_content` parse + replay | ✅ | [KimiProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/KimiProvider.kt), [EidosModels.kt](../../src/main/java/com/example/optimalx/data/eidos/model/EidosModels.kt) |
| Kimi `thinking: { type: enabled, keep: all }` | ✅ | KimiProvider payload |
| OpenAI/xAI reasoning in response | ✅ | [OpenAIProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/OpenAIProvider.kt), [XAIProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/XAIProvider.kt) |
| History trim disabled | ✅ | `EidosApiClient.send()` — full history (`// History trimming disabled until tool-safe trimming exists`) |
| MESSAGES_CACHED growing messages on tool hops | ✅ | `prepareOutboundHistory` stubs bulk tools beyond last 3 rounds |
| RESPONSES_CHAINED incremental continuations (non-workshop) | ✅ | OpenAI + xAI |
| Workshop incremental continuations (Responses) | ✅ | `shouldUseIncrementalToolContinuation` — all scopes on xAI/OpenAI (2026-06-08) |
| Kimi workshop write replay redaction | ✅ | `redactToolCallForKimiReplay()` |
| LLM reasoning → chat bubble preview | ✅ | [ReasoningPersistPolicy.kt](../../src/main/java/com/example/optimalx/data/eidos/ReasoningPersistPolicy.kt) — final-hop only on `ChatMessage` |
| API trace compounding factor | ✅ | Transport plan Phase 5 |

### Location context ✅ (router Phase 3–4 — 2026-06-22)

| Context | PROMPT_SYSTEM target | Code today |
|---------|---------------------|------------|
| Subfolder note | Summary tiers | ✅ `NotePromptContext` via composer |
| Subfolder files | Tool-driven manifest | ✅ defers to `list_folder_contents` / `read_file` |
| Parent folder | Bounded subfolder catalog | ✅ `ParentFolderContext` (≤20) |
| Daily memory | Inject on general/parent/subfolder | ✅ `DailyMemoryContext` |
| Workshop cold start | README ≤2k | 🟡 `WorkshopSpecMarkdown` bounded spec |
| Workshop steady state | Manifest + search/read | ✅ file manifest; Chat mode host-link enrich |
| Memory bodies in prompt | Tool-driven LTM/journal; daily inject on main chat | ✅ |

---

## Decisions locked (product review)

| Topic | Decision |
|-------|----------|
| Note body in prompt | **Summary only** — orientation; facts from `search_semantic` chunks |
| Parent / subfolder chat | **Bounded parent catalog** (≤20 subfolders) — ✅ shipped; file bodies via tools |
| Workshop | Startup spec/README bounded; steady state manifest + search → read → write |
| Daily memory | **Inject** (bounded + relevance guidance) on `general.app`, `parent`, `subfolder` — ✅ shipped 2026-06-22 |
| Long-term memory | **Search / tool only** — embedded in semantic index; do not inject |
| Journal | **Search / tool only** — embedded in semantic index; no prompt inject until write quality fixed |
| Subfolder memory cache | **Replaced** by `[Memory]` note summary tiers — ✅ |
| Core behavioral rule | Per-profile ontology in `EidosScopeProfileRegistry` — ✅ shipped 2026-06-22 |
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
| 1a | Core rule verbatim in base prompt | ✅ `EidosContextLimits.BASE_SYSTEM_PROMPT` (2026-06-11) |
| 1b | Web search: only when user needs current/external facts | ✅ In base prompt + provider-specific blocks |
| 1c | General chat tone (conversational, don’t push tools) | ✅ In base prompt |
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
| 3a | Parent folder: bounded subfolder catalog | ✅ router Phase 3.6 (2026-06-22) |
| 3b | Subfolder: inline file list (name, id, type) | ✅ by design — tool-driven, not inlined |
| 3c | Subfolder memory via note summary tiers | ✅ replaces legacy cache |
| 3d | Workshop README cold-start (≤2k dedicated block) | 🟡 Via `WorkshopSpecMarkdown` fallback |
| 3e | Memory tool pointers (no LTM/journal bodies) | ✅ via `EidosIdentityPrompt` + tool-first rules |

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
| 5a | Audit journal writers (rollover, tools) | ❌ |
| 5b | Dedupe / substance rules on `write_journal_entry` | ❌ |
| 5c | Optional recent journal excerpt in prompt | ❌ Blocked on 5b |

### Phase 6 — Documentation

| Doc | Status |
|-----|--------|
| [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) | ✅ Updated for chunk pipeline |
| [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) | ✅ Verified 2026-06-22 (router Phase 6) |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md) | 🟡 Cross-link semantic track |
| This plan | ✅ Updated 2026-06-22 |

### Phase 7 — Prompt transport fix ([PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md))

| ID | Task | Status |
|----|------|--------|
| 7.0 | Doc correction (PROMPT_SYSTEM, EIDOS_LLM_CONTEXT_CLEANUP, Auto-Continue plan, LLM_API_REFERENCE, Kimi spec) | ✅ 2026-06-08 |
| 7.1 | Workshop Responses incremental (`!isPanelWorkshop` removal) | ✅ 2026-06-08 |
| 7.2 | Lean workshop system (hop-1-only spec/open excerpt; dedupe retrieval policy) | ✅ 2026-06-08 |
| 7.3 | Tool-result history trim + re-enable memory-tier trim | ✅ 2026-06-08 |
| 7.4 | Kimi/Anthropic system-on-continuation optimization | ✅ 2026-06-08 |
| 7.5 | API trace compounding metrics | ✅ 2026-06-08 |
| 7.6 | Auto-Continue chunk 2+ lean history | ✅ 2026-06-08 |

### Phase 8 — Prompt architecture fix ([PROMPT_ARCHITECTURE_FIX_PLAN.md](./PROMPT_ARCHITECTURE_FIX_PLAN.md))

| ID | Task | Status |
|----|------|--------|
| 8.1 | Canonical `BASE_SYSTEM_PROMPT` + core rule / memory pointers / web policy | ✅ 2026-06-11 |
| 8.2 | Workshop retrieval policy dedupe + negative-rule cleanup | ✅ 2026-06-11 |
| 8.3 | Stable-prefix section structure (`EidosPromptSections`) | ✅ 2026-06-11 — manual trace verification open |
| 8.4 | Tool-hop budget in kickoff + edit prompts | ✅ 2026-06-11 — manual Chutes kickoff verification open |
| 8.5 | `workshop_replace_string` worked example | ✅ 2026-06-11 |
| 8.6 | Doc truth sync (PROMPT_SYSTEM tables + agent rule 6) | ✅ 2026-06-11 |
| — | Sentinel tests | ✅ [PromptAssemblySentinelTest.kt](../../src/test/java/com/example/optimalx/data/eidos/PromptAssemblySentinelTest.kt) |

---

## Phase 9 — Scope router ([PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md)) — ✅ complete 2026-06-22

| ID | Task | Status |
|----|------|--------|
| 9.0 | Product alignment — profile matrix (ontology, location, tools), `entrySurface`, sign-off | ✅ Doc hygiene 2026-06-22 |
| 9.1 | Router skeleton + registry + composer shell (parity) | ✅ |
| 9.2 | Tool allowlists + widget `entrySurface` wiring | ✅ |
| 9.3 | Prompt migration — one profile per PR; composer owns all user-facing profiles | ✅ |
| 9.4 | Scope hygiene — universal blocks, workshop dedupe, daily memory inject | ✅ |
| 9.5 | Internal scopes + API trace observability | ✅ |
| 9.6 | PROMPT_SYSTEM.md verified rows (doc-only) | ✅ 2026-06-22 |

**Remaining debt:** golden prompt snapshot tests (Phase 1 ⏳); delete legacy `assembleSystemPrompt` passthrough when safe.

**Canonical profile spec:** ontology + tools in router plan profile tables.

---

## Recommended next work (priority order)

1. **Golden prompt snapshot tests** — per-profile stable-prefix snapshots (Phase 1 debt in router plan).
2. **Memory rollover pipeline** — reliability of `write_daily_memory` + daily note clearing (separate from prompt router).
3. **Manual verification** — API trace stable-prefix diff + `cached_tokens` uplift per provider family.
4. **Phase 0d (legacy)** — Global tool loop cap (13) + duplicate-failure guard.
5. **Cross-scope note summary inject** — v2 via `CustomPanelAssignment` host link (deferred).
6. **Phase 5 (journal)** — Journal quality before any journal prompt inject.
7. **Remove legacy `assembleSystemPrompt` passthrough** — when golden tests + manual pass confirm no edge-case callers.

**Do not** re-build semantic indexing inside this plan — it is largely complete.

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
| 7 | Parent folder chat | Subfolder names/ids visible in system prompt (bounded catalog) |
| 8 | Eidos Index | Removed from app; retrieval via `search_semantic` |

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
| Prompt router (canonical) | `data/eidos/prompt/*` — `EidosScopeRouter`, `EidosScopeProfileRegistry`, `EidosPromptComposer`, `EidosIdentityPrompt`, `EidosInternalPromptBlocks`, `EidosPromptTrace` |
| Prompt context helpers | `DailyMemoryContext.kt`, `ParentFolderContext.kt`, `WorkshopHostLinkContext.kt`, `EidosSearchSemanticEnrich.kt` |
| Prompt assembly (legacy) | `EidosApiClient.kt` (passthrough fallback), `EidosContextLimits.kt`, `PanelPlatformSpec.kt`, `EidosChatViewModel.kt`, `WidgetVoiceService.kt` |
| Prompt sentinels | `PromptAssemblySentinelTest.kt`, `EidosPromptComposer*Test.kt`, `EidosScopeRouterTest.kt` |
| Tools | `EidosToolCatalog.kt`, `RoomToolExecutor.kt` |
| Semantic index | `SemanticIndexer.kt`, `SemanticChunkBuilder.kt`, `SemanticMaterializer.kt`, `SemanticSyncService.kt`, `SemanticScopeSearch.kt`, `EmbeddingEngine.kt` |
| Providers | `KimiProvider.kt`, `AnthropicProvider.kt`, `OpenAIProvider.kt`, `XAIProvider.kt` |
| Reasoning preview | `ReasoningPersistPolicy.kt`, `ReasoningTrace.kt`, `EidosChatScreen.kt` |
| Summaries | `ContentSummaryService.kt`, `WorkshopSpecMarkdown.kt` |
| Rollover | `MemoryRolloverService.kt`, `RolloverOrchestrator.kt`, `RolloverAuditLogger.kt` |
