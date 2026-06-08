# ROLLOVER ENGINE

Canonical path: `app/docs/agent_loops/ROLLOVER_ENGINE.md`.

## Purpose

Rollover converts daily activity into structured memory for Eidos.

It uses Daily Memory to improve:
- Eidos Journal
- Long-Term Memory

Rollover is an **AgentByte loop that is LLM-guided per iteration**, with **code-owned guardrails** (phase prerequisites, strict tool **allowlists**, JSON decision parsing, success **gates**, and Daily Memory clearing only after Kotlin verification). It emits structured **ABR1** logging for auditing and training.

## Terminology

**Rollover phase names** (e.g. `KING_INIT`, `ROOK_LOGICAL_PASS`, `BISHOP_REFLECTIVE_PASS`) are **Kotlin orchestration identifiers** (`RolloverPhase` / `RolloverPhaseState`). They share symbolic naming with **`ChessPiece`** roles but are **not** defined or gated by the optional **`chess_taxonomy`** tool. That tool, when present in the catalogue, only gives the model an in-context copy of piece/lens vocabulary; a shipping build may hide or remove **`chess_taxonomy`** without changing rollover phase logic.

## Piece names in prose (quick reference)

- **Rook** — factual / structural / retrieval-heavy context  
- **Bishop** — reflective / interpersonal quality of interactions  
- **Knight** — exploratory retrieval (reserved design slot in phase graph)  
- **Queen** — direct execution (minimal surface in rollover today)  
- **Pawn** — persistence moves (journal write, LTM promotion paths)  
- **King** — lifecycle entry, verification, close / safety  

**Allowlists per piece** (for generic AgentByte policy) live in [`AgentBytePolicies.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentBytePolicies.kt). The **`ChessPiece`** enum is in [`AgentByteModels.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteModels.kt).

