# Panel Workshop Auto-Continue — implementation plan

| Field | Value |
|--------|--------|
| **Status** | Active — canonical plan for Auto-Continue + prompt/token fixes (2026-06) |
| **Audience** | Product, Kotlin implementers, Eidos prompt authors |
| **Supersedes** | `WORKSHOP_EIDOS_TOKEN_LOOP_PLAN.md` (renamed; same content lineage) |
| **Related** | [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md), [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md), [PANEL_WORKSHOP_RECOVERY_PLAN.md](./PANEL_WORKSHOP_RECOVERY_PLAN.md), [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md), [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md), [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) |

---

## What this plan delivers

1. **Auto-Continue** — a first-class Panel Workshop **execution system**: chunked Eidos runs, LLM-authored handoff, synthetic user message, next `send()` — integrated into kickoffs (initial build + implementation plan), not a side setting.
2. **Two execution profiles** — **Build run** vs **Workshop edit** — documented and enforced in prompts/policy so mode/workflow conflicts reduce.
3. **Token loop fixes (Phase 1)** — tool-hop caps, leaner workshop system prompt, deduped retrieval policy.
4. **Doc alignment** — architecture and implementation `.md` files updated as each phase lands (checklist below).

**Goal:** Hands-off greenfield builds and plan-phase execution for non-coders; predictable interactive editing afterward — **without** breaking Accept gates, Diff Review on edit paths, or the v2 phase model.

---

## Executive summary

Auto-Continue replaces “one giant tool loop per user tap” with **chunk → handoff → chunk** until a kickoff completes or a guard fires. That directly addresses token burn, Kimi reasoning tail growth, and “stuck until the user types continue.”

Supporting cleanup (Phase 1) fixes prompt bloat that hurts every workshop send:

1. Resending full system prompt every tool hop (workshop; intentional — prefix cache).
2. Unbounded tool rounds in build/plan kickoffs (only Chat capped today).
3. Duplicate retrieval policy in system context.
4. Misleading global tool-first prose (folder/note tools not in workshop API).

| Phase | Deliverable |
|-------|----------------|
| **1** | Tool-hop cap, workshop prompt cleanup, honest pause when cap hit |
| **1.5** | **Auto-Continue operational** — `runWorkshopChunkedRun`, handoff parser, synthetic user resend |
| **2** | Cache/history/reasoning UX tuning on chunked chains |
| **3+** | Auto-advance after Accept; optional multi-LLM per phase |

---

## Two execution profiles (distinct systems)

Panel Workshop today mixes **project phase**, **Eidos mode chips** (Chat · Plan · Edit), and **kickoff modes** (Build design, Build logic, Build plan). Auto-Continue applies differently to two **execution profiles** — this split is the main antidote to conflicting workflow bugs.

| | **Build run profile** | **Workshop edit profile** |
|---|----------------------|---------------------------|
| **User intent** | “Build my panel” / “Run this plan phase” — hands-off | “Fix this” / “Change that” — interactive |
| **Typical entry** | Generate specs, Build design, Build logic, Build plan kickoffs | Edit chip in review/complete/update; chat then edit |
| **Writes** | Direct to disk (`WorkshopReviewPolicy` off for build family + build phases) | Diff Review proposals (`shouldReview` true) |
| **Human review** | **After** slice: Preview + **Accept** gates — not per-file mid-loop | Diff Review before live; user drives each fix |
| **Auto-Continue** | **On** — chain chunks until kickoff done or max chunks | **Off or minimal** — short cap; stop if Diff Review pending |
| **Eidos modes** | Internal `BUILD_*` kickoffs + Plan for specs; not casual Edit | Chat · Plan · Edit chips |
| **Prompt emphasis** | Handoff, finish kickoff, no spec rewrites in build phases | Targeted patches, Diff Review honesty, no false “live” |

```text
BUILD RUN PROFILE                          WORKSHOP EDIT PROFILE
─────────────────                          ─────────────────────
Intake → Generate specs ──►                COMPLETE / UPDATE / REVIEW
Build design ──► Accept design               User: "button is wrong"
Build logic  ──► Accept logic                     ▼
(Build plan phases in UPDATE)              Edit + Diff Review
     │                                            │
     ▼ Auto-Continue chains                      ▼ Usually single send
     chunk ↔ handoff ↔ chunk                     (no long auto chain)
```

**Implementation rule:** `WorkshopExecutionProfile` (or equivalent) derived from phase + mode + active kickoff — drives Auto-Continue eligibility, cap values, and prompt block (`PanelPlatformSpec`).

Code today (partial): [WorkshopReviewPolicy.kt](../../src/main/java/com/example/optimalx/data/revision/WorkshopReviewPolicy.kt), kickoffs in [EidosChatViewModel.kt](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt). Phase 1.5 wires **profile → chunked run** explicitly.

---

## Auto-Continue system (overview)

**Not** an optional “auto-continue toggle.” It is how **Build run profile** executes:

