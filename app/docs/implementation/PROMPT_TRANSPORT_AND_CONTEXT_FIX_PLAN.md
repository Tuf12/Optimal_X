# Prompt transport & context fix plan

| Field | Value |
|--------|--------|
| **Status** | Active — Phases 0–6 shipped (2026-06-08); regressed by the scope-router `EidosApiClient` rewrite, **re-restored profile-owned 2026-07-01** (see note below). **Desktop parity shipped 2026-08-16** — [prompt-transport-and-context.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/prompt-transport-and-context.md) |
| **Audience** | Product, prompt authors, Kotlin implementers, coding agents |
| **Supersedes / corrects** | Misleading sections in [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) (Tool Loop), [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md) (workshop hop policy), [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) (“full system every hop — intentional”) |
| **Related** | [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md), [SEARCH_AND_RETRIEVAL.md](../systems/SEARCH_AND_RETRIEVAL.md), [KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md), [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) |

---

> **Re-restoration note (2026-07-01):** The scope-router `EidosApiClient` rewrite silently dropped all of these fixes — it re-added the `!isPanelWorkshop` incremental opt-out, deleted `prepareOutboundHistory` (bulk tool stubbing), stopped omitting the system block on Messages continuations, and removed the tool-round cap. This caused a runaway 23-round / ~8M-token workshop build. Restored via profile-owned policy: `EidosScopeProfile.loopPolicy` (`ToolLoopPolicy`) + `transportHints` (`TransportHints`). Helpers now live at `providerFamilyUsesIncrementalToolContinuation` / `providerFamilyOmitsSystemOnToolContinuation` (gated by profile hints); bulk stubbing at `EidosHistoryTrimmer.prepareOutboundHistory`; cap enforced in `send()` with `EidosToolLoopPause`; compounding-factor observability at `EidosContextTransportMetrics` (Logcat `OptimalX.Eidos.Transport`). Putting policy on the profile prevents a future `send()` rewrite from dropping it again.

## Executive summary

Panel Workshop (and, to a lesser extent, Kimi/Anthropic main chat) currently **re-transmits large stable context on every in-flight tool hop** inside a single user send. That behavior is **not required** for model quality at our context sizes, **does not help** the model reason better, and **wastes user credits and latency** even when provider prefix caching reduces billed tokens.

The root cause is a **transport policy mistake**, not a prompt-content mistake alone:

1. Workshop **opts out** of incremental tool continuations that xAI/OpenAI already use everywhere else.
2. Kimi/Anthropic **must** grow `messages[]`, but we still **re-attach the full system block** and **replay unbounded tool bodies** each hop.
3. Docs describe this as “intentional for prefix cache” and “so the model won’t lose fileReferenceIds” — **both rationales are wrong or overstated** for in-loop hops within one user turn.

**Goal:** One assembled system prompt per **user turn** (not per HTTP round). Each tool hop sends only **new** assistant/tool traffic. Stable workshop context is either chained (Responses API) or cached once (Messages API). Retrieval stays tool-first; we stop spamming orientation text the model already saw 30 seconds ago.

---

## Ground truth — what the code does today

Code: [`EidosApiClient.send()`](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt), [`assembleSystemPrompt()`](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt), [`buildWorkshopPanelContext()`](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt).

### One user send = one or more HTTP rounds (tool loop)

```
User taps Send
  → assembleSystemPrompt() once in memory
  → HTTP round 1 (FULL):     system + history + userMessage + tools
  → model returns tool_calls
  → HTTP round 2..N (TOOL_CONTINUATION):
        workshop:  SAME system + FULL growing history + userMessage=""
        other scopes on xAI/OpenAI:  NO system + incremental slice + previous_response_id
        Kimi/Anthropic (all scopes):  SAME system + FULL growing history
```

`userMessage = ""` on later hops is **correct**: the user text was already inserted into `mutableHistory` via `ensureActiveUserTurnInHistory()`. The model does **not** leave a separate “message for the next hop”; continuation is **tool-driven** (assistant `tool_calls` → tool results → next model turn) until `tool_calls` is empty.

### What `mutableHistory` is

