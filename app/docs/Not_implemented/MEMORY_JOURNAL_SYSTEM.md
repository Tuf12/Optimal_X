# JOURNAL_SYSTEM.md

## Purpose

This file defines the full memory and continuity system for Eidos in OptimalX v2.

Eidos has no built-in memory between sessions. This system is how it maintains continuity — building context, tracking decisions, and persisting what matters without storing everything.

Coding agents should use this file to understand every memory layer, how they load into the prompt, when search is triggered, how the midnight rollover works, and what to do when the rollover fails.

---

## Two Distinct Systems — Do Not Confuse Them

| System | Purpose |
|---|---|
| Memory system | Continuity over time — what Eidos knows and remembers |
| Retrieval system | Search and navigation — finding notes, folders, chats, logs |

The memory system is defined in this file.
The retrieval system is defined in SEARCH_AND_RETRIEVAL.md.

They work together but serve different purposes. Memory is not search. Search is not memory.

---

## Memory Architecture Overview

Eidos memory has six layers. Some load into every prompt automatically. Others are only fetched when triggered by context.

### Always in the prompt — no trigger needed

| Layer                       | What it is                                    | Why always included |
|-----------------------------|-----------------------------------------------|---|
| Current location context    | Active subfolder note + attached files        | The user's immediate working environment |
| Subfolder memory cache      | AI-maintained context note for this subfolder | Living context specific to this location |
| Daily Memory                | Today's short-term working memory             | Small, earned its place, relevant all day |
Long-Term Memory | Eidos Memory system folder | Tag & Hint index always in prompt. Full entries fetched on demand via tag match. 
| Journal Tag & Hint index    | One Tag & Hint line per journal entry         | Cheap trigger layer — enables journal search without loading full entries |
| Active conversation history | Current session messages                      | Required for coherent conversation |

### Only fetched when triggered

| Layer | Trigger                                                   | How it searches                                                            |
|---|-----------------------------------------------------------|----------------------------------------------------------------------------|
| Full journal entries | A journal Tag & Hint index line matches the current topic | Two-pass: Tag & Hint match → semantic search within matched entry          |
| Other subfolder notes | User references content outside current location          | Two-pass: folder tag match → semantic search within matched subfolders     |
| Eidos Log | Eidos needs to verify a past action                       | Two-pass: tag/date match → semantic search within matched log entries      |
| Chat history | User asks to find a past conversation                     | Two-pass: scope + tag match → semantic search within matched conversations |

The rule: if it is small and always relevant, it rides in the prompt. If it is large or only sometimes relevant, it is searchable and fetched on demand.

---

## Layer 1 — Current Location Context

The active subfolder name, note content, and list of attached files.

Injected automatically into every system prompt when the user is inside a subfolder. No tool call required.

If this answers the query — stop here. Do not reach into memory or retrieval unnecessarily.

Detail on assembly is in API.md and LLM_API_REFERENCE.md.

---

## Layer 2 — Subfolder Memory Cache

### What it is
A small AI-maintained context note that lives alongside every subfolder's main note.

This is separate from the note the user writes. The user does not edit it directly. It is Eidos' living understanding of what this subfolder is about — what has happened here, what decisions were made, what patterns have emerged. When the user opens a subfolder and starts chatting, Eidos already knows the history of that space without having to search for it.

This is what makes a subfolder feel alive rather than just a container.

### Structure
Stored as a second note field on the Subfolder object. Separate from `content` which holds the user's note. See Data Model Changes below.

### What Eidos writes here
- Key decisions made in this subfolder
- Patterns observed about how this space is used
- Ongoing threads or unresolved items relevant to this location
- Anything that would help Eidos give a more informed response next time the user is here
- Updated whenever something meaningful happens in this subfolder

### What Eidos does NOT write here
- Raw chat transcripts
- Log entries
- Everything — be selective

### Prompt inclusion
The subfolder memory cache loads alongside the subfolder note automatically whenever the user is in that subfolder. Both are always present together.

---

## Layer 3 — Daily Memory

### What it is
Short-term working memory scoped to the current day. Resets at midnight via the rollover process.

### System folder
- Parent folder: **Eidos Daily** (system locked)
- Structure: single note, updated throughout the day
- One active daily memory note at any time

### Prompt inclusion
Daily Memory is included in full on every prompt. It is not searched — it is always present.

This is intentional. If something is in Daily Memory it earned its place. The daily reset keeps it from growing unmanageable. On a heavy day there might be 20-30 entries — that is fine. They are there for a reason, and the user can delete any of them.

### What Eidos writes here
- Ongoing tasks and open threads from today
- Decisions made during the day with brief context
- Compact summaries of important moments with flags and importance markers
- Anything useful later the same day

