# LLM_API_REFERENCE.md

Reference for every component sent to and received from the LLM on each call.
Source files: `EidosApiClient.kt`, `EidosChatViewModel.kt`, `EidosToolCatalog.kt`, `RoomToolExecutor.kt`, provider files.

**Primary model:** Kimi K2.6 — product and Moonshot transport detail in [KIMI_K26_MOONSHOT_SPEC.md](../implementation/KIMI_K26_MOONSHOT_SPEC.md). Overview: [API.md](./API.md).  
**Transport fix (active):** [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](../implementation/PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md).

---

## Providers

| Setting value | Class | Endpoint | Auth header |
|---|---|---|---|
| `kimi` | `KimiProvider` | `https://api.moonshot.ai/v1/chat/completions` | `Authorization: Bearer <key>` |
| `xai` | `XAIProvider` | `https://api.x.ai/v1/responses` | `Authorization: Bearer <key>` |
| `openai` | `OpenAIProvider` | `https://api.openai.com/v1/responses` | `Authorization: Bearer <key>` |
| `anthropic` | `AnthropicProvider` | `https://api.anthropic.com/v1/messages` | `x-api-key: <key>` |
| `local` | `LitertLmProvider` | On-device LiteRT-LM (`litertlm-android`) | None — model file on storage |

`SettingsDefaults.ACTIVE_PROVIDER` is `xai` on fresh install; select **Kimi** or **Local Gemma 4** in Settings as needed.

### Model IDs / artifacts

| Provider | Model |
|---|---|
| **Kimi (Moonshot)** | `kimi-k2.6` |
| xAI | `grok-4.3` (Settings) |
| OpenAI | `gpt-5.6-luna` |
| Anthropic | `claude-sonnet-4-6` |
| **Local (LiteRT-LM)** | `gemma-4-E4B-it.litertlm` (user path; default candidates in `LitertLmDefaults`) |

### Provider-specific options

**Local (LiteRT-LM)** — `LitertLmProvider` + shared `LitertLmEngineHolder`. `Conversation.sendMessageAsync` streaming; `OpenApiTool` adapters with `automaticToolCalling = false` (tool loop via `EidosApiClient` + `RoomToolExecutor`). Vision: `Content.ImageFile`; scribe: `Content.AudioBytes` via `GemmaLocalScribeEngine`. GPU/CPU backend in Settings. Warm pool while local provider **or** local scribe toggle is on; stays loaded across minimize / widget hops for the process lifetime. No hosted web search — local tools + `search_semantic` only. Full detail: [LITERT_LM.md](../LITERT_LM.md).

**xAI** — Responses API. Sends hosted `web_search` plus local Eidos function tools.

**OpenAI** — Responses API (not Chat Completions). Hosted `web_search`, local tools, `reasoning.effort = "medium"`, `text.verbosity = "low"`. GPT-5.6 Luna: `prompt_cache_key` per conversation, `prompt_cache_options` (`explicit`, `30m`), explicit breakpoint on [ComposedPrompt.stableSystemPrefix](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosScopeProfile.kt) (identity + ontology + static location prose); volatile location/note/prefetch in [volatileSystemSuffix](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosScopeProfile.kt); `previous_response_id` + incremental `input` on tool continuations (`EidosRequestPhase.TOOL_CONTINUATION`).

**Anthropic** — Messages API. Hosted `web_search`/`web_fetch` + local tools. System + last tool + history breakpoint use `cache_control: { type: "ephemeral" }`. Usage reports `cache_read_input_tokens` / `cache_creation_input_tokens`.

**Kimi (Moonshot)** — Chat Completions (`kimi-k2.6`). `cache_control` on system, last tool, history breakpoint; `prompt_cache_key` per conversation. `thinking: { type: "enabled" }` on normal turns; `keep: "all"` only on in-flight tool continuations. Outbound history omits stale `reasoning_content` (still stored on `ChatMessage` for UI). Local Eidos tools plus Moonshot Formula `web_search` / `fetch`; client executes via `/formulas/.../fibers`. Thinking stays enabled (unlike builtin `$web_search`). Streaming-only transport.