`mutableHistory` is the **in-memory conversation thread for this send**: prior chat from DB + new assistant rows (text, tool calls, reasoning) + tool result rows. It is **supposed** to grow — the model needs prior tool results to know what it already read/wrote.

The bug is not “history exists.” The bug is **re-sending the entire history array on every hop** on Messages providers, and **re-sending the full system prompt on every hop** on workshop (all providers). That creates **quadratic** total bytes over the loop:

| Hop | What gets sent again |
|-----|----------------------|
| 1 | system (~6–12k) + history₀ + user |
| 2 | system (~6–12k) + history₀ + round₁ |
| 3 | system (~6–12k) + history₀ + round₁ + round₂ |
| … | stable prefix × N + triangular growth of tool traffic |

So yes: **both system and history compound in the HTTP payload** for workshop today. Your intuition is right even if the exact prompt shape differs.

### What the ~6–8k “first prompt” actually is (not full files)

API trace logs list **file names + `fileReferenceId`s** in the manifest — that is metadata, not file bodies. Typical first-hop system size breakdown:

| Block | Approx size | Notes |
|-------|-------------|-------|
| `buildBasePrompt()` + `WORKSHOP_TOOL_FIRST_CONTEXT_RULES` | ~0.5–1k | |
| Provider / web / Kimi Formula prose | ~0.5–1.5k | Often irrelevant in workshop; should be scoped |
| `PanelPlatformSpec.eidosInstructionsForMode(...)` | **~2–5k** | Largest variable; duplicated retrieval policy inside |
| File manifest (all files, names + ids only) | ~0.2–1k | Grows with file count |
| Bounded spec `.md` (`WorkshopSpecMarkdown`, ≤6k total) | 0–6k | Cold-start orientation, not runtime code |
| Open editor excerpt (≤6k) | 0–6k | Skipped in Chat mode |
| Intake summary (≤8k) | 0–8k | When present |
| Tool definitions (API `tools` array, not system string) | separate | Still resent every hop |

**Runtime HTML/CSS/JS bodies are not inlined** in the system prompt by design. Large char counts after tool use usually come from **`workshop_read_file` / `search_semantic` results in history**, not from the manifest.

### Workshop-specific opt-out (the main bug)

```kotlin
// EidosApiClient.send() — today
val useIncremental = !isPanelWorkshop &&
    providerFamilyUsesIncrementalToolContinuation(family) &&
    !currentResponseId.isNullOrBlank()
```

Workshop **forces** `useIncremental = false` → full `assembledSystemPrompt` + full `mutableHistory` every hop.

### Kimi `reasoning_content` — what is actually required

Code: [`EidosHistoryTrimmer.prepareKimiOutboundHistory()`](../../src/main/java/com/example/optimalx/data/eidos/EidosHistoryTrimmer.kt), [`KimiProvider.buildMessages()`](../../src/main/java/com/example/optimalx/data/eidos/provider/KimiProvider.kt).

| Content | Required on replay? | Cached? |
|---------|---------------------|---------|
| System prompt (stable prefix) | Yes on Messages API | `cache_control: ephemeral` — **billing** may hit cache; **wire bytes** still sent |
| `reasoning_content` on assistant rows **with `tool_calls`** | **Yes** — Moonshot requirement when thinking enabled | Not a substitute for dropping system |
| `reasoning_content` on **text-only** assistant replies | **No** on FULL phase — stripped | |
| Old-turn reasoning during TOOL_CONTINUATION | Partially stripped for pre-user-index rows | |
| Tool result bodies | Yes until we trim/summarize | Grows every hop |

**Misconception to kill:** “Reasoning is always cached so resending system is free.” Prefix cache can reduce **charged** tokens on repeated identical prefixes; it does **not** remove latency, confusion risk, or input-size limits. We still should not re-send an 8k system block twelve times per send.

---

## Why the old rationale is wrong

### “Model will lose `fileReferenceId` / phase rules mid-loop”

Within a single `send()` under our caps (12 workshop build hops × modest history), modern models retain IDs from:

