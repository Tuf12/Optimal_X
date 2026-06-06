# Eidos LLM context & provider architecture

**Status:** Phases 1–5 **complete** for original scope (2026-05-21). Superseded in part by chunk semantic search — see [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md) and [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md).  
**Goal:** Smart reasoning, lean prompts, tool-first context, correct per-provider transport.

**Kimi K2.6:** Moonshot-specific transport, preserved thinking, Formula web tools, streaming, and chat-linked reasoning UX are defined in [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md). When that doc conflicts with this file on Kimi behavior, **follow the Kimi spec** and update this doc.

### Completion summary

| Area | Done? | Notes |
|------|-------|-------|
| Phase 1 Provider wiring (Kimi, families, cache keys, reasoning) | 🟡 | Kimi: thinking + cache + Formula `web_search` shipped; full Moonshot preserved-thinking + streaming → [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md) |
| Phase 2 Lean context baseline | ✅ | Evolved: semantic chunks replace bulk inline; parent/subfolder **inventories** planned in PROMPT_SYSTEM |
| Phase 3 Per-conversation memory UI | ✅ | Trim **disabled** until tool-safe history trim — tier label still in prompt |
| Phase 4 Content summaries | ✅ | `FileReference.summary` still deferred |
| Phase 5 Observability | ✅ | |
| Semantic chunk index | ✅ | Not in original doc — shipped separately |
| Eidos Index (Tag & Hint) | On hold | Retrieval via `search_semantic` |
| PROMPT_SYSTEM alignment | 🟡 | Inventories, daily inject, loop guards — see PROMPT_SYSTEM checklist |

**Original cleanup goal is met.** Remaining work lives under PROMPT_SYSTEM (prompt contents, daily inject, inventories) — not this file’s Phase 1–5 scope.

---

## Principles

1. **Reasoning on, prompts lean** — Models should think (`reasoning_effort` / `thinking: enabled` at **medium** where supported). Waste comes from inlining notes, folder trees, file lists, and 80k chat replay in the **system prompt** — not from Moonshot-required Kimi `reasoning_content` in API `messages` (see [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md)).

2. **Tools over bulk context** — Eidos has `read_note`, `read_file`, `list_folder_contents`, `workshop_read_file`, semantic search, tag hints, memory cache, etc. Default prompt = **IDs + policy**, not bodies or enumerations (50 subfolders × names is still bloat).

3. **Summaries (Phase 5)** — User-triggered **Generate Summary** / **Regenerate Summary** on notes, workshop projects, and files. Cached summary text becomes a **stable, cache-friendly** system-prefix block. Long notes → chunked summaries with section anchors. Workshop: summary + spec `.md` orientation only — **never inline live `.html/.css/.js`** (stale after edits).

4. **Two provider families** — Transport must match how each API was designed:

| Family | Providers | Multi-turn / tools |
|--------|-----------|-------------------|
| **RESPONSES_CHAINED** | xAI, OpenAI | `previous_response_id` + **incremental** `input` on tool continuations (no full system/history replay) |
| **MESSAGES_CACHED** | Anthropic, Kimi | Growing `messages` + **stable** cached prefix (`cache_control` + `prompt_cache_key`) |

Code: `EidosProviderFamily.kt`, `providerFamily()`, `EidosRequestPhase` (`FULL` | `TOOL_CONTINUATION`).

5. **In-chat memory tiers** — User-controlled **Low / Medium / High** for how much **conversation thread** is resent (not note/folder content). Settings default today; per-conversation override on `Conversation` entity planned.

| Tier | ~Messages | ~Chars |
|------|-----------|--------|
| Low (default) | 8 | 12,000 |
| Medium | 16 | 30,000 |
| High | 40 | 80,000 |

Code: `EidosContextLimits.kt`, `SettingsKeys.CONVERSATION_MEMORY_DEPTH`.

6. **Future: multi-model orchestration (V3/V4)** — Grok search → Claude/Kimi reason/plan → GPT write. Out of scope for this cleanup; keep xAI/OpenAI for future image generation.

