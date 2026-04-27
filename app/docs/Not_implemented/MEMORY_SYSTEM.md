# MEMORY_SYSTEM.md

## Purpose

This file defines the full memory system for Eidos in OptimalX v2.

This is a standalone implementation guide. Cursor should use this file as the primary reference for building the memory system. Supporting detail exists in JOURNAL_SYSTEM.md, DATA_MODEL.md, TOOL_FUNCTIONS.md, and API.md — this file pulls the critical implementation requirements from all of them into one place.

---

## What the Memory System Is

An LLM has no built-in memory between sessions. The memory system is how Eidos (the LLM) maintains continuity — building context, tracking decisions, and persisting what matters without storing everything.

---

## Six Memory Layers

### Always loaded into every prompt

| Layer | Source | Notes                                                                                  |
|---|---|----------------------------------------------------------------------------------------|
| Current location context | Active subfolder note + files | Auto-injected. If this answers the query, stop here.                                   |
| Subfolder memory cache | Subfolder.memoryCache field | Loaded alongside note when user is in a subfolder. Null until first written.           |
| Daily Memory | Eidos Daily system folder | Full content, always present. Resets at midnight rollover.                             |
| Long-Term Memory | Eidos Memory system folder | Tag & Hint index always in prompt. Full entries fetched on demand via Tag & Hint match. |
| Journal Tag & Hint index | Eidos Journal system folder | One compact Tag & Hint line per journal entry. Trigger layer only — full entries never auto-load. |
| Active conversation history | Current session messages | Trimmed from oldest if context grows large.                                            |

### Only fetched when triggered

| Layer | Trigger | How fetched |
|---|---|---|
| Full journal entries | Journal Tag & Hint line matches current topic | `read_journal` tool call |
| Other subfolder notes | User references outside current location | Two-pass: tag match → semantic search |
| Eidos Log | Eidos needs to verify a past action | `read_log` tool call with two-pass search |
| Chat history | User asks to find a past conversation | Two-pass: scope + tag match → semantic search |

Rule: small and always relevant → rides in prompt. Large or sometimes relevant → searchable, fetched on demand.

---

## System Folders

Six system-locked parent folders created automatically on first install.
All have `isSystemFolder = true`. Cannot be renamed, moved to trash, or deleted.
Do not appear in the regular user folder list. Accessible from the Eidos section of the app.

| Folder name | Purpose | Structure |
|---|---|---|
| Eidos Daily | Daily short-term working memory | Single note, updated throughout the day, cleared at rollover |
| Eidos Journal | Durable reflective memory | One subfolder per day (YYYY-MM-DD), one journal entry note per subfolder |
| Eidos Memory | Long-term stable facts and preferences | Single note, maintained by Eidos |
| Eidos Log | Action audit trail | One subfolder per day (YYYY-MM-DD), one log note per subfolder |
| Eidos Chats | General conversation history | Stores widget and parent folder page conversations |
| Quick Notes | Voice capture system | One subfolder per day (YYYY-MM-DD), entries appended in order |

### Journal Tag & Hint index
Stored as a note inside a dedicated subfolder within Eidos Journal.
Example path: Eidos Journal / Tag & Hint Index / [note]
One compact Tag & Hint line per journal entry — this is what loads into every prompt, not the full journal.

---

## Data Model Requirements

### ParentFolder — required fields
| Field | Type | Description |
|---|---|---|
| isSystemFolder | Boolean | Default false. System folders cannot be renamed, trashed, or deleted. |
| semanticTags | String | Comma-separated AI-generated tags. Empty string until generated. |

### Subfolder — required fields
| Field | Type | Description |
|---|---|---|
| isSystemSubfolder | Boolean | Default false. System subfolders cannot be renamed, trashed, or deleted. |
| memoryCache | String? | Eidos-maintained context note. Null until first written. Cleared on trash. Not restored on restore. |
| semanticTags | String | Comma-separated AI-generated tags. Empty string until generated. |
| agentReasoningNote | String? | AgentByte loop reasoning trace. Null until first loop runs. Eidos only. Cleared on trash. |

All fields are on the existing Subfolder entity — no new tables required.

---

## Layer Detail

### Layer 1 — Current Location Context
Auto-injected into every system prompt when user is inside a subfolder.
Contains: subfolder name, subfolder ID, full note content, list of attached files (names and types only).
If AI lock is enabled on the note: note content is omitted, replaced with "Note exists but AI lock is enabled."
No tool call required. Assembly detail in API.md.

### Layer 2 — Subfolder Memory Cache
Field: `Subfolder.memoryCache` (String?, nullable)
Loaded automatically alongside the note whenever user is in a subfolder.
Written and maintained by Eidos only — user does not edit it directly.

What Eidos writes here:
- Key decisions made in this subfolder
- Patterns observed about how this space is used
- Ongoing threads or unresolved items
- Anything that helps Eidos give a more informed response next time

What Eidos does NOT write here: raw chat transcripts, log entries, everything.
Tool: `update_subfolder_memory_cache` to write, `read_subfolder_memory_cache` to read.