**xAI** — `reasoning_effort: "medium"`, `prompt_cache_key`, incremental tool continuations like OpenAI.

### Provider families (`EidosProviderFamily`)

| Family | Providers | Tool-loop transport (target) |
|---|---|---|
| `RESPONSES_CHAINED` | xAI, OpenAI | Hop 1: full system + history + user. Hop 2+: `previous_response_id`, **empty system**, incremental `input` (last assistant + tool round only). **All scopes including workshop.** |
| `MESSAGES_CACHED` | Anthropic, Kimi | Hop 1: cached system + history + user. Hop 2+: **omit system**; `prompt_cache_key` + hop-1 cache; append messages + stub bulk tools. |
| `LOCAL_CONVERSATION` | Local Gemma | Hop 1: system (if configured) + full history + user (text and/or `Content.ImageFile`). Hop 2+: **omit system**; rebuild conversation from growing history; tools via `OpenApiTool` + manual loop. |

**Legacy:** Workshop currently opts out of Responses incremental mode (`useIncremental = !isPanelWorkshop`) — see transport fix plan Phase 1.

---

## Request structure

### Per user turn (Kotlin)

One UI **Send** → one `EidosApiClient.send()`. Inside that call:

```
1. assembleSystemPrompt() once  →  turn context (stable for this send)
2. Tool definitions             →  resent each HTTP round (provider requirement)
3. Conversation history       →  grows with tool results inside the loop
4. User message               →  hop 1 only; hop 2+ uses "" (seeded in history)
```

### Per HTTP round (wire)

| Phase | When | RESPONSES_CHAINED payload | MESSAGES_CACHED payload |
|-------|------|----------------------------|-------------------------|
| `FULL` | First round of a send | system + full history + user | system (cached) + history + user |
| `TOOL_CONTINUATION` | After tool_calls | **no system** + incremental slice + `previous_response_id` | **no system** + full messages[] (`prompt_cache_key` carries hop-1 prefix) |

**Accounting note:** Total input bytes over N tool hops grow **quadratically** when each hop resends full system + full history (legacy workshop behavior). Target: hop 1 carries turn context; hops 2+ add only new assistant/tool traffic (+ required Kimi tool-row `reasoning_content`).

**Context policy:** Full notes, file lists, and workshop runtime code are **not** inlined in the system prompt. Workshop manifest is metadata (names + ids). Bounded spec `.md` and open-file excerpt are **hop-1-only** orientation.

---

## 1. System prompt

Built in `assembleSystemPrompt()`. Three sections concatenated with `\n\n`:

### 1a. Base prompt
**Source:** `EidosChatViewModel.buildBasePrompt()`

```
You are Eidos inside OptimalX.
Be concise, clear, and operationally helpful.
Use tools when needed and explain actions briefly.
```

**Approximate tokens:** ~30

> Widget (`WidgetVoiceService`) uses a different base prompt: `"You are Eidos. Be concise and helpful."` (~8 tokens).

---

### 1b. Subfolder context
**Source:** `buildSubfolderContext()` — only when `currentSubfolderId != null`

```
Current subfolder:
Name: <name>
subfolderId: <id>
Note: <NotePromptContext tier — inline small body, or memory-only for large notes>
Attachments: use list_folder_contents / read_file — not listed inline.
```

Blind/lock states still apply. `[Memory]` bullets are co-maintained via `write_note_summary`.

---

### 1c. Recent journal context 
**Source:** `loadRecentJournalContext(days = 3)`

Loads up to **3 journal subfolders** updated within the last **3 days**, sorted newest first.

```
[2026-04-08 14:30] <journal entry name>
<note content, capped at 2500 chars per entry>

[2026-04-07 09:15] <journal entry name>
<note content, capped at 2500 chars per entry>
```

