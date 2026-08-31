# Eidos prefetch RAG plan

| Field | Value |
|--------|--------|
| **Status** | **Complete — shipped 2026-07-15** |
| **Audience** | Product, prompt authors, Kotlin implementers, coding agents |
| **Scope** | Proactive semantic retrieval **before** the first API hop per user turn — memory, notes, conversations, and (later) workshop files |
| **Related** | [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md), [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md), [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md), [NOTE_SUMMARY.md](../systems/NOTE_SUMMARY.md), [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md), [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md), [DumpEditContext.kt](../../src/main/java/com/example/optimalx/data/dumpedit/DumpEditContext.kt) (precedent) |

---

## Executive summary

Today Eidos retrieves most memory **reactively**: the model must call `search_semantic` (extra hop, easy to skip). Daily Memory is **always** injected (up to 6k chars) whether or not it matters. Long-Term Memory and Journal are indexed but rarely surface on turn 1. Personal facts the user states (location, age, preferences) are not reliably promoted to LTM or folder `[Memory]` bullets.

**Goal:** add a **prefetch retrieval layer** that runs on-device **after** the user sends a message and **before** the first provider call. Ranked `chunk_text` hits are injected in a **volatile** prompt section. `search_semantic` stays available for refinement, edits, and conversation-history deep dives.

**Non-goals:** replacing the tool loop; embedding-driven tool selection; prefetching from the **system prompt text** as the search query; shipping workshop prompt slimming before core prefetch infra is proven.

### Product outcome

More **personal, grounded** replies without bloating every prompt. Eidos “remembers” relevant daily facts, LTM, journal, note passages, and (optionally) past chat chunks when the user’s message warrants it — not on every “hi”.

---

## Problem statement (observed)

| Symptom | Likely cause |
|---------|----------------|
| User repeats location, age, sex, preferences | `write_long_term_memory` under-used; weak tool descriptions; no prompt nudge when user states durable facts |
| Daily memory barely written | Model does not distinguish **daily** (today’s working context) vs **LTM** (durable facts) |
| Journal entries stale / user-centric | Journal tool copy says “your experiences not the user”; rollover journal step quality varies |
| LTM entries exist but low quality | No structured guidance for **personal profile** bullets vs project noise |
| `write_note_summary` under-used in subfolders | Large-note nudge exists; no parallel nudge for “user just told you a durable fact” |
| First-turn answers miss memory | Tool-first retrieval; model skips `search_semantic` |
| Workshop prompts heavy | File manifest + project summary + open-file excerpt inlined in `WorkshopPanelContext` regardless of user question |

---

## Design principles

### 1. Query source — user turn only (not system prompt)

**Prefetch query** is built from:

1. Latest **user message** (required when substantive)
2. Optional **rolling conversation summary** (`Conversation.threadSummary`) when the thread is folded — blend, do not replace the user message
3. **Never** embed or search against the assembled system prompt, tool definitions, or identity block

> **Clarification:** An earlier brainstorm mentioned “scope keywords” (e.g. appending subfolder name to the query). **Not needed.** Location is enforced via `SemanticScopeSearch` filters (`scopeType`, `scopeId`) and profile policy — not by stuffing folder names into the query string.

The TFLite/MediaPipe embedder **only ranks chunks**. It does **not** choose tools, change the tool allowlist, or alter provider routing. Tool policy remains on `EidosScopeProfile.toolPolicy`.

### 2. Volatile injection block — cache-safe

Composer layout (target):

```
## Identity & rules          ← stable (EidosIdentityPrompt, ontology, tool-first rules)
## Location & scope          ← semi-stable per session
## Retrieved context         ← NEW — volatile per turn; omitted when empty / gated out
## This turn                 ← daily memory (transition), workshop volatile, etc.
```

Put prefetch results in **`## Retrieved context`** (or equivalent delimiter) **after** the stable prefix so provider prompt-cache prefixes stay valid. Record `retrievedContext` char count in `ComposedPrompt` / API trace (extend `EidosContextTransportMetrics`).

