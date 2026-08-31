# Prompt scope inventory

| Field | Value |
|--------|--------|
| **Status** | **Router Phases 0–6 complete (2026-06-22)** — canonical profile spec: [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) |
| **Purpose** | Code-traced map of **what OptimalX sends to LLMs** + **known drift** vs product spec |
| **Audience** | Implementers verifying behavior; product edits profiles in router plan |
| **Companion** | [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) — ontology, location, tool matrix |
| **Product intent (verified)** | [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) — verified 2026-06-22 |

---

## Why this doc exists

The prompt system is scattered across `EidosApiClient`, `EidosChatViewModel`, `PanelPlatformSpec`, context helpers, and background services. There is no one place to answer: *“What is the LLM’s job in Workshop Edit vs Quick Notes vs General chat, and what exactly do we put in the system prompt?”*

**Migration complete (2026-06-22):** all user-facing and internal LLM sends route through `EidosScopeRouter` + `EidosPromptComposer`. Remaining drift items below are **deferred v2** or **cleanup debt** — not blockers.

---

## How a user-facing LLM call works today (2026-06-22)

```text
UI / Worker / Widget / Background service
        │
        ▼
  EidosApiClient.send()          ← single orchestrator (tool loop, provider)
        │
        ├── EidosScopeRouter       ← scopeType + entrySurface + workshop mode/phase → profile
        ├── toolsForProfile()      ← EidosScopeProfileRegistry allowlist
        ├── EidosPromptComposer    ← sectioned system prompt (replaces assembleSystemPrompt branches)
        └── provider payload       ← hop-1 system; incremental continuations on tool hops
        └── conversation history   ← ConversationOutboundHistory (rolling summary + verbatim tail)
```

**Scope resolution:** `scopeType` + `entrySurface` (+ workshop mode/phase) → `EidosScopeProfile` via `EidosScopeRouter`. Widget Ask/Chat/Quick Note use distinct profiles (`widget.ask`, `widget.chat`, `widget.quick_note` → `quick_notes.day`).

### Where identity / system text is authored today (2026-06-22)

| Source | File | What it produces |
|--------|------|------------------|
| Profile registry | `EidosScopeProfileRegistry.kt` | Ontology, location policy, tools, `contextPolicy` per profile |
| Composer | `EidosPromptComposer.kt` | Sectioned system prompt for all profiles |
| Shared identity | `EidosIdentityPrompt.kt` | Identity + tone (ViewModel passes as hint) |
| Tool-first rules | `EidosContextLimits.TOOL_FIRST_CONTEXT_RULES` | Appended by composer (non-workshop, non-internal) |
| Workshop volatile | `WorkshopPanelContext.kt` | Manifest, mode instructions, **single** retrieval tail |
| Internal jobs | `EidosInternalPromptBlocks.kt`, `RolloverPromptBlocks` | Background summaries + rollover |
| Legacy fallback | `assembleSystemPrompt()` | Thin passthrough only |

---

## Universal layers (the main waste problem)

**Every** user chat scope currently receives these blocks, regardless of whether they are relevant:

| Block | Approx purpose | Workshop build needs it? | Quick Notes? |
|-------|----------------|--------------------------|--------------|
| `baseSystemPrompt` (3-line identity) | “You are Eidos…” | Partially | Yes |
| `TOOL_FIRST_CONTEXT_RULES` | search_semantic-first, note/file retrieval | Partially (workshop has own policy) | Yes |
| Active location rule | Default writes to current folder | No (workshop has project ids) | Yes |
| `providerWebBlock` | Kimi Formula web_search/fetch, convert/date/excel | **No** (build should not browse web) | Only if user asks |
| `Active scope: …` line | Scope label | Yes | Yes |
| Memory depth + trim notice | User tier | Yes | Yes |

Example you flagged — Kimi provider block is injected into workshop build, gallery, DumpEdit, etc.:

```32:44:app/src/main/java/com/example/optimalx/data/eidos/prompt/EidosSystemPromptLayers.kt
    fun providerWebBlock(activeProvider: String?, kimiFormulaToolsLoaded: Boolean): String = when {
        activeProvider == "kimi" && kimiFormulaToolsLoaded -> """
            Provider-native web access is enabled when the selected API provider supports it (xAI, OpenAI, Anthropic, Kimi).
            ...
            Kimi also has Formula utility tools — use them instead of in-thought math or flattened reads:
            • convert — ...
```

*Target (AI OS):* these become **profile-selected layers**, not universal append.

---

## Inventory by scope

Columns:

- **Today** = traced from Kotlin as of 2026-06-14
- **Target** = proposed router profile (*for discussion*, not shipped)
- **Inject / Tool / —** = what goes in system prompt vs fetched via tool vs not applicable

---

### `general` — split into three profiles (2026-06-22)

`scopeType=general` is used in multiple places that are **not** the same surface. Router target: resolve via `entrySurface`.

| Profile id | Entry point | `entrySurface` |
|------------|-------------|----------------|
| `general.app` | `EidosChatViewModel` — main app General chat | `APP_CHAT` |
| `widget.ask` | `WidgetVoiceService.handleQuickAskTranscript` — Talk → Send | `WIDGET_ASK` |
| `widget.chat` | `WidgetChatActivity` — widget Chat button | `WIDGET_CHAT` |

**Not general:** widget Quick Note → `quick_notes.day` + `WIDGET_QUICK_NOTE` (see Quick Notes section).

| | `general.app` | `widget.ask` / `widget.chat` |
|--|---------------|------------------------------|
| **LLM role target** | Main-app General — help across OptimalX; not folder-anchored | Widget Q&A or widget full chat UI; not folder-anchored; no rename/trash |
| **Tools today** | `toolsForScopedChat(GENERAL)` — too broad, includes rename/trash/quick_note | Same resolver today — **wrong** |
| **Tools target** | Full matrix in [PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) | Same tool list for ask + chat; **no** `write_quick_note`, **no** rename/trash |

**Prompt:** shared `EidosIdentityPrompt` + per-profile **location block** (factual UI description). Ask vs Chat may share prompt in v1; split only if needed.

**Open questions**

- Web panel URL in general.app when user has web tab open elsewhere?
- Product edit of location-block prose per profile

---