**Min:** 0 chars (no recent journal entries)
**Max:** 3 entries × 2500 chars = **7,500 chars (~1,875 tokens)**

---

## 2. Tool definitions

**Source:** `EidosToolCatalog.all` — local OptimalX tools sent on every call regardless of scope. Provider adapters add hosted web tools separately.

**Approximate total tokens for local tool definitions:** ~1,300–1,600 tokens, plus provider-hosted web tool declarations.

| Tool name | Description | Inputs | Needs confirmation | Modifies system |
|---|---|---|---|---|
| `create_parent_folder` | Create a new parent folder | `name` | No | Yes |
| `create_subfolder` | Create a new subfolder under a parent | `parentFolderId`, `name` | No | Yes |
| `rename_folder` | Rename a parent folder or subfolder | `folderId`, `newName` | No | Yes |
| `move_to_trash` | Move a folder to trash | `folderId` | **Yes** | Yes |
| `list_folder_contents` | List folder contents | `folderId` | No | No |
| `write_note` | Set markdown on empty note or append when content exists | `subfolderId`, `content` | No | Yes |
| `note_replace_string` | Find-and-replace patch in a note (unique oldString) | `subfolderId`, `oldString`, `newString` | No | Yes |
| `list_files` | List attached files in a subfolder | `subfolderId` | No | No |
| `read_file` | Extract and return text from a file | `fileReferenceId` | No | No |
| `describe_image` | Describe an image file via vision API | `fileReferenceId` | No | No |
| `summarize_file` | Summarize file content (word count + 8-line preview) | `fileReferenceId` | No | No |
| `search_system` | Keyword search across folders/subfolders/notes | `query`, `dateFrom`, `dateTo` | No | No |
| `search_semantic` | Semantic/embedding search across notes/folders/files | `query`, `limit`, `dateFrom`, `dateTo` | No | No |
| `write_journal_entry` | Write a journal entry | `content`, `timestamp` | No | Yes |
| `read_journal` | Read journal entries by keyword/date | `query`, `dateFrom`, `dateTo` | No | No |
| `write_log_entry` | Write a log entry | `action`, `timestamp` | No | Yes |
| `read_log` | Read log entries by keyword/date | `query`, `dateFrom`, `dateTo` | No | No |

All input parameters are typed as `string` in the schema (including numeric IDs — the executor parses them).

`describe_image` is for **Files** rows. Desktop **chat composer attach** is a different path (pixels on that user message, local `models.vision`). Mobile chat attach: [CHAT_VISION_ATTACH_PLAN.md](../implementation/CHAT_VISION_ATTACH_PLAN.md).

### Tool confirmation gate
Tools marked `requiresConfirmation = true` (`move_to_trash`, `prune_long_term_memory`) pause execution and call `ConfirmationHandler.confirm()`. If the user declines, a `"User declined confirmation"` tool result is inserted and the API returns an early response.

**Note writes** (`write_note`, `note_replace_string`) use **Diff Review** instead of the confirmation handler when the note body is non-empty: proposals queue under `SCOPE_SUBFOLDER` until the user accepts on `DiffReviewScreen`. Empty notes auto-apply on first `write_note`. Uncommitted user edits are **auto-committed** before Eidos note tools when working copy ≠ HEAD. See [NOTE_PERSISTENCE_MODEL.md](../architecture/NOTE_PERSISTENCE_MODEL.md).

### Auto-log on modify
Every tool where `isModifying = true` OR where `ToolExecutionResult.Success.modifiedSystem = true` triggers an automatic `write_log_entry` call after execution. This is an extra API round-trip for every modifying action.

---

## 3. Conversation history

**Source:** [`ConversationOutboundHistory.build()`](../../src/main/java/com/example/optimalx/data/eidos/ConversationOutboundHistory.kt) — called before each send from chat UI, background worker, and widget.

Long-thread context is a **verbatim tail** plus **active-conversation prefetch** (exact chunks in Retrieved context). Full rows remain in `chat_messages` for semantic search.