### 3. Gating and budget

| Rule | Value / behavior |
|------|------------------|
| **Skip prefetch** | Empty user message, greetings (“hi”, “thanks”, “ok”), or message &lt; ~12 chars after trim (tunable) |
| **Score floor** | Reuse `SemanticScopeSearch.WEAK_SCORE_THRESHOLD` (0.55) — no block if top hit below |
| **Chunk limit** | Default 6 hits; profile overrides allowed |
| **Char budget** | Default 3_500 chars total retrieved text; hard cap 5_000 |
| **Deduplication** | Drop hits whose `chunk_text` overlaps already-injected note body (subfolder inline tier) |

### 4. Hybrid retrieval — prefetch + tools

| Prefetch (turn 1) | `search_semantic` (tool) |
|-------------------|---------------------------|
| Memory corpus + scoped notes + optional chat chunks | Refined query after partial context |
| Ground personal / project Q&A without a hop | `note_replace_string` — need fresh `chunk_text` + line refs |
| | `read_file` / `workshop_read_file` after file hits |
| | Conversation history when user asks about a past thread explicitly |

Update `EidosContextLimits.TOOL_FIRST_CONTEXT_RULES` and tool descriptions: **“Retrieved context may already include relevant passages; call search_semantic when you need more or are editing.”**

### 5. Memory writing is a parallel concern — not a blocker

Prefetch **surfaces** what is already indexed. Poor LTM/journal **content** still hurts quality. **Phase 0** (lightweight prompt + tool copy) should land **before or alongside** Phase 1 so prefetch retrieves better bullets once users/Eidos write them. Do **not** delay the retrieval engine until rollover/journal are perfect.

---

## Architecture

```
User message (+ optional threadSummary)
        │
        ▼
 EidosRetrievalPlanner.plan(profile, sendContext, query)
        │  → List<RetrievalPass> (scopeMode, scopeId, corpus filter, limit)
        ▼
 SemanticScopeSearch.search(...) per pass
        │
        ▼
 EidosRetrievedContextBlock.format(hits, budget, gates)
        │
        ▼
 EidosPromptComposer — append volatile "## Retrieved context"
        │
        ▼
 EidosApiClient.send() hop 1 (tools unchanged)
```

### New / extended types (target)

| Type | Role |
|------|------|
| `EidosRetrievalPlanner` | Profile-driven pass list from `EidosSendContext` + query |
| `EidosRetrievalPass` | One scoped search: `scopeType`, `scopeId`, `corpus` (`memory_daily`, `memory_ltm`, `memory_journal`, `notes`, `files`, `conversations`, `all`) |
| `EidosRetrievedContextBlock` | Format gated hits for prompt |
| `EidosContextPolicy` | Extend with `prefetchPolicy: EidosPrefetchPolicy` |
| `EidosPrefetchPolicy` | `profileEnabled`, `maxChunks`, `maxChars`, `scoreThreshold` per profile |
| `EidosPromptComposeContext` | Add `userMessage`, `conversationId`, `threadSummary` for compose-time retrieval |

**Precedent:** [DumpEditContext.kt](../../src/main/java/com/example/optimalx/data/dumpedit/DumpEditContext.kt) already prefetches buffer excerpts from `userMessage` via [ContentSectionRetriever.kt](../../src/main/java/com/example/optimalx/data/semantic/ContentSectionRetriever.kt) — same pattern, backed by `semantic_chunks` index instead of ad-hoc segmentation.

### Corpus filters (memory)

Journal, Daily, and LTM are all `object_type=note` in `semantic_chunks`. Filter by **system parent folder** when resolving `subfolderId → parentFolderId`:

| Corpus | System parent (`SystemFolderNames`) |
|--------|-------------------------------------|
| `memory_daily` | `EIDOS_DAILY` |
| `memory_ltm` | `EIDOS_MEMORY` |
| `memory_journal` | `EIDOS_JOURNAL` |

