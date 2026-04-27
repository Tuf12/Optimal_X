# AGENT_LOOP.md

## Purpose

This file defines the AgentByte agentic loop system for OptimalX v2.

The agentic loop is the engine that runs underneath every multi-step task Eidos performs.
It is not specific to any one feature — it is a single generic system used everywhere in the app.
The panel workshop, note writing, research, memory rollover, and any future multi-step task all run through this loop.

Coding agents should use this file to build the AgentByte loop class and integrate it into the app.

---

## What the Agentic Loop Is

The agentic loop is a repeating decision cycle.

On every iteration it:
1. Reads the current state of the board
2. Classifies what kind of situation it is in
3. Selects the appropriate reasoning mode
4. Activates only the tools relevant to that mode
5. Sends to the model and receives a response
6. Evaluates the response and acts on it
7. Loops back until the goal is complete

The loop does not predetermine a sequence of steps.
It reads the board after every move and decides what to do next based on what it sees.
This is the same principle as a chess engine — continuous board evaluation, not a fixed script.

---

## Chess Piece Taxonomy

AgentByte uses six chess pieces as reasoning mode symbols.
Each piece represents a type of thinking and a set of tools appropriate to that thinking.

This taxonomy serves two purposes:
- Right now: routes the model toward the correct reasoning approach and tool set for each situation
- Over time: becomes training signal — logs accumulate per piece per situation and the system can be fine-tuned to recognize and select the right piece faster

### The Six Pieces

| Piece | Reasoning Mode | When to Use |
|---|---|---|
| Pawn | Routine task | Standard daily operations, reading, logging, listing |
| Rook | Logical, systematic | One correct path, retrieval, structured search |
| Bishop | Emotional, personal | User sentiment, preferences, personal context |
| Knight | Troubleshoot, lateral | Stuck, error recovery, workarounds, clever paths |
| Queen | Fast, decisive | Clear goal, execute immediately, no ambiguity |
| King | Defensive, cautious | Safety checks, destructive actions, token limits, risk |

### Piece Definitions

**Pawn — Routine Task**
The least powerful piece but essential to every game.
Small, deliberate, repeatable movements that build structure over time.
Pawns execute known, defined procedures reliably — they do not improvise.
Do not confuse Pawn with unimportant — a well-placed Pawn wins games.
Every routine daily operation runs through a Pawn move.
Tools: read_note, read_daily_memory, read_long_term_memory, list_folder_contents, list_files, write_daily_memory, write_log_entry

**Rook — Logical, Systematic**
There is one correct path and it should be followed.
Retrieval, search, structured reading. No ambiguity about what needs to happen.
Tools: search_system, search_semantic, read_journal, read_journal_tag_hint_index, read_long_term_tag_hint_index, read_log, read_subfolder_memory_cache, read_file, summarize_file

**Bishop — Emotional, Personal**
The situation involves the user's feelings, preferences, or personal context.
Tone matters. Memory of the user as a person matters.
Tools: read_long_term_memory, write_long_term_memory (PREFERENCE flag), write_daily_memory (PREFERENCE or SENSITIVE flag), update_subfolder_memory_cache, read_subfolder_memory_cache

**Knight — Troubleshoot, Lateral**
Something is not working. Standard path failed or is blocked.
Think sideways. Try a different approach. Find a workaround.
Activates automatically when an error is detected in the loop.
Tools: search_semantic, search_system, read_log, read_journal, describe_image

**Queen — Decisive, Powerful, Confident**
The most powerful piece on the board.
The Queen does not hesitate and does not second-guess.
When the situation is clear and the action is right, the Queen executes with full confidence and maximum impact.
Queen is not about speed — she is decisive because she sees the board clearly and acts on it with authority.
Do not assign Queen to routine tasks — that is Pawn territory.
Queen leads when the action is significant, the goal is unambiguous, and execution requires authority.
Tools: create_parent_folder, create_subfolder, rename_folder, write_note, append_note, update_semantic_tags

**King — Defensive, Cautious**
Something risky or irreversible is about to happen.
Slow down. Check safety. Confirm before acting.
King always runs before any destructive action regardless of which piece is leading.
Tools: move_to_trash, prune_long_term_memory, edit_note_section, write_note (full overwrite)

Note: `clear_daily_memory` is a rollover orchestrator/worker step and is not model-visible in normal chat tool sets.

### King Override Rule

King is not selected — King is triggered.

Any time the loop is about to execute a destructive or irreversible action, King activates automatically as a safety check regardless of which piece is currently leading. After the King check passes or the user confirms, the original piece resumes.

King also triggers when:
- Token usage is approaching the user-set cap
- An action would affect system folders
- An action cannot be undone

---

## Situation Classification