### `parent` — Parent folder chat

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` (`setParentFolderScope` — subfolder list screen for one parent) |
| **scopeType** | `parent` |
| **LLM role today** | Folder-aware assistant (generic) |
| **LLM role target** | *Parent project shell* — discuss the project across components (**B**); help orient and route into the right subfolder when needed (**A**). Chatty scope for a singular project with many subfolder components. |
| **Tools today** | `toolsForScopedChat(scopeType)` — see router plan matrix for target |
| **Alignment (2026-06-14)** | ✅ Agreed — bounded inject + on-demand full list; auto-enrich `search_semantic` (target) |

**System prompt blocks today**

| Block | Inject / Tool |
|-------|---------------|
| Universal layers (above) | Inject |
| Parent name + id | Inject |
| Subfolder list | **Tool only** (`list_folder_contents`) — not inlined |

**Target policy (agreed — not implemented yet)**

| Block | Inject / Tool | Rule |
|-------|---------------|------|
| Parent name + `parentFolderId` | Inject | Stable |
| Subfolder catalog | **Inject (bounded)** | See bounded inject rules below |
| Note / file bodies | **Tool** / `search_semantic` | Never inline |
| `search_semantic` scope | Orchestrator auto-enrich | `scopeType=parent`, `scopeId=parentFolderId` (like workshop today — **code gap**) |

#### Tool families in parent scope (agreed)

| Family | Tools | Purpose |
|--------|-------|---------|
| **Retrieval** | `search_semantic`, `read_file`, `read_conversation` | Find **content** across the parent branch. Unlisted subfolders are reachable via `search_semantic(scopeType=parent)` — hits include `subfolderId` and `chunk_text`. Do **not** call `list_folder_contents` just to learn what is *inside* an unlisted folder. |
| **Structure / writes** | `list_folder_contents`, `create_subfolder`, `write_note`, `create_folder`, … | Resolve **folder identity** (`name` → `subfolderId`), list directory, create or write to a named destination. `list_folder_contents` belongs here — not in the retrieval path. |

#### Bounded subfolder inject (target)

| Condition | Header line (required) | Body |
|-----------|------------------------|------|
| Count **≤ 20** | `All subfolders in this parent (N):` | Every subfolder: `name` + `subfolderId` only |
| Count **> 20** | `Subfolders shown (20 of N total — same order as your folder list):` | First **20** in **UI sort order** (`SubfolderScreen` `SortOrder`) |
| After capped list | One-line footer (optional; can live in profile rules instead) | `N total — content in unlisted folders: search_semantic(scopeType=parent). For write/create or name→subfolderId on an unlisted folder: list_folder_contents(folderId=<parentFolderId>).` |

**Why the count matters:** Without `20 of N` (or `All N`), models often treat the injected block as the **complete** catalog and reply “I don’t see that folder” instead of searching or listing. The header count is the main signal that more folders exist.

**When ≤ 20:** `All subfolders (N)` → catalog is **exhaustive** for structure/writes.

**When > 20:** `20 of N` → catalog is **partial**. More folders exist; that does **not** mean call `list_folder_contents` on every turn — only when you need an unlisted **name → subfolderId** for structure/write tools.

**When to inject:** hop 1 of a user turn (stable prefix). Do **not** re-expand the full catalog on every message in a long thread unless the folder set or UI sort/filter changed.

#### `list_folder_contents` — structure tool, on demand

Do **not** call this tool just because count &gt; cap or because the user is asking about **content** in a folder that is not in the top 20.

| User intent | Use |
|-------------|-----|
| “What did we decide about X?” / topic across the project | `search_semantic` (parent scope) — may return chunks from unlisted subfolders with `subfolderId` in hits |
| “Add this to Zebras” / write or create targeting a **folder name** not in the inject block | `list_folder_contents` → resolve `subfolderId`, then `write_note` / etc. |
| “What subfolders exist?” / pure directory question | `list_folder_contents` (or answer from inject if catalog is complete) |
| Read note passage after search hit | Use `chunk_text` from the hit (and subfolder inject tiers for orientation) — not `list_folder_contents` |

The injected catalog (or full list when ≤ 20) is the default for **structure**. `list_folder_contents` is the escape hatch when an unlisted folder **name** must be resolved for writes — not a supplement for retrieval.

#### Semantic search vs injected `name` + `subfolderId` (FAQ)

**Q: Can the LLM pass prompt subfolder names into `search_semantic` to search one folder?**

**A: Not as a separate “dial-in” parameter.** `search_semantic` takes a **text query** (and optional `scopeType` / `scopeId`, dates, limit). It does **not** accept a subfolder name or id as a dedicated filter beyond scope:

- `scopeType=parent` + `scopeId=parentFolderId` → search all notes/files under that parent (indexed by embeddings).
- `scopeType=subfolder` + `scopeId=subfolderId` → search one subfolder only.

The index does not read the system prompt. Injected `name` + `subfolderId` lines are **not** fed into the embedding search.

**What the injected list is for**

1. **Interpret search results** — hits return `subfolderId`, `location`, `chunk_text`. Map ids to names using the catalog.
2. **Tighter search** — optional `search_semantic(..., scopeType=subfolder, scopeId=…)` when the target folder is already in the catalog.
3. **Structure / writes** — `write_note`, `create_subfolder`, etc. when `subfolderId` is already known from the catalog.

**What search returns:** ranked `chunk_text` passages with scores and location metadata. For content questions about an **unlisted** folder, prefer parent-scoped `search_semantic` — not `list_folder_contents`.

**Target orchestrator fix:** auto-enrich `search_semantic` in parent scope with `scopeType=parent` and `scopeId=currentParentFolderId` (today only workshop/runner get auto-enrich in `enrichToolArguments`).

**Open questions**

- ~~Bounded inject vs tool-only?~~ → **Resolved:** bounded inject + on-demand full list.
- ~~Inject on every message?~~ → **Resolved:** hop 1 / stable unless folder set changes.
- Pass UI search filter into prompt when user has filtered the subfolder list? → *Optional later* (inject filtered set instead of top-20 when search active).

---

### `subfolder` — Standard note subfolder

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` |
| **scopeType** | `subfolder` |
| **LLM role today** | Note-location assistant |
| **LLM role target** | *Local workspace agent* — summary orientation; never inline full note |
| **Tools today** | `toolsForScopedChat(scopeType)` — see router plan matrix for target |