1. User or top-bar starts a kickoff → one logical **run**.
2. Each **chunk** = one `EidosApiClient.send()` with ≤ N tool hops.
3. Chunk ends when model stops with tools, hits cap, or emits handoff (see [Handoff strategies](#handoff-strategies) below).
4. **Code** persists assistant reply, extracts **LLM handoff** (primary) or fallback ticket.
5. **Code** inserts `ChatMessage(role=user, handoffText)` — synthetic, visible in chat.
6. **Code** calls `send()` again — handoff becomes the new `userMessage` for the next chunk.
7. Repeat until kickoff complete, `WORKSHOP_MAX_CHUNKS_PER_KICKOFF`, cancel, or edit-profile guard.

See [Auto-Continue — detailed behavior](#auto-continue--detailed-behavior) (formerly “synthetic continue” sections below).

---

## When Diff Review applies (build run vs edit)

The plan previously treated Diff Review as a blocker for **every** chunked run. That does **not** match the main **full build** flow or current policy in code.

### Three workshop modes of work

| Mode | What the user does | Diff Review during LLM loop? | Human review when? |
|------|-------------------|------------------------------|-------------------|
| **Full build run** | Intake → Generate specs → Build design → Build logic (top-bar kickoffs) | **No** — runtime writes go **direct to disk** | **After** a slice completes: Preview, then **Accept specs / Accept design / Accept logic** (phase gates), not per-file diffs mid-loop |
| **Simple edits** | “Fix the button color”, “enemy collision is wrong” (Edit in review/complete/update) | **Yes** — proposals queue per file | Diff Review screen before changes are “live”; user-driven, small deltas |
| **Build plan execution** | UPDATE + accepted `IMPLEMENTATION_PLAN.md`, **Build plan** (one tap) | **No** — same build-run profile as design/logic (`BUILD_PLAN` → direct disk) | Preview when **all** plan phases finish; **Accept update** to sync specs |

Code: [WorkshopReviewPolicy.kt](../../src/main/java/com/example/optimalx/data/revision/WorkshopReviewPolicy.kt) — `isBuildFamily` + `DESIGN_BUILD` / `LOGIC_BUILD` → `shouldReview == false`; `DESIGN_REVIEW`, `LOGIC_REVIEW`, `COMPLETE`, `UPDATE` (Edit) → `true`.

### Implication for synthetic continue / chunked runs

| Context | Chain across LLM handoffs? | Pause for Diff Review mid-chain? |
|---------|---------------------------|----------------------------------|
| **Main build** (`BUILD_DESIGN`, `BUILD_LOGIC`, Generate specs `.md` only) | **Yes** — primary target for hands-off chunked run | **No** — not in this scenario; review the **whole** build at Accept / Preview |
| **Simple edits** (Edit, post-complete fixes) | Optional — shorter caps; user often watches | **Yes** when `shouldReview` — do not claim “fixed on disk” until accepted; chaining across pending proposals is usually wrong |
| **Build plan** (`BUILD_PLAN` kickoff) | **Yes** — tool-hop chunks **and** Auto-Continue **between plan phases** (handoff) | **No** — not during build plan run; user reviews in Preview after full plan |

**Full build auto-continue** = run chunk → handoff → chunk → … until kickoff task completes or `WORKSHOP_MAX_CHUNKS`, **without** stopping for Diff Review. User reviews once the run finishes (or at existing Accept gates), not every few tool calls.

**Simple edits** = keep Diff Review semantics; chunked continue is secondary and may stop when the queue is non-empty if we ever chain edits at all.

Open product question: ~~should **Build plan** execution match main build~~ **Resolved (2026-06):** Build plan uses **build-run profile** — direct disk, Auto-Continue across plan phases, no Diff Review mid-run. Edit in UPDATE still uses Diff Review.

**Build plan Auto-Continue:** One **Build plan** tap → run plan phases in order. Within each plan phase: ≤ `WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS` (12) tool hops per chunk, ≤ `MAX_CHUNKS_PER_KICKOFF` (6) chunks per plan phase. At plan phase boundary: `## Workshop handoff` → synthetic user → next phase (chunk counter resets). Stop when model declares **implementation plan complete** or `MAX_PLAN_PHASES_PER_RUN` (20).

---

## How one user send actually flows (workshop + Kimi)

Correct mental model:

```
User taps Send
  → HTTP round 1 (FULL)
      system: [full assembled workshop system prompt]
      messages: [trimmed chat history] + user message
      tools: [workshop subset + Kimi Formula tools]
  → model may return tool_calls (no final text yet)

While tool_calls not empty:
  → HTTP round 2..N (TOOL_CONTINUATION)
      system: [same full assembled system prompt again]   ← workshop always
      messages: [entire history including prior tool results] + new tool results
      userMessage: "" (empty; turn seeded in history)
```

**Not** `userMessage → system → tool → userMessage → system` as alternating roles. The **user message is once per send**; later hops append **assistant** (tool call) and **tool** (result) rows, then call the API again with the **same system string** re-attached.

Code: `EidosApiClient.send()` — `useIncremental = !isPanelWorkshop` for xAI/OpenAI only; workshop keeps `assembledSystemPrompt` every hop.

```481:491:app/src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt
                    // Workshop multi-tool runs need full system + history every hop (file manifest, mode, IDs).
                    val useIncremental = !isPanelWorkshop &&
                        providerFamilyUsesIncrementalToolContinuation(family) &&
                        !currentResponseId.isNullOrBlank()
                    workingRequest = EidosRequest(
                        systemPrompt = if (useIncremental) "" else assembledSystemPrompt,
                        conversationHistory = if (useIncremental) {
                            continuationSlice
                        } else {
                            mutableHistory
                        },
```

**Why it was written this way:** Workshop system context includes live manifest, phase, mode, open-tab excerpt, and gates — authors wanted the model to never “lose” `fileReferenceId`s or phase rules mid-loop.

**Why it still hurts on Kimi:** Moonshot caches the **stable prefix**, but you still pay latency and input-token accounting on growth; repeated reasoning and tool bodies dominate when loops run long.

---

## Auto-Continue — detailed behavior

**Problem:** Non-coders want to describe a panel and walk away during Build logic / design / plan. A single uncapped `send()` bloats history and Kimi reasoning. The user should **not** have to type “continue” every few minutes.

**Correct product model (aligned):** The **synthetic user message is not** a Kotlin-only template and **not** an optional “auto-continue” setting. It is the **mechanism** for starting the next chunk:

1. **Chunk 1** ends (tool cap or natural stop).
2. **Assistant (LLM)** produces visible handoff text: what was done, what to do next (guided by mode instructions).
3. **Code** persists that assistant reply, **extracts** the handoff block, inserts a **`role=user`** row with that text (synthetic — human did not type it), then **immediately** calls `send()` again with that text as the new turn’s `userMessage`.
4. **Chunk 2** — same model/provider by default — reads the handoff as the user turn and continues.

So: **code + LLM working together** — the LLM authors the continue instruction; the code registers it in chat and re-submits it to the next loop run. Typically **same LLM**, new `send()` (fresh tool budget), not “userMessage–system” ping-pong inside one `while`.

### What is *not* required

| Misconception | Reality |
|---------------|---------|
| Optional Settings “auto-continue on/off” as the core feature | Chaining **is** synthetic handoff; optional setting might only **disable** chaining for power users (`pause between chunks`) |
| Different LLM per chunk (Phase 1.5) | Same `ACTIVE_PROVIDER`; V3+ may swap provider per **phase** |
| Reset inside `while (toolCalls)` | **Not possible** — exit `send()`, persist DB messages, new `send()` from ViewModel |
| Human must tap Continue every time | Human tap is **fallback** if handoff missing or chain stopped at guardrail |

### Flow (chunked run — default during build kickoffs)

```
Human or kickoff → userMessage₀ → send() chunk 1 (≤ N tool hops)
  → assistant visible text includes HANDOFF block (LLM-written)
  → code: insert ChatMessage(user, handoffText)   // synthetic
  → code: callApiAndInsertReply(conversation, handoffText)  // = userMessage₁
  → send() chunk 2 …
  → repeat until done, guardrail, or maxChunks (see [When Diff Review applies](#when-diff-review-applies-product-model--read-this-first))
```

Chat shows: human → Eidos → **Eidos continue** (styled synthetic user) → Eidos → … User can **cancel** the in-flight `apiExchangeJob` to stop chaining.

### Feasibility with the **current** stack

**Verdict: feasible and moderately scoped — not blocked by architecture.** Not as exotic as multi-LLM orchestration; it fits existing `EidosChatViewModel.callApiAndInsertReply`.

| Already exists | What Phase 1.5 adds |
|----------------|---------------------|
| `callApiAndInsertReply(conversation, userText)` loads history from DB, calls `api.send`, inserts assistant | After capped `send()`, parse handoff → insert synthetic user → call `callApiAndInsertReply` again (recursive or loop in ViewModel) |
| `ChatMessage` user/assistant rows | `isSyntheticHandoff` column or `[Eidos handoff]` prefix for UI |
| Workshop kickoffs set mode/phase | `WorkshopChunkedRun.active` during kickoff until complete |
| `apiExchangeJob` cancel | Clears `WorkshopChunkedRun` — user stop switch |
| Tool cap (Phase 1) | Triggers handoff path |

**Same provider path:** `api.send(userMessage = handoffText, conversationHistory = …)` includes the synthetic user row once inserted (today’s flow inserts user **before** send in the send path — verify insert order matches kickoffs).

**Code touchpoints:**

- `EidosApiClient.send()` → return `EidosSendResult.PausedForHandoff` when cap hit (include whether last assistant had parseable handoff).
- `EidosChatViewModel` → `maybeChainWorkshopChunk(response, conversation)` after `callApiAndInsertReply`.
- `PanelPlatformSpec` / cap notice → instruct model: when paused at tool limit, **must** output a `## Workshop handoff` section with Done / Next / Do not.
- `WorkshopHandoffParser` → extract section from `textResponse`; **fallback** `WorkshopContinueTicket.build(...)` from phase/pending if model omitted block (reliability safety net, not primary UX).

**Hard parts (manageable):**

| Risk | Mitigation |
|------|------------|
| Last assistant row is tool_calls only, no visible text | On cap, either require prior hop to leave handoff text, or one **tool-free** completion call “emit handoff only”, or code-only fallback ticket |
| Model claims “done” when not | Prompt + detect kickoff complete signals; don’t chain if Accept gate / empty handoff |
| Infinite chain | `WORKSHOP_MAX_CHUNKS_PER_KICKOFF` (e.g. 5–8) |
| Diff Review pending | **Only** when `shouldReview` — **not** during main build kickoff |

**Too advanced?** Only if we also demand V3 multi-LLM and cross-phase Accept automation in the same release. **LLM handoff + code resend** alone is a reasonable Phase 1.5 on top of Phase 1 cap.

### Handoff content — LLM primary, code fallback

**Primary (LLM-authored):** Visible assistant message ends with:

```markdown
## Workshop handoff
- Done: …
- Next: …
- Constraints: …
```

**Fallback (code):** If parser finds no section, `WorkshopContinueTicket.build(phase, mode, pendingCount, lastToolsRun)` — same shape, marked `[Eidos handoff — system]` so logs show fallback.

Do **not** rely on code-only tickets for the happy path; the model should steer itself across chunks.

### Why this limits bloat

| Mechanism | Effect |
|-----------|--------|
| Cap N tool hops per chunk | Ends one in-flight Kimi `keep: all` chain |
| New `send()` per chunk | Fresh tool-round budget |
| Handoff as **short** next `userMessage` | Next chunk’s steering text without re-pasting all tool JSON |
| Phase 2 lean history | Optional: chunk 2+ history = original human ask + last handoff + last assistant summary |

### Relationship to existing kickoffs

Kickoffs (`sendWorkshopBuildLogicKickoff`, etc.) set `WorkshopBuildKickoff` and the first `userMessage`. Synthetic continue **resumes the same kickoff/mode** until the model finishes or guards fire — user does not tap the top bar again between chunks.

**Main build / build plan:** chain freely during `DESIGN_BUILD` / `LOGIC_BUILD` / `BUILD_PLAN` kickoffs; human reviews at **Accept** / Preview after the run completes, not Diff Review mid-loop. **Edit in UPDATE:** respect `shouldReview`.

### Guardrails

| Guard | Behavior |
|-------|----------|
| `WORKSHOP_MAX_CHUNKS_PER_KICKOFF` | Stop resending; assistant tells user to review or tap **Retry continue** |
| Diff Review pending (`shouldReview` only) | Do not chain next chunk until user accepts proposals |
| User cancel | Abort `apiExchangeJob`; clear chunked-run state |
| Chat mode | No chunked chain (2 tool rounds only) |
| Missing handoff | Fallback ticket once; if still fails, surface **Continue** button with last assistant text |

### Implementation sketch (Phase 1.5)

| Layer | Responsibility |
|-------|----------------|
| `EidosApiClient` | `PausedForHandoff` when `toolRound > cap`; optional forced final text-only hop for handoff (only if needed) |
| `WorkshopHandoffParser` | Parse `## Workshop handoff` from assistant `textResponse` |
| `WorkshopContinueTicket` | Fallback builder only |
| `EidosChatViewModel` | `runWorkshopChunkedRun`: after reply inserted → parse → insert synthetic user → `callApiAndInsertReply(handoff)` until done/guard |
| `EidosChatScreen` | Style synthetic user rows; show “Building…” during chain; cancel stops chain |
| Settings (optional) | `workshop_pause_between_chunks` default **false** during kickoff — inverse of old “auto-continue off” |

**Do not** insert synthetic messages inside `while (toolCalls)` in `EidosApiClient`.

### What this is not (deferred)

- **Multi-LLM relay** (GPT plans → Kimi codes → Grok structures) — V3+; handoff text format stays the same.
- **Mid-loop API reset** — unavailable from providers.
- **Skipping Accept gates** on main build — phase Accept (specs/design/logic) stays; Diff Review is for edit/plan paths, not the full build loop.

---

## FAQ — your questions answered

### What is “intake summary”?

Persisted text from **Phase 1 intake chat** (What / Why / How), not something Eidos invents each loop.

| Item | Source |
|------|--------|
| Built from | User messages in the workshop Eidos thread (`WorkshopIntakeSummary.fromChatMessages`) |
| Stored | `WorkshopProjectPreferences` per subfolder |
| Max size | 8,000 chars (`WorkshopIntakeSummary.MAX_CHARS`) |
| Injected when | Non-blank, in `buildWorkshopPanelContext` under “Intake summary (chat alignment…)” |
| Used for | Spec generation / build kickoffs so Eidos does not re-derive intent from scratch |

It is **orthogonal** to `eidosContextSummary()` and **orthogonal** to user-generated **project summary** (`Subfolder.projectSummary`).

---

### Do we send the full Eidos tool catalog in Panel Workshop?

**API tools array:** **No.** Workshop uses `EidosToolCatalog.toolsForWorkshopMode(mode, phase)` — typically `search_semantic`, workshop file tools, optional `call_panel_function`, not `write_note`, folder tools, etc.

**System prompt prose:** **Partially misleading.** Every Eidos call (including workshop) still injects:

- `EidosContextLimits.TOOL_FIRST_CONTEXT_RULES` — mentions `read_note`, `read_file`, folder tools.
- A long **provider / Kimi Formula** block in `assembleSystemPrompt()` — convert, date, excel, web_search, fetch.

The model only **can** call tools present in the request payload; prose about unavailable tools is wasted tokens and can confuse.

**Phase 1 action:** Add a **workshop-scoped** variant of tool-first rules + provider note (manifest + workshop tools + Formula web only). Do **not** change `toolsForWorkshopMode` gating unless product asks.

---

### Kimi Formula block — does workshop use it?

**Yes, when provider is Kimi** — Formula tools are attached in `KimiProvider.buildTools()` via `KimiFormulaToolService`, not via the generic catalog.

| Formula | Workshop |
|---------|----------|
| `web_search`, `fetch` | Available (useful for API/docs debugging) |
| `convert`, `date`, `excel` | Available globally today — rarely needed in workshop |
| ~~`quickjs`~~ | **Removed (2026-06-06).** Was briefly shipped for workshop DEBUG; product dropped it — no Formula sandbox JS in workshop or general chat. |

Workshop **does** allow Formula execution in `EidosApiClient` (Fiber dispatch) — same as general chat. The issue is **prompt bloat and unused tools**, not “code blocks Formula.”

---

### What is `eidosContextSummary()` vs project summary?

| Name | What it is |
|------|------------|
| **`PanelPlatformSpec.eidosContextSummary()`** | Fixed **platform contract** blurb (~300 chars): PanelHtmlComposer, bridge.js vs script.js, persistence, Android layout hint. Appended inside **mode instructions**. Not user content. Not “Eidos talking to itself for the next loop.” |
| **Project summary** | User-triggered **Generate Project Summary** → stored on `Subfolder.projectSummary`, injected as “Project summary (user-generated…)” |
| **Spec fallback** | If no project summary: bounded spec `.md` via `WorkshopSpecMarkdown` (6k total, 2k/file) |
| **Intake summary** | Intake chat rollup (see above) |

---

### Duplicate `EIDOS_WORKSHOP_RETRIEVAL_POLICY` — bug or practice?

**Bug / oversight on our end.** It appears inside `eidosInstructionsForMode(...)` **and** again at the end of `buildWorkshopPanelContext()`.

**Phase 1:** Remove the duplicate append in `EidosApiClient.buildWorkshopPanelContext` (keep single copy in mode instructions).

---

### `search_semantic` — how do chunks get into the prompt?

1. Model **chooses** to call `search_semantic` (not automatic).
2. Tool returns a **JSON array** of up to **10 hits** (default `limit`; max 50 if model passes it).
3. Each hit includes **`chunk_text`** (~1,800 char target per chunk, 200 char overlap between windows — see `ContentSegmentation.CHUNK_EMBED_TARGET_CHARS`).
4. Those strings become **tool role messages** in history → resent on every workshop hop.

**The app does not** inject 10 chunks into the system prompt without a tool call.

**The model may** call `search_semantic` once and stop, or call it multiple times, or follow with `workshop_read_file`.

**Rough upper bound per search call:** 10 × ~1.8k ≈ **18k characters** of chunk text in one tool result (plus JSON overhead). For small workshop files, overlapping chunks can feel redundant but usually not “entire file × 10” unless the file is tiny and highly repetitive.

**Not the same as:** reading the whole file once per chunk. `workshop_read_file` is a **separate** tool invocation.

---

### `workshop_read_file` after search — whole file per chunk?

**No.** One `workshop_read_file` call reads **one file** (`fileReferenceId`).

| Call shape | Behavior |
|------------|----------|
| `query` or `startLine`/`endLine` | Scoped excerpt via `ContentSectionRetriever` |
| No query, file ≤ **2,000** chars | **Full file** returned once |
| No query, file > 2,000 chars | Truncation **hint** only — must pass query or line range |

`workshop_read_file` also re-indexes that file into semantic chunks (`semanticChunkBuilder.indexFile`) — side effect for search quality, not N reads.

**Risk pattern to watch in Logcat:** `search_semantic` → `workshop_read_file` (no query) on a ≤2k `script.js` → **full file in history** → another `workshop_read_file` on same id next hop. That is **model loop behavior**, not chunk multiplication.

**Phase 1:** No change to chunk count unless metrics show pain. Optional **Phase 2:** lower default `limit` for workshop scope only (e.g. 6) — needs quality testing.

---

### Kimi / Anthropic caching vs resending the system prompt

**Resending the system string each hop is correct** for `MESSAGES_CACHED` providers when using Chat Completions / Messages with `cache_control` on a stable prefix:

- Moonshot/Anthropic match a **prefix hash**; cache hit reduces **cost** on repeated prefix tokens.
- You still send the bytes; the server recognizes duplication.
- Workshop **does not** use OpenAI/xAI `previous_response_id` incremental mode (empty system on continuation).

Logcat “cached tokens” growing on later hops often means **prefix cache hits** on system + early messages — good — but **new** tool results and reasoning at the tail are never cached on first sight.

**If cache hits are high but it still feels slow:** tail growth (reasoning + tool JSON) and **many HTTP round trips** dominate latency, not prefix misses.

---

### `reasoning_content` every tool hop — required? practical?

| Phase | Outbound API (`prepareKimiOutboundHistory`) |
|-------|---------------------------------------------|
| `FULL` | Strip `reasoning_content` on **text-only** assistant replies |
| `TOOL_CONTINUATION` | Keep reasoning on assistant rows with **`tool_calls`** in the **current turn**; strip older turns |

Moonshot **requires** reasoning replay on tool-call rows when `thinking` + `keep: all` during an in-flight chain ([KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md)). We should not remove that without vendor guidance.

**UX issue (long, repetitive thinking):** product/perf, not “wrong transport.” Phase 2 options:

- UI collapse / don’t stream full reasoning to chat during tool storms
- Stricter cap on **stored** reasoning length (DB/UI only) while keeping API replay compliant
- **Do not** arbitrarily truncate reasoning in API payloads for active tool rows

**Write replay:** `workshop_write_file` / `workshop_create_file` **arguments** are redacted in Kimi replay (`redactToolCallForKimiReplay`); **tool result bodies** are not.

---

### Clarification: “truncate read-only tool results” (rejected idea)

**Not** “give the model only read tools.”

Meant (optional, Phase 2+): when **re-sending history** on hop 8+, replace **old** `search_semantic` / `workshop_read_file` **tool result text** with a short pointer (“see hop 3”) while keeping write results and recent reads intact. High risk of breaking edit chains — **deferred** unless we have tests. **Not in Phase 1.**

---

## Phase 1 — approved direction (tread lightly)

| # | Change | Risk | Files (likely) |
|---|--------|------|----------------|
| 1 | **Max tool rounds** for workshop Edit/Build/Plan  | Low if message is honest | `EidosApiClient` — new constant e.g. `WORKSHOP_MAX_TOOL_ROUNDS = 12`; on exceed return fixed assistant text |
| 2 | **Pause message content** (no fake “done”) | — | Template: list pending intent, files touched this turn, “Reply **continue** to resume” / “Start new chat if unrelated” |
| 3 | **Drop duplicate** `EIDOS_WORKSHOP_RETRIEVAL_POLICY` in `buildWorkshopPanelContext` | Very low | `EidosApiClient.kt` |
| 4 | **Omit project summary** from system prompt (keep manifest + mode + optional intake); model uses `search_semantic` / `workshop_read_file` on README/specs | Low–medium | `buildWorkshopPanelContext`; update PROMPT_SYSTEM workshop table |
| 5 | **Workshop-scoped system prose** — shorter tool-first + Formula lines; drop general-folder tool mentions | Low | `EidosApiClient.assembleSystemPrompt` branch for `PANEL_WORKSHOP` |
| ~~6~~ | ~~Always exclude QuickJS~~ | — | **Done / obsolete** — QuickJS Formula removed from codebase (2026-06-06) |
| 7 | **Tests** | — | `EidosApiClient` or resolver tests for round cap; snapshot test that workshop system block does not contain duplicate retrieval heading twice |

### Max tool rounds — behavior spec

When `toolRound > WORKSHOP_MAX_TOOL_ROUNDS` **before** executing more tools:

1. **Do not** execute further tools this send.
2. Return **visible assistant text** (persist to chat) including:
   - What was in progress (from last assistant content if any).
   - Tools already run this send (count + names).
   - Explicit: **“Paused after N tool steps — not finished.”**
   - User actions: continue in thread, or New Chat; mention Diff Review only when `shouldReview` applies.
3. **Do not** claim files are live on disk when writes are still queued in Diff Review (edit/plan paths only).
4. Expose pause outcome to UI (marker text or `PausedForContinue`) for **Continue** / auto-continue (Phase 1.5).

Suggested constants (tune in implementation):

```kotlin
// Chat already uses WORKSHOP_CHAT_MAX_TOOL_ROUNDS = 2
const val WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS = 12   // Phase 1 — tool hops per chunk
const val WORKSHOP_MAX_CHUNKS_PER_KICKOFF = 6        // Phase 1.5 — LLM handoff chains per kickoff
```

### Project summary omission

**Rationale:** Summary duplicates spec/README content; steady-state retrieval is `search_semantic` + targeted read. User can still **Generate Project Summary** for human reading; we simply stop injecting it every hop.

**Keep:** File manifest, mode instructions, intake summary (bounded), open-tab excerpt policy unchanged unless separately decided.

---

## Phase 1.5 — Auto-Continue (LLM handoff → resend)

### Handoff strategies

| Strategy | When | Notes |
|----------|------|-------|
| **LLM stops + `## Workshop handoff`** | Primary | Model ends chunk with visible handoff; no cap drama |
| **`workshop_emit_handoff` tool** (optional) | Phase 1.5+ | Structured done/next; code ends chunk reliably |
| **Tool-hop cap** | Safety net | Moderate N (12–16); not “infinite cap” |
| **Code fallback ticket** | Parser miss | `WorkshopContinueTicket` — logged, not happy path |

High cap “let the model decide only” does **not** replace a safety cap — loops still happen.

**Depends on:** Phase 1 max tool rounds.

| # | Change | Risk | Files (likely) |
|---|--------|------|----------------|
| 1 | Mode prompt: require `## Workshop handoff` when chunk ends at tool cap | Low | `PanelPlatformSpec`, cap message in `EidosApiClient` |
| 2 | `WorkshopHandoffParser` — extract handoff from assistant `textResponse` | Low | New parser + tests |
| 3 | `WorkshopContinueTicket` — **fallback only** if parser empty | Low | New file |
| 4 | `EidosApiClient` → `PausedForHandoff` (or flag on `EidosResponse`) when cap hit | Low | `EidosApiClient.kt` |
| 5 | `EidosChatViewModel.runWorkshopChunkedRun` — after assistant saved: parse → insert synthetic user → `callApiAndInsertReply(handoff)` | Medium | `EidosChatViewModel.kt` |
| 6 | UI: synthetic user styling, cancel stops chain, optional “Retry continue” if chain aborted | Low–medium | `EidosChatScreen`, `ChatMessage` metadata |
| 7 | Stop chain when kickoff complete / `WORKSHOP_MAX_CHUNKS`; Diff Review only if `shouldReview` | Medium | ViewModel + `WorkshopReviewPolicy` |
| 8 | Optional Settings: `workshop_pause_between_chunks` (default **false** on kickoffs) | Low | Settings |

### Pause vs complete

| Outcome | Next step |
|---------|-----------|
| Cap hit, handoff parsed | Insert synthetic user = handoff text → **immediate** next `send()` (default) |
| Cap hit, no handoff | Fallback ticket → insert → resend; log fallback |
| `toolCalls` empty, kickoff task complete | Clear `WorkshopBuildKickoff`, **no** synthetic resend |
| Spec accept gate ready | Tell user Accept — **do not** chain writes |
| Diff Review pending | **Stop** chain only when `shouldReview` (**Edit** in UPDATE — not Build plan) |

### Modes in scope for 1.5

| Mode / kickoff | Synthetic continue |
|----------------|-------------------|
| `BUILD_DESIGN` / `BUILD_LOGIC` / `BUILD_PLAN` kickoffs | **Yes** — primary target |
| Edit on `LOGIC_BUILD` / `DESIGN_BUILD` long fixes | **Yes** |
| `sendWorkshopGenerateSpecsKickoff` | Optional — cap + continue until specs valid or max chunks |
| Chat | **No** (existing 2-round cap sufficient) |
| Doc align / Plan-only `.md` | Careful — shorter caps; user may want review between chunks |

---

## Phase 2 — cache & transport (higher risk, measure first)

| Item | Notes |
|------|------|
| Workshop incremental continuations | Experiment: stable cached system + manifest snapshot id; only append delta in messages. Must not drop `fileReferenceId` or phase gates. |
| Workshop-only `search_semantic` default limit | e.g. 6 vs 10 — quality test on real panels |
| Reasoning UX | Collapse in UI; avoid showing 4 parallel plans during tool loops |
| Old tool-result compaction | See clarification above — only with strong tests |
| **Lean history on auto-continue** | Optional flag: next chunk sends only ticket + last assistant summary + original user ask — reduces cross-chunk token bleed; needs tests |

---

## V3+ — phase orchestration & multi-LLM (later)

**Not part of Phase 1 / 1.5.** Builds on synthetic continue + existing Accept gates.

| Idea | Notes |
|------|-------|
| **Auto-advance after Accept** | User Accepts specs → app inserts synthetic “Start Build design” + kickoff — no top-bar tap |
| **Build plan phase chain** | After each plan phase handoff → synthetic user → next phase (same kickoff; chunk budget resets). No Diff Review between phases. |
| **Per-phase provider** | e.g. specs=OpenAI, logic=Kimi — `WorkshopOrchestratorProvider` table; handoff ticket stays short |
| **Conductor model** | One cheap model plans next step; workers run kickoffs — aligns with Kimi spec “sub-LLMs” note |

**Complexity:** High — phase state machine, Accept timing, provider capability matrix (Kimi Formula vs Responses chain), UX for cancel mid-pipeline.

**Recommendation:** Ship Phase 1 + 1.5 on `main` or `experiment/workshop-continue`; prototype V3 on a branch with feature flag.

---

## Verification checklist

### Phase 1 — automated (CI / local, no device)

| Check | How | Status |
|-------|-----|--------|
| Pause message + marker | `WorkshopToolRoundPauseTest` | ✅ |
| Workshop context rules scoped | `EidosContextLimitsTest.workshopToolFirstContextRules_areWorkshopScoped` | ✅ |
| Retrieval policy once in mode instructions | `PanelPlatformSpecTest.eidosInstructionsForMode_includesRetrievalPolicyOnce` | ✅ |
| No duplicate retrieval append in client | `buildWorkshopPanelContext` does not append `EIDOS_WORKSHOP_RETRIEVAL_POLICY` (mode block only) | ✅ |
| No project summary inject | `buildWorkshopPanelContext` does not call `formatWorkshopSummaryForPrompt` | ✅ |
| Tool-cap flag + kickoff guard | `EidosChatViewModel` logs `OptimalX.Workshop.Phase1`; does **not** clear `WorkshopBuildKickoff` when `workshopPausedForToolCap` | ✅ |

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.optimalx.data.eidos.WorkshopToolRoundPauseTest' --tests 'com.example.optimalx.data.eidos.EidosContextLimitsTest' --tests 'com.example.optimalx.data.eidos.PanelPlatformSpecTest'`

### Phase 1 — device smoke (manual, one real workshop project)

Prereq: Kimi (or chosen provider) API key; workshop project past intake.

1. **Generate specs** or **Build design** kickoff — let run until natural end or tool-cap pause.
2. **Logcat** — filter `OptimalX.Eidos.Usage` and `OptimalX.Workshop.Phase1`:
   - Usage: per-hop `round` ≤ 12 while tools run; on cap, `tool_cap_paused rounds=12`.
   - Phase1: `tool_cap_pause persisted rounds=12 conv=…` when capped.
3. **Chat** — if capped, assistant text includes `Paused after … tool steps`, `not finished`, `[workshop_tool_cap_paused]`; mode chip still shows build kickoff (kickoff **not** cleared on cap).
4. **Diff Review (edit path only)** — in `DESIGN_REVIEW` + Edit with pending proposals, cap message may mention Diff Review; build kickoffs (`BUILD_DESIGN` / `BUILD_LOGIC`) must **not** mention Diff Review.
5. **Kimi cache** — `cached_tokens` on hop 2+ in Usage log (no regression).
6. **API Trace** (Settings → Developer) — assembled system prompt: `Retrieval (all workshop modes)` appears **once**; no inlined project summary block.
7. **Tool list** — workshop send exposes workshop tools only (no `write_note`).

```bash
adb logcat -s OptimalX.Eidos.Usage OptimalX.Workshop.Phase1
```

### Phase 1.5 (synthetic continue)

7. Cap hit → assistant includes `## Workshop handoff`; synthetic **user** row matches LLM handoff text.
8. Next chunk starts **without** human typing (same provider unless Settings say otherwise).
9. Chain stops at `WORKSHOP_MAX_CHUNKS_PER_KICKOFF` with honest “not finished” copy.
10. Main build chain does **not** pause for Diff Review; edit/build-plan paths pause when `shouldReview` and queue non-empty.
11. User **cancel** stops further chunks mid-chain.
12. Completed kickoff (natural end, no cap) does **not** insert synthetic handoff.
13. Fallback ticket used when model omits handoff block (logged).

---

## Open questions

1. `WORKSHOP_EDIT_BUILD_MAX_TOOL_ROUNDS` — 10, 12, or 16?
2. `WORKSHOP_MAX_CHUNKS_PER_KICKOFF` — 5, 6, or 8?
3. On cap with tool-only assistant (no text), handoff-only completion call vs code-only fallback?
4. Omit project summary entirely, or only when `projectSummaryUpdatedAt` older than latest spec mtime?
5. Shorten open-tab excerpt (6k) in Edit when semantic index is fresh?
6. Phase 1 / 1.5 behind a single Settings “Workshop experiments” flag for dogfooding?
7. Store synthetic handoff rows with DB flag (`isSyntheticHandoff`) for UI styling?
8. Lean history on chunked chains — Phase 2 or required for 1.5?
9. **Build plan:** ~~keep Diff Review between phases~~ **Resolved** — build-run profile (direct disk + phase Auto-Continue). Diff Review only for **Edit** in UPDATE, not Build plan.

---

## Documentation alignment checklist

Update these when a phase ships (check off in PR description):

| Document | Alignment |
|----------|-----------|
| [WORKSHOP_MODES.md](../architecture/WORKSHOP_MODES.md) | Two execution profiles; Auto-Continue vs chips/phases |
| [PANEL_WORKSHOP.md](../architecture/PANEL_WORKSHOP.md) | Link this plan; build run vs edit user journeys |
| [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) | Workshop prompt table: build-run vs edit-profile context |
| [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) | Phase checklist points here |
| [DIFF_REVIEW.md](../architecture/DIFF_REVIEW.md) | Build run = no mid-loop review; edit = proposals |
| [PANEL_WORKSHOP_RECOVERY_PLAN.md](./PANEL_WORKSHOP_RECOVERY_PLAN.md) | Recovery items vs Auto-Continue phases |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md) | Cross-link; workshop transport notes |
| [SEMANTIC_SEARCH.md](../architecture/SEMANTIC_SEARCH.md) | Workshop scoped search unchanged unless Phase 2 limit |
| [docs/README.md](../README.md) | Implementation map lists this file |
| [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) | Chunked run / handoff behavior if API surface changes |

---

## Rollout and rollback

| Step | Action |
|------|--------|
| 1 | Tag stable point: `git tag workshop-pre-auto-continue` |
| 2 | Branch: `experiment/panel-workshop-auto-continue` |
| 3 | Merge **Phase 1 only** first; dogfood caps + prompts on main |
| 4 | Phase 1.5 behind `WorkshopProjectPreferences` or Settings **experiments** flag until one greenfield panel completes |
| 5 | Smoke: intake → Generate specs → Build design → Accept → Build logic → Accept |

Rollback: revert PR or disable flag; build/edit profile split in `WorkshopReviewPolicy` is unchanged unless explicitly modified.

---

## File index

| Area | File |
|------|------|
| **This plan** | `PANEL_WORKSHOP_AUTO_CONTINUE_PLAN.md` |
| Send loop | [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) |
| Workshop system context | `buildWorkshopPanelContext`, `assembleSystemPrompt` |
| Mode / platform copy | [PanelPlatformSpec.kt](../../src/main/java/com/example/optimalx/data/eidos/PanelPlatformSpec.kt) |
| Tool gating | [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt) |
| Reads | [RoomToolExecutor.kt](../../src/main/java/com/example/optimalx/data/eidos/RoomToolExecutor.kt) (`search_semantic`, `workshop_read_file`) |
| Chunks | [ContentSegmentation.kt](../../src/main/java/com/example/optimalx/data/semantic/ContentSegmentation.kt) |
| Kimi | [KimiProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/KimiProvider.kt), [KimiFormulaToolService.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/KimiFormulaToolService.kt) |
| History / reasoning | [EidosHistoryTrimmer.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosHistoryTrimmer.kt) |
| Intake | [WorkshopIntakeSummary.kt](../../src/main/java/com/example/optimalx/data/eidos/WorkshopIntakeSummary.kt) |
| Kickoffs | [EidosChatViewModel.kt](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt) (`sendWorkshop*Kickoff`) |
| Phase / kickoff prefs | [WorkshopProjectPreferences.kt](../../src/main/java/com/example/optimalx/data/preferences/WorkshopProjectPreferences.kt) |
| Auto-Continue (planned) | `WorkshopExecutionProfile.kt`, `WorkshopHandoffParser.kt`, `WorkshopContinueTicket.kt`, `runWorkshopChunkedRun` in `EidosChatViewModel` |
