# Eidos Prompt System

**Status:** Active — 2026-05-21  
**Implementation tracker:** [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](../implementation/PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md)  
**Retrieval:** [SEARCH_AND_RETRIEVAL.md](./SEARCH_AND_RETRIEVAL.md)

---

## Core Behavioral Rule

Applies to: Parent Folder, Subfolder, Workshop, Dumpedit

```
Search before reading. Read before writing. Never assume content — retrieve it.
```

Primary retrieval is **`search_semantic`** (chunk embeddings). Read tools expand line ranges or prep edits — not a required second hop for Q&A.

---

## Tool Declaration Structure

Contexts that carry the core behavioral rule use this tool order:

```
Primary tools:
- search_semantic
- read_note / read_file / workshop_read_file
- write_note / write_file / workshop_write_file

Extended tools:
[remaining catalog]
```

All other contexts use a flat tool list in standard catalog order (today: global order in `EidosToolCatalog.coreTools`).

---

## Web Search

Declared separately from the tool catalog in every context that includes it.

Instruction: use web search only when the user explicitly requests current information or the answer clearly requires data beyond model knowledge.

---

## Tool Loop

| Context | Max Iterations |
|---|---|
| All app contexts | 13 |
| Workshop panel | 18 |

Repeated failure guard: if the same tool call fails twice consecutively the loop terminates regardless of iteration count.

System prompt assembled once per user turn, reused unchanged across all iterations.

---

## Memory & History

### Conversation history (in-chat)

User-controlled **Low / Medium / High** tier (default: low). Controls how much **chat thread** is resent — not note/file bodies.

**Note:** History trimming is **disabled** in code until a tool-safe trimmer exists (atomic assistant + tool-result rounds). Memory tier UI remains for future use.

### Memory layers (Daily, LTM, Journal, subfolder cache)

| Layer | Prompt policy | Rationale |
|-------|---------------|-----------|
| **Daily Memory** | **Inject** (bounded excerpt for today) | “At hand for the day”; working set. Instruct model: use only when relevant — do not nag the user with unrelated daily items. |
| **Long-Term Memory** | **Do not inject** — `search_semantic` + `read_long_term_memory(query)` | Large, grows over time; embedded in semantic index. |
| **Journal** | **Do not inject** — `search_semantic` + `read_journal(query)` | Embedded in semantic index; prompt inject deferred until journal write quality is fixed (spam). |
| **Subfolder memory cache** | **Inject per scope** when populated (subfolder / parent-folder chat only) | Small, location-specific ruleset; stable enough for scope prefix. Tool: `read_subfolder_memory_cache` for full text. |

Eidos Index (Tag & Hint) is **on hold** — use semantic search instead.

---

## Prompt Contents by Context

Legend: **Inject** = in system prompt · **Tool** = fetch via tool · **Search** = `search_semantic` · **—** = not applicable

### General Chat

| Component | Policy |
|---|---|
| Eidos identity | Inject |
| Full tool list | Inject |
| Web search | Inject (hosted tools + policy) |
| Daily memory | Inject (bounded; relevance-only guidance) |
| Long-term memory | Search / Tool |
| Journal | Search / Tool |
| Subfolder cache | — |
| Folder / note content | — |

Tone: conversational, not task-pushing. Tools available but not volunteered.

---

### Widget / Quick Note

Inherits General Chat prompt. No additional content.

---

### Parent Folder

| Component | Policy |
|---|---|
| Eidos identity | Inject |
| Primary + extended tool list | Inject |
| Web search | Inject |
| Daily memory | Inject (bounded) |
| Long-term memory | Search / Tool |
| Journal | Search / Tool |
| Current folder name + id | Inject |
| Subfolder list | Inject (names + ids; no note bodies) |
| Subfolder memory cache | Inject for active subfolder scope only when populated |
| Note content | — |

---

### Subfolder / Note

| Component | Policy |
|---|---|
| Eidos identity | Inject |
| Primary + extended tool list | Inject |
| Web search | Inject |
| Daily memory | Inject (bounded) |
| Long-term memory | Search / Tool |
| Journal | Search / Tool |
| Current folder + subfolder name + ids | Inject |
| Subfolder memory cache | Inject when populated for this subfolder |
| File list | Inject (names, types, ids) |
| Note content | Summary only — see below |
| Panel bridge | Inject only when panel is active |

**Note content rules (prompt):**

| Condition | Sent |
|---|---|
| Note has summary | Summary only + `read_note` / `search_semantic` hint |
| No summary yet | Orientation line only — use `read_note` or Generate Summary in editor |
| `aiBlind` | Nothing |
| `aiLocked` | Summary if exists; else lock notice |

Full note bodies are **never** inlined in the system prompt.

---

### DumpEdit (`dump_edit` scope)

| Component | Policy |
|---|---|
| Eidos identity + tool-first rules | Inject |
| DumpEdit buffer block | Inject per turn (see below) |
| `read_dump_edit` | Tool when buffer large or AI locked |
| Folder create/write for buffer | **No** — user promotes manually |

Buffer injection (`DumpEditContext`):