- The **first** system prompt (hop 1), and/or
- **Chained** context (`previous_response_id` on Responses API), and/or
- **Tool results** that echo ids, and/or
- **Assistant tool_call arguments** already in history.

Losing IDs was a plausible fear when workshop sent **incremental-only** history without chaining. The fix is **provider-correct chaining**, not **re-send everything**.

We should only refresh full workshop context when **session facts change**: new user turn, phase/mode kickoff, file created/deleted, Accept gate crossed, open-tab switch (optional).

### “Intentional for prefix cache”

Caching makes **repeated identical prefixes cheaper**, not **desirable**. Optimal transport:

- Send stable prefix **once per turn** (or rely on chain).
- Append **deltas** per hop.

Re-sending system + full history is the opposite of how Responses APIs were designed (`previous_response_id` + incremental `input`).

### PROMPT_SYSTEM line 70 is misleading

> “System prompt assembled once per user turn, reused unchanged across all iterations.”

True in **Kotlin memory**, false on the **wire** for workshop and for Kimi/Anthropic. Coding agents reading this line have implemented “resend full system every hop.” **Change this to describe HTTP transport explicitly.**

---

## Design principles (target architecture)

### 1. Separate “turn context” from “hop context”

| Layer | When built | When sent |
|-------|------------|-----------|
| **Turn context** | Once per user Send | HTTP round 1 only (or chained via `previous_response_id`) |
| **Hop context** | Each tool round | Incremental: last assistant + tool results only |

**Turn context:** identity, tool policy, workshop phase/mode instructions, file manifest snapshot, diff-review status, bounded cold-start spec orientation.

**Hop context:** assistant `tool_calls`, tool results, optional short assistant text, required Kimi tool-row reasoning.

### 2. Search-first content policy (unchanged intent, stricter enforcement)

| Content | First hop only? | Tool retrieval? |
|---------|-----------------|-----------------|
| File manifest (names + ids) | Yes | `workshop_list` implicit in manifest |
| Spec `.md` bodies | **Cold start / phase entry only** — not every hop | `search_semantic`, `workshop_read_file` |
| Open file excerpt | First hop only if editor open | `workshop_read_file` |
| Runtime source | Never inline | tools only |
| Intake summary | First hop of build kickoff | — |

Steady-state build loop: **manifest + mode block + search** — not 6k of README every hop.

### 3. History is necessary; history **replay** must be bounded

- **Keep:** tool results the model needs for the current edit chain (recent N rounds).
- **Trim/summarize:** old `workshop_read_file` full bodies after the model has acted on them.
- **Keep:** tool-call arguments (already redacted for Kimi writes).
- **Re-enable** tool-safe `trimHistoryIfNeeded` with **atomic assistant+tool rounds**.

### 4. Provider families — one policy table

| Family | Providers | Tool hop transport (target) |
|--------|-----------|------------------------------|
| **RESPONSES_CHAINED** | xAI, OpenAI | Hop 1: full system + history + user. Hop 2+: `previous_response_id`, **empty system**, incremental input = last round only. **Workshop uses same path.** |
| **MESSAGES_CACHED** | Kimi, Anthropic | Hop 1: cached system + history + user. Hop 2+: **omit system body if byte-identical** (send cache breakpoint only if provider supports) OR rely on cache_control on unchanged system block; **do not rebuild prose**; append new messages only. Trim old tool bodies in outbound history. |

### 5. Auto-Continue complements hop efficiency

[Auto-Continue](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) (chunk → handoff → new `send()`) limits **depth** of one chain. It does **not** replace hop-level efficiency — a single chunk with 12 hops still explodes if each hop resends 8k system + full history.

---

## Workshop prompt contents — revised policy

Replace the Workshop table in [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) with:

