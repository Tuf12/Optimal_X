# API.md

## Purpose

This file defines the AI model integration layer for OptimalX v2.

It covers which models are supported, how requests are structured, how tool calling works, how context is assembled, and how the user switches between providers.

Coding agents should use this file to build the API layer that connects Eidos to the underlying language model.

**Related docs (LLM layer):**

| Doc | Role |
|---|---|
| [LLM_API_REFERENCE.md](./LLM_API_REFERENCE.md) | Per-call request/response shape, usage fields, errors |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](../implementation/EIDOS_LLM_CONTEXT_CLEANUP.md) | Lean prompts, provider families, memory tiers |
| [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) | **Authoritative** for Kimi K2.6 transport, Formula tools, streaming |

---

## Design Philosophy

OptimalX is not locked to a single AI provider.

The API layer is built as an abstraction — one clean interface that any supported provider plugs into.
Switching providers does not change how Eidos works, how tools are called, or how context is assembled.
The provider is a setting the user controls.

---

## Supported Models

**Primary LLM (product):** **Kimi K2.6** (`kimi-k2.6`) — Moonshot thinking model with Formula tools, preserved `reasoning_content`, and streaming. Full behavior: [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md).

**Fallback providers:** xAI, OpenAI, and Anthropic remain selectable in Settings; they share the same Eidos tool catalog.

**On-device provider:** Local Gemma 4 (LiteRT-LM) runs `gemma-4-E4B-it.litertlm` with no API key. See [LITERT_LM.md](../LITERT_LM.md).

| Provider | Model | Model ID | Tool calling | Transport family |
|---|---|---|---|---|
| **Kimi (Moonshot)** | **Kimi K2.6** | `kimi-k2.6` | Yes — local Eidos tools + Formula `web_search` / `fetch` / etc. | `MESSAGES_CACHED` (Chat Completions) |
| xAI | Grok 4.3 | `grok-4.3` (Settings) | Yes — hosted `web_search` + local tools | `RESPONSES_CHAINED` |
| OpenAI | GPT-5.6 Luna | `gpt-5.6-luna` | Yes — hosted `web_search` + local tools | `RESPONSES_CHAINED` |
| Anthropic | Claude Sonnet 4.6 | `claude-sonnet-4-6` | Yes — hosted `web_search` / `web_fetch` + local tools | `MESSAGES_CACHED` |
| **Local (LiteRT-LM)** | **Gemma 4 E4B-it** | `gemma-4-E4B-it.litertlm` (path on device) | Yes — scoped local Eidos tools only (no hosted web) | `LOCAL_CONVERSATION` |

All five providers support scoped tool functions from `EidosToolCatalog` (cloud adapters add hosted web tools separately; local has no provider web search).

The user selects the active provider in **Settings → Provider**. Fresh installs default to **xAI** in `SettingsDefaults`; choose **Kimi (Moonshot K2.6)** for the primary path.

---

## Provider Abstraction Layer

The app wraps all four providers in a single abstraction layer.

### How it works
- All requests go through a single `EidosApiClient` class
- The client reads the selected provider from user settings
- It formats the request correctly for that provider
- It parses the response back into a standard format the rest of the app understands
- Tool call format differences between providers are handled inside the client — Eidos never sees provider-specific formatting

### Provider switching
- User selects provider in app settings
- No restart required
- The next Eidos request automatically uses the newly selected provider
- API keys are stored securely per provider — the user only needs to enter a key once per provider

---

## API Keys

Each cloud provider requires its own API key. **Local Gemma** uses a model file on device storage — no API key.