| Constant | Value |
|----------|-------|
| `ConversationOutboundHistory.OUTBOUND_HARD_CAP_CHARS` | 30,000 |

Outbound shape: recent user/assistant/tool turns only. Oldest exchanges drop when the hard cap is exceeded.

Message roles:
- `USER` → user turn
- `ASSISTANT` → Eidos text reply (may also contain tool call references)
- `TOOL` → tool execution result returned to the model

---

## 4. User message

The literal text the user typed or spoke, sent as the final turn.

**Min:** 1 char
**Max:** Unbounded in code (UI `TextField` has no enforced limit)

---

## Tool execution outputs (returned to model)

These are returned as `TOOL` role messages in the next request after a tool call.

| Tool | Output format | Output size |
|---|---|---|
| `create_parent_folder` | Plain string: `"Created parent folder 'x' (id=n)"` | ~50 chars |
| `create_subfolder` | Plain string: `"Created subfolder 'x' (id=n) under parent 'y'"` | ~60 chars |
| `rename_folder` | Plain string: `"Renamed ... to ..."` | ~50 chars |
| `move_to_trash` | Plain string describing what was trashed | ~60 chars |
| `list_folder_contents` | JSON array of `{id, name, updatedAt}` objects | Proportional to subfolder count |
| `write_note` | `"Note created"` / `"Content appended"` (auto-apply) or `"Note change proposal queued for review…"` | ~80–200 chars |
| `note_replace_string` | Queued-review message or patch outcome; `not_found` / `ambiguous` errors include line-numbered snippet | ~80–500 chars |
| `list_files` | JSON array of `{id, name, type, path}` | Proportional to file count |
| `read_file` | Full extracted text (txt/md/pdf/docx/xlsx/ods/odt) | **Unbounded** |
| `describe_image` | Metadata block + vision model description | ~200–800 chars |
| `summarize_file` | Word count + line count + 8-line preview | ~300–500 chars |
| `search_system` | JSON array of match objects with type/id/name/snippet | Up to ~20 results |
| `search_semantic` | JSON array of match objects with score | Up to `limit` results (default 10, max 50) |
| `write_journal_entry` | `"Journal entry written to subfolder 'x'"` | ~50 chars |
| `read_journal` | Concatenated journal entry content | **Unbounded** |
| `write_log_entry` | `"Log entry written"` | ~18 chars |
| `read_log` | Concatenated log entry content | **Unbounded** |

---

## Response structure

All providers normalize to `EidosResponse`:

```kotlin
data class EidosResponse(
    val textResponse: String,
    val toolCalls: List<EidosToolCall>,
    val providerResponseId: String?,    // xAI/OpenAI chaining
    val usage: EidosTokenUsage?,        // Parsed from provider "usage" JSON
)

data class EidosTokenUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val cachedInputTokens: Int?,           // Responses / Kimi: input_tokens_details / prompt_tokens_details
    val cacheCreationInputTokens: Int?,  // Anthropic
    val cacheReadInputTokens: Int?,      // Anthropic
    val reasoningTokens: Int?,
)
```

### Provider `usage` field mapping

| Provider | API | Cached / cache-read signal |
|---|---|---|
| xAI, OpenAI | Responses `usage` | `input_tokens_details.cached_tokens` → `cachedInputTokens` |
| Anthropic | Messages `usage` | `cache_read_input_tokens`, `cache_creation_input_tokens` |
| Kimi | Chat Completions `usage` | `prompt_tokens_details.cached_tokens` → `cachedInputTokens` |

Parsing: `ProviderUsageParser.fromResponseRoot()`.

### Debug usage logging (Phase 5)

When the app is **debuggable** (debug APK / run from Android Studio), each provider HTTP round logs to Logcat tag **`OptimalX.Eidos.Usage`**:

```
provider=xAI phase=TOOL_CONTINUATION round=2 tools=1 resp=resp_abc… input=1200 output=80 cached=950 resp=…
```