Implementation: post-filter search hits (or add optional `parentFolderId` filter to `SemanticIndexer.searchChunks` if cleaner).

---

## Prefetch policy by profile (target)

| Profile | Prefetch passes | Notes |
|---------|-----------------|-------|
| `general.app` | `memory_ltm` + `memory_journal` + `memory_daily` + **global user notes** + `conversations` | Daily via prefetch only; reserved note/chat slots in merge |
| `parent` | `parent` scope notes + memory corpora (gated) | `expand_if_weak` |
| `subfolder` | `local_first` on active subfolder + memory corpora (gated) | Dedup against `NotePromptContext` inline body |
| `widget.ask` / `widget.chat` | Memory corpora + **global user notes** + conversations | `WIDGET_MEMORY`: 4 chunks / 2k chars; reserved note + chat slots |
| `quick_notes.*` | Off or minimal | Capture-first surfaces |
| `workshop.chat` / `workshop.plan` / `workshop.edit` / `workshop.intake` | `local_first` on workshop `subfolderId` (file + note chunks) | Slim volatile block — no blunt open-file/spec/summary inject; manifest + phase gates stay inline |
| `internal.*` | Off | Background jobs keep explicit prompts |

---

## Phases

### Phase 0 — Memory writing hygiene ✅ (2026-07-10)

**Why first:** Prefetch amplifies what is stored. Fix the **write path** copy and prompts before expecting personal continuity to improve.

| Task | Files / notes | Status |
|------|----------------|--------|
| 0.1 Rewrite `write_daily_memory` tool description | [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt) | ✅ |
| 0.2 Rewrite `write_long_term_memory` | [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt) | ✅ |
| 0.3 Add identity prompt memory block | [EidosIdentityPrompt.kt](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosIdentityPrompt.kt) — one-line behavioral nudge; tool-specific detail stays in catalog | ✅ |
| 0.4 Subfolder `[Memory]` nudge | [NotePromptContext.kt](../../src/main/java/com/example/optimalx/data/eidos/NotePromptContext.kt) | ✅ |
| 0.5 Journal tool copy | [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt) | ✅ |
| 0.6 Doc sync | [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md), [JOURNAL_SYSTEM.md](../systems/JOURNAL_SYSTEM.md) | ✅ |
| 0.7 User delete Daily/LTM entries | [EidosSystemScreens.kt](../../src/main/java/com/example/optimalx/ui/eidos/EidosSystemScreens.kt), [EidosSystemMemoryFormat.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosSystemMemoryFormat.kt) — long-press + confirm | ✅ |

**Done when:** Tool descriptions merged; identity block mentions daily vs LTM vs `write_note_summary`; manual chat test: user says “I’m in Chicago, 42, male” → model calls `write_long_term_memory` without user asking.

**Does not include:** Rollover orchestrator rewrite, journal spam cleanup, or automatic backfill of existing LTM notes (optional manual user edit).

---

### Phase 1 — Prefetch infrastructure ✅ (2026-07-10)

| Task | Detail | Status |
|------|--------|--------|
| 1.1 `EidosPrefetchPolicy` + extend `EidosContextPolicy` | Registry defaults per profile; main chat always on (no user toggle) | ✅ |
| 1.2 `EidosRetrievalPlanner` | Pure planner unit tests — no DB | ✅ |
| 1.3 `EidosRetrievedContextBlock` | Format hits: location, `chunk_type`, capped `chunk_text`, source tag | ✅ |
| 1.4 Corpus post-filter helper | `EidosMemoryCorpusFilter` — map hit → daily / ltm / journal | ✅ |
| 1.5 Greeting / short-message skip | `EidosRetrievalQuery.shouldPrefetch(message)` | ✅ |
| 1.6 Wire compose context | `EidosPromptComposeContext.userMessage`, `conversationId`, `threadSummary`; load summary in `EidosApiClient` before `compose()` | ✅ |
| 1.7 Composer hook | `retrievedContextBlockForProfile(...)` appended in `generalAppPromptText`, `parentPromptText`, `subfolderPromptText` | ✅ |
| 1.8 Trace | Log `prefetchPassCount`, `prefetchHitCount`, `prefetchChars`, `topScore`, `skippedReason` on API trace run | ✅ |