| Provider | Where to get key / model |
|---|---|
| Kimi (Moonshot) | [platform.kimi.ai/console/api-keys](https://platform.kimi.ai/console/api-keys) |
| xAI | console.x.ai |
| OpenAI | platform.openai.com |
| Anthropic | console.anthropic.com |
| **Local Gemma** | Download `gemma-4-E4B-it.litertlm` from [litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) or copy from Google AI Edge Gallery; set path in Settings → Local Gemma |

### Key storage
- API keys are stored in Android EncryptedSharedPreferences
- Keys are never logged, transmitted, or stored in plain text
- Keys are entered once in app settings and persist until the user removes them

---

## Request Structure

Every request to the API includes four components assembled in this order:

### 1. System prompt
The system prompt is assembled fresh on every request. Exact layers are listed under **Context Assembly** below.
Tool definitions are not part of the system prompt string; they are sent alongside the request per provider.

### 2. Conversation history
The full message history for the current session.
Oldest messages are trimmed first if the context window limit approaches (`trimHistoryIfNeeded` in `EidosApiClient`).

### 3. Tool definitions
All tool functions defined in TOOL_FUNCTIONS.md (from `EidosToolCatalog.all`) are passed to the model on every request.
The model decides which tools to call based on the user's message and context.

### 4. User message
The current message from the user.

---

## Context Assembly

Context is built in `EidosApiClient.assembleSystemPrompt` plus the rest of the provider request. The goal is the most relevant information at the lowest token cost.

### Target contract vs implementation

**Target steady-state** (what should eventually be in the default chat system prompt) is documented in [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md) — LTM, journal, and subfolder memory cache are **tool-driven**, not inlined as full text.

**Current implementation** may still inject extra excerpts (Long-Term Memory, recent journal) until the pipeline matches that contract; see the implementation note in MEMORY_SYSTEM.md.

### Injected by `assembleSystemPrompt` today

These blocks are merged into the system prompt string in code ([`EidosApiClient.kt`](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt)):

| Component | Source | Notes |
|---|---|---|
| Base Eidos prompt | Hardcoded | Identity, role, behavior |
| Active location rule | Hardcoded | Default writes to current location |
| Provider web access rule | Hardcoded | xAI/OpenAI/Anthropic hosted web; Kimi Formula `web_search` + local Eidos tools |
| Active scope | App state | `general` / `parent` / `subfolder` |
| Subfolder or parent context | Room | **Subfolder:** name, ID, current note text (or AI-lock notice), attached file names/types only. **Parent:** name, ID, listed subfolders (user + system caps). |
| Daily Memory | Eidos Daily | Today’s note; shown as empty if missing |
| Long-Term Memory excerpt | Eidos Memory | Included when non-empty, length-capped in code |
| Recent journal excerpt | Eidos Journal | Rolling window (3 days in code) when non-empty |

### Not automatically injected into the system prompt

Fetch via tools when needed (names from `EidosToolCatalog`):

| Need | Tools |
|---|---|
| Per-note folder memory ([Memory]) | `write_note_summary`; subfolder inject + editor panel |
| Full LTM / journal / log / chat beyond excerpts | `read_long_term_memory`, `read_journal`, `read_log`, `search_chat_history`, `read_conversation`, etc. |
| File bodies | `read_file`, `describe_image`, `workshop_read_file`, … |
| Semantic retrieval | `search_semantic` |

### Session-attached (not part of the system prompt string)

| Component | Notes |
|---|---|
| Conversation history | Current thread messages; trimmed oldest-first when over budget |
| Prior tool results | Already in history as tool messages |

### Never bulk pre-loaded

| Component | Reason |
|---|---|
| Full journal or full log | Too large — search/read tools |
| All files in folder | Only names in context; content on demand |
| Notes from other subfolders | Unless user asks or search finds them |

---

## Rollover — separate system prompts

Memory rollover does **not** use the normal chat system prompt. [`MemoryRolloverService`](../../src/main/java/com/example/optimalx/data/eidos/MemoryRolloverService.kt) builds phase-specific prompts and runs [`RolloverOrchestrator`](../../src/main/java/com/example/optimalx/data/eidos/RolloverOrchestrator.kt). The same entry point is used for **Settings → Force memory rollover**; the scheduler calls `runMemoryRollover()` identically.

Canonical doc: [ROLLOVER.md](../systems/ROLLOVER.md). Folder roles: [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md).

---

## Tool Calling

Tool calling is the mechanism by which Eidos takes action inside the app.

### How it works
1. User sends a message
2. Request is sent to the model with tool definitions included
3. Model decides whether to call a tool or respond directly
4. If a tool is called, the app executes the tool function locally (`RoomToolExecutor`)
5. The result is sent back to the model as a tool result
6. The model continues and produces a final response
7. This loop repeats until the model produces a final text response

### Confirmation flow

Only tools with **`requiresConfirmation == true`** in `EidosToolCatalog` trigger the UI confirmation handler in `EidosApiClient`. The catalog flag **`isModifying`** is separate metadata (e.g. logging); it does **not** gate confirmation.

**Tools that require confirmation today:** `move_to_trash`, `prune_long_term_memory`.

**Note writes** (`write_note`, `note_replace_string`) do **not** use the catalog confirmation handler. When the note body is non-empty, proposals queue for **Diff Review** (`SCOPE_SUBFOLDER`); the user accepts on `DiffReviewScreen` (editor badge or chat banner). **Empty notes** auto-apply on first `write_note`. Uncommitted user edits are **auto-committed** before Eidos tools run when working copy ≠ HEAD. Manual **Commit** advances HEAD without Diff Review. See [NOTE_PERSISTENCE_MODEL.md](../architecture/NOTE_PERSISTENCE_MODEL.md).

For tools that require confirmation:

1. Eidos presents the action to the user in the chat
2. User approves or declines
3. If approved, the tool executes; decline outcomes are logged
4. If declined, execution stops for that tool and Eidos acknowledges

Most create/read/search/journal/log/tag tools run without confirmation. See TOOL_FUNCTIONS.md and MEMORY_SYSTEM.md for the full catalog table.

### Tool call logging

After a successful tool execution, if the tool is marked modifying (`isModifying`) or the executor reports `modifiedSystem`, `EidosApiClient` invokes **`write_log_entry`** automatically with structured details (`writeAutomaticLog`). The model does not need to log those actions itself.

---

## Context Window Management

Each model has a context window limit. OptimalX manages this to avoid hitting the limit.

| Model | Context Window |
|---|---|
| Kimi K2.6 | See [Moonshot model docs](https://platform.kimi.ai/docs/api/models-overview.md); `max_tokens` 32_384 per request in app |
| Grok 4.3 | Check xAI documentation for current limit |
| GPT-5.6 Luna | 128,000 tokens (typical); see OpenAI docs for long-context pricing |
| Claude Sonnet 4.6 | 200,000 tokens (typical) |

In-chat history trim is **disabled** until tool-round-safe trimming exists; memory tier UI remains for future use ([EIDOS_LLM_CONTEXT_CLEANUP.md](../implementation/EIDOS_LLM_CONTEXT_CLEANUP.md)).

### Trimming priority

If context grows large, trim in roughly this order:

1. **Oldest conversation messages** (history is variable-length)
2. Portions of **recent journal / LTM excerpts** only if pressure remains extreme — prefer trimming variable history first
3. **Never** drop the base system prompt identity/layers wholesale
4. **Never** drop current subfolder note text from location context (working surface)
5. **Never** drop Daily Memory wholesale — it is small by design

There is no full Tag & Hint index inside the assembled prompt to trim as a block; that index is tool-fetched.

---

## Kimi K2.6 (primary path)

Moonshot-specific behavior is **not** duplicated here. Use [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) for:

- `thinking: { type: "enabled" }` and `keep: "all"` on tool continuations
- `reasoning_content` parse, UI display, and outbound replay rules
- Formula tools (`web_search`, `fetch`, `convert`, `date`, `excel`)
- Streaming-only transport (`KimiProvider` + SSE)
- Workshop write replay redaction (`redactToolCallForKimiReplay`)

Per-call field mapping: [LLM_API_REFERENCE.md](./LLM_API_REFERENCE.md) (Kimi row).

---

## OpenAI / xAI / Anthropic notes

**OpenAI (GPT-5.6 Luna)** — Responses API (`reasoning.effort: medium`, `text.verbosity: low`). `prompt_cache_key` per conversation; stable system prefix (identity, rules, static location prose) cached via explicit breakpoint; volatile location/note/prefetch/memory in a second developer block; `previous_response_id` + incremental `input` on tool continuations.

**xAI (Grok 4.3)** — Responses API; `reasoning_effort: medium`; same chained continuation pattern as OpenAI.

**Anthropic (Claude Sonnet 4.6)** — Messages API; `cache_control` on system, tools, and history breakpoint; full message history each tool hop.

---

## Prompt Caching

Prompt caching reduces cost by reusing previously processed parts of the prompt.

### How it helps

If stable sections of the system prompt stay the same across turns, caching means those tokens are processed once. **Kimi** and **Anthropic** use `cache_control` + `prompt_cache_key` on a stable prefix; **OpenAI** (GPT-5.6 Luna) uses `prompt_cache_key`, `prompt_cache_options` (`explicit`, `30m`), and an explicit breakpoint after the stable system block (tools + instructions); **xAI** uses `prompt_cache_key` with Responses prefix caching.

### What to cache (provider-dependent)

Candidate stable blocks match what is actually in the assembled prompt:

- Base system prompt and fixed rules
- Location block (while scope and note/files unchanged)
- Daily Memory (while unchanged)
- Long-Term Memory excerpt and recent journal excerpt (while unchanged)
- Current note content in subfolder context (while the note has not changed)

### What not to cache

- Conversation history — changes every message
- Tool result messages — unique per call

---

## Fallback Logic

If a request to the selected provider fails:

1. Retry the request once after a short delay
2. If it fails again, notify the user that the provider is unavailable
3. Offer the user the option to switch to a different provider
4. Do not automatically switch providers without user consent — the user controls which provider is active

---

## Summary

| Decision | Choice |
|---|---|
| Primary LLM (product) | **Kimi K2.6** — see [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md) |
| Settings default (fresh install) | xAI — user selects Kimi in Settings → Provider |
| Fallback providers | Grok 4.3, GPT-5.6 Luna, Claude Sonnet 4.6 |
| API key storage | Android EncryptedSharedPreferences (per provider) |
| Provider switching | User controlled in settings |
| Transport detail | [LLM_API_REFERENCE.md](./LLM_API_REFERENCE.md), [EIDOS_LLM_CONTEXT_CLEANUP.md](../implementation/EIDOS_LLM_CONTEXT_CLEANUP.md) |
| Tool confirmation gate | `requiresConfirmation` in `EidosToolCatalog` only |
| Automatic logging | `write_log_entry` via `EidosApiClient` for modifying tool outcomes |
| Fallback on error | Retry once, then notify user |
| Chat system prompt | Built in `assembleSystemPrompt` — see Context Assembly; target contract in MEMORY_SYSTEM.md |
| Tool-driven memory layers | Tag & Hint index, subfolder cache, full LTM/journal/log/chat reads |
| Rollover | `MemoryRolloverService` — separate prompts, not the chat system prompt |