| Component | Inject | When | Notes |
|-----------|--------|------|-------|
| Workshop mode + phase instructions | Yes | Turn start | Single `PanelPlatformSpec` block; **dedupe** embedded `EIDOS_WORKSHOP_RETRIEVAL_POLICY` |
| Workshop tool list (API) | Yes | Every HTTP round | Required by providers for function calling — **not** the same as system prose |
| File manifest | Yes | Turn start | Names + `fileReferenceId` only |
| Spec `.md` excerpt | Optional | **First hop of turn** or phase/kickoff entry | `WorkshopSpecMarkdown` ≤6k; skip on TOOL_CONTINUATION |
| Open editor excerpt | Optional | First hop only (non-Chat) | ≤6k |
| Intake summary | Yes | Build kickoff / spec phases | ≤8k |
| Project summary (`Subfolder.projectSummary`) | No | — | Human + `search_semantic` |
| Panel bridge | When Preview active | Turn start | |
| Diff Review queue status | Yes | Turn start | Short line |
| Daily memory | Bounded | Turn start | Relevance-only guidance |
| LTM / journal bodies | No | — | `search_semantic` |

**Remove from workshop system prompt (move to tools-only or drop):**

- Global `TOOL_FIRST_CONTEXT_RULES` folder/note prose (workshop already has `WORKSHOP_TOOL_FIRST_CONTEXT_RULES`).
- Long Kimi Formula convert/date/excel block when `activeScope == PANEL_WORKSHOP`.
- Repeated retrieval policy inside every `eidos*Instructions()` variant — **inject once** per turn.

---

## Main chat — scope of fixes

Main chat on **xAI/OpenAI** already uses incremental continuations — **no change** except regression tests.

Main chat on **Kimi/Anthropic** has the same **full history replay** issue:

| Issue | Fix |
|-------|-----|
| Full system resent every hop | Stable system with cache_control; no reassembly unless turn boundary |
| Unbounded tool result bodies in history | Same trim/summarize policy as workshop |
| History trim disabled globally | Re-enable with tool-round-safe trimmer |
| Subfolder file list not inline (per PROMPT_SYSTEM) | Keep — do not re-add bulk lists |

---

## Implementation phases

### Phase 0 — Documentation correction (do first)

**Prevents coding agents from re-introducing bloat.**

| File | Action |
|------|--------|
| [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) | Rewrite **Tool Loop** section: distinguish turn assembly vs HTTP hops; remove “reused unchanged” ambiguity; add provider table |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md) | Replace “workshop: full system + history every hop” with **target** transport; mark current behavior as **bug/legacy** |
| [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) | Strike “intentional — prefix cache”; point here for hop policy |
| [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) | Add tracker rows for Phases 1–4 below |
| [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) | Document per-phase request shape with byte/accounting notes |

**Agent rule (add to PROMPT_SYSTEM or `.cursor/rules`):**

> Never set `useIncremental = false` for workshop on Responses providers. Never inline spec/runtime file bodies on TOOL_CONTINUATION hops. Never duplicate retrieval policy blocks in `PanelPlatformSpec`.

### Phase 1 — Workshop Responses transport ✅ (2026-06-08)

**Files:** `EidosApiClient.kt`, `EidosProviderFamily.kt`, `EidosProviderFamilyTest.kt`.

1. ✅ Removed `!isPanelWorkshop` guard — `shouldUseIncrementalToolContinuation(family, previousResponseId)`.
2. 🟡 Verify `previous_response_id` chain carries workshop context on xAI/OpenAI — manual API trace.
3. ✅ Unit tests for incremental policy (`EidosProviderFamilyTest`).
4. 🟡 Manual: API trace hop 2+ should not contain workshop system text on Grok/OpenAI.

**Acceptance:** Total input chars over a 5-hop workshop send on Grok drops by ~40–60% vs prior behavior.

### Phase 2 — Workshop lean system (content) ✅ (2026-06-08)

**Files:** `EidosApiClient.kt`, `PanelPlatformSpec.kt`, `WorkshopProjectContext.kt`, tests.

1. ✅ `WorkshopSystemPromptDetail` (`FULL_TURN` vs `TOOL_CONTINUATION`) + `workshopContinuationSystemPrompt` for Kimi/Anthropic hops.
2. ✅ Hop-1-only: open excerpt, editor tab line, bounded spec `.md`, spec accept-gate report.
3. ✅ `PanelPlatformSpec.eidosSharedWorkshopPromptTail()` — retrieval + platform summary once in `buildWorkshopPanelContext`.
4. ✅ Removed duplicate retrieval/`eidosContextSummary()` from all `eidos*Instructions()` variants.
5. ✅ Scoped out long provider/Kimi Formula prose from workshop `assembleSystemPrompt`.

