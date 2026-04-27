# API.md

## Purpose

This file defines the AI model integration layer for OptimalX v2.

It covers which models are supported, how requests are structured, how tool calling works, how context is assembled, and how the user switches between providers.

Coding agents should use this file to build the API layer that connects Eidos to the underlying language model.

---

## Design Philosophy

OptimalX is not locked to a single AI provider.

The API layer is built as an abstraction — one clean interface that any supported provider plugs into.
Switching providers does not change how Eidos works, how tools are called, or how context is assembled.
The provider is a setting the user controls.

---

## Supported Models

| Provider | Model | Model ID | Input | Output | Tool Calling |
|---|---|---|---|---|---|
| xAI | Grok 4.1 | grok-4.1 | $0.20/M tokens | $0.50/M tokens | Yes |
| OpenAI | GPT-5.4 nano | gpt-5.4-nano-2026-03-17 | $0.20/M tokens | $1.25/M tokens | Yes — except tool search and computer use |
| Anthropic | Claude Haiku 4.5 | claude-haiku-4-5 | $0.50/M tokens | $2.50/M tokens | Yes — full tool calling, strong agent capability |

All three models support the tool functions defined in TOOL_FUNCTIONS.md.
The user selects their preferred provider in app settings.
The default provider is Grok 4.1 (lowest cost).

---

## Provider Abstraction Layer

The app wraps all three providers in a single abstraction layer.

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

Each provider requires its own API key.

| Provider | Where to get key |
|---|---|
| xAI | console.x.ai |
| OpenAI | platform.openai.com |
| Anthropic | console.anthropic.com |

### Key storage
- API keys are stored in Android EncryptedSharedPreferences
- Keys are never logged, transmitted, or stored in plain text
- Keys are entered once in app settings and persist until the user removes them

---

## Request Structure

Every request to the API includes four components assembled in this order:

### 1. System prompt
The system prompt is assembled fresh on every request. It contains everything Eidos needs to operate with full context. See Context Assembly below for exact contents.

### 2. Conversation history
The full message history for the current session.
Oldest messages are trimmed first if the context window limit approaches.

### 3. Tool definitions
All tool functions defined in TOOL_FUNCTIONS.md are passed to the model on every request.
The model decides which tools to call based on the user's message and context.

### 4. User message
The current message from the user.

---

## Context Assembly

The system prompt is assembled before every request from the following layers, in order.
The goal is to give Eidos the most relevant information at the lowest token cost.

### Always included — no trigger required

These components are included on every request regardless of context:

| Component | Source | Notes |
|---|---|---|
| Eidos base prompt | Hardcoded | Identity, role, behavior instructions |
| Current subfolder name and ID | App state | Only when user is inside a subfolder |
| Current note content | Room database | Omitted if AI lock is enabled on the note |
| Subfolder memory cache | Subfolder.memoryCache field | Omitted if null — included alongside note when present |
| List of attached files | FileReference records | Names and types only — not file content |
| Daily Memory | Eidos Daily system folder | Full content, always included |
| Long-Term Tag & Hint index | Eidos Memory system folder | One compact Tag & Hint line per long-term entry — full entries fetched on demand |
| Journal Tag & Hint index | Eidos Journal system folder | One compact Tag & Hint line per journal entry — full entries never auto-load |
| Conversation history | Current session messages | Trimmed from oldest if context grows large |

### Included when triggered

These components are fetched by Eidos via tool calls during the session — not pre-loaded:

| Component | Trigger |
|---|---|
| Full journal entries | A journal Tag & Hint index line matches the current topic |
| Full long-term memory entries | A long-term Tag & Hint index line matches the current topic |
| Other subfolder notes | User references content outside current location |
| File content | Eidos is asked to read or summarize a specific file |
| Log entries | Eidos needs to verify a past action |
| Chat history | User asks to find a past conversation |

### Never pre-loaded

| Component | Reason |
|---|---|
| Full journal history | Too large — fetched selectively via Tag & Hint index trigger |
| Full long-term memory history | Too large — fetched selectively via Tag & Hint index trigger |
| Full Eidos Log history | Too large — searched via two-pass system on demand |
| All file content | Loaded only when Eidos is explicitly asked to read a file |
| Notes from other subfolders | Fetched only when Eidos searches for them |

---

## Rollover Context — Different System Prompt

The midnight rollover (`EidosMidnightWorker`) uses a separate, dedicated system prompt.
It does not use the standard chat system prompt above.