**Done when:** Unit tests green; with flag on and synthetic index, compose output contains `## Retrieved context` for a substantive query and **omits** it for “hi”.

---

### Phase 2 — App chat scopes (general, parent, subfolder) ✅ (2026-07-10)

| Task | Detail | Status |
|------|--------|--------|
| 2.1 Enable prefetch on `general.app`, `parent`, `subfolder` | Always on via profile policy | ✅ |
| 2.2 Memory corpus passes | Split into Daily + LTM + Journal global passes (limit 4 each) | ✅ |
| 2.3 Subfolder dedup | Exclude note hits for active subfolder when `NotePromptContext` tier is `INLINE_FULL` | ✅ |
| 2.4 Update tool-first rules | `EidosContextLimits.PREFETCH_RETRIEVAL_RULES` on main chat profiles | ✅ |
| 2.5 Android tests | `EidosPrefetchServiceTest` + unit tests for planner/filter | ✅ |

**Done when:** Subfolder chat about a topic only in LTM returns relevant LTM chunk on turn 1 without `search_semantic` tool call.

---

### Phase 3 — Daily memory transition ✅ (2026-07-10)

**Goal:** Stop shipping up to 6k chars of daily memory on every turn when prefetch can surface **relevant** daily chunks.

| Task | Detail | Status |
|------|--------|--------|
| 3.1 Remove blunt daily inject | Main chat never ships full `DailyMemoryContext` block; prefetch + one-line fallback only | ✅ |
| 3.2 Always include today’s daily note in prefetch corpus | `memory_daily` pass still runs; if user message is vague but daily has content, weak matches may still pass gate — tune | ✅ |
| 3.3 Fallback line | If daily note non-empty but no daily hits pass gate, inject **one** line: “Daily memory exists for today — use write_daily_memory / search_semantic if needed.” | ✅ |
| 3.4 Compare token metrics | `prefetchDailyHits` on API trace; settings toggle removed — prefetch is the only path | ✅ |

**Done when:** `general.app` prompt char count drops on average vs today; daily-specific questions still answer correctly.

---

### Phase 4 — Conversation history prefetch ✅ (2026-07-10)

| Task | Detail | Status |
|------|--------|--------|
| 4.1 Add `conversations` pass | `scopeMode=chat_history`, corpus `CHAT`, limit 3 on main chat profiles | ✅ |
| 4.2 Query blend | `userMessage` + last ~500 chars of `threadSummary` when present | ✅ (Phase 1) |
| 4.3 Exclude current conversation | `excludeConversationId` from active thread in prefetch hit filter | ✅ |
| 4.4 Tool copy | `search_semantic` — past chat threads + prefetch may include excerpts | ✅ |

**Done when:** “What did we decide about X last week?” surfaces a prior thread chunk on turn 1 in general chat.

---

### Phase 4.5 — Widget Ask / Widget Chat prefetch ✅ (2026-07-11)

**Why before Phase 5:** Widget Chat is a primary chat surface; Widget Ask benefits from fast personal-context answers without a tool round-trip. Same memory + conversation corpora as main chat, tighter caps.

| Task | Detail | Status |
|------|--------|--------|
| 4.5.1 Profile policy | `widget.ask` / `widget.chat` → `EidosPrefetchPolicy.WIDGET_MEMORY` | ✅ |
| 4.5.2 Planner passes | Split Daily + LTM + Journal (limit 2 each) + `chat_history` (limit 2) | ✅ |
| 4.5.3 Composer wire | `composeWidgetSurface` runs prefetch; inject `## Retrieved context` after identity block | ✅ |
| 4.5.4 Docs | [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md), this plan | ✅ |