**Acceptance:** First-hop smaller (deduped tail); Kimi hop 2+ uses lean continuation system; Grok/OpenAI hop 2+ still empty system (Phase 1).

### Phase 3 — Tool-result history trimming ✅ (2026-06-08)

**Files:** `EidosHistoryTrimmer.kt`, `EidosApiClient.buildTracedRequest`, `EidosHistoryTrimmerTest.kt`.

1. ✅ `prepareOutboundHistory(history, phase, policy)` — last **K=3** tool rounds verbatim in current user exchange; older bulk tool results stubbed.
2. ✅ Prior user exchanges: all bulky `search_semantic` / `read_*` / `workshop_read_file` results stubbed on outbound replay.
3. ✅ Wired in `buildTracedRequest` (all providers). Kimi chains `prepareKimiOutboundHistory` after bulk trim.
4. ✅ `trimHistoryIfNeeded` already active in `EidosApiClient.send()` (atomic user exchanges).

**Stub format:** `[Earlier read (fileReferenceId=…) — omitted from API replay; toolCallId=…. Call workshop_read_file again if needed.]`

**Acceptance:** In-flight 12-hop send retains last 3 full tool bodies; older hops send ~1-line stubs. DB / `mutableHistory` unchanged.

### Phase 4 — Messages API system optimization ✅ (2026-06-08)

**Research:** Moonshot/Anthropic prefix caching (`cache_control`, `prompt_cache_key`) reduces **billed** tokens on repeated prefixes; wire bytes may still be sent if the system block is included. Omitting the system block on tool continuations mirrors Responses `previous_response_id` chaining — hop-1 system is cached under the conversation key; hop 2+ relies on cached prefix + growing `messages[]` (with Phase 3 bulk-tool stubs).

**Shipped:**

1. ✅ `shouldOmitSystemPromptOnToolContinuation(MESSAGES_CACHED)` — empty `systemPrompt` on hop 2+ in `EidosApiClient`.
2. ✅ `KimiProvider` / `AnthropicProvider` — skip system block when `systemPrompt` is blank (`includeSystemPromptInMessagesPayload`).
3. ✅ Removed redundant `workshopContinuationSystemPrompt` double-assembly (Phase 2 lean block superseded by omit-on-continuation).
4. ✅ `prompt_cache_key` unchanged per conversation.

**Fallback if quality regresses:** re-enable lean `WorkshopSystemPromptDetail.TOOL_CONTINUATION` assembly instead of empty string (see git history 7.2).

**Acceptance:** Kimi hop 2+ API trace shows no system role / system field; payload growth = new messages + tools only.

### Phase 5 — Observability & guardrails ✅

**Files:** `EidosContextTransportMetrics.kt`, `EidosApiTraceRecorder.kt`, `EidosUsageLogger.kt`, `EidosApiTraceScreens.kt`.

1. ✅ Trace each HTTP round: `phase`, `systemChars`, `historyChars`, `historyNewChars` (delta), `toolResultChars`, `reasoningChars`.
2. ✅ Log **compounding factor** = total input chars / hop-1 input chars (`OptimalX.Eidos.Usage`).
3. ✅ Alert in debug when compounding factor > 2.5 on a single send (`Log.w`).
4. ✅ API trace run detail: “context efficiency” row + per-round transport summary.

### Phase 6 — Auto-Continue history policy ✅

When starting chunk 2+ of a kickoff (synthetic handoff `userMessage`):

- API history = kickoff human ask + prior assistant summary (`## Workshop handoff` stripped from assistant — handoff is the current `userMessage`).
- Full DB thread unchanged for UI.

**Files:** `WorkshopAutoContinueLeanHistory.kt`, `ChatMessageApiHistory.toEidosApiHistoryForSend`, `EidosChatViewModel`, `EidosChatSendWorker`.

Documented in [PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md](./PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md) § “Phase 2 lean history”.