### What Eidos does NOT write here
- Everything — be selective
- Raw chat transcripts
- Log entries

### Metadata flags
Every Daily Memory entry carries at least one flag:

| Flag | Meaning |
|---|---|
| DECISION | A choice was made — candidate for Long-Term Memory |
| PREFERENCE | User expressed a preference — likely promotes |
| TECHNICAL | Implementation detail or system note |
| SENSITIVE | Handle carefully — does not promote unless explicitly confirmed |
| ONGOING | Task or thread not yet complete |
| RESOLVED | Was ONGOING, now complete |

Each entry also carries:
- **Importance**: low / medium / high
- **Confidence**: low / medium / high
- **Source**: subfolder ID or conversation ID the entry came from (optional)

### Entry format
```
[HH:MM] DECISION|imp=high|conf=high|src=subfolder:482
Switched lumber supplier — better pricing, faster delivery.
```

### Resets
Cleared at midnight by the rollover process after promoting anything worth keeping.

---

## Layer 4 — Eidos Journal

### What it is
Eidos' durable reflective memory. Written once per day during the midnight rollover. This is where Eidos processes what happened, identifies patterns, and creates the record it will use to rebuild context in future sessions.

Not a log. Not raw notes. Reflection — written at rollover, not throughout the day.

### System folder
- Parent folder: **Eidos Journal** (system locked)
- Subfolders: one per day, named by date (e.g. 2026-04-09)
- Note inside each subfolder: the journal entry written that night

### Journal Tag & Hint index
Every journal entry has a one-line Tag & Hint generated at write time.

This Tag & Hint index rides in every prompt as a compact list — one line per journal entry. When a topic in the conversation matches something in the index, Eidos fetches the full entry using semantic search.

This is the trigger mechanism. The full journal never loads automatically. Only the Tag & Hint index does. Full entries are fetched on demand when a Tag & Hint line fires.

### Summary line format
```
[2026-04-08] DECISION|QUEEN|suppliers,lumber,Johnson — switched lumber supplier
[2026-04-08] ONGOING|ROOK|Johnson,timeline,scheduling — Johnson job timeline discussed
[2026-04-08] PREFERENCE|BISHOP|formatting,summaries,notes — bullet format preference
```

Short. Scannable. Enough for the LLM to recognize a match and know the full entry is worth fetching.

### What Eidos writes in full journal entries
- Reflective Tag & Hint of what happened that day
- Patterns observed about the user's working style or system
- Meaningful decisions or events worth carrying forward
- Notes on ONGOING items from Daily Memory that did not resolve
- Structured metadata: type/category, importance, source links

### What Eidos does NOT write here
- Log entries
- Raw chat content
- Minor actions

### Full entry format
```
[2026-04-08] SUMMARY|imp=high
Today focused on the Johnson job. User switched lumber supplier (src=subfolder:482).
Timeline discussion suggests project runs 2 weeks longer than originally scoped.
User prefers bullet-format summaries when reviewing options. Noted for future responses.
ONGOING: Permit paperwork not yet resolved — check next session.
```

### How Eidos uses the journal
- Journal Tag & Hint index is always in the prompt
- When a Tag & Hint line matches the current topic, Eidos calls `read_journal` for that entry
- Search is two-pass: Tag & Hint index identifies the entry, semantic search within the entry finds the specific content
- Eidos never loads the entire journal — only what is triggered

### User access
- User can read journal entries
- User can delete individual entries
- User cannot write to the journal

---

## Layer 5 — Long-Term Memory

### What it is
Stable, high-value facts that persist indefinitely. Small by design. Only what genuinely matters long-term belongs here.

### System folder
- Parent folder: **Eidos Memory** (system locked)
- Structure: single note, maintained by Eidos
- Eidos appends new entries and prunes outdated ones during rollover

### Prompt inclusion
Long-Term Memory is included in full on every prompt, same as Daily Memory.

It is small enough that including everything is cheaper than searching it. If it ever grows large enough that full inclusion becomes expensive, tag-based search becomes the fallback — but this should be rare in normal use.

### What gets stored here
- User preferences (confirmed, not assumed)
- System rules or constraints the user has established
- Repeated patterns observed across multiple sessions
- Decisions with long-term consequence (promoted from Daily Memory during rollover)

### What does NOT go here
- Temporary context
- SENSITIVE flagged entries unless explicitly confirmed by the user
- Anything that has not proven durable

### Entry format
```
[2026-04-08] PREFERENCE|BISHOP|formatting,summaries,notes — bullet format preference
```