| Condition | Injected context |
|-----------|------------------|
| Empty | “Empty buffer” |
| `aiBlind` | Blind notice — no content |
| `aiLocked` | Lock notice — use `read_dump_edit` |
| ≤ 8K chars, not blind/locked | Full buffer text |
| > 8K chars | Semantic excerpts for user message + `read_dump_edit` hint |

Not a subfolder — no `list_folder_contents` default target for buffer writes.

---

### Workshop

| Component | Policy |
|---|---|
| Workshop mode identity + mode instructions | Inject |
| Primary + extended workshop tool list | Inject |
| Web search | Inject |
| Daily memory | Inject (bounded) |
| Long-term memory | Search / Tool |
| Journal | Search / Tool |
| File manifest | Inject (names + fileReferenceIds) |
| README / spec `.md` | Inject only at cold start (bounded ≤2k per spec file; see `WorkshopSpecMarkdown`) |
| Project summary | Inject when user has generated one |
| Open file content | — (Chat mode: discuss only; use `workshop_read_file` when user asks) |
| Panel bridge | Inject when Preview / panel tab active |

Steady state: manifest + summary + search → read → write loop. Workshop UX/efficiency improvements tracked separately.

---

### Web Browser

| Component | Policy |
|---|---|
| Eidos identity | Inject |
| Full tool list | Inject |
| Web search | Inject, primary for external facts |
| Daily memory | Inject (bounded) |
| Long-term memory | Search / Tool |
| Journal | Search / Tool |
| Current page | URL (+ title when known) |
| Page content | Tool / provider web fetch |

---

## Note & File Summaries

User-triggered **Generate Eidos summary** / **Regenerate** in the note editor and workshop drawer (`ContentSummaryService`). Summary text may appear in the stable system prefix for orientation.

`read_note`, `read_file`, and `workshop_read_file` accept optional **`query`**, **`startLine`**, **`endLine`** for section expansion (see `EidosToolCatalog` and `SEARCH_AND_RETRIEVAL.md`).

---

## Implementation Checklist

Code status as of 2026-05-21. ✅ done · 🟡 partial · ❌ not done

### Retrieval & tools

| Item | Status |
|------|--------|
| Chunk-level semantic index (notes, files, conversations) | ✅ |
| `search_semantic` primary in tool catalog | ✅ |
| Scoped search (`local_first`, `expand_if_weak`) | ✅ |
| `read_*` query + line ranges | ✅ |
| Eidos Index / Tag & Hint on hold | ✅ |
| Startup + Settings rebuild semantic index | ✅ |

### Provider transport & tool loops

| Item | Status |
|------|--------|
| Kimi `reasoning_content` replay + `thinking.keep: all` | ✅ |
| OpenAI/xAI reasoning capture + Reasoning folder log | ✅ |
| Kimi workshop write replay redaction | ✅ |
| History trim disabled (tool-safe trimmer pending) | ✅ |
| Global max iterations 13 / workshop 18 | ❌ |
| Duplicate tool-failure guard | ❌ |
| Anthropic multi-tool regression verified | 🟡 |

### Prompt text & policy

| Item | Status |
|------|--------|
| Semantic-first rules in `TOOL_FIRST_CONTEXT_RULES` | ✅ |
| Verbatim core behavioral rule in system prompt | ❌ |
| Web search “only when needed” wording | ❌ |
| Conversational base prompt tone | ❌ |
| Memory tool pointer lines (LTM/journal = search) | ❌ |

### Location context

| Item | Status |
|------|--------|
| Note summary-only in subfolder prompt | ✅ |
| Parent folder: inline subfolder list | ❌ |
| Subfolder: inline file list | ❌ |
| Subfolder memory cache inject when populated | ❌ |
| Daily memory inject (bounded + relevance guidance) | ❌ |
| Workshop cold-start README/spec bounded | 🟡 (`WorkshopSpecMarkdown`) |
| Workshop manifest + project summary | ✅ |

### Memory layers

| Item | Status |
|------|--------|
| LTM / journal **not** in system prompt (search instead) | ✅ (nothing injected today) |
| Daily memory inject per policy above | ❌ |
| Journal write quality / spam fix before any inject | ❌ |

### Workshop UX (separate polish track)

| Item | Status |
|------|--------|
| `search_semantic` in all workshop modes (scoped to project) | ✅ |
| Mode instructions: search → read(query) → write | ✅ |
| Mode clarity (Chat / Plan / Edit / Build / Debug) | 🟡 working |
| Chat mode tool round cap | ✅ (4 rounds) |
| Panel bridge inactive guidance in prompt | ✅ |
| Efficiency / user-friendly flows | 🟡 ongoing |

### Documentation

| Item | Status |
|------|--------|
| [SEARCH_AND_RETRIEVAL.md](./SEARCH_AND_RETRIEVAL.md) | ✅ |
| [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](../implementation/PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) | ✅ |
| [EIDOS_LLM_CONTEXT_CLEANUP.md](../implementation/EIDOS_LLM_CONTEXT_CLEANUP.md) status update | 🟡 see that doc |
| [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) sync | 🟡 |
