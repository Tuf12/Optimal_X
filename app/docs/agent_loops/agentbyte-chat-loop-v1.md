# AgentByte Chat Loop (V1) Design

## Status

**Target contract** — not yet wired as the default chat path. [`EidosChatViewModel.kt`](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt) today uses the provider send path without `AgentByteLoop`; this spec describes the intended integration (typically behind a feature flag once a `ChatAgentByteLoopService` exists). Behavior truth for the loop primitive remains [`AgentByteLoop.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteLoop.kt).

## Goal
Run regular day-to-day chat conversations through a deterministic AgentByte loop (like rollover), so we get:
- consistent tool-use policy enforcement
- better reliability under failures
- structured reasoning logs for training data

## Scope
This V1 applies to:
- `general` chat scope
- `parent` chat scope
- `subfolder` chat scope

Out of scope for V1:
- voice-only special behavior changes
- multi-agent orchestration
- long-horizon autonomous tasks

## Why this next
Yes, this is the correct next step. We already proved the pattern in rollover. Chat is the main traffic path, so looping chat is the highest-value extension.

## High-Level Flow
For each user turn:
1. Build board state from current scope + user message + lightweight runtime signals.
2. Run `AgentByteLoop.run(...)` with mode-aware limits.
3. Allow exactly one tool call per iteration.
4. Persist full structured reasoning records per step (`ABR1|...` JSON line).
5. Exit on explicit complete, user cancel, unrecoverable, token pause, or max iterations.
6. Return final assistant text to UI.

## Engine Contract (Chat)
Implement a chat `LoopEngine` analogous to rollover:

- `assembleBoardState(...)`
  - set `scopeType`, `scopeId`, `operatingMode`
  - pass user turn text into `userMessage`
  - set intent/error/path signals

- `buildPrompt(snapshot)`
  - include current mode plan
  - include selected **`ChessPiece`** and allowlisted tools from **`AgentBytePolicies`** (same policy layer as rollover — not the optional **`chess_taxonomy`** catalogue tool)
  - include bounded context pack (recent chat, scope note pointers, tags/index snippets)

- `chooseToolCall(prompt, snapshot)`
  - call model for the next action
  - parse into:
    - tool call
    - explicit complete
    - no action

- `executeToolCall(toolCall, snapshot)`
  - delegate to `RoomToolExecutor`
  - return structured `StepOutcome` with `llmResponseText` when applicable

- `runKingClose(...)`
  - optional compact logging/handoff updates

## Logging Requirements
Every iteration should produce one structured record line:
- `type=step`
- full prompt packet
- called tool + args
- full LLM response text (when present)
- outcome notes

Run lifecycle should include:
- `type=run_start`
- `type=run_end`

Storage format remains:
- `ABR1|{json}` one line per record

## Tool Policy

**Piece selection** maps to the **`ChessPiece`** enum in [`AgentByteModels.kt`](../../src/main/java/com/example/optimalx/data/eidos/agentbyte/AgentByteModels.kt). **Which tools the model may call** is bounded by **`AgentBytePolicies.toolsForPiece(...)`** (and mode plans), not by calling **`chess_taxonomy`** — that tool only echoes vocabulary for Tag & Hint enrichment when exposed; chat can use **`read_tag_hints`** and index context without it.

Use existing piece allowlists as hard boundaries.

V1 should not broaden dangerous tools. If new chat behaviors are needed:
- add explicit tools to the appropriate piece
- keep irreversible/system-modifying tools behind King warnings

## Integration Points
Primary integration target:
- `EidosChatViewModel` send/response path

Hook sequence:
1. user submits message
2. build chat loop context
3. execute loop
4. update conversation/messages from loop outputs
5. append reasoning records to scope-appropriate reasoning note

## Acceptance Criteria (V1)
1. Regular chat turns (general/parent/subfolder) run through AgentByte loop.
2. Tool calls are policy-gated by selected piece.
3. Reasoning records are written as structured `ABR1|` entries per step.
4. Existing rollover loop still works unchanged.
5. UI response latency remains acceptable (configure max iterations conservatively).

## Suggested Iteration Caps
- `GENERAL`: 6-10
- `PARENT_FOLDER`: 8-12
- `SUBFOLDER`: 8-12
- hard stop with graceful fallback message when cap reached

## Rollout Plan
1. Wire loop for `general` only behind a feature flag.
2. Verify logs + tool gating + output quality.
3. Enable `parent` scope.
4. Enable `subfolder` scope.
5. Remove flag once stable.

## Open Questions
1. Should final assistant prose always come from model explicit-complete step, or can a no-tool completion path synthesize final text?
2. Do we want per-turn token budgets exposed in settings?
3. Should reasoning inbox default to grouped-by-run rendering for `ABR1` entries?

## First Implementation Tasks
1. Add `ChatAgentByteLoopService` (new orchestration class).
2. Add chat `LoopEngine` implementation using existing providers and `RoomToolExecutor`.
3. Route `EidosChatViewModel` turn execution through service (flagged).
4. Reuse `AgentByteReasoningLogger` with scope-aware routing.
5. Add lightweight telemetry counters: iterations, exits, blocked tools.
