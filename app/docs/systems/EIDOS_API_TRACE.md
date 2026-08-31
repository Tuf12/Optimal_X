# Eidos API Trace (Developer Inspector)

**Status:** Active — 2026-06-04  
**Implementation tracker:** [EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md](../implementation/EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md)  
**Related:** [PROMPT_SYSTEM.md](./PROMPT_SYSTEM.md) · [API.md](../reference/API.md) · [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md)

---

## Purpose

API Trace is a **developer-only** in-app inspector that records the **exact outbound JSON** sent to LLM providers on every Eidos send, including the full tool loop (initial request + each continuation round).

Use it when you need to verify:

- What system prompt and messages actually left the device
- Which tools were declared on each hop
- How provider-specific transport differs across Kimi, OpenAI, xAI, and Anthropic
- Whether prompt or tool-catalog changes altered a live conversation

The feature is **hidden by default**. Normal users never see capture overhead or UI unless they opt in.

---

## Enabling and disabling

| Control | Location | Behavior |
|---------|----------|----------|
| **Capture toggle** | Settings → Developer → **API Trace inspector** | When off: no DB writes, no Eidos section entry |
| **View UI** | Eidos → **API Trace** (only when toggle is on) | Browse stored traces |

Turn capture **off** to stop recording. Previously stored runs remain until cleared from the inspector.

Default: **disabled** (`EidosApiTraceFeature.ENABLED_BY_DEFAULT = false`).

---

## Navigation model

Three screens, newest activity first at each level:

```
Eidos → API Trace
  └── Directories (grouped by chat scope bucket)
        └── Runs in directory (one per user send)
              └── Run detail (expandable rounds)
```

### Directory buckets

A **directory** is the chat **scope bucket**, not an Eidos Journal/Log folder. Examples:

| Directory key | Label example |
|---------------|---------------|
| `general` | General |
| `subfolder:42` | Subfolder · My Note |
| `web_editor:42` | Web · My Note |
| `panel_workshop:42` | Workshop · My Panel |
| `web_widget` | Widget Web |
| `panel_gallery` | Panel Gallery |

Resolution: [EidosApiTraceDirectoryResolver.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiTraceDirectory.kt).

Within a directory, runs are sorted by **startedAtMillis DESC** so the most recent send is at the top.

---

## What gets recorded

### One run per user send

Each time the user sends a message (or Auto-Continue triggers a send), one **run** is created with:

- Conversation id + title (when available)
- Directory key + human label
- Scope type, active provider, user message preview (≤240 chars)
- Start/finish timestamps, final status, round count
- **`sectionCharCountsJson`** on the run — prompt section sizes including prefetch metrics when applicable (`prefetchPassCount`, `prefetchHitCount`, `prefetchChars`, `prefetchTopScoreMilli`, `prefetchDailyHits`, `prefetchNoteHits`, `prefetchSkipped`)

### One round per provider HTTP call

Inside a run, each `provider.send()` is one **round**:

| Round | Phase | Typical content |
|-------|-------|-----------------|
| 1 | `FULL` | System prompt + history + tools + user message |
| 2+ | `TOOL_CONTINUATION` | Continuation payload (provider-specific: full history vs chained response id) |

Each round stores:

- **Request:** pretty-printed outbound JSON (Authorization header never stored)
- **Response:** parsed summary — text, tool calls, usage, reasoning snippet, provider response id (not raw SSE)

Tool execution happens on-device between rounds; tool **results** appear in the **next** round's request messages, matching what the provider actually received.

---

## Retention and privacy

- **Max 250 runs** device-wide; oldest pruned on new capture
- Per-directory and global **Clear** actions in the UI
- Traces live in local Room DB only (`eidos_api_trace_runs`, `eidos_api_trace_rounds`)
- API keys are never written to trace JSON

---

## What API Trace is not

- Not a replacement for conversation history in chat UI
- Not a log of tool executor side effects (file writes, search hits, etc.)
- Not raw streaming tokens — use provider dashboards for token-level SSE
- Not enabled in production builds for end users unless they toggle Developer settings

---

## Typical workflow

1. Settings → Developer → enable **API Trace inspector**
2. Reproduce the issue (e.g. web panel Kimi send with `fetch`)
3. Eidos → API Trace → open the relevant directory (e.g. `web_editor:…`)
4. Open the newest run → expand Round 1 → **Request** tab
5. Copy JSON to compare system prompt, tools array, and messages against [PROMPT_SYSTEM.md](./PROMPT_SYSTEM.md) expectations
6. Expand Round 2+ to verify tool-loop continuations (e.g. Kimi resending full system prompt on `MESSAGES_CACHED` path)

Disable capture when finished to avoid filling retention with noise.