**System prompt blocks today** (`buildSubfolderContext`)

| Block | Inject / Tool |
|-------|---------------|
| Universal layers | Inject |
| Subfolder name + id | Inject |
| Note summary (if generated) | Inject (orientation only) |
| Full note body | Small notes **inject**; large notes **`search_semantic` / `read_note` / `read_note_section`** |
| File list | **Tool** (`list_folder_contents`) |
| Editor surface hint | Inject if present |
| Web panel URL | Inject if present |
| Panel bridge | Inject when preview active (see bridge section) |

---

### `quick_notes_day` — Daily capture inbox

| | |
|--|--|
| **Entry points** | `EidosChatViewModel`, `WidgetVoiceService` (Quick Note) |
| **scopeType** | `quick_notes_day` |
| **LLM role today** | **Same as subfolder** (`buildSubfolderContext`) — no Quick Notes-specific prose |
| **LLM role target** | *Capture daemon* — append-only daily inbox; emphasize `write_quick_note`; widget always targets today |
| **Tools today** | `toolsForScopedChat(scopeType)` — see router plan matrix for target |

**Drift:** Product treats Quick Notes as its own domain ([PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md) Quick Notes section). Code does not.

**Target-only blocks (not in code)**

| Block | Inject / Tool |
|-------|---------------|
| Quick Notes capture role | Inject |
| Append-only / timestamp format | Inject |
| `write_quick_note` emphasis | Inject (tools already in catalog) |

---

### `quick_notes_root` — Quick Notes directory browser

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` |
| **scopeType** | `quick_notes_root` |
| **LLM role today** | **Same as parent** (`buildParentFolderContext`) |
| **LLM role target** | *Directory librarian* — browse day folders; not the live capture surface |
| **Tools today** | `toolsForScopedChat(scopeType)` — see router plan matrix for target |

**Drift:** Same as above — falls through to parent context.

---

### `web_editor` / `web_widget` — Web-scoped search chat

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` (web search threads) |
| **scopeType** | `web_editor` or `web_widget` |
| **LLM role today** | Web-focused assistant |
| **LLM role target** | *Browser agent* — current URL is focus; fetch page via provider web tools |
| **Tools today** | `toolsForScopedChat(scopeType)` — see router plan matrix for target |

**Additional blocks today**

| Block | Inject / Tool |
|-------|---------------|
| Web-scoped chat rules | Inject |
| Loaded tab URL | Inject when known |
| Provider web instructions (again, inside web rules) | Inject |

---

### `dump_edit` — Scratch buffer

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` |
| **scopeType** | `dump_edit` |
| **LLM role today** | Scratch buffer helper |
| **LLM role target** | *Staging buffer* — promote-only workflow; no `write_note` |
| **Tools today** | `EidosToolCatalog.all` (not restricted in code) |

**System prompt blocks today** (`DumpEditContext` + rules)

| Block | Inject / Tool |
|-------|---------------|
| Universal layers | Inject (includes location rule — questionable for DumpEdit) |
| Buffer content | Inject if ≤8K and not blind/locked; else excerpts + `read_dump_edit` |
| DumpEdit rules | Inject |

---

### `panel_gallery` — Panel project list

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` |
| **scopeType** | `panel_gallery` |
| **LLM role today** | Panel librarian (metadata) |
| **LLM role target** | *Launcher/catalog agent* — browse projects; no file writes |
| **Tools today** | `search_semantic` only |