Before the first move of every loop, AgentByte reads the incoming request and classifies it into one of six situation types.
The situation type determines the opening piece and initial tool set.

| Situation | Primary Piece | Supporting Pieces |
|---|---|---|
| Conversational | Pawn | Bishop if emotional context detected |
| Build task | Queen | Rook, Pawn |
| Retrieval | Rook | Pawn |
| Troubleshoot | Knight | Rook |
| Destructive action | King | Rook |
| Planning / strategy | Rook | Queen, Pawn |

### Situation can change mid-loop

If the board changes — user reveals emotional context, an error occurs, a tool returns unexpected results — the situation is reclassified at the top of the next iteration.

The loop does not restart when this happens. Conversation history and context carry forward. Only the piece selection updates.

Example: Loop starts as Conversational (Pawn). User mentions they had a bad day. Bishop enters. Next iteration runs with Bishop leading. History is preserved.

---

## Loop Structure

### Core iteration in pseudocode

```
fun runAgenticLoop(goal: String, tools: List<Tool>, context: AgentContext) {

    // 1. READ THE BOARD
    val boardState = assembleBoardState(
        currentLocation = context.currentLocation,
        memoryLayers = context.memory,
        conversationHistory = context.history,
        tokenUsage = context.tokenCount
    )

    // 2. CLASSIFY THE SITUATION
    val situation = classifySituation(boardState)

    // 3. SELECT PRIMARY PIECE AND SUPPORTING PIECES
    val primaryPiece = selectPrimaryPiece(situation)
    val supportingPieces = selectSupportingPieces(situation, boardState)

    // 4. ACTIVATE RELEVANT TOOLS ONLY
    val activeTools = filterToolsForPieces(primaryPiece, supportingPieces)

    // 5. SEND TO MODEL
    val response = sendToModel(
        systemPrompt = buildPrompt(primaryPiece, supportingPieces),
        tools = activeTools,
        boardState = boardState,
        goal = goal
    )

    // 6. EVALUATE RESPONSE
    when (response.type) {

        TOOL_CALL -> {
            if (requiresKingCheck(response.toolCall)) {
                val safe = runKingCheck(response.toolCall)
                if (!safe) {
                    notifyUser(response.toolCall)
                    awaitUserDecision()
                }
            }
            val result = executeTool(response.toolCall)
            logMove(response.toolCall, result)
            context.appendResult(result)
            runAgenticLoop(goal, tools, context) // loop back
        }

        USER_INPUT_NEEDED -> {
            surfaceQuestionToUser(response.question)
            val answer = awaitUserResponse()
            context.appendUserInput(answer)
            runAgenticLoop(goal, tools, context) // loop back with new input
        }

        SITUATION_CHANGED -> {
            context.updateSituation(response.newSituation)
            runAgenticLoop(goal, tools, context) // re-enter with updated classification
        }

        TOKEN_CAP_APPROACHING -> {
            // King override
            notifyUser("Approaching token limit — continue or pause?")
            awaitUserDecision()
        }

        GOAL_COMPLETE -> {
            logCompletion(goal, context)
            notifyUser("Done")
            return // exit loop
        }

        ERROR -> {
            // Knight activates automatically
            val workaround = attemptKnightMove(response.error, context)
            if (workaround != null) {
                context.appendWorkaround(workaround)
                runAgenticLoop(goal, tools, context)
            } else {
                notifyUser(response.error) // cannot recover — surface to user
                return
            }
        }
    }
}
```

---

## Loop Exit Conditions

The loop exits cleanly under four conditions:

| Condition | Piece | What happens |
|---|---|---|
| Goal complete | Any | Log completion, notify user, return |
| Token cap hit | King | Pause, save state, notify user, await decision |
| Unrecoverable error | Knight failed | Surface error to user, return |
| User cancels | Any | Stop immediately, log cancellation, return |

The loop never exits silently. Every exit is logged and the user is notified.

---

## Token Control

The loop tracks cumulative token usage across every iteration.
Token count is returned by the provider on every API response — no estimation required.

### Milestone notifications
A notification fires at each milestone and pauses the loop:

> "500,000 tokens used (~$0.25 with Grok). Continue or stop?"

- **Continue** — loop resumes
- **Stop** — loop pauses, full state saved to reasoning note

Default milestone interval: every 200,000 tokens.
User adjusts the interval in app settings — higher or lower.

### Approximate cost display
Each notification shows token count plus approximate cost based on the active provider rate.
Cost is a guide, not a bill. Provider rates are defined in API.md.

### Hard cap
Optional hard stop the user can set in settings.
Loop terminates automatically at the cap without prompting.
Default: off.

### King and token milestones
Token milestone notifications are a King trigger — safety first, pause before continuing.
The loop never burns through a milestone silently.

