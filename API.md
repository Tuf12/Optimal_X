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

Every request to the API includes:

### 1. System prompt
Describes who Eidos is, what it can do, and how it should behave.
The system prompt is assembled fresh each session and includes:
- Eidos identity and role
- Current app context (which subfolder the user is in, note content, attached files)
- Recent journal entries (selective — most relevant to current session)
- Available tool definitions

### 2. Conversation history
The full message history for the current session.
Oldest messages are trimmed if the context window limit approaches.

### 3. Tool definitions
All tool functions defined in TOOL_FUNCTIONS.md are passed to the model on every request.
The model decides which tools to call based on the user's message and context.

### 4. User message
The current message from the user.

---

## Context Assembly

Context is assembled before every request. The goal is to give Eidos the most relevant information without wasting tokens.

### What always goes in context
- Eidos system prompt
- Current subfolder name and ID
- Current note content (if AI lock is off)
- List of files attached to current subfolder
- Conversation history for current session

### What goes in context selectively
- Recent journal entries (last 1-3 days by default, more if relevant)
- Search results if Eidos has run a search during the session
- File content (only files Eidos has been asked to read — not all files at once)

### What never goes in context
- Full journal history (too large — Eidos searches it instead)
- Full Eidos Log history (too large — Eidos searches it instead)
- Notes from other subfolders unless Eidos explicitly searches for them

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
For tools that require confirmation (move_to_trash, write_note, edit_note_section):
1. Eidos presents the action to the user in the chat
2. User approves or declines
3. If approved, the tool executes and the result is logged
4. If declined, nothing happens and Eidos acknowledges the decline

### Tool call logging
Every tool call that modifies the system triggers a write_log_entry call automatically.
This is handled at the API layer — the model does not need to remember to log.

---

## Context Window Management

Each model has a context window limit. OptimalX manages this to avoid hitting the limit.

| Model | Context Window |
|---|---|
| Grok 4.1 | Check xAI documentation for current limit |
| GPT-5.4 nano | 1M tokens (standard pricing up to 272K, long context pricing above that) |
| Claude Haiku 4.5 | 200,000 tokens |

All three models have large enough context windows that aggressive trimming is rarely needed in normal use.

### General trimming strategy
- Trim oldest conversation messages first if context grows very large
- Keep system prompt intact — never trim it
- Keep current note content intact — never trim it
- Trim journal context before trimming conversation history

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
If the system prompt and note content stay the same across multiple messages in a session, caching means those tokens are only processed once.
Claude Haiku 4.5 supports prompt caching natively — savings up to 90% on repeated input tokens.

### When to use it
- Enable caching on the system prompt
- Enable caching on note content when the note has not changed
- Do not cache conversation history — it changes every message

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