### Pruning
Eidos may prune Long-Term Memory during the midnight rollover if entries are outdated or superseded.
The user can also delete individual entries.
Eidos cannot prune Long-Term Memory outside of the rollover process.

---

## Layer 6 — Eidos Log

### What it is
A factual record of every action Eidos took that modified the system. Not memory — an audit trail.

### System folder
- Parent folder: **Eidos Log** (system locked)
- Subfolders: one per day, named by date
- Note inside each subfolder: all log entries for that day

### What gets logged
- Created folder / subfolder
- Wrote, appended, or edited a note
- Moved to trash
- Attached file
- Updated any memory layer (Daily, Journal, Long-Term, subfolder cache)

Read-only actions are not logged. Only modifications.

### How Eidos searches logs
Logs are never loaded into the prompt automatically.

When Eidos needs to verify a past action, it searches using the same two-pass system used across the whole app:
- Pass 1: tag/date match narrows to relevant day and action type
- Pass 2: semantic search within matched entries finds the specific action

Even with years of log history, Eidos never scans everything.

### Log entry format
Each entry includes:
- Timestamp
- Action taken
- Location (folder/subfolder/note)
- Deep link to where the action occurred

### Deep link behavior

| Action type | Link behavior |
|---|---|
| Folder created | Opens the new folder |
| Subfolder created | Opens the subfolder note |
| Folder renamed | Opens the renamed folder |
| Note written or appended | Opens the note, scrolls to changed section |
| Note section edited | Opens the note, scrolls to edited section |
| Moved to trash | Opens trash filtered to that item |
| Memory updated | Opens the relevant memory system folder |

Deep links use the existing object ID system from DATA_MODEL.md plus a short text anchor for note-level scroll positioning.

### Log rules
- Append only — Eidos never edits or deletes its own log entries
- Every system modification produces a log entry automatically
- Eidos cannot act silently

### User access
- User can read log entries
- User can delete individual entries
- User cannot write to the log

---

## Chat History Search

Chat conversations are hierarchically structured the same way the app is.

| Chat scope | Where it lives |
|---|---|
| General (widget / parent folder page) | Eidos Chats system folder |
| Parent folder scope | That parent folder's conversation list |
| Subfolder scope | That subfolder's conversation list |

When a user asks to find a past conversation, Eidos searches using the same two-pass system:
- Pass 1: scope narrows the branch (general / parent / subfolder), tag match narrows further
- Pass 2: semantic search within matched conversations finds the specific exchange

Chat searches are user-initiated. Eidos does not proactively search chat history during normal sessions — important content from past chats should already be in memory via the rollover process.

Full chat storage detail is in DATA_MODEL.md and WIDGET_SYSTEM.md.

---

## System Folders

Six system-locked parent folders are created automatically on first install:

| Folder | Purpose |
|---|---|
| Eidos Daily | Daily short-term working memory |
| Eidos Journal | Durable reflective memory (nightly rollover entries) |
| Eidos Memory | Long-term stable facts and preferences |
| Eidos Log | Action audit trail |
| Eidos Chats | General conversation history (widget + parent folder page) |

All are system locked. They do not appear in the regular user folder list. Accessible from a dedicated Eidos section in the app.

---

## Midnight Rollover

### What it is
A background task that fires at midnight every day. Eidos reviews the day, writes a journal entry, updates the Tag & Hint index, promotes what matters to Long-Term Memory, and clears Daily Memory.

### Implementation
Uses **Android WorkManager** with a nightly scheduled task (`EidosMidnightWorker`).

- Survives app restarts and device reboots
- Configured with a network constraint — waits for connectivity before firing
- Fires within approximately ±15 minutes of midnight (Android battery optimization — acceptable)
- Retries automatically on failure using exponential backoff

### Rollover sequence

Eidos executes the following via tool calls:

1. `read_daily_memory` — read today's full Daily Memory
2. `read_log` (today) — review today's action log
3. `read_journal` (today, if entries exist) — review anything written today
4. `read_long_term_memory` — review current Long-Term Memory for context
5. Optionally: `read_note` or other reads on sources flagged in Daily Memory entries
6. `write_journal_entry` — write a reflective Tag & Hint of the day, with a one-line Tag & Hint at the top for the index
7. `update_journal_Tag & Hint_index` — append the new Tag & Hint line to the index
8. For each Daily Memory entry flagged for promotion: `write_long_term_memory`
9. Optionally: `prune_long_term_memory` for any outdated entries
10. Note any unresolved ONGOING entries inside the journal entry so they are not lost
11. `clear_daily_memory` — wipe Daily Memory
12. `write_log_entry` — log rollover completion

### Rollover system prompt