### State preservation
Reasoning note saves full loop state on any pause.
Resume picks up exactly where it stopped — nothing is lost.

---

## Logging Every Move

Every tool call, every piece selection, every situation classification is logged to the Eidos Log.

Log entry format per move:
```
[HH:MM] PIECE:Queen | SITUATION:build_task | TOOL:create_subfolder | RESULT:success
[HH:MM] PIECE:King | SITUATION:destructive | CHECK:move_to_trash | USER:confirmed
[HH:MM] PIECE:Knight | SITUATION:error | WORKAROUND:search_semantic | RESULT:recovered
```

This log is the training data.
Over time, patterns in piece selection, situation classification, and tool use can be used to fine-tune a model to internalize the taxonomy — reducing dependence on runtime classification and improving prediction speed.

---

## Where the Loop Is Used

The agentic loop is a single generic class. Context and tool set determine behavior.

| Context | Tools passed in | Goal definition |
|---|---|---|
| Panel workshop — doc phase | note tools, file tools | All MD files complete |
| Panel workshop — code phase | file tools, WebView feedback | All code files written and rendered |
| Note writing — long form | note tools, search tools | All sections complete |
| Memory rollover | memory tools, journal tools | Rollover sequence complete |
| Research task | web tools, note tools | Summary written |
| Casual conversation | none or minimal | User satisfied |

---

## Reasoning Note System

Every location where the agentic loop runs stores its reasoning trace in a dedicated reasoning note scoped to that location.
This is not a separate system folder — it lives inside the existing app hierarchy exactly where the work happened.

### What the reasoning note stores
- Which situation was classified and when
- Which pieces were selected at each iteration
- Which tools were called and what they returned
- Where the loop stopped if it was interrupted
- The outcome — complete, paused, or failed
- User outcome signal if provided (positive / negative)

### Scoping — where reasoning notes live

| Where loop ran | Reasoning note lives |
|---|---|
| Inside a subfolder | That subfolder — alongside its regular note and memory cache |
| Parent folder scope | That parent folder's designated reasoning subfolder |
| General (widget, main page) | Eidos Chats system folder — general reasoning subfolder |
| Quick Notes | Quick Notes system folder — reasoning subfolder |

The reasoning note is always co-located with the work it describes.
No searching required to find it — it is right where Eidos was working.

### Resume behavior
If a loop was interrupted — user left, app closed, token cap hit — the reasoning note preserves the full state.
When the user returns to that subfolder or context, Eidos reads the reasoning note and knows:
- What the goal was
- How far it got
- What the next move should be
- What has already been completed

Even if days have passed and daily memory has rolled over, the reasoning note keeps the loop resumable.
The user does not need to re-explain anything.

### Data model
One new field on Subfolder:

| Field | Type | Description                                                                                                                                    |
|---|---|------------------------------------------------------------------------------------------------------------------------------------------------|
| agentReasoningNote | String? | Loop reasoning trace for this subfolder and the parent folder for general use(none sufolder specific). Null until first loop runs. Eidos only. |

Same pattern as memoryCache — a field on Subfolder, not a joined object.
Cleared if subfolder is moved to trash. Not restored on restore.

### Outcome signal
After a loop completes, a small unobtrusive prompt appears once in the UI:
👍 or 👎 — one tap.
The outcome is appended to the reasoning note entry for that loop run.
This is the positive/negative label that makes the log useful as training data.

### Tags and log connection
The reasoning note is tagged with the situation type and pieces used.
Those tags connect it to the relevant Eidos Log entries for that session.
Full traceability: reasoning note → tags → log entries → specific tool calls.

### What reasoning notes are NOT
- Not user-editable — Eidos only
- Not shown in the regular note editor
- Not surfaced in search results by default
- Not deleted automatically — user manages them same as memory cache

---

## What Does Not Exist in v1 of AgentByte

- No fine-tuned model yet — classification runs through the base LLM using the taxonomy in the system prompt
- No cross-app learning — the loop and its logs are specific to OptimalX
- No automatic piece weight adjustment — piece selection is determined by situation classification, not learned weights yet
- No parallel loops — one loop runs at a time per session

Fine-tuning and weight adjustment are future additions built on top of accumulated logs.

---

## Integration Notes

- The loop class is called `AgentByteLoop`
- It is instantiated by any feature that needs multi-step agentic behavior
- The panel workshop, memory rollover, and research tasks each instantiate it with their own tool set and goal
- The loop does not know what feature is using it — it only knows its goal, its tools, and its context
- All colors, UI notifications, and progress indicators follow THEME.md token system
- Full tool definitions are in TOOL_FUNCTIONS.md
- Memory layer assembly follows MEMORY_SYSTEM.md
- Search behavior follows SEARCH_AND_RETRIEVAL.md