Fields omitted when the provider does not report them. Filter Logcat with `OptimalX.Eidos.Usage` to compare cache hits across turns.

### Tool call loop

If `toolCalls` is non-empty, the client:

1. Executes all tools
2. Appends `ASSISTANT` (+ optional `reasoning_content` on Kimi) + `TOOL` messages to `mutableHistory`
3. Builds the next `EidosRequest` with `phase = TOOL_CONTINUATION` and `userMessage = ""`
4. Sends another HTTP request
5. Repeats until `toolCalls` is empty, a cap fires, or an error occurs

**Caps:** Profile-owned via `EidosScopeProfile.loopPolicy` (`ToolLoopPolicy`), enforced in `EidosApiClient.send()`; on exceed the loop returns an `EidosToolLoopPause` message. Workshop Chat — 2; workshop build/plan/edit/intake — 12 (`WORKSHOP_BUILD_MAX_TOOL_ROUNDS`); all other scopes — 13 global backstop (`GLOBAL_MAX_TOOL_ROUNDS`).

**Incremental (Responses):** Hop 2+ sends `conversationHistory = lastToolRoundMessages(mutableHistory)` and `systemPrompt = ""` when `useIncremental` is true. `useIncremental = transportHints.incrementalContinuation && providerFamilyUsesIncrementalToolContinuation(family) && previousResponseId != null` — workshop uses the same path (no `!isPanelWorkshop` opt-out).

**Kimi reasoning:** `prepareKimiOutboundHistory` strips text-only reasoning on old turns; keeps `reasoning_content` on assistant rows with `tool_calls` in the current turn. Required by Moonshot — separate from system-prompt resend policy.

**Bulk tool trim:** `prepareOutboundHistory` (all providers, via `buildTracedRequest`) stubs `search_semantic` / `read_*` / `workshop_read_file` results older than the last 3 tool rounds in the current user exchange (and all bulky results in prior exchanges). Full bodies remain in DB and in-memory loop state.

---

## Token budget summary (per message, typical chat)

| Component | Min tokens | Typical tokens | Max tokens |
|---|---|---|---|
| Base system prompt | ~30 | ~30 | ~30 |
| Subfolder context (IDs + summary) | 0 | ~80–400 | ~800 (summary) |
| Workshop context (file manifest + open excerpt ≤6k + summary/spec) | ~200 | ~400–2,500 | ~8,500 |
| Journal context (3 days) | 0 | ~300 | ~1,875 |
| **Local tools + hosted web tools** | **~1,300** | **~1,500** | **~1,700** |
| Conversation history | 0 | ~200 | Summary + verbatim tail; hard cap ~30k chars |
| User message | ~5 | ~30 | Unbounded |

Full note/workshop bodies are loaded via tools on demand, not inlined every turn.

---

## HTTP timeouts

| Timeout | Value |
|---|---|
| Connect | 30 seconds |
| Write | 30 seconds |
| Read | 180 seconds |
| Call (total) | 210 seconds |

Retry: one automatic retry with a 700ms delay on any failure. If both attempts fail, a user-facing error message is returned instead of a crash.

---

## Error handling

| HTTP status | User-facing message |
|---|---|
| 401, 403 | `"<Provider> rejected your API key (HTTP n) at <endpoint>. Update the key in Settings > API Keys and try again."` |
| 429 | `"<Provider> rate limit reached (HTTP 429) at <endpoint>. Wait a moment and try again."` |
| Other HTTP error | `"<Provider> request failed (HTTP n) at <endpoint>. Details: <first 220 chars of body>"` |
| Socket timeout | `"<Provider> timed out at <endpoint>. The request may still be processing..."` |
| No API key | `"No API key is saved for <Provider>. Open Settings > API Keys, add the key, and send again."` |
| Unknown error | `"<Provider> is unavailable right now. Details: <message, 220 char cap>"` |