---

## Testing matrix

| Case | Provider | Assert |
|------|----------|--------|
| Workshop build 5× `workshop_read_file` | xAI | Hop 2+ no system; incremental input only |
| Workshop build 5× read | Kimi | System size stable; old reads trimmed after K |
| Chat mode 2 tool rounds | Kimi | No spec excerpt on hop 2 |
| Subfolder note chat | Anthropic | No regression on multi-tool |
| Phase change mid-thread | all | Next user turn refreshes manifest |
| API trace UI | all | Compounding factor displayed |

---

## What not to do

| Anti-pattern | Why |
|--------------|-----|
| Inline runtime files “so the model sees them” | Violates search-first; stale after edits |
| Re-send full workshop context every hop “for safety” | Quadratic cost; no quality gain under 200k |
| Disable `previous_response_id` for workshop | Throws away the fix |
| Put tool definitions in system prompt | Already in `tools` array |
| Synthetic user messages **inside** `while (toolCalls)` | Breaks provider chain semantics — Auto-Continue stays between `send()` calls |
| Assume prefix cache = free to spam | Latency, limits, and confusion remain |

---

## Success metrics

| Metric | Current (typical) | Target |
|--------|-------------------|--------|
| Workshop hop-1 system chars | 6–12k | ≤4k (excl. intake) |
| System chars on hop 2+ (xAI/OpenAI) | 6–12k | 0 |
| Compounding factor (8-hop build, Kimi) | 4–8× | ≤2× |
| Time-to-first-token hop 8 vs hop 1 | Often 2–4× slower | ≤1.5× |
| User-visible “stuck/spammy” loops | Subjective reports | Fewer cap-pause events |

---

## Doc edit checklist (Phase 0)

- [x] PROMPT_SYSTEM.md — Tool Loop + Workshop table + agent rule (2026-06-08)
- [x] EIDOS_LLM_CONTEXT_CLEANUP.md — workshop hop section (2026-06-08)
- [x] PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md — remove “intentional full resend” (2026-06-08)
- [x] PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md — link this plan; Phase 7 tracker (2026-06-08)
- [x] LLM_API_REFERENCE.md — per-phase HTTP round table (2026-06-08)
- [x] KIMI_K26_MOONSHOT_SPEC.md — clarify reasoning replay vs system resend (2026-06-08)

---

## Code touchpoints (summary)

| Area | File |
|------|------|
| Tool loop / incremental | `EidosApiClient.kt` |
| System assembly | `EidosApiClient.assembleSystemPrompt`, `buildWorkshopPanelContext` |
| Mode prose | `PanelPlatformSpec.kt` |
| Spec cold start | `WorkshopSpecMarkdown.kt` |
| History trim | `EidosHistoryTrimmer.kt` |
| Kimi outbound | `KimiProvider.kt`, `prepareKimiOutboundHistory` |
| Responses incremental | `XAIProvider.kt`, `OpenAIProvider.kt` |
| Traces | `EidosApiTraceRecorder.kt` |
| Tests | `EidosHistoryTrimmerTest.kt`, new `EidosApiClientToolLoopTest.kt` |

---

## FAQ (for reviewers)

**Q: If we stop resending system, how does the model know the phase?**  
A: Hop 1 system + chained context + tool results. Phase changes only on user turn or kickoff — not mid-tool-loop.

**Q: Is history “spam”?**  
A: Tool results are **useful** when recent. **Stale** full-file reads repeated on every hop are spam. Trim old rounds, keep recent ones.

**Q: Should the LLM leave a message each hop?**  
A: Only when pausing for Auto-Continue handoff between **separate** `send()` calls — not inside the tool loop.

**Q: Are we sending full files in the system prompt?**  
A: Usually no — manifest + bounded spec excerpts. If traces show 6–8k on hop 1, it's mostly **instructions + orientation**, not `script.js`. After reads, **history** carries bodies — that's what Phase 3 trims.

---

*This plan is the source of truth for prompt **transport** until Phases 1–4 ship. [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) remains the source of truth for **what** to inject on turn start; this doc governs **how often** it hits the wire.*