### Layer 3 — Daily Memory
Location: Eidos Daily system folder — single note
Always loaded in full. Never searched. Resets at midnight rollover.

Entry format:
```
[HH:MM] DECISION|imp=high|conf=high|src=subfolder:482
Switched lumber supplier — better pricing, faster delivery.
```

Flags (at least one required per entry):
| Flag | Meaning |
|---|---|
| DECISION | A choice was made — candidate for Long-Term Memory |
| PREFERENCE | User expressed a preference — likely promotes |
| TECHNICAL | Implementation detail or system note |
| SENSITIVE | Handle carefully — does not promote unless explicitly confirmed |
| ONGOING | Task or thread not yet complete |
| RESOLVED | Was ONGOING, now complete |

Each entry also carries importance (low/medium/high), confidence (low/medium/high), and optional source ID.
Tools: `read_daily_memory`, `write_daily_memory`, `clear_daily_memory` (rollover only).

### Layer 4 — Eidos Journal
Location: Eidos Journal system folder — one subfolder per day, one note per subfolder
Written once per day during midnight rollover. Reflective, not a log.

Full entry format:
```
[2026-04-08] SUMMARY|imp=high
Reflective summary of what happened today.
Patterns, decisions, anything worth carrying forward.
ONGOING: unresolved items noted here so they are not lost.
```

Tag & Hint index line format (one per entry, stored in Tag & Hint Index subfolder):
```
[2026-04-08] Switched lumber supplier, discussed Johnson job timeline, noted user prefers bullet summaries.
```

Tools: `write_journal_entry`, `read_journal`, `read_journal_tag_hint_index`, `update_journal_tag_hint_index`.
User can read and delete journal entries. User cannot write to the journal.

### Layer 5 — Long-Term Memory
Location: Eidos Memory system folder — structured store, maintained by Eidos
Tag & Hint index always loaded into every prompt — one compact line per entry.
Full entries fetched on demand when a Tag & Hint line matches the current context.
Same two-pass pattern as the journal — Tag & Hint rides in prompt, content fetched only when triggered.
Scales indefinitely without bloating every API call.

Entry format:
```
[2026-04-08] PREFERENCE|imp=high|conf=high|tags=formatting,summaries,user-style
User prefers bullet-format summaries when reviewing multiple options.
First observed: subfolder:482. Confirmed twice since.
```

Tag & Hint index line format (one per entry):
```
[2026-04-08] PREFERENCE — user prefers bullet summaries when reviewing options.
```

What gets stored: confirmed user preferences, system rules, repeated patterns, high-consequence decisions.
What does NOT go here: temporary context, unconfirmed SENSITIVE entries, anything not proven durable.
Tags on each entry enable pass one pruning — only matching entries are fetched, not the full store.
Tools: `read_long_term_memory`, `write_long_term_memory`, `read_long_term_tag_hint_index`, `prune_long_term_memory` (requires confirmation).

### Layer 6 — Eidos Log
Location: Eidos Log system folder — one subfolder per day, one note per subfolder
Never loaded into the prompt. Searched on demand via two-pass system.
Append only — Eidos never edits or deletes its own log entries.

What gets logged: every action that modifies the system.
What does NOT get logged: read-only actions.

Log entry format:
```
[HH:MM] Created subfolder 'Site Visit Notes' inside Johnson (id=subfolder:891)
[HH:MM] Appended note content to subfolder:482
[HH:MM] Updated memoryCache for subfolder:482
```

Deep link: each entry includes an optional object ID and anchor for navigating directly to the affected location.
Tool: `write_log_entry` (deepLink parameter optional), `read_log`.
User can read and delete log entries. User cannot write to the log.

---

## Midnight Rollover

### Implementation
Android WorkManager — nightly scheduled task (`EidosMidnightWorker`).
Survives app restarts and device reboots.
Network constraint — waits for connectivity before firing.
Fires within ±15 minutes of midnight (Android battery optimization — acceptable).
Retries automatically on failure using exponential backoff.
Worker only triggers the run. The actual rollover is executed by Eidos through the same API path as normal chat.
If Eidos is unavailable (no API key, provider failure, network failure), rollover does not complete and Daily Memory is preserved.
There is no LLM-less fallback rollover path.

### Rollover sequence
1. `read_daily_memory` — read today's full Daily Memory
2. `read_journal_tag_hint_index` — scan compact journal Tag & Hint lines
3. `read_long_term_tag_hint_index` — scan compact long-term Tag & Hint lines
4. Optionally `read_journal` — fetch full journal entries only when Tag & Hint lines match Daily topics
5. Optionally `read_long_term_memory` — fetch long-term details only when Tag & Hint lines match Daily topics
6. Optionally `read_log` (today) — verify actions only when Daily entries indicate a conflict or need verification
7. Optionally `read_note` on sources flagged in Daily Memory entries
8. `write_journal_entry` — reflective summary with one-line Tag & Hint at top for index
9. `update_journal_tag_hint_index` — append new Tag & Hint line
10. `write_long_term_memory` for each DECISION or PREFERENCE entry flagged for promotion
11. Optionally `prune_long_term_memory` for outdated entries
12. Note unresolved ONGOING entries inside journal entry
13. `clear_daily_memory` — wipe Daily Memory
14. `write_log_entry` — log rollover completion