**System prompt blocks today** (`PanelGalleryContext`)

| Block | Inject / Tool |
|-------|---------------|
| Universal layers | Inject (**wasteful** — includes full tool-first + provider web) |
| Project list + counts | Inject |

---

### `panel_runner` — Full-screen panel runtime

| | |
|--|--|
| **Entry points** | `EidosChatViewModel` |
| **scopeType** | `panel_runner` |
| **LLM role today** | Runtime panel operator |
| **LLM role target** | *Runtime process* — `call_panel_function` loop; read workshop files; no project writes |
| **Tools today** | `search_semantic`, `workshop_read_file`, `call_panel_function` |

**System prompt blocks today** (`PanelRunnerContext` + bridge)

| Block | Inject / Tool |
|-------|---------------|
| Universal layers | Inject |
| Runner instructions | Inject |
| Panel bridge (functions + recent events) | Inject |
| Runtime file bodies | **Tool** |

---

### `panel_workshop` — Panel Workshop (largest scope)

Workshop is **one scopeType** with **many behaviors** driven by:

- `WorkshopEidosMode` (Chat / Plan / Edit + internal build kickoffs)
- `WorkshopProjectPhase` (Intake → … → Update)
- Optional `WorkshopDocAlignScope` (Plan align gates)

#### LLM roles (target — one profile per behavior, not one blob)

| Behavior | Target profile | LLM job (one line) |
|----------|----------------|-------------------|
| Intake / Chat | `workshop.chat` | Discuss project; read-only; no writes in intake |
| Plan / spec review | `workshop.plan` | Spec `.md` authoring; no runtime writes until accept |
| Build design/logic/plan kickoff | `workshop.build.*` | Coding agent; manifest + search/read/write; hand off at hop budget |
| Edit + Diff Review | `workshop.edit` | Patch agent; Diff Review queue; optional panel bridge |
| Doc align (Plan) | `workshop.doc_align` | Sync spec `.md` to code state on accept path |

#### Tools today (`EidosToolCatalog.toolsForWorkshopMode`)

| Mode / phase | Tools |
|--------------|-------|
| Intake | `search_semantic`, `workshop_read_file` |
| Chat | same |
| Plan | + write/create/replace workshop files |
| Edit / Build family | full panel tool set ± `call_panel_function` (gated by phase) |

#### System prompt blocks today (`buildWorkshopPanelContext` + universal layers)

| Block | Inject / Tool | Notes |
|-------|---------------|-------|
| Universal layers | Inject | **Includes provider web + general tool-first — likely wrong for build** |
| Project header, phase, mode | Inject | |
| File manifest (names + ids) | Inject | Not file bodies |
| Diff Review count | Inject when review active | |
| Open file excerpt (≤6K) | Inject except Chat mode | |
| Mode instructions | Inject | `PanelPlatformSpec.eidosInstructionsForMode` — **large** |
| Content policy line | Inject | `workshopContentPolicy()` |
| `EIDOS_WORKSHOP_RETRIEVAL_POLICY` | Inject | **Also embedded inside many mode instruction blocks — duplicate** |
| Intake summary | Inject when present | |
| Spec cap report + accept gate | Inject in spec review | |
| Project summary or bounded spec `.md` | Inject | Cold-start orientation |
| New-chat nudge | Inject when turn count high | |
| Panel bridge | Inject only Edit + phase allows bridge | |
| “User's current request is authoritative” | Inject | Workshop only today |

#### Tool loop today

| Cap | Value | Where |
|-----|-------|-------|
| Workshop Chat | 2 tool rounds | `WORKSHOP_CHAT_MAX_TOOL_ROUNDS` |
| Workshop build/edit | No explicit cap constant in main source | |
| Incremental continuation (xAI/OpenAI) | **Disabled for workshop** | `useIncremental = !isPanelWorkshop` |

**Open questions**

- Is workshop one “OS process” with mode switches, or separate processes per mode?
- Should build kickoffs get **zero** web/provider prose?
- Retire tool-cap pause / auto-continue (per your router plan) — confirm handoff-only model?