The rollover prompt is scoped only to the memory task — no subfolder context, no conversation history, no file lists.
Full rollover detail is defined in MEMORY_SYSTEM.md.

Rollover retrieval rule:
- Read Daily Memory in full first.
- Then use Journal and Long-Term Tag & Hint indices to decide what else to fetch.
- Fetch full journal/long-term entries only when Tag & Hint lines match Daily topics.
- Read the Log only when verification is needed.

---

## Tool Calling

Tool calling is the mechanism by which Eidos takes action inside the app.

### How it works
1. User sends a message
2. Request is sent to the model with tool definitions included
3. Model decides whether to call a tool or respond directly
4. If a tool is called, the app executes the tool function locally
5. The result is sent back to the model as a tool result
6. The model continues and produces a final response
7. This loop repeats until the model produces a final text response

### Confirmation flow
For tools that require confirmation (move_to_trash, write_note, edit_note_section, prune_long_term_memory):
1. Eidos presents the action to the user in the chat
2. User approves or declines
3. If approved, the tool executes and the result is logged
4. If declined, nothing happens and Eidos acknowledges the decline

### Tool call logging
Every tool call that modifies the system triggers a `write_log_entry` call automatically.
This is handled at the API layer — the model does not need to remember to log.

---

## Context Window Management

Each model has a context window limit. OptimalX manages this to stay well within it.

| Model | Context Window |
|---|---|
| Grok 4.1 | Check xAI documentation for current limit |
| GPT-5.4 nano | 1M tokens (standard pricing up to 272K, long context pricing above that) |
| Claude Haiku 4.5 | 200,000 tokens |

All three models have large enough context windows that trimming is rarely needed in normal use.

### Trimming priority — what gets cut first

If context grows large, trim in this order:

1. Oldest conversation messages (trim from the front)
2. Journal Tag & Hint index (trim oldest lines if index is very long)
3. Never trim Daily Memory — it is small by design and always relevant
4. Never trim Long-Term Tag & Hint index — it is compact by design and always relevant
5. Never trim the subfolder memory cache — it is small by design
6. Never trim current note content — it is the user's immediate working environment
7. Never trim the base system prompt

The memory layers (Daily, Long-Term, subfolder cache) are protected from trimming because they are small by design. If any of them grow large enough to cause context pressure, that is a signal the memory system is not being maintained correctly — not a reason to start trimming them.

---

## GPT-5.4 Nano Specific Notes

GPT-5.4 nano is a reasoning model with configurable reasoning effort and verbosity.

- Default reasoning effort is `none` — correct for low latency conversational use
- Verbosity defaults to `medium` — use `low` for faster, more concise responses
- Does not support tool search or computer use — all custom tool functions are supported
- Uses the Responses API, not the Chat Completions API
- Pass `previous_response_id` between turns to preserve chain of thought and improve cache hit rates
- Reasoning effort can be increased to `low`, `medium`, `high`, or `xhigh` for complex tasks if needed

---

## Prompt Caching

Prompt caching reduces cost by reusing previously processed parts of the prompt.

### How it helps
If the system prompt contents stay the same across multiple messages in a session, caching means those tokens are only processed once.
Claude Haiku 4.5 supports prompt caching natively — savings up to 90% on repeated input tokens.

### What to cache
- Base system prompt — changes rarely within a session
- Current note content — cache while the note has not changed
- Subfolder memory cache — cache while unchanged
- Daily Memory — cache while unchanged within the session
- Long-Term Tag & Hint index — cache while unchanged within the session
- Journal Tag & Hint index — cache while unchanged within the session

### What not to cache
- Conversation history — changes every message
- Tool results — unique per call

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
| Default provider | Grok 4.1 (lowest cost) |
| Best for agents | Claude Haiku 4.5 (strongest tool calling) |
| Largest context | GPT-5.4 nano (1M tokens) |
| API key storage | Android EncryptedSharedPreferences |
| Provider switching | User controlled in settings |
| Tool logging | Handled at API layer automatically |
| Fallback | Retry once, then notify user |
| Memory layers in prompt | Daily Memory, subfolder cache, long-term Tag & Hint index, journal Tag & Hint index — always included |
| Triggered context | Full journal entries, full long-term entries, other notes, file content, logs, chat history — fetched on demand |
| Rollover prompt | Separate dedicated prompt — not the standard chat prompt |