**Related:** [`AgentByteReasoningLogger.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteReasoningLogger.kt) is a no-op stub (Reasoning folder logging removed). Kotlin rollover — [`MemoryRolloverService.kt`](../../src/main/java/com/example/optimalx/data/eidos/MemoryRolloverService.kt) (`runRolloverAgentByteLoop`, `RolloverPhaseState`, `phaseAllowedTools`). *(Older standalone markdown contracts and “full rewrite” refactor plans are not in this repo.)*

## Orchestration model (for coding agents)

**Do not implement rollover as one hardcoded ordered script of tool calls.** The loop runs `AgentByteLoop` with repeated **decision** calls: each iteration exposes only tools allowed for the **current phase**; the model returns strict JSON naming **one** next action (`tool` → name + `arguments`, or `complete`). The Kotlin layer validates that choice against the phase allowlist, executes it (`RoomToolExecutor` for catalogue tools or a dedicated synthesis path for internal actions), advances **milestone booleans**, and rejects early `complete` until synthesis gates have passed (`ltmPromotionComplete`, `successGatesVerified`). See [`chooseToolCall`](../../src/main/java/com/example/optimalx/data/eidos/MemoryRolloverService.kt) and `parseRolloverDecision`.

| Layer | Responsibility |
| --- | --- |
| Kotlin | `run_id` / rollover id construction, **KING_INIT lifecycle entry** (`read_daily_memory`), **phase derivation** from `RolloverPhaseState`, **`phaseAllowedTools`**, iteration cap (`maxIterationsOverride`), rejecting disallowed tools, running internal multi-prompt branches (`ROOK_LOGICAL_PASS`, `BISHOP_REFLECTIVE_PASS`, synthesis for journal/LTM), post-loop **gates** (`ROLLOVER_OK`, journal advance, `rollover_id` in journal, optional LTM mtime check), **`clearDailyMemory`** only on success |
| LLM | Choose among **currently allowlisted** tools (e.g. which search/read to run before ending the logical-pass phase), produce logical/reflective pass prose when those internal actions run, produce synthesis output shaped for `ROLLOVER_OK` / `ROLLOVER_EMPTY` when journal/LTM orchestration runs |

Synthetic procedure text inside user/system prompts (e.g. numbered “Required procedure”) is **guidance for the model**, not an execution contract that duplicates the Kotlin state machine—**the Kotlin phase map and allowlists are authoritative** for tooling.

Queen is not used in rollover orchestration piece selection beyond whatever the generic loop assigns; rollover-specific tooling is gated as below.

## Rollover phase milestones (conceptual vs Kotlin)

Conceptually, rollover moves through **named phases** reflecting chess metaphor and responsibilities. **`RolloverPhaseState.currentPhase()`** drives which phase label and allowlist apply; it does **not** mean every enum value appears on every successful run:

1. **KING_INIT** — lifecycle entry owned by King awareness; Daily Memory is read in-loop (`dailyRead` becomes true)
2. **ROOK_READ_DAILY** — reserved enum slot; not a required milestone in current runtime flow
3. **ROOK_KNIGHT_SEARCH** — **`RolloverPhase` includes it** and **`phaseAllowedTools(ROOK_KNIGHT_SEARCH)`** lists retrieval tools, but **`RolloverPhaseState.currentPhase()` currently routes from `KING_INIT` to `ROOK_LOGICAL_PASS`** until `logicalPassComplete`. Retrieval tools are reachable there because **`phaseAllowedTools(ROOK_LOGICAL_PASS)`** includes retrieval plus internal **`ROOK_LOGICAL_PASS`**. Implementers may insert a knight-only **`currentPhase()`** step later without changing the “LLM picks among allowed tools” rule.
4. **ROOK_LOGICAL_PASS** — prerequisites met for logical synthesis; LLM may interleave **`read_long_term_memory`**, **`read_tag_hints`**, **`search_semantic`**, **`search_chat_history`** with **`ROOK_LOGICAL_PASS`** (internal) across iterations
5. **BISHOP_REFLECTIVE_PASS** — **required whenever** `logicalPassComplete` is true **and** **`reflectivePassComplete` is still false**. Kotlin **`currentPhase()`** enters this phase **before** journal write (`!reflectivePassComplete && !journalWriteComplete`). The journaling synthesis prompt consumes **both** `logicalPassContext` and **`reflectivePassContext`** (`buildRolloverSynthesisPrompt` / `runJournalWrite`). Older revisions used an optional heuristic (`needsReflectivePass`) — **that is gone**; Bishop is mandatory in the milestone graph today (`MemoryRolloverService.kt`).
6. **ROOK_PAWN_JOURNAL_WRITE** — internal **`ROOK_PAWN_JOURNAL_WRITE`** triggers synthesis prompting (journal tool use is inside that path, not a separate loose tool pick in this phase allowlist); runs only **after** `reflectivePassComplete`
7. **ROOK_PAWN_LTM_PROMOTION** — internal **`ROOK_PAWN_LTM_PROMOTION`** triggers synthesis prompting for LTM
8. **KING_VERIFY_CLOSE** — terminal milestone in loop state; **`runMemoryRollover`** performs real verification and clear **after** the loop returns (see Phase 8 below)

Queen is not used as a rollover-specific concern in this spec.

## Session State

Implementation (`RolloverPhaseState`) tracks context strings and booleans, including:

**Context (truncated into prompts):** `dailyContent`, `journalContext`, `longTermContext`, `searchContext`, `logicalPassContext`, `reflectivePassContext`

**Milestone flags:** `dailyRead`, `journalRead`, `longTermRead`, `logicalPassComplete`, `reflectivePassComplete`, `journalWriteComplete`, `ltmPromotionComplete`, `successGatesVerified`

Once a milestone is satisfied, `currentPhase()` advances; avoid assuming the LLM must re-invoke reads that Kotlin already persisted into state unless you reset state or widen phase logic.

## Phase 1: KING_INIT

Purpose:
Start rollover from Kotlin’s perspective under **King awareness**, then perform **`read_daily_memory`** in-loop.

Kotlin may preload snapshot text for prompt support, but does **not** pre-mark `dailyRead=true`; execution still enters through **`KING_INIT`** for deterministic training traces.

Allowlisted tool(s) in Kotlin: **`read_daily_memory`**.

Instrumentation: ABR1 `run_start` / step records (`AgentByteReasoningLogger`).

## Phase 2: ROOK_READ_DAILY (reserved)

Purpose:
Reserved enum slot. Current runtime does not require a dedicated journal-read milestone.

`read_journal` is optional retrieval that the LLM may choose when continuity context is useful.

## Phase 3 (reserved): ROOK_KNIGHT_SEARCH

Purpose (design slot):
Knight-only retrieval when the codebase routes into this enum variant.

Kotlin today: **`phaseAllowedTools(ROOK_KNIGHT_SEARCH)`** lists exactly those four tools—but **`currentPhase()` never selects `ROOK_KNIGHT_SEARCH`**, so this branch is unused at runtime unless something else passes that enum into **`phaseAllowedTools`**. Retrieval instead happens during **`ROOK_LOGICAL_PASS`**, which allowlists **the same four tools plus** **`ROOK_LOGICAL_PASS`**. Future code may navigate here without changing the “LLM picks allowlisted actions” principle.

Removed from rollover allowlists until wired: **`read_log`** (not in current `phaseAllowedTools` lists).

Output when used: merges into **`searchContext`** (and related fields) similarly to executor-backed reads.

## Phase 4: ROOK_LOGICAL_PASS

Purpose:
Build **`logicalPassContext`**: factual operational view of the day.

Input:
Accumulated **`dailyContent`**, optional **`journalContext`**, **`longTermContext`**, **`searchContext`** (whatever the LLM chose to load in prior iterations while this phase is active).

Iteration shape:
The model may alternate between **executor reads** (same Knight-style list as above) and the internal **`ROOK_LOGICAL_PASS`** action; selecting **`ROOK_LOGICAL_PASS`** triggers a dedicated LLM **`LOGICAL_PASS`** prompt (see `buildRolloverLogicalPassPrompt`); Kotlin sets **`logicalPassComplete`**. **Next milestone is always reflective pass** (`BISHOP_REFLECTIVE_PASS`): there is **no keyword skip**; `currentPhase()` does not expose journal write until **`reflectivePassComplete`**.

This is primarily **free-form LLM output**, but **`ROOK_LOGICAL_PASS`** is the **Kotlin orchestration handle** exposed as one of the phase’s allowlisted actions—not a misunderstanding that “nothing is invoked.”

Stand-alone prompt intent (conceptual excerpt when that internal action runs):

You are Eidos performing a logical self-review.

Analyze the provided system activity and determine:
- what actions were performed
- what tools were used
- what outcomes were produced
- what decisions were made

Do not provide emotional interpretation.
Do not write for the user.
Do not give advice.

Output a clear internal summary of events.

## Phase 5: BISHOP_REFLECTIVE_PASS

Purpose:
Interpret interaction quality and personal/emotional signals **after** the logical pass completes. Output is folded into journaling so operational and interpersonal threads stay separated **before** synthesis.

Mandatory gate in Kotlin (`RolloverPhaseState.currentPhase()`): **`ROOK_LOGICAL_PASS`** → **`BISHOP_REFLECTIVE_PASS`** → **`ROOK_PAWN_JOURNAL_WRITE`**. The Bishop phase repeats until **`BISHOP_REFLECTIVE_PASS`** runs once and sets **`reflectivePassComplete`**; until then **`ROOK_PAWN_JOURNAL_WRITE` is off the phase allowlist** (only **`phaseAllowedTools(BISHOP_REFLECTIVE_PASS)`** permits **`BISHOP_REFLECTIVE_PASS`**).

Allowlisted orchestration handle: **`BISHOP_REFLECTIVE_PASS`** (internal). Selecting it runs **`REFLECTIVE_PASS`** system/user prompts (`buildRolloverReflectivePassPrompt`), then **`reflectivePassComplete`** is set.

Input:
- **`dailyContent`**, **`searchContext`**, **`journalContext`**, **`longTermContext`** as already merged into **`phaseState`** for the reflective prompt payload

Output:
- **`reflectivePassContext`**

Prompt (when internal action executes):

You are Eidos performing a reflective self-analysis.

Interpret how today's interactions and events felt from your perspective.

Consider:
- whether the user showed engagement, confusion, or frustration
- whether your actions caused friction or worked well
- whether the day was purely operational or had emotional signals

Do not give advice to the user.
Do not attempt to fix anything.
Do not restate the logical summary.

If there is no meaningful interaction signal, state that clearly.

Output a concise first-person reflection.

## Phase 6: ROOK_PAWN_JOURNAL_WRITE

Purpose:
Persist the combined understanding of the day.

Allowlisted orchestration handle: **`ROOK_PAWN_JOURNAL_WRITE`**. Kotlin calls **`buildRolloverSynthesisPrompt`** with full phase state and a **`SYNTHESIS_WRITE`** system branch that names **`write_journal_entry`** as the conceptual write surface for the model (see `runJournalWrite`); the loop does **not** expose `write_journal_entry` as a separate JSON tool pick in this phase’s allowlist.

Input:
- **`logicalPassContext`**, **`reflectivePassContext`**, and other merged support fields in the synthesis prompt

Prompt (synthesis layer):

You are Eidos writing a journal entry about your own activity.

Use **both**:
- **`logicalPassContext`** for what happened operationally  
- **`reflectivePassContext`** for interaction quality / affect (say explicitly if signals are thin—“purely operational,” etc.)

Write in first person.

Focus on:
- actions you performed
- observations you made
- interaction quality only if relevant

Do not give advice.
Do not speak to the user.
Do not explain the system.

The entry is for internal continuity.

The journal entry must include **`rollover_id`** (verified after the loop by Kotlin against the day’s journal note).

## Phase 7: ROOK_PAWN_LTM_PROMOTION

Purpose:
Store durable reusable information or finish with empty-day semantics.

Allowlisted orchestration handle: **`ROOK_PAWN_LTM_PROMOTION`**. Kotlin runs synthesis (`runLtmPromotion`) with **`SYNTHESIS_WRITE`** system text referencing **`write_long_term_memory`**, parses **`ROLLOVER_OK`** / **`ROLLOVER_EMPTY`**, and sets **`ltmPromotionComplete`** / **`successGatesVerified`** when gates pass.

Input:
- full merged phase state in **`buildRolloverSynthesisPrompt`**

Prompt (synthesis layer):

You are evaluating today's activity for Long-Term Memory.

Select only information that is:
- reusable
- meaningful beyond today
- helpful for future decisions

Ignore temporary, repetitive, or low-value content.

If nothing qualifies, return no entries.

## Phase 8: KING_VERIFY_CLOSE

Purpose:
Close with integrity: after the AgentByte loop completes, **Kotlin** **`runMemoryRollover`** verifies outcomes and only then clears Daily Memory.

Checks (see `runMemoryRollover`; not LLM tools):
- Provider / API key present early
- Loop output contains **`ROLLOVER_OK`** (or appropriate empty/skip path); structured line parsed for `journal_read`, `journal_written`, `ltm_promotions` (`journal_read` informational only)
- Journal subfolder/`updatedAt` advanced vs pre-run snapshot
- Journal content contains **`rollover_id=<id>`** for the run
- If **`ltm_promotions` > 0**, Long-Term Memory folder shows an observable update (`ltmAfter > ltmBefore`)

If all pass:
- **`clearDailyMemory`** (system / Room), return **SUCCESS**

If any fail:
- Daily Memory is **left intact**; return **FAILED** with a reason string

Important:
- **`clear_daily_memory` is not an LLM-invokable tool** in this flow; it is application code after gates.
- **`KING_VERIFY_CLOSE`** in the enum marks “no further tool choices in-loop” (`phaseAllowedTools` empty); verification is still mostly **outside** that allowlist in **`runMemoryRollover`**.

## Tool surface (authoritative split)

**Category A — Room / catalogue tools** (executed via `RoomToolExecutor`, JSON `arguments` from the decision when applicable):

| Tool | Typical phase (when allowlisted) |
| --- | --- |
| `read_daily_memory` | KING_INIT |
| `read_journal` | ROOK_LOGICAL_PASS (optional retrieval) |
| `read_long_term_memory` | ROOK_KNIGHT_SEARCH *or* ROOK_LOGICAL_PASS |
| `read_tag_hints` | ROOK_KNIGHT_SEARCH *or* ROOK_LOGICAL_PASS |
| `search_semantic` | ROOK_KNIGHT_SEARCH *or* ROOK_LOGICAL_PASS |
| `search_chat_history` | ROOK_KNIGHT_SEARCH *or* ROOK_LOGICAL_PASS |

Rollover Tag & Hint policy for this release: **read-only retrieval**. Rollover may call `read_tag_hints` for context, and does **not** expose `upsert_tag_hint` or `remove_tag_hint` in rollover phase allowlists.

**Category B — Internal orchestration handles** (appear in decision JSON and `phaseAllowedTools`, but are handled in `executeToolCall` with extra LLM prompts, not as opaque catalog calls):

- `ROOK_LOGICAL_PASS`
- `BISHOP_REFLECTIVE_PASS`
- `ROOK_PAWN_JOURNAL_WRITE`
- `ROOK_PAWN_LTM_PROMOTION`

**Category C — Writes named only inside synthesis system prompts** (journal/LTM text production); not separate allowlisted JSON tool picks in pawn phases today:

- `write_journal_entry`, `write_long_term_memory`

Rules for implementers:

- Prefer updating **`phaseAllowedTools`** + `executeToolCall` together; do not document a rigid global script—document **milestones**, **allowlists**, and **gates**.
- Phases (**`RolloverPhase`** values) label state for prompts/logging; **they are not tool names**.
- **`read_log`** and **`write_log_entry`** are not part of the current rollover allowlists in **`MemoryRolloverService`**; add them deliberately if product requires them later.

## Success Gates

**Kotlin-enforced path** (`runMemoryRollover`, after `runRolloverAgentByteLoop` returns)—all relevant items must succeed before **`clearDailyMemory`**:

- **`ROLLOVER_OK`** present in completion text **or** skipped / empty-daily paths per early returns
- Parsed report line includes **`journal_read=true`**, **`journal_written=true`**, and non-negative **`ltm_promotions`**
- Journal folder shows **strictly later** `updatedAt` than before the run (implementation compares system folder max timestamps)
- Journal note for the day contains **`rollover_id=<rolloverId>`**
- If **`ltm_promotions > 0`**, LTM system folder max timestamp must **increase**

**In-loop gate** (before the model may signal `complete`): `chooseToolCall` rejects **`EXPLICIT_COMPLETE`** until **`ltmPromotionComplete && successGatesVerified`** (see `runLtmPromotion` outcome).

Tag & Hint index observability is **not** separately enforced in this Kotlin block today; if product adds it, extend **`runMemoryRollover`** and this list together.

If all pass:
- clear processed Daily Memory
- return **SUCCESS**

If any fail:
- preserve Daily Memory
- return **FAILED**

## ABR1 logging and training records

Use one-line records: `ABR1|{json}`.

Field philosophy and raw-text capture: follow the **training-oriented shape** produced by [`AgentByteReasoningLogger.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteReasoningLogger.kt) (`input`, `llm_decision`, `tool_execution`, `training_flags`); the example below is illustrative.