### Rollover system prompt
```
You are Eidos running a nightly memory rollover for OptimalX.

Your task:
1. Read today's Daily Memory in full first.
2. Use Journal and Long-Term Tag & Hint indices to decide what else is relevant.
   Fetch full entries only when Tag & Hint lines match today's Daily topics.
   Use the Log only when verification is needed.
3. Write a reflective journal entry summarizing what happened.
   Include one compact Tag & Hint line at the top for the Tag & Hint index.
   Focus on patterns, decisions, and anything worth remembering.
   Be concise — do not repeat the log verbatim.
4. Append the Tag & Hint line to the journal Tag & Hint index.
5. Review Daily Memory entries. Promote DECISION and PREFERENCE entries
   marked high importance to Long-Term Memory.
   Do not promote SENSITIVE entries unless importance and confidence are both high.
   Do not promote ONGOING entries — note them in the journal entry instead.
6. Prune any Long-Term Memory entries that are now outdated or superseded.
7. Clear Daily Memory.
8. Log rollover completion.
```

### Rollover failure detection
On every app launch check whether Eidos Daily contains content with an `updatedAt` timestamp from a previous day.
If yes, rollover failed or was skipped.

User prompt (non-blocking):
> **Daily memory rollover pending**
> Eidos hasn't processed yesterday's memory yet.
> [Run now] [Postpone]

Run now triggers rollover immediately in the foreground via the same `runMemoryRollover()` path used by scheduled rollover.
Postpone dismisses — WorkManager retries on next opportunity.
If Daily Memory spans more than one day, rollover processes everything in a single pass.

---

## Required Tool Functions

All tools below must be implemented as specified. Worker-only actions are not model-visible tools.

| Tool | Confirmation | Modifies | Description |
|---|---|---|---|
| `read_daily_memory` | No | No | Read full Daily Memory note |
| `write_daily_memory` | No | Yes | Append entry to Daily Memory |
| `clear_daily_memory` | No | Yes | Wipe Daily Memory — rollover only (worker/orchestrator only, not model-visible) |
| `read_long_term_memory` | No | No | Read full Long-Term Memory note |
| `write_long_term_memory` | No | Yes | Append entry to Long-Term Memory |
| `prune_long_term_memory` | Yes | Yes | Remove specific entry by anchor text |
| `read_journal_tag_hint_index` | No | No | Read full journal Tag & Hint index |
| `update_journal_tag_hint_index` | No | Yes | Append new Tag & Hint line to index |
| `read_long_term_tag_hint_index` | No | No | Read full long-term memory Tag & Hint index |
| `write_journal_entry` | No | Yes | Write reflective journal entry |
| `read_journal` | No | No | Read journal entries by keyword or date |
| `read_subfolder_memory_cache` | No | No | Read subfolder memory cache |
| `update_subfolder_memory_cache` | No | Yes | Write or update subfolder memory cache |

`clear_daily_memory` does not require confirmation — only fires inside rollover.
`prune_long_term_memory` requires confirmation — permanently removes a stored fact.

---

## Prompt Assembly — Memory Layers

The system prompt is assembled fresh on every request.
Memory layers are included in this order:

```
1. Eidos base prompt
2. Current subfolder name and ID (if in subfolder)
3. Current note content (omitted if AI locked)
4. Subfolder memory cache (if not null)
5. List of attached files (names and types only)
6. Daily Memory (full content)
7. Long-Term Memory Tag & Hint index (full list of Tag & Hint lines)
8. Journal Tag & Hint index (full list of Tag & Hint lines)
9. Conversation history (trimmed from oldest if large)
```

### Trimming priority if context grows large
1. Oldest conversation messages — trim first
2. Journal Tag & Hint index — trim oldest lines if index is very long
3. Never trim Daily Memory
4. Never trim Long-Term Memory
5. Never trim subfolder memory cache
6. Never trim current note content
7. Never trim base system prompt

Memory layers are protected from trimming. If they grow large enough to cause pressure that is a signal the memory system is not being maintained correctly — not a reason to trim them.

---

## Summary Table

| Layer | Always in prompt | Resets | Written by | User can delete |
|---|---|---|---|---|
| Current location context | Yes | Per session | App (auto) | No |
| Subfolder memory cache | Yes (when in subfolder) | Never | Eidos | Yes |
| Daily Memory | Yes | Midnight rollover | Eidos | Yes |
| Journal Tag & Hint index | Yes | Never | Eidos (rollover) | Yes |
| Full journal entries | No — triggered | Never | Eidos (rollover) | Yes |
| Long-Term Memory | No — Tag & Hint index in prompt, full entries triggered | Never | Eidos (rollover) | Yes |
| Eidos Log | No — triggered | Never | Eidos (auto) | Yes |
| Chat history | No — user initiated | Never | Eidos + User | Yes |
