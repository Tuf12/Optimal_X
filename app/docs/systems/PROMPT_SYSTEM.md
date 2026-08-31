# Eidos Prompt System

**Status:** Active — verified against scope router **2026-06-22**  
**Canonical assembly spec:** [PROMPT_SCOPE_ROUTER_PLAN.md](../implementation/PROMPT_SCOPE_ROUTER_PLAN.md) (profile ontology, location, tools, composer)  
**Implementation tracker:** [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](../implementation/PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md)  
**Pre-router architecture (superseded):** [PROMPT_ARCHITECTURE_FIX_PLAN.md](../implementation/PROMPT_ARCHITECTURE_FIX_PLAN.md)  
**Transport fix (shipped):** [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](../implementation/PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md)  
**Retrieval:** [SEARCH_AND_RETRIEVAL.md](./SEARCH_AND_RETRIEVAL.md)  
**LLM layer:** [API.md](../reference/API.md) · [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) · [EIDOS_LLM_CONTEXT_CLEANUP.md](../implementation/EIDOS_LLM_CONTEXT_CLEANUP.md) · [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) (Kimi K2.6)

---

## Assembly (runtime)

All `EidosApiClient.send()` calls resolve an **`EidosScopeProfile`** via `EidosScopeRouter` (`scopeType` + `entrySurface` + workshop mode/phase), then compose the system prompt in **`EidosPromptComposer`**. Tool allowlists come from **`EidosScopeProfileRegistry`**. Legacy `assembleSystemPrompt()` remains as a thin fallback only; user-facing and internal background jobs route through the composer (verified 2026-06-22).

Profile ids, ontology blocks, and tool matrices: [PROMPT_SCOPE_ROUTER_PLAN.md](../implementation/PROMPT_SCOPE_ROUTER_PLAN.md). Code-traced drift notes: [PROMPT_SCOPE_INVENTORY.md](../implementation/PROMPT_SCOPE_INVENTORY.md).

### Prefetch (`## Retrieved context`) — shipped 2026-07

On **substantive** user messages, `EidosPrefetchService` runs on-device semantic search **before hop 1** and injects ranked chunks under `## Retrieved context` (after identity, before location/volatile blocks). Greetings and very short messages skip prefetch. `search_semantic` remains for refinement and edits.

| Profile | Prefetch on | Passes (summary) | Budget |
|---------|-------------|------------------|--------|
| `general.app` | ✅ | Daily + LTM + Journal + global notes + past chats | 6 chunks / ~3.5k chars |
| `parent` | ✅ | Parent-scope notes + memory corpora + chats | same as main chat |
| `subfolder` | ✅ | `local_first` active note + memory corpora + chats | same; dedup when note body inlined |
| `widget.ask` / `widget.chat` | ✅ | Memory corpora + global notes + chats | 4 chunks / ~2k chars |
| `workshop.chat` / `plan` / `edit` / `intake` | ✅ | `local_first` workshop files + notes (+ `project_summary` chunk) | 3–6 chunks by mode |
| `quick_notes.*`, `dump_edit`, gallery, runner, internal | — | — | Tool-only retrieval |