**Not included:** Blunt daily memory inject (widgets never had it); folder-scoped note prefetch (widgets are not folder-anchored).

**Latency note:** Widget Ask disables extended thinking for fast first token; prefetch adds on-device search before hop 1 — budgets are intentionally smaller than main chat.

---

### Phase 4.6 — Global user-note prefetch ✅ (2026-07-11)

**Why:** Widget/general chat is not folder-anchored but users store personal/project facts in notes. Without a note pass, note Q&A required many `search_semantic` tool hops.

| Task | Detail | Status |
|------|--------|--------|
| 4.6.1 Global note pass | `scopeMode=global`, corpus `NOTE` (excludes Daily/LTM/Journal system parents) on `general.app` + widget | ✅ |
| 4.6.2 Reserved merge slots | `reservedNoteSlots` / `reservedChatSlots` in [EidosPrefetchChunkSelector.kt](../../src/main/java/com/example/optimalx/data/eidos/prefetch/EidosPrefetchChunkSelector.kt) | ✅ |
| 4.6.3 AI-blind filter | Skip note hits where `Note.aiBlind` (matches `search_semantic`) | ✅ |
| 4.6.4 Trace | `prefetchNoteHits` on API trace | ✅ |

**Caps:** general — 3 note candidates / 2 reserved slots; widget — 2 note candidates / 1 reserved slot.

---

### Phase 5 — Panel Workshop prompt slim + file prefetch ✅ (2026-07-15)

**Do this after Phases 1–4.** Workshop has the largest volatile prompts ([WorkshopPanelContext.kt](../../src/main/java/com/example/optimalx/data/eidos/WorkshopPanelContext.kt)): file manifest, project summary, spec fallback, open-file excerpt.

| Task | Detail | Status |
|------|--------|--------|
| 5.1 Audit workshop volatile chars | Open-file + spec fallback up to ~12k removed from default inject when prefetch on | ✅ |
| 5.2 Keep in prompt (always) | Project ids, phase, mode, **compact** file manifest (names + ids only), diff-review status, host-link block | ✅ |
| 5.3 Move to prefetch | Project summary, spec excerpts, open-file body — `local_first` on workshop subfolder | ✅ |
| 5.4 Chat mode | File/note prefetch pass; no open-file excerpt (unchanged) | ✅ |
| 5.5 Revisit `WorkshopSpecMarkdown.loadBounded` | Not default-injected when prefetch on (`slimHeavyBlocks`) | ✅ |
| 5.6 Profile `prefetchPolicy` for workshop modes | `WORKSHOP_CHAT` / `WORKSHOP_BUILD` / `WORKSHOP_INTAKE` caps | ✅ |

**Done when:** Workshop edit turn 1 prompt smaller by ≥30% median; codegen questions still retrieve spec chunks via prefetch or one `search_semantic` hop.

---

### Phase 5.1 — Index cached project summary ✅ (2026-07-15)

**Goal:** Prefetch can surface the human-generated `Subfolder.projectSummary` on vague orientation questions — not only spec/file chunks.

| Task | Detail | Status |
|------|--------|--------|
| 5.1.1 `project_summary` semantic chunk | `SemanticChunkBuilder` indexes `Subfolder.projectSummary` as NOTE chunk on subfolder | ✅ |
| 5.1.2 Re-index on generate | `ContentSummaryService.generateWorkshopProjectSummary` calls `indexNote` after save | ✅ |
| 5.1.3 Prefetch display tag | `EidosMemoryCorpusFilter` → `Project summary` tag in Retrieved context | ✅ |
| 5.1.4 Auto-generate summary | After Generate specs + successful doc-align when specs change; digest skip when unchanged | ✅ 2026-07-15 |

**Generate summary:** **automated** after Generate specs and after each successful doc-align when specs changed (`WorkshopProjectSummaryAutomation`). Manual **Regenerate** remains in the workshop drawer.

---

### Phase 6 — Docs, desktop parity, rollout ✅ (2026-07-15)