---

## What the prompt contains now (implemented lean baseline)

### Always

- `buildBasePrompt()` + `EidosContextLimits.TOOL_FIRST_CONTEXT_RULES`
- Location rules, scope label, provider web note (xAI/OpenAI/Anthropic hosted web; Kimi Formula `web_search` + local Eidos tools)
- Full **tool catalog** (required for function calling)
- In-chat memory tier label

### Subfolder / note (not workshop)

- Subfolder **name + id** only
- Note: blind / locked / “use `read_note`” — **no full body**
- **No** attached file name list
- Parent folder **id** only (not name lists)
- Panel bridge **only if** a live panel is visible (`buildPanelBridgeContext` → null otherwise)

### Panel workshop

- Project name, subfolder id, **compact file manifest** (`fileName` + `fileReferenceId` per file)
- **Bounded open-tab excerpt** (≤6k chars of active editor buffer; not full-project dump)
- Mode instructions (`PanelPlatformSpec`) — **build run** vs **edit** profile (see [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md))
- Optional user-generated **project summary** or bounded spec `.md` fallback (project summary may be dropped from prompt per Auto-Continue Phase 1)
- Tool loops: **full system + history every hop** (no incremental-only Responses continuations for workshop)
- **Auto-Continue (planned):** chunked `send()` + synthetic user handoff between chunks on build kickoffs — not unbounded single-loop tool storms
- **No** inlined full runtime tree (old ~24k open-file dump removed on purpose)

### Parent-folder scope

- Parent name + id + “use `list_folder_contents`” — **no** subfolder enumeration

### Web panel

- Loaded URL when relevant (small, user-facing)

---

## Provider knobs (current code)

| Provider | Endpoint | Reasoning | Caching / chain |
|----------|----------|-----------|-----------------|
| xAI Grok 4.3 | `/v1/responses` | `reasoning_effort: medium` | `prompt_cache_key`, `previous_response_id`, incremental tool input |
| OpenAI 5.4 mini | `/v1/responses` | `reasoning.effort: medium` | auto prefix cache, `previous_response_id`, incremental tool input |
| Anthropic Sonnet 4.6 | `/v1/messages` | (model default) | `cache_control` on system/tools/history breakpoint |
| Kimi K2.6 | `/v1/chat/completions` | `thinking: enabled`, `keep: all` | `cache_control`, `prompt_cache_key`, Formula `web_search`, full messages (lean prefix). Remaining Moonshot alignment: [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md) |

---

## Tool loop (implemented)

**First call (`EidosRequestPhase.FULL`):** lean system + **full** conversation history + user message + tools.

**History trim:** `trimHistoryIfNeeded` is **disabled** (2026-05) until tool-round-safe trimming exists. Memory tier UI remains for future use.

**Tool continuations:**

- **RESPONSES_CHAINED:** `phase = TOOL_CONTINUATION`, empty system string, `conversationHistory` = last assistant tool-call round only, `previous_response_id` set → providers skip system block in `buildInput`.
- **MESSAGES_CACHED:** full lean system (stable for cache) + **full** history with new tool results. Kimi: echo `reasoning_content` on **all** assistant replay (tool-call and final); Formula `web_search` via Fiber; workshop writes redact large `content` in replay args. Gaps tracked in [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md).

---

## Phase checklist

### Phase 1 — Provider wiring ✅

- [x] Kimi provider + settings
- [x] `prompt_cache_key` per conversation
- [x] `PromptCacheMarkers` shared with Anthropic
- [x] Grok/OpenAI `reasoning` medium; Kimi `thinking` enabled
- [x] `EidosProviderFamily` + incremental tool continuations (xAI/OpenAI)

### Phase 2 — Lean context ✅ (baseline)

- [x] Tool-first rules in system prompt
- [x] Remove full note, file lists, parent/subfolder enumerations from prompt
- [x] Workshop: no inlined code/README/file lists
- [x] Panel bridge only when visible
- [x] Settings: Low/Medium/High chat memory depth