Planner detail: [EIDOS_PREFETCH_RAG_PLAN.md](../implementation/EIDOS_PREFETCH_RAG_PLAN.md). Prefetch vs tool table: [SEARCH_AND_RETRIEVAL.md](./SEARCH_AND_RETRIEVAL.md#prefetch-vs-search_semantic). Debug: Settings → Developer → **API Trace** → run `sectionCharCountsJson` (`prefetchPassCount`, `prefetchHitCount`, `prefetchChars`, `prefetchDailyHits`, `prefetchNoteHits`).

---

## LLM providers

| Provider | Model ID | Prompt / transport notes |
|---|---|---|
| **Kimi (primary)** | `kimi-k2.6` | Thinking enabled; Formula web tools; `reasoning_content` replay — [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) |
| xAI | `grok-4.3` | Responses API; hosted `web_search`; incremental tool continuations |
| OpenAI | `gpt-5.6-luna` | Responses API; `reasoning.effort: medium`; explicit prompt cache |
| Anthropic | `claude-sonnet-4-6` | Messages API; `cache_control` on stable prefix |

Provider-specific web and cache behavior in the system prompt defers to [API.md](../reference/API.md) and [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md).

---

## Core Behavioral Rule

Applies to: Parent Folder, Subfolder, Workshop, Dumpedit

```
Search before reading. Read before writing. Never assume content — retrieve it.
```

Primary retrieval is **`search_semantic`** (chunk embeddings). Read tools expand line ranges or prep edits — not a required second hop for Q&A.

---

## Tool Declaration Structure

Contexts that carry the core behavioral rule use this tool order:

```
Primary tools:
- search_semantic
- read_file / workshop_read_file
- write_note / note_replace_string / write_note_summary / workshop_write_file

Extended tools:
[remaining catalog]
```

All other contexts use profile-scoped allowlists from `EidosScopeProfileRegistry` / `EidosToolCatalog.toolsForProfile()` (verified 2026-06-22).

---

## Web Search

Declared separately from the tool catalog in every context that includes it.

Instruction: use web search only when the user explicitly requests current information or the answer clearly requires data beyond model knowledge.

---

## Tool Loop

One **user Send** may trigger multiple **HTTP rounds** while the model returns `tool_calls`. Distinguish:

| Concept | Meaning |
|---------|---------|
| **User turn** | One tap Send → one `EidosApiClient.send()` call from the UI |
| **HTTP round** | One provider HTTP request inside that `send()` (FULL or TOOL_CONTINUATION) |
| **Turn context** | System prompt + tools + chat history + user message — built once in Kotlin per user turn |
| **Hop context** | New assistant `tool_calls` + tool results appended between HTTP rounds |

### Caps (per user turn, inside one `send()`)

Caps are **profile-owned** (`EidosScopeProfile.loopPolicy: ToolLoopPolicy`) and enforced as a hard circuit breaker in `EidosApiClient.send()`; on exceed the loop stops with an honest `EidosToolLoopPause` message (marker `[tool_loop_paused]`) rather than silently churning more billed hops. Re-shipped profile-owned 2026-07-01.

| Context | Max tool rounds | Code |
|---------|-----------------|------|
| General / subfolder / parent / widget / quick notes / web / etc. | 13 | `EidosScopeProfile.GLOBAL_MAX_TOOL_ROUNDS` (default `loopPolicy`) |
| Workshop Chat mode | 2 | `EidosScopeProfile.WORKSHOP_CHAT_MAX_TOOL_ROUNDS` (`workshop.chat` profile) |
| Workshop build / plan / edit / intake | 12 | `EidosScopeProfile.WORKSHOP_BUILD_MAX_TOOL_ROUNDS` (workshop build profiles) |

Repeated failure guard (target): if the same tool call fails twice consecutively, terminate regardless of iteration count — **not implemented yet**.

Auto-Continue (workshop kickoffs) is **retired** (2026-06-14) — superseded by the prompt scope router. Do not re-implement.

### HTTP transport per provider family (target)

Canonical detail: [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](../implementation/PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md).

| Family | Providers | Hop 1 (`FULL`) | Hop 2+ (`TOOL_CONTINUATION`) |
|--------|-----------|----------------|------------------------------|
| **RESPONSES_CHAINED** | xAI, OpenAI | system + history + user | **No system**; `previous_response_id` + incremental input (last assistant + tool round only) |
| **MESSAGES_CACHED** | Kimi, Anthropic | Hop 1: cached system + history + user | Hop 2+: **omit system block**; append messages + stub old bulk tool results |

**Turn assembly:** `EidosPromptComposer.compose()` runs once per user turn in memory. **Wire policy:** stable turn context is sent on hop 1 only (Responses) or once per turn with cache markers (Messages) — **not** re-attached on every tool hop.

**Responses (xAI/OpenAI):** Workshop uses the same incremental tool continuations as other scopes — hop 2+ sends empty system + `previous_response_id` + last tool round only. Gated by `providerFamilyUsesIncrementalToolContinuation(family)` **and** the profile's `transportHints.incrementalContinuation` (default true). Re-restored profile-owned 2026-07-01.

**Messages (Kimi/Anthropic):** Hop 1 full system (cached); hop 2+ **omit system block** (`providerFamilyOmitsSystemOnToolContinuation(family)` + `transportHints.omitSystemOnContinuation`) — rely on `prompt_cache_key` + growing `messages[]` + bulk tool stubs. `KimiProvider`/`AnthropicProvider` skip the system block when `systemPrompt` is blank.

**`userMessage = ""` on hop 2+:** Correct — the user text is seeded into `conversationHistory` once; continuation is assistant tool calls → tool results → next model turn.

**Guardrails (do not regress):** Never opt Panel Workshop out of incremental continuation (no `!isPanelWorkshop` guard). Never re-attach the full system prompt on `TOOL_CONTINUATION` hops. Never remove the profile `loopPolicy` cap from the `send()` tool loop. Transport + loop policy live on `EidosScopeProfile` so they cannot be silently dropped by a `send()` rewrite.

---

## Memory & History

### Conversation history (in-chat)

User-controlled **Low / Medium / High** tier (default: low). Controls how much **chat thread** is resent — not note/file bodies.

**Note:** `trimHistoryIfNeeded` drops oldest **user exchanges** when over tier budget (atomic assistant + tool rounds). Bulky tool **results** in outbound API replay are stubbed separately via `prepareOutboundHistory` (last 3 tool rounds kept verbatim per turn).

### Memory layers (Daily, LTM, Journal, subfolder cache)

| Layer | Prompt policy | Rationale |
|-------|---------------|-----------|
| **Daily Memory** | **Prefetch** on `general.app`, `parent`, `subfolder` (+ one-line fallback when today has entries but no daily hit); **never** blunt 6k inject ✅ 2026-07-10 | Widget/workshop: via prefetch only when profile enabled |
| **Long-Term Memory** | **Do not inject** — `search_semantic` + `read_long_term_memory(query)` | Large, grows over time; embedded in semantic index. |
| **Journal** | **Do not inject** — `search_semantic` + `read_journal(query)` | Embedded in semantic index; prompt inject deferred until journal write quality is fixed (spam). |
| **Folder memory ([Memory])** | **Inject** in subfolder scope via `NotePromptContext`; user/Eidos co-op via `write_note_summary` + editor panel | Replaces legacy subfolder memory cache (removed 2026-06). ✅ verified 2026-06-22 |

Eidos Index (Tag & Hint) was **removed** from the shipping app — use `search_semantic` for retrieval.

---

## Prompt Contents by Context

Legend: **Inject** = in system prompt · **Tool** = fetch via tool · **Search** = `search_semantic` · **—** = not applicable · **✅ verified YYYY-MM-DD** = covered by scope-router composer tests (not full golden snapshot unless noted)

### General Chat (`general.app`)

| Component | Policy |
|---|---|
| Eidos identity | Inject ✅ verified 2026-06-22 |
| Profile ontology + location | Inject ✅ verified 2026-06-22 |
| Tool-first + active-location rules | Inject ✅ verified 2026-06-22 |
| Tool allowlist (registry) | API `tools` array ✅ verified 2026-06-22 |
| Provider web prose | Inject when profile allows ✅ verified 2026-06-22 |
| **Retrieved context (prefetch)** | Daily, LTM, Journal, **user notes**, past chats — semantic search before hop 1 (6 chunks / ~3.5k chars; reserved note + chat slots) ✅ 2026-07-11 |
| Daily memory (blunt inject) | — (relevant daily chunks via prefetch + one-line fallback) |
| Long-term memory | Search / Tool |
| Journal | Search / Tool |
| Folder / note content | Prefetch (global user notes) + `search_semantic` for more |

Tone: conversational, not task-pushing. Tools available but not volunteered.

---

### Widget Ask / Widget Chat (`widget.ask`, `widget.chat`)

Separate profiles — **not** the same prompt as `general.app` (different location blocks; same tool set). ✅ verified 2026-06-22

| Component | Policy |
|---|---|
| Eidos identity + tool-first rules | Inject ✅ verified 2026-06-22 |
| Widget-specific location block | Inject ✅ verified 2026-06-22 |
| **Retrieved context (prefetch)** | Inject on substantive messages — Daily, LTM, Journal, **user notes**, and past chat chunks via on-device semantic search (tighter budget than main app: 4 chunks / ~2k chars; reserved note + chat slots) ✅ 2026-07-11 |
| Daily memory (blunt inject) | — (never injected; relevant daily chunks arrive via prefetch) |
| `write_quick_note` | — (widget Ask/Chat do not expose; Quick Note capture uses `widget.quick_note` → `quick_notes.day`) |

---

### Quick Notes (`quick_notes.day`, `quick_notes.root`)

| Component | Policy |
|---|---|
| Day inbox capture rules | Inject ✅ verified 2026-06-22 |
| Root browse rules | Inject ✅ verified 2026-06-22 |
| Daily memory | — |

---

### Parent Folder (`parent`)

| Component | Policy |
|---|---|
| Eidos identity + rules | Inject ✅ verified 2026-06-22 |
| Bounded subfolder catalog (≤20) + parent scope rules | Inject ✅ verified 2026-06-22 |
| Parent `search_semantic` auto-enrich (`scopeType=parent`) | Orchestrator ✅ verified 2026-06-22 |
| Provider web | Inject ✅ verified 2026-06-22 |
| **Retrieved context (prefetch)** | Same as `general.app` (memory + global notes + chats) ✅ 2026-07-11 |
| Daily memory (blunt inject) | — (prefetch + one-line fallback) |
| Long-term memory / journal | Search / Tool |
| Note content in child subfolders | Parent-scope prefetch pass + `search_semantic` |

---

### Subfolder / Note (`subfolder`)

| Component | Policy |
|---|---|
| Eidos identity + rules + note-write rules | Inject ✅ verified 2026-06-22 |
| Subfolder volatile context (`SubfolderContext`) | Inject ✅ verified 2026-06-22 |
| Panel bridge (when active) | Inject ✅ verified 2026-06-22 |
| Provider web | Inject ✅ verified 2026-06-22 |
| **Retrieved context (prefetch)** | `local_first` on active subfolder + memory corpora + chats; dedup note hits when body inlined ✅ 2026-07-11 |
| Daily memory (blunt inject) | — (prefetch + one-line fallback) |
| File list bodies | Tool — manifest line defers to `list_folder_contents` / `read_file` |
| Note content | `NotePromptContext` tiers: inline small body; large notes memory-only + tools ✅ |

**Note content rules (prompt):**

| Condition | Sent |
|---|---|
| Small body (≤ `INLINE_NOTE_MAX_CHARS`) | Full body + `[Memory]` if present |
| Large body | `[Memory]` (+ leftover `[Content]` if stored); no body slice — prefetch / `search_semantic` / `read_note` |
| `aiBlind` | Nothing |
| `aiLocked` | Memory if exists; else lock notice |

---

### DumpEdit (`dump_edit` scope)

| Component | Policy |
|---|---|
| Eidos identity + tool-first rules | Inject ✅ verified 2026-06-22 |
| DumpEdit buffer block | **This turn** (rules in Provider & scope) ✅ verified 2026-06-22 |
| `read_dump_edit` | Tool when buffer large or AI locked |
| Folder create/write for buffer | **No** — user promotes manually |

Buffer injection (`DumpEditContext`):

| Condition | Injected context |
|-----------|------------------|
| Empty | “Empty buffer” |
| `aiBlind` | Blind notice — no content |
| `aiLocked` | Lock notice — use `read_dump_edit` |
| ≤ 8K chars, not blind/locked | Full buffer text |
| > 8K chars | Semantic excerpts for user message + `read_dump_edit` hint |

Not a subfolder — no `list_folder_contents` default target for buffer writes.

---

### Workshop (`workshop.plan`, `workshop.edit`, `workshop.intake`, `workshop.chat`, build phases)

**Execution profiles:** [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) — **build run** (kickoffs + Auto-Continue) vs **workshop edit** (Diff Review, short sends). Prompts must not treat both the same.

| Component | Policy | When (HTTP) |
|---|---|---|
| Workshop mode identity + mode instructions | Inject (profile-aware: build run vs edit) ✅ verified 2026-06-22 | Hop 1 only |
| Workshop retrieval policy (`EIDOS_WORKSHOP_RETRIEVAL_POLICY`) | Inject **once** via `WorkshopPanelContext.buildVolatileContext()` tail — not duplicated in mode instruction blocks ✅ verified 2026-06-22 | Hop 1 only |
| Tool allowlist (registry per profile) | API `tools` array ✅ verified 2026-06-22 | Every HTTP round |
| Provider web | `workshop.chat` only — plan/edit/intake gated off ✅ verified 2026-06-22 | Hop 1 when allowed |
| Daily memory | — (not injected on workshop profiles) | — |
| Long-term memory / journal | Search / Tool | — |
| File manifest | Inject (names + `fileReferenceId` only) ✅ verified 2026-06-22 | Hop 1 |
| README / spec `.md` excerpt | — (prefetch or `workshop_read_file` when needed) ✅ 2026-07-15 | Not blunt-injected on hop 1 |
| Project summary | Prefetch (`project_summary` chunk) + `search_semantic` / `workshop_read_file` ✅ 2026-07-15 | Auto after Generate specs + doc-align when specs change; manual Regenerate in drawer |
| **Retrieved context (prefetch)** | `local_first` workshop file/note/`project_summary` chunks ✅ 2026-07-15 | After identity block |
| Open editor file excerpt | — (prefetch when relevant) ✅ 2026-07-15 | Was up to 6k blunt inject on plan/edit |
| Diff Review pending count | Inject when review phases active | **This turn** |
| Panel bridge (functions + usage) | Inject when Preview / panel tab active | Stable **Project context** |
| Panel bridge recent events | Last 3 events with timestamps | **This turn** only |
| **Workshop Chat cross-source retrieval** | `read_file` on allowlist; host-link block + `search_semantic` auto-enrich when panel linked to parent/subfolder ✅ verified 2026-06-22 | — |
| Runtime HTML/CSS/JS | **Never** inline | `search_semantic` → `workshop_read_file` |

**System prompt sections (all scopes):** `## Identity & rules` → `## Provider & scope` → `## Project context` → `## This turn`. Volatile per-turn data must appear only under **This turn**. Composer: [EidosPromptComposer.kt](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosPromptComposer.kt) (supersedes pre-router `EidosPromptSections` path for user-facing scopes).

Steady state: manifest + search → read → write. **Build run:** Auto-Continue chunks + handoff. **Edit:** Diff Review, no long chains.

Trace logs listing every filename are the **manifest** (metadata), not full-file injection. Large char counts after tool use usually come from **tool results in history**, not the system string.

---

## Coding agent rules (prompt + transport)

When changing Eidos prompts or `EidosApiClient.send()`:

1. **Never** set `useIncremental = false` for workshop on Responses providers (xAI/OpenAI) — use `previous_response_id` chaining like other scopes.
2. **Never** inline spec or runtime file bodies on `TOOL_CONTINUATION` hops — turn-start orientation only.
3. **Never** duplicate `EIDOS_WORKSHOP_RETRIEVAL_POLICY` inside every `PanelPlatformSpec.eidos*Instructions()` block — inject once per turn.
4. **Never** document full system + full history replay per hop as intentional or required for `fileReferenceId` retention — IDs survive via hop-1 context, chaining, and tool results within our caps.
5. Prefix cache reduces **billing** on repeated identical prefixes; it does **not** justify spamming stable context on every hop (latency and limits remain).
6. **Volatile per-turn data** (open-file excerpt, Diff Review count, bridge events, loaded web URL, memory-depth/trim notices) goes in the **`## This turn`** section only — never between stable blocks. Enforced by `EidosPromptComposer` (verified 2026-06-22).

---

### Panel Gallery (`panel_gallery`)

| Component | Policy |
|---|---|
| Gallery ontology + location block | Inject ✅ verified 2026-06-22 |
| Tool allowlist (registry) | API `tools` array ✅ verified 2026-06-22 |
| Daily memory | — |

---

### Panel Runner (`panel_runner`)

| Component | Policy |
|---|---|
| Runner ontology + active panel context | Inject ✅ verified 2026-06-22 |
| Tool allowlist (registry) | API `tools` array ✅ verified 2026-06-22 |

---

### Internal background scopes

No `TOOL_FIRST_CONTEXT_RULES`. Routed through `EidosPromptComposer` with `EidosInternalPromptBlocks` / `RolloverPromptBlocks` — ✅ verified 2026-06-22.

| Profile | Purpose |
|---|---|
| `internal.content_summary` | Note/workshop summary generation |
| `internal.note_rolling_summary` | Rolling note digest |
| `internal.conversation_summary` | Conversation rolling summary |
| `internal.memory_rollover` | End-of-day memory rollover |

API trace observability (`toolAllowlistHash`, `stablePrefixSha256`, `sectionCharCountsJson`) on `eidos_api_trace_runs` — ✅ verified 2026-06-22.

---

### Web Browser (`web`)

| Component | Policy |
|---|---|
| Eidos identity + web-primary rules | Inject ✅ verified 2026-06-22 |
| Tool allowlist (registry) | API `tools` array ✅ verified 2026-06-22 |
| Provider web | Inject, primary for external facts ✅ verified 2026-06-22 |
| Daily memory | — |
| Long-term memory / journal | Search / Tool |
| Current page | URL (+ title when known) ✅ verified 2026-06-22 |
| Page content | Tool / provider web fetch |

---

## Note & File Summaries

Auto **project summary** after Generate specs and doc-align (`WorkshopProjectSummaryAutomation` → `ContentSummaryService.generateWorkshopProjectSummary`). Manual **Regenerate** in the workshop drawer. Per-note folder memory uses the editor **Folder memory & digest** panel and `write_note_summary` — see [NOTE_SUMMARY.md](NOTE_SUMMARY.md).

`read_file` and `workshop_read_file` accept optional **`query`**, **`startLine`**, **`endLine`** for section expansion (see `EidosToolCatalog` and `SEARCH_AND_RETRIEVAL.md`). Notes use inject tiers + `search_semantic` instead of a read tool.

---

## Implementation Checklist

Code status as of **2026-06-22** (scope router Phases 0–6). ✅ done · 🟡 partial · ❌ not done · **Target** = deferred v2

### Scope router & composer

| Item | Status |
|------|--------|
| `EidosScopeRouter` + `EidosScopeProfileRegistry` | ✅ |
| `EidosPromptComposer` — all user-facing profiles | ✅ verified 2026-06-22 |
| Widget split (`entrySurface` → `widget.ask` / `widget.chat` / `widget.quick_note`) | ✅ verified 2026-06-22 |
| Internal scopes in composer (no ad-hoc `baseSystemPrompt`) | ✅ verified 2026-06-22 |
| API trace prompt observability (hash + section counts) | ✅ verified 2026-06-22 |
| Golden prompt snapshot tests (full per-profile) | 🟡 composer unit tests shipped; golden snapshots still ⏳ |
| Delete legacy `assembleSystemPrompt` branches entirely | 🟡 thin passthrough fallback remains |

### Retrieval & tools

| Item | Status |
|------|--------|
| Chunk-level semantic index (notes, files, conversations) | ✅ |
| `search_semantic` primary in tool catalog | ✅ |
| Scoped search (`local_first`, `expand_if_weak`) | ✅ |
| `read_*` query + line ranges | ✅ |
| Eidos Index / Tag & Hint removed | ✅ |
| Startup + Settings rebuild semantic index | ✅ |

### Provider transport & tool loops

| Item | Status |
|------|--------|
| Kimi `reasoning_content` replay + `thinking.keep: all` | ✅ |
| OpenAI/xAI reasoning capture + chat bubble preview | ✅ |
| Kimi workshop write replay redaction | ✅ |
| History trim disabled (tool-safe trimmer pending) | ✅ |
| Global max iterations 13 / workshop build 12 per send | 🟡 workshop cap shipped Phase 1 (`WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS`); global 13 still open |
| Workshop incremental continuations (Responses) | ✅ 2026-06-08 |
| Workshop lean system (shared tail, hop-1-only volatile blocks) | ✅ 2026-06-08 |
| Tool-result history trim (stale bulk tool results) | ✅ 2026-06-08 |
| API trace compounding factor | ✅ Phase 5 |
| Duplicate tool-failure guard | ❌ |
| Anthropic multi-tool regression verified | 🟡 |

### Prompt text & policy

| Item | Status |
|------|--------|
| Semantic-first rules in `TOOL_FIRST_CONTEXT_RULES` | ✅ |
| Per-profile ontology via `EidosIdentityPrompt` + registry blocks | ✅ verified 2026-06-22 |
| Web search “only when needed” wording | ✅ |
| Conversational base prompt tone | ✅ |
| Memory tool pointer lines (LTM/journal = search) | ✅ |
| Workshop retrieval policy deduped (once per turn) | ✅ verified 2026-06-22 |
| Stable-prefix section structure (`EidosPromptComposer`) | ✅ verified 2026-06-22 |
| Tool-hop budget line in kickoff + edit prompts | ✅ |
| `workshop_replace_string` worked example in patch policy | ✅ |
| Workshop Chat: `read_file` + host-link semantic enrich | ✅ verified 2026-06-22 |
| Provider web gated off workshop plan/edit/intake | ✅ verified 2026-06-22 |

### Location context

| Item | Status |
|------|--------|
| Note summary-only in subfolder prompt | ✅ verified 2026-06-22 |
| Parent folder: bounded subfolder catalog (≤20) | ✅ verified 2026-06-22 |
| Subfolder: file list via tools (not inlined bodies) | ✅ by design |
| Daily memory inject on general/parent/subfolder | ✅ verified 2026-06-22 |
| Workshop cold-start README/spec bounded | 🟡 (`WorkshopSpecMarkdown`) |
| Workshop manifest (+ bounded spec fallback) | ✅ |
| Cross-scope note summary inject via host link | Target v2 (not implemented) |

### Memory layers

| Item | Status |
|------|--------|
| LTM / journal **not** in system prompt (search instead) | ✅ |
| Daily memory inject on general/parent/subfolder | ✅ verified 2026-06-22 |
| Journal write quality / spam fix before any inject | ❌ |

### Workshop UX (Auto-Continue + polish)

| Item | Status |
|------|--------|
| `search_semantic` in all workshop modes (scoped to project) | ✅ |
| Mode instructions: search → read(query) → write | ✅ |
| Build run vs edit execution profiles documented | 🟡 [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](../implementation/PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) |
| Auto-Continue (chunked kickoffs + LLM handoff) | ✅ Phase 1.5 (2026-06-03) |
| Tool-hop cap + workshop prompt cleanup | ✅ Phase 1 (2026-06-03) |
| Mode clarity (Chat / Plan / Edit / Build / Debug) | 🟡 working |
| Chat mode tool round cap | ✅ (2 rounds) |
| Panel bridge inactive guidance in prompt | ✅ |

### Documentation

| Item | Status |
|------|--------|
| [SEARCH_AND_RETRIEVAL.md](./SEARCH_AND_RETRIEVAL.md) | ✅ |
| [PROMPT_SCOPE_ROUTER_PLAN.md](../implementation/PROMPT_SCOPE_ROUTER_PLAN.md) Phases 0–6 | ✅ 2026-06-22 |
| [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](../implementation/PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) | ✅ 2026-06-22 |
| [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](../implementation/PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md) | ✅ 2026-06-08 |
| [PROMPT_ARCHITECTURE_FIX_PLAN.md](../implementation/PROMPT_ARCHITECTURE_FIX_PLAN.md) | Superseded by scope router (2026-06-22) |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](../implementation/EIDOS_LLM_CONTEXT_CLEANUP.md) status update | 🟡 |
| [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) sync | 🟡 |
| [API.md](../reference/API.md) — Kimi K2.6 + four providers | ✅ |