---

### Panel bridge (cross-cutting)

When preview/panel tab is active, `buildPanelBridgeContext` may inject:

| Block | Stable / volatile |
|-------|-------------------|
| Registered function names | Stable |
| getState / runAction usage | Stable |
| Recent panel events + timestamps | **Volatile** (should be “this turn” only) |

Included for: subfolder (when panel open), runner, workshop edit (when bridge eligible).

---

## Background LLM processes (not user chat)

These call `EidosApiClient.send()` but **bypass** `assembleSystemPrompt` branches. They should become explicit **internal profiles** with no universal chat layers.

| Process | scopeType | System prompt source | Tools | Target profile |
|---------|-----------|---------------------|-------|----------------|
| Note/workshop summary | `content_summary` | `ContentSummaryService` (workshop only) | None | `internal.content_summary` |
| Memory rollover | `rollover` | `MemoryRolloverService.buildRolloverSystemPrompt` | Phase-specific allowlist | `internal.memory_rollover` |
| Tag & Hint enrichment | `tag_hint_enrichment` | — | — | **Removed** (index deleted from shipping app) |

---

## AI OS framing (target architecture — for alignment)

```text
┌─────────────────────────────────────────────────────────┐
│  OptimalX (device)                                       │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │ Scope router │→│ Profile      │→│ Composer      │ │
│  │ (which job?) │  │ (contract)   │  │ (prompt text) │ │
│  └─────────────┘  └──────────────┘  └───────────────┘ │
│         │                  │                  │         │
│         ▼                  ▼                  ▼         │
│  ┌─────────────────────────────────────────────────────┐│
│  │ EidosApiClient orchestrator (history, tools, HTTP)  ││
│  └─────────────────────────────────────────────────────┘│
│         │                                               │
│         ▼                                               │
│  Tool executor + DB + semantic index  (“kernel”)          │
└─────────────────────────────────────────────────────────┘
```

**Profile contract (per scope)** — what we need to agree on for each row in this doc:

1. **Role** — one sentence job description  
2. **Identity block** — what “Eidos” means in this process  
3. **Context blocks** — inject vs tool vs never  
4. **Tool allowlist** — must match prompt mentions  
5. **History policy** — verbatim tail + hard cap via `ConversationOutboundHistory`; older turns via active-conversation prefetch.  
6. **Loop policy** — max tool hops, continuation transport  
7. **Provider adaptations** — web block, cache markers (thin layer)

---

## Known problems (consensus checklist before coding)

| # | Problem | Affects |
|---|---------|---------|
| 1 | Universal `TOOL_FIRST_CONTEXT_RULES` + provider web on every scope | Token waste, wrong instructions in workshop/gallery |
| 2 | Identity duplicated (ViewModel vs widget) | Drift risk |
| 3 | Quick Notes uses subfolder/parent context | Wrong product semantics |
| 4 | Workshop retrieval policy duplicated | Token waste, confusion |
| 5 | No section structure (stable vs volatile) | Cache + debug difficulty |
| 6 | Tool allowlist decoupled from prompt prose | Model told about tools it does not have |
| 7 | `assembleSystemPrompt` is the only assembly point but unreadable | Your tracking pain |
| 9 | Parent `search_semantic` not auto-scoped to `parentFolderId` | Extra model burden; should match workshop auto-enrich |

---

## Phase 0 — complete (2026-06-22)

1. Profile matrix (ontology, location, tools) — **[PROMPT_SCOPE_ROUTER_PLAN.md](./PROMPT_SCOPE_ROUTER_PLAN.md)** (product-edited).
2. This inventory — code-traced **today** + drift checklist below.
3. [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) — points at router as active track.
4. **Gate:** Phase 1 code starts after router plan sign-off checklist is checked.

---

## Prep work kept (feeds Phase 1)

`data/eidos/prompt/EidosSystemPromptLayers.kt` — partial extract from `assembleSystemPrompt` (no behavior change). **Keep** — Phase 1 composer builds on this.

---