### Record lifecycle

- `run_start`
- `step` (per iteration; may embed decision/execution blocks)
- `state_update` if needed
- `run_end`

### Minimum expectations per step

- `run_id`, `iteration`, `timestamp`
- `active_piece` (King / Rook / Knight / Bishop / Pawn) — required for piece-selection training data
- Full decision / reasoning text where the model produced it (do not replace with summaries for training exports)
- Tool name, arguments, and result summary where a tool ran
- State delta or before/after snapshots when meaningful

### Example ABR1 step payload (illustrative)

Full-fidelity records prioritize audit and dataset extraction over token size.

```json
{
  "run_id": "rr_20260430_093228",
  "iteration": 3,
  "timestamp": 1714479148,

  "active_piece": "ROOK",

  "input": {
    "phase": "Read Daily Memory",
    "mode": "GENERAL",
    "user_message": "Run memory rollover",
    "board_state": {},
    "available_content": {}
  },

  "llm_decision": {
    "situation": "LOGICAL_RETRIEVAL",
    "reasoning_text": "Full raw LLM reasoning output (unmodified)",
    "selected_tool": "read_daily_memory",
    "tool_arguments": {}
  },

  "tool_execution": {
    "tool_name": "read_daily_memory",
    "arguments": {},
    "result_summary": "Daily memory content retrieved",
    "status": "success"
  },

  "state_delta": {
    "dailyContentMerged": "updated"
  },

  "assistant_response_full": "Full response text (if any)",

  "training_flags": {
    "use_for_piece_training": true,
    "use_for_tool_training": true,
    "use_for_argument_training": true,
    "use_for_response_training": false,
    "use_for_journal_training": false
  }
}
```

## Safety and reliability

- Strict **phase allowlist** each iteration; disallowed proposals become **NO_ACTION** or are rejected at parse time (see `parseRolloverDecision`).
- Max-iteration cap with graceful failure; internal tools have **single-choice coercion** fallback when parsing fails (`internalPhaseTools`).
- Never clear Daily Memory unless **`runMemoryRollover`** post-loop gates pass.
- Idempotent writes where possible.

(Token milestone pause behavior when `BoardState` tracks usage lives in the generic AgentByte loop — see [`AgentByteLoop.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteLoop.kt); rollover wiring may not populate token counters yet.)