```
You are Eidos running a nightly memory rollover for OptimalX.

Your task:
1. Read today's Daily Memory, Log, and any Journal entries written today.
2. Write a reflective journal entry summarizing what happened.
   Include one compact Tag & Hint line at the top for the Tag & Hint index.
   Focus on patterns, decisions, and anything worth remembering.
   Be concise — do not repeat the log verbatim.
3. Append the Tag & Hint line to the journal Tag & Hint index.
4. Review Daily Memory entries. Promote DECISION and PREFERENCE entries
   marked high importance to Long-Term Memory.
   Do not promote SENSITIVE entries unless importance and confidence are both high.
   Do not promote ONGOING entries — note them in the journal entry instead.
5. Prune any Long-Term Memory entries that are now outdated or superseded.
6. Clear Daily Memory.
7. Log rollover completion.
```

### ONGOING entry handling
Unresolved ONGOING entries in Daily Memory are noted inside the journal entry at rollover. They are not automatically carried into the next day's Daily Memory. Eidos picks them up from the journal at the start of the next session when relevant.

---

## Rollover Failure and Recovery

### Detection
On every app launch, the app checks whether Eidos Daily contains content with an `updatedAt` timestamp from a previous day. If it does, the rollover failed or was skipped.

### User prompt
A non-blocking prompt appears in the Eidos section of the app:

> **Daily memory rollover pending**
> Eidos hasn't processed yesterday's memory yet.
> [Run now] [Postpone]

- **Run now** — triggers rollover immediately in the foreground
- **Postpone** — dismisses; WorkManager retries on next opportunity

The prompt appears once per missed rollover. It does not block app use.

### Multiple missed days
If Daily Memory spans more than one day, the rollover processes everything in a single pass, writes one catch-up journal entry, and clears.

---

## New Tool Functions Required

These extend the tool catalog in TOOL_FUNCTIONS.md.

| Tool                              | Description                                | Confirmation | Modifies |
|-----------------------------------|--------------------------------------------|---|---|
| `read_daily_memory`               | Read full Daily Memory note                | No | No |
| `write_daily_memory`              | Append an entry to Daily Memory            | No | Yes |
| `clear_daily_memory`              | Wipe Daily Memory (rollover only)          | No | Yes |
| `read_long_term_memory`           | Read full Long-Term Memory note            | No | No |
| `write_long_term_memory`          | Append an entry to Long-Term Memory        | No | Yes |
| `prune_long_term_memory`          | Remove a specific entry by anchor text     | Yes | Yes |
| `read_journal_Tag & Hint_index`   | Read the full journal Tag & Hint index     | No | No |
| `update_journal_Tag & Hint_index` | Append a new Tag & Hint line to the index  | No | Yes |
| `read_subfolder_memory_cache`     | Read a subfolder's memory cache            | No | No |
| `update_subfolder_memory_cache`   | Write or update a subfolder's memory cache | No | Yes |

`clear_daily_memory` does not require confirmation — it only fires inside the rollover.
`prune_long_term_memory` requires confirmation — it permanently removes a stored fact.

---

## Data Model Changes Required

### ParentFolder — new field
| Field | Type | Description |
|---|---|---|
| isSystemFolder | Boolean | If true, cannot be renamed, moved to trash, or deleted. Default false. |

### Subfolder — new field
| Field | Type | Description |
|---|---|---|
| memoryCache | String? | Eidos-maintained context note for this subfolder. Null until first written. |

The journal Tag & Hint index is stored as a note inside a dedicated subfolder within the Eidos Journal system folder. No new object type required — uses the existing ParentFolder / Subfolder / Note structure.

---

## Timestamps

All memory entries use Unix timestamps stored as Long in the database.
Display format: human readable (e.g. April 9, 2026 — 11:58 PM)
Daily subfolders in Journal and Log use ISO date format: YYYY-MM-DD

---

## Summary Table

| Layer                    | Always in prompt | Searchable | Resets | Written by | Deleted by |
|--------------------------|---|---|---|---|---|
| Current location context | Yes | No | Per session | App (auto) | — |
| Subfolder memory cache   | Yes (when in subfolder) | No | Never | Eidos | User |
| Daily Memory             | Yes | No | Midnight rollover | Eidos | Rollover / User |
| Journal Tag & Hint index | Yes | No | Never | Eidos (rollover) | User |
| Full journal entries     | No | Yes — triggered by index | Never | Eidos (rollover) | User |
| Long-Term Memory         | Yes | Fallback only if large | Never | Eidos (rollover) | Eidos / User |
| Eidos Log                | No | Yes — triggered by Eidos | Never | Eidos (auto) | User |
| Chat history             | No | Yes — user initiated | Never | Eidos + User | User |