### Phase 3 — Per-conversation memory UI ✅

- [x] `Conversation.memoryDepth` column + migration 12→13 (`AppDatabaseMigration12To13Test`)
- [x] `EidosApiClient` resolves per-conversation depth before Settings default
- [x] Tap **Memory: …** chip in Eidos header (active thread) to cycle: App default → Low → Medium → High → App default
- [x] Settings **Eidos chat** section still sets app-wide default for new threads

### Phase 4 — Content summaries ✅

- [x] `Note.summary` + `summaryChunksJson` + `summaryUpdatedAt` (chunked path for notes ≥12k plain chars)
- [x] Workshop `Subfolder.projectSummary` + `projectSummaryUpdatedAt`
- [ ] `FileReference.summary` optional (deferred)
- [x] Editor UI: **Generate Eidos summary** / **Regenerate Eidos summary** (note ⋮ menu; workshop drawer)
- [x] `ContentSummaryService` — active provider, no tools; inject via `EidosApiClient` subfolder/workshop context
- [x] Workshop: bounded spec `.md` fallback when no project summary (`WorkshopSpecMarkdown`, 6k total / 2k per file)
- [x] DB v14, `MIGRATION_13_14`, `AppDatabaseMigration13To14Test`

### Phase 5 — Observability ✅

- [x] `EidosTokenUsage` on `EidosResponse`; parsed in all providers via `ProviderUsageParser`
- [x] Debug Logcat: tag `OptimalX.Eidos.Usage` (`BuildConfig.DEBUG` only), per HTTP round + phase + tool count
- [x] `ProviderUsageParserTest` (Responses, Anthropic, Chat Completions shapes)
- [x] `LLM_API_REFERENCE.md` updated (context policy + usage fields)

---

## Summary + cache strategy (design)

```
[Stable prefix — cache hit target]
  base prompt + tool-first rules + tool defs (fixed order)
  + project/note summary (changes only when user regenerates)
  + mode instructions (workshop)

[Dynamic suffix — grows each turn]
  chat messages (per memory tier)
  + user message
  + tool results (appended)
```

When user edits note/workshop code, **summary unchanged** until regenerate → prefix stays cached. Editing `.md` spec files invalidates that slice of prefix (acceptable; `.md` should change rarely).

---

## Files

| Area | Files |
|------|--------|
| Policy / limits | `EidosContextLimits.kt`, `EidosProviderFamily.kt` |
| Assembly + loop | `EidosApiClient.kt` |
| Providers | `XAIProvider.kt`, `OpenAIProvider.kt`, `AnthropicProvider.kt`, `KimiProvider.kt`, `KimiFormulaToolService.kt`, `PromptCacheMarkers.kt` |
| Models | `EidosModels.kt` (`EidosRequestPhase`), `Conversation.memoryDepth` |
| Memory UI | `EidosContextLimits.kt`, `EidosChatViewModel.cycleConversationMemoryDepth`, `EidosBottomSheet` header chip |
| Settings | `SettingsPreferences.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt` |
| DB | `AppDatabase` v14, `MIGRATION_12_13`, `MIGRATION_13_14` |
| Summaries | `ContentSummaryService.kt`, `WorkshopSpecMarkdown.kt`, `ContentSummaryChunk.kt` |
| Usage logging | `ProviderUsageParser.kt`, `EidosUsageLogger.kt`, `EidosTokenUsage` |
| Docs | `LLM_API_REFERENCE.md`, [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md), [API.md](../reference/API.md), this file |

---

## Open questions (remaining)

1. xAI/OpenAI: periodic regression — incremental `input` + `previous_response_id` on 3+ tool hops.
2. Summary generation: which model for “Generate Summary” — active provider or fixed cheap model?
3. Re-enable history trim with atomic `(assistant tool_use + tool_results)*` rounds.

Prompt/memory policy follow-ups: [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) checklist.