| Task | Detail | Status |
|------|--------|--------|
| 6.1 Update [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) | Prefetch matrix + per-profile Retrieved context rows | ✅ |
| 6.2 Update [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) | Prefetch vs `search_semantic` table | ✅ |
| 6.3 [OptimalX Desktop design.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md) | Align “Retrieved chunks” layer with mobile planner | ✅ |
| 6.4 Debug prefetch stats | API Trace `sectionCharCountsJson` on each run (no user toggle — prefetch always on per profile) | ✅ |
| 6.5 Golden prompt fixtures | `EidosPrefetchProfileCoverageTest` — rules + retrieved-block placement per profile | ✅ |
| 6.6 Enable flag default on | **Superseded** — profile-owned policy; settings toggle removed Phase 3 | ✅ N/A |

**Initiative complete.** Definition of done below — maintain via API Trace + planner tests on future profile changes.

---

## Memory / journal / rollover — what this plan does **not** fix

| System | Status in this plan |
|--------|----------------------|
| Midnight rollover journal quality | Out of scope — track separately in [ROLLOVER.md](../systems/ROLLOVER.md) |
| Backfill bad existing LTM entries | User/manual or one-off migration script — not required for prefetch |
| Journal spam from past builds | Prefetch gating reduces blast radius; cleanup is editorial |
| `read_journal` / `read_long_term_memory` removal | Already deprecated for user chat per router plan — prefetch reinforces semantic path |

---

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| On-device search latency each send | Cap passes; run searches sequentially only if needed; cache query embedding for multi-pass same turn |
| Wrong chunks steer answers | Score gate + cite source tags; tool-first “verify with search_semantic if unsure” |
| Prompt bloat from prefetch + daily + note inline | Budget + dedup + Phase 3 daily transition |
| Workshop regression (codegen) | Phase 5 last; keep manifest + phase metadata always inline |
| User thinks embedder “controls” Eidos | Query excludes system prompt; tools fixed per profile; document in settings help |

---

## Testing strategy

| Layer | Tests |
|-------|-------|
| Planner | Profile → expected passes; skip greetings |
| Gating | Sub-threshold hits → empty block |
| Corpus filter | Hit in Eidos Memory parent → `LTM` tag |
| Composer | Stable prefix hash unchanged when retrieved block varies |
| Integration | Seed DB + semantic index; compose general/subfolder prompts |
| Manual | Personal fact → LTM write (Phase 0); next session message → prefetch hit (Phase 2) |

---

## Definition of done (whole initiative) ✅

1. Phase 0 tool copy + identity memory guidance shipped.
2. Prefetch enabled for `general.app`, `parent`, `subfolder`, widget, and workshop profiles with gating and budget.
3. Daily inject replaced by gated prefetch on main chat (Phase 3).
4. Conversation history + global user-note prefetch (Phases 4–4.6).
5. Workshop volatile prompt slimmed with file/note/`project_summary` prefetch (Phase 5).
6. [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) + [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) updated; API Trace shows prefetch metrics.
7. `search_semantic` remains in allowlists; second-hop retrieval works for edits and refinement.

---

## Suggested first PR (Phase 0 + 1 skeleton)

1. Tool description updates (Phase 0.1–0.5) — no behavior change except model steering.
2. `EidosPrefetchPolicy`, `EidosRetrievalPlanner`, `EidosRetrievedContextBlock` + unit tests.
3. Feature flag off; composer accepts empty retrieved block.
4. No profile enabled until Phase 2 PR.

---

## Open questions (product)

| Question | Default recommendation |
|----------|------------------------|
| Enable prefetch on widget surfaces? | Yes, memory corpora only, tight budget |
| Auto-call `write_long_term_memory` when user shares personal facts? | Prompt nudge only in Phase 0; no silent auto-write |
| Merge memory corpora into one search pass? | Start with one global pass + post-filter; split if precision suffers |
| Desktop mobile proxy `/search`? | Phase 6 — desktop can mirror same planner API |
