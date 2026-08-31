# Prompt scope router plan

| Field | Value |
|--------|--------|
| **Status** | Phase 6 complete — router Phases 0–6 shipped (2026-06-22) |
| **Canonical profile spec** | Profile tables in **this document** (ontology, location, tools). [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md) = code-traced “today” + drift notes only. |
| **Audience** | Product, prompt authors, Kotlin implementers, coding agents |
| **Scope** | System-wide prompt **routing, composition, and scope contracts** — not provider HTTP transport (see [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md)) |
| **Related** | [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md) (code-traced drift), [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) (product intent — verified 2026-06-22), [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) (tracker), [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt), [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt) |

---

## Executive summary

OptimalX has outgrown a single manipulated system prompt. Today every `EidosApiClient.send()` call funnels through one `assembleSystemPrompt()` with scope-specific `if` branches, while tool allowlists are partially scoped elsewhere. **`ConversationScopes.GENERAL` is overloaded** — the same scope string is used for main-app General chat, widget Ask Eidos, and widget Chat UI, which need different tool sets and sometimes different prompt blocks.

**Goal:** introduce a **scope router** and **scope profiles** so each **surface** (where the user invoked Eidos) declares exactly what the LLM should see and do — identity, location description, stable prompt blocks, volatile turn facts, tools, history budget, and loop caps — in one place.

**Product framing:** OptimalX is an **AI OS** — multiple **processes** (scoped surfaces + background jobs), each with its own capability manifest, sharing one kernel (`RoomToolExecutor`, semantic index, providers). The router is the **prompt/routing layer** only; it is not a whole-app rewrite.

**Key design decision (2026-06-22):** resolve profiles from **`scopeType` + `entrySurface`**, not `scopeType` alone. Two conversations can both be `scopeType=general` but resolve to `widget.ask` vs `general.app` with different tools.

**Non-goals:** rewrite workshop product behavior, change tool executor semantics, merge transport fixes into the same PR series, dynamic tool escalation mid-turn, meta-tool that lists the full catalog, or Eidos **navigating the user** to another app screen (v-next).

### What this initiative covers vs “full AI OS”

| In scope (Phases 1–5) | Out of scope / later |
|------------------------|----------------------|
| Per-surface **profile** (ontology, location, tools, prompt blocks) | UI navigation / “open the editor to edit” handoff |
| `entrySurface` resolution (widget Ask vs Chat vs app General) | Full `PROMPT_SYSTEM.md` rewrite before code |
| Tool allowlists co-located with prompt policy | Daily memory inject until memory pipeline ready |
| Composer replaces `assembleSystemPrompt` branches | Parent bounded subfolder inject (Phase 3/4) |
| API trace: `profileId`, `entrySurface`, allowlist hash | [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md) — parallel track |

The router converts the **LLM boundary** from one assistant prompt to an OS-style capability manifest per surface. Broader product “OS feel” (navigation, richer inject) builds on top after profiles ship.

---

## Ground truth — code inspection

Verify against the tree before trusting any doc.

### Where prompts are built today

| Source | What it does (verified in code) |
|--------|----------------------------------|
| [EidosChatViewModel.buildBasePrompt()](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt) | 3-line identity string passed as `baseSystemPrompt` on main-chat sends |
| [WidgetVoiceService](../../src/main/java/com/example/optimalx/widget/WidgetVoiceService.kt) | Duplicate 3-line identity (`widgetBaseSystemPrompt`) — Ask Eidos + Quick Note |
| [WidgetChatActivity](../../src/main/java/com/example/optimalx/widget/WidgetChatActivity.kt) | Uses shared chat UI; sends via same path as main chat with `scopeType=general` |
| [EidosApiClient.assembleSystemPrompt()](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) | Appends universal blocks + scope `when` branches; flat `joinToString` |
| [EidosSystemPromptLayers.kt](../../src/main/java/com/example/optimalx/data/eidos/prompt/EidosSystemPromptLayers.kt) | Extracted universal blocks (partial migration) |
| Background callers | Own inline system strings (`ContentSummaryService`, `MemoryRolloverService`) |

### Tool allowlists (partially scoped — in progress)

| Scope signal | Resolver (code today) |
|--------------|----------------------|
| `panel_runner` | `EidosToolCatalog.toolsForPanelRunner()` |
| `panel_gallery` | `EidosToolCatalog.toolsForPanelGallery()` |
| `panel_workshop` + mode + phase | `EidosToolCatalog.toolsForWorkshopMode()` |
| Other chat scopes | `EidosToolCatalog.toolsForScopedChat(scopeType)` |

**Gap:** `toolsForScopedChat` maps `general` / `null` to `generalScopedChatToolNames`, which is **too broad for widget surfaces** and still includes `write_quick_note`, `rename_folder`, `move_to_trash`, etc. Widget Ask/Chat and Quick Note are not distinguished.

Tool policy is **not** co-located with prompt policy — a scope can receive prose for tools it does not have, or vice versa.

### Transport (orchestrator — keep separate from router)

| Behavior | Code today |
|----------|------------|
| Rolling conversation summary + verbatim tail | ✅ `ConversationOutboundHistory` |
| Responses incremental tool continuation | ✅ xAI/OpenAI when `!isPanelWorkshop` |
| Workshop incremental continuation | ❌ opted out |
| Kimi reasoning outbound shaping | ✅ `prepareKimiOutboundHistory` |
| Workshop chat tool cap | `WORKSHOP_CHAT_MAX_TOOL_ROUNDS = 2` — **drop** when `workshop.chat` profile ships |

### Known pain points (code-backed)

1. **`general` overloaded** — widget Ask, widget Chat, and main-app General share `scopeType=general` but are different surfaces.
2. **Quick Notes conflated** — widget Quick Note and app Quick Notes day inbox need the same capture tools; Ask Eidos must **not** get `write_quick_note`.
3. **Universal blocks** — `TOOL_FIRST_CONTEXT_RULES` and provider web notes appended broadly, including workshop build.
4. **Scattered identity** — base prompt duplicated across ViewModel and widget.
5. **Memory tool sprawl** — read/prune/journal tools in catalog but should be internal or semantic-only for most chat scopes.
6. **Doc/code drift** — [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md) still lists `EidosToolCatalog.all` for several scopes; code has moved to `toolsForScopedChat`.

---

## Design — four components

```
SendContext (runtime facts: scopeType + entrySurface + ids + hints)
       │
       ▼
 EidosScopeRouter.resolve()  ──► EidosScopeProfile (declarative contract)
       │                              │
       │                              ├── toolPolicy        (explicit tool name list)
       │                              ├── contextPolicy
       │                              ├── historyPolicy
       │                              ├── loopPolicy
       │                              └── transportHints
       ▼
 EidosPromptComposer.compose(profile, turnFacts) ──► ComposedPrompt
       │         (stable prefix → volatile tail)
       ▼
 EidosApiClient.send()  — orchestrator only
```

| Component | Owns | Must not own |
|-----------|------|--------------|
| **Router** | Map `SendContext` → exactly one `profileId` | HTTP, DB queries for prompt text |
| **Profile registry** | Location description, blocks, policies, **tool name list** | Provider JSON wire format |
| **Composer** | Section order, stable/volatile split, char budgets | Tool execution |
| **Orchestrator** (`send`) | Tool loop, transport phases, tracing | Inline `if (workshop)` prompt prose |

---

## `SendContext` — two dimensions for chat scopes

`ConversationScopes` alone is insufficient. Add **`entrySurface`** so the router can distinguish widget vs app entry points that share the same DB `scopeType`.

```kotlin
enum class EidosEntrySurface {
    APP_CHAT,           // EidosChatViewModel / EidosChatScreen
    WIDGET_ASK,         // WidgetVoiceService Ask (Talk → Send)
    WIDGET_CHAT,        // WidgetChatActivity full chat UI
    WIDGET_QUICK_NOTE,  // WidgetVoiceService Quick Note capture
    BACKGROUND_WORKER,  // EidosChatSendWorker
    INTERNAL,           // summary, rollover, note rolling summary
}

data class EidosSendContext(
    val userMessage: String,
    val conversationId: Long?,
    val scopeType: String?,           // ConversationScopes.* or internal
    val entrySurface: EidosEntrySurface,
    val subfolderId: Long?,
    val parentFolderId: Long?,
    val conversationHistory: List<EidosMessage>,
    val activeProvider: String,
    // Surface hints (unchanged from send() today)
    val editorSurfaceHint: String?,
    val webPanelPageUrl: String?,
    val workshopOpenFileName: String?,
    val workshopOpenFileContent: String?,
    val workshopEidosMode: WorkshopEidosMode?,
    val workshopProjectPhase: WorkshopProjectPhase?,
    val workshopDocAlignScope: WorkshopDocAlignScope?,
    val workshopUpdateSection: WorkshopUpdateSection?,
    val kimiFormulaToolsLoaded: Boolean,
)
```

### Router resolution examples

| `scopeType` | `entrySurface` | Resolved `profileId` |
|-------------|----------------|----------------------|
| `general` | `APP_CHAT` | `general.app` |
| `general` | `WIDGET_ASK` | `widget.ask` |
| `general` | `WIDGET_CHAT` | `widget.chat` |
| `quick_notes_day` | `WIDGET_QUICK_NOTE` | `quick_notes.day` |
| `quick_notes_day` | `APP_CHAT` | `quick_notes.day` |
| `parent` | `APP_CHAT` | `parent` |
| `subfolder` | `APP_CHAT` | `subfolder` |
| `panel_workshop` | `APP_CHAT` | `workshop.*` (mode/phase resolver) |
| `content_summary` | `INTERNAL` | `internal.content_summary` |
| `rollover` | `INTERNAL` | `internal.memory_rollover` |

**Prompt difference `widget.ask` vs `widget.chat`:** same **tool list** (agreed 2026-06-22). Prompt may differ later (Ask = short voice-oriented turns; Chat = full UI thread). Phase 1 can use one shared stable block; split only if golden tests show conflation hurts behavior.

---

## Shared identity

All user-facing profiles start from one constant (extract from ViewModel + widget duplicates):

```kotlin
object EidosIdentityPrompt {
    const val TEXT = """
        You are Eidos inside OptimalX.
        Be concise, clear, and operationally helpful.
        Use tools when needed and explain actions briefly.
    """.trimIndent()
}
```

Each profile adds two stable prose blocks after identity (product-owned; edit in this doc or [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md)):

| Block | Purpose | Changes when |
|-------|---------|--------------|
| **Location block** | Where the user **invoked** Eidos this session — UI entry point, anchoring, constraints | Per `entrySurface` / navigation |
| **Ontology (environment)** | What this place **is** in OptimalX's world model — folders, panels, capture inboxes, workshop | Per profile family; stable across turns |

**Location** = "you are here now." **Ontology** = "here is how this part of OptimalX works."

**Example — `widget.ask` location:**

> The user invoked **Ask Eidos** from the home-screen widget. This is a short question-and-answer flow (often voice); the conversation may have one or two follow-ups. You are not in a folder, the note editor, or Panel Workshop.

**Example — `general.app` ontology:**

> General chat is a place to communicate with the user about any topic — questions, planning, or casual conversation. It is not tied to a specific parent folder or subfolder.

The router owns **which blocks** attach to which profile; product owns the **prose** in the profile tables below.

---

## Memory and journal tools — cross-profile policy

| Tool | User chat profiles | Rationale |
|------|-------------------|-----------|
| `write_daily_memory` | All profiles that allow memory writes | User can record working memory from any surface |
| `write_long_term_memory` | Same | Durable prefs from any surface |
| `read_daily_memory` | **None** (user chat) | Today's daily memory is **injected** in prompt per [MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md) target |
| `read_long_term_memory` | **None** | Use `search_semantic` for meaning-based recall |
| `read_journal` | **None** | Journal is Eidos-internal + rollover; semantic index covers search |
| `read_log` | **None** | Deprecation candidate — no product use |
| `prune_long_term_memory` | **None** | Rollover / internal only |
| `write_journal_entry` | **None** | Rollover orchestrator only |
| `search_chat_history` | **None** | Keyword fallback for rollover; prefer `search_semantic` in chat |

**Provider-hosted web** (`web_search`, `fetch`, xAI hosted search): attach at provider adapter layer for profiles in the Web policy column below — not entries in `EidosToolCatalog`.

---

## Rejected patterns

| Pattern | Verdict |
|---------|---------|
| Meta-tool `list_available_tools` / full-catalog pull | **Rejected** — wastes a hop, invites calls to tools outside allowlist, no API support for mid-request tool registration |
| Dynamic tool expansion mid-turn | **Rejected** — capability escalation without user changing surface breaks OS model |
| Eidos instructing navigation to correct app location | **Deferred (v-next)** — good product direction ("open this note in the editor to edit"); not in router v1 |

---

## Profile registry — full matrix with tool lists

Source of truth for tool **names**: [EidosToolCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosToolCatalog.kt). Profiles reference name sets; catalog holds schemas.

Legend:

- **Web** = provider-hosted search/fetch when active provider supports it
- **Tools** = local `EidosToolCatalog` names only
- **Location block** = this-session UI entry (volatile-ish; may differ per `entrySurface`)
- **Ontology (environment)** = stable product copy — what this place is in OptimalX (product edits here)
- ✅ = in allowlist — ❌ = not exposed

### App chat — folder tree

#### `general.app`

| | |
|--|--|
| **When** | Main app → General chat (`EidosChatViewModel`, `scopeType=general`, `entrySurface=APP_CHAT`) |
| **Location block** | User opened General chat from the main app — not anchored to a parent folder or subfolder. Help across OptimalX; retrieve on demand. |
| **Ontology (environment)** | General chat is a place to communicate with the user about any topic or just for fun — questions, planning, or open conversation. It is not tied to a specific parent folder or subfolder. |
| **Web** | ✅ |
| **Inject (target)** | Identity, ontology, location block, tool-first rules, provider web, daily memory (when pipeline ready), scope line |
| **Volatile** | Trim notice, web panel URL if another surface has web tab open |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_file` | ✅ |
| `read_conversation` | ❌ |
| `list_folder_contents` | ✅ |
| `create_parent_folder` | ✅ |
| `create_subfolder` | ✅ |
| `write_note` | ✅ |
| `note_replace_string` | ✅ |
| `rename_folder` | ✅ |
| `move_to_trash` | ✅ |
| `write_daily_memory` | ✅ |
| `write_long_term_memory` | ✅ |
| `describe_image` | ❌ |
| `write_note_summary` | ❌ |
| `write_quick_note` | ❌ — Quick Notes surfaces only |
| All `workshop_*` | ❌ |
| `read_dump_edit` | ❌ |
| Memory/journal reads, `prune_*`, `search_chat_history` | ❌ |

---

#### `parent`

| | |
|--|--|
| **When** | Subfolder list screen for one parent (`scopeType=parent`) |
| **Location block** | User is browsing a **parent folder** (subfolder list). Discuss and act across this project's subfolders. |
| **Ontology (environment)** | A parent folder is a project container. User projects live in parent folders; inside a parent folder, subfolders organize the components of that project. |
| **Web** | ✅ |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_file` | ✅ |
| `read_conversation` | ❌ |
| `list_folder_contents` | ✅ |
| `create_subfolder` | ✅ |
| `write_note` | ✅ |
| `note_replace_string` | ✅ |
| `write_note_summary` | ✅ |
| `rename_folder` | ✅ |
| `create_parent_folder` | ✅ |
| `move_to_trash` | ✅ |
| `write_daily_memory` | ✅ |
| `write_long_term_memory` | ✅ |
| `describe_image` | ❌ |
| `write_quick_note` | ❌ |

---

#### `subfolder`

| | |
|--|--|
| **When** | Editor open for a standard user subfolder (`scopeType=subfolder`) |
| **Location block** | User is in a **subfolder** (note workspace). Default writes target this subfolder. |
| **Ontology (environment)** | Subfolders sit inside parent folders. Inside a subfolder the user works with standard panels — Note, Files, and Web browser — and can add custom panels created in the panel workshop. |
| **Web** | ✅ |
| **Inject (target)** | Subfolder name/id, note summary orientation (`NotePromptContext` / `ContentSummaryService`) — not full note body |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_file` | ✅ |
| `read_conversation` | ✅ |
| `list_folder_contents` | ❌ |
| `create_subfolder` | ✅ |
| `write_note` | ✅ |
| `note_replace_string` | ✅ |
| `write_note_summary` | ✅ |
| `rename_folder` | ❌ |
| `write_daily_memory` | ✅ |
| `write_long_term_memory` | ✅ |
| `describe_image` | ❌ |
| `create_parent_folder` | ✅ |
| `move_to_trash` | ❌ |
| `write_quick_note` | ❌ |

---

### Widget surfaces

#### `widget.ask`

| | |
|--|--|
| **When** | Widget **Talk → Send** (`WidgetVoiceService.handleQuickAskTranscript`, `scopeType=general`, `entrySurface=WIDGET_ASK`) |
| **Location block** | User invoked **Ask Eidos** from the widget — short Q&A, often voice, limited follow-ups. Not folder-anchored. |
| **Ontology (environment)** | Ask Eidos from the widget is a lightweight question-and-answer surface — often one question and a follow-up, but regular conversation is fine also. |
| **Web** | ✅ |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_file` | ✅ |
| `read_conversation` | ❌ |
| `list_folder_contents` | ❌ |
| `create_parent_folder` | ✅ |
| `create_subfolder` | ✅ |
| `write_note` | ✅ |
| `note_replace_string` | ✅ |
| `write_daily_memory` | ✅ |
| `write_long_term_memory` | ✅ |
| `rename_folder` | ❌ |
| `move_to_trash` | ❌ |
| `write_note_summary` | ❌ |
| `write_quick_note` | ❌ |
| `describe_image` | ❌ |

---

#### `widget.chat`

| | |
|--|--|
| **When** | Widget **Chat** button → `WidgetChatActivity` (`scopeType=general`, `entrySurface=WIDGET_CHAT`) |
| **Location block** | User opened **Widget Chat** — full chat UI from the widget for ongoing conversation. Not folder-anchored. |
| **Ontology (environment)** | Widget Chat is a place to communicate with the user about any topic or just for fun — questions, planning, or open conversation. It is not tied to a specific parent folder or subfolder. |
| **Web** | ✅ |
| **Tools** | **Same list as `widget.ask`** (2026-06-22). Prompt block may diverge later. |

---

### Quick Notes

#### `quick_notes.day`

| | |
|--|--|
| **When** | App Quick Notes day inbox **or** widget Quick Note (`scopeType=quick_notes_day`) |
| **Location block** | User is in a **Quick Notes** dated inbox. Append-only capture for that calendar day. |
| **Ontology (environment)** | Quick Notes is a daily capture feature. The user uses it to save things for later — lists, reminders, ideas, or anything they want written down without opening a full project note. Each calendar day has its own Quick Notes page; entries are timestamped and appended to that day. Your main job here is to capture what they said using write_quick_note. |
| **Web** | Optional — low priority; capture-first |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `write_quick_note` | ✅ |
| `read_conversation` | ✅ (same-day thread context) |
| All other write/create/folder tools | ❌ |

#### `quick_notes.root`

| | |
|--|--|
| **When** | Quick Notes directory / day-folder browser (`scopeType=quick_notes_root`) |
| **Location block** | User is browsing the **Quick Notes** folder list — not inside a specific day's inbox. |
| **Ontology (environment)** | Quick Notes root is the directory of dated day folders with Quick note entries— browse past days and open an inbox; it is not the live capture surface for today's entries. |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `list_folder_contents` | ✅ |
| `read_conversation` | ✅ |
| `write_quick_note` | ❌ — must be inside a day inbox |

---

### Other app surfaces

#### `dump_edit`

| | |
|--|--|
| **When** | DumpEdit scratch buffer (`scopeType=dump_edit`) |
| **Location block** | User is in the **DumpEdit** scratch buffer — unsaved staging text, not a folder note. |
| **Ontology (environment)** | DumpEdit is a temporary scratch pad for draft content you and the user can work on together before it becomes a saved note in a folder — or before the user discards it. |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_dump_edit` | ✅ |
| `write_note` | ✅ | 
| `note_replace_string` | ✅ |

#### `web_widget`

| | |
|--|--|
| **When** | Web widget / compact web-scoped chat (`scopeType=web_widget`) |
| **Location block** | User is in a **web-widget** chat thread — focused on a loaded page or URL context. |
| **Ontology (environment)** | Web-widget chat is for questions about the open web page or live URL. Provider web fetch/search is the primary way to read page content. |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_file` | ❌ |
| `read_conversation` | ❌ |
| `list_folder_contents` | ❌ |
| `write_note` | ❌|
| `note_replace_string` | ❌ |
| **Web** | ✅ (primary) |

#### `web_editor`

| | |
|--|--|
| **When** | In-app Web panel with Eidos chat (`scopeType=web_editor`) |
| **Location block** | User has the **Web browser panel** open inside a subfolder; chat is scoped to that browsing context. |
| **Ontology (environment)** | The Web panel is a standard subfolder panel for browsing. Eidos can discuss the loaded page and, when needed, relate findings back to the subfolder's Note and Files. |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `read_file` | ✅ |
| `write_note` | ✅ |
| `note_replace_string` | ✅ |
| **Web** | ✅ (primary) |


#### `panel_gallery`

| | |
|--|--|
| **When** | Panel Gallery list surface (`scopeType=panel_gallery`) |
| **Location block** | User is browsing the **Panel Gallery** — catalog of panels and workshop projects. |
| **Ontology (environment)** | Panel Gallery is the library of panels and workshop projects — metadata and discovery, not live panel runtime or file editing. A panel is a custom mini-app built in Panel Workshop and run inside OptimalX. |

| Tool | |
|------|--|
| `search_semantic` | ✅ only (`toolsForPanelGallery`) |

#### `panel_runner`

| | |
|--|--|
| **When** | Panel Runner — live panel open (`scopeType=panel_runner`) |
| **Location block** | User is running a **panel live** in Panel Runner — bridge to panel JavaScript is active when preview/tab is open. |
| **Ontology (environment)** | You are viewing a panel in Panel Runner. A panel is a custom mini-app built in Panel Workshop and run inside OptimalX. Panel Runner is the live runtime for a built panel. Eidos can read workshop project files and optionally invoke panel JavaScript (`getState` / `runAction`) when availabe.  |

| Tool | |
|------|--|
| `search_semantic` | ✅ |
| `workshop_read_file` | ✅ |
| `call_panel_function` | ✅ |

---

### Workshop family

Workshop is a **separate system**. Most folder/note OS tools stay out. Resolve via `WorkshopScopeKey` (mode + phase + execution kind) — see below.

#### `workshop.chat` (target — expands cross-source retrieval)

| | |
|--|--|
| **When** | Panel Workshop user chip **Chat** |
| **Location block** | Panel Workshop **Chat** mode — discuss the project; retrieve from workshop files and from the parent/subfolder project this panel serves when gathering context. |
| **Ontology (environment)** | Panel Workshop is a separate build system for interactive panels (HTML/CSS/JS projects) inside OptimalX. Chat mode is for discussion and research — including pulling context from normal parent/subfolder notes and files when the panel serves a real project. No project file writes in Chat — just have a conversation with the user. |
| **Inject (target)** | Workshop manifest only (v1). **Note summary inject** is a `subfolder`-scope feature today — `NotePromptContext` on every send when `scopeType=subfolder`. Cross-scope summary inject (e.g. workshop Chat pulling host-note orientation without opening that subfolder) is **deferred v2** — see [Explicit deferrals](#explicit-deferrals). |
| **Web** | ✅ |

| Tool | |
|------|--|
| `search_semantic` | ✅ — **workshop project:** default auto-enrich `scopeType=local_first`, `scopeId=<workshopSubfolderId>`. **Main app (when panel linked to a host):** model may pass `scopeType=subfolder` / `parent` / `global` to search notes and files in that project; orchestrator may auto-enrich `scopeId` from assignment. **Notes:** answer from ranked `chunk_text` hits — there is no `read_note` tool. |
| `workshop_read_file` | ✅ — expand workshop **file** hits (`query` / line range) |
| `read_file` | ✅ **new** — expand non-workshop **file** hits from `search_semantic` |
| `read_note` | ❌ — removed app-wide; note facts via `search_semantic` + injected summaries |
| `workshop_write_file`, `workshop_create_file`, `workshop_replace_string` | ❌ |
| `call_panel_function` | ❌ |
| `write_note`, `write_note_summary`, folder OS tools | ❌ |

**Retrieval policy (notes vs files):** Same as `subfolder` / `parent` profiles — summaries orient; `search_semantic` supplies passage-level note content. Use `read_file` / `workshop_read_file` only to expand **file** hits before edits (not applicable in Chat — read-only).

**Rationale:** Panels are often built for content living in normal parent/subfolder notes. Chat mode gathers that context via `search_semantic` (and `read_file` for files) — without workshop writes, summary inject outside subfolder scope, or a full-note read tool.

#### `workshop.plan` / `workshop.edit` / `workshop.build.*`

Use existing `toolsForWorkshopMode()` matrices. No general note/folder tools. Workshop retrieval policy **once** per composed prompt.

| Profile | Ontology (environment) | Tools (code today) |
|---------|------------------------|-------------------|
| `workshop.plan` | Workshop **Plan** mode — shape specs and scaffold project files; write workshop files; no live panel bridge required. | `search_semantic`, `workshop_read_file`, `workshop_write_file`, `workshop_create_file`, `workshop_replace_string` |
| `workshop.edit` / `workshop.build.*` | Workshop **Edit/Build** — implement and fix panel code in the project; Diff Review applies writes; `call_panel_function` optional when Preview is open. | `search_semantic` + full `panelToolNames` (incl. `call_panel_function` when phase allows) |
| `workshop.chat` | (see table above) | `search_semantic`, `workshop_read_file` → **add** `read_file`; cross-app notes via `search_semantic` only (no `read_note`) |
| Intake phase | Workshop **Intake** — clarify project before build; chat-only tools. | Chat-only subset |

---

### Internal scopes (background LLM)

| Profile id | Caller | Ontology (environment) | Tools |
|------------|--------|------------------------|-------|
| `internal.content_summary` | `ContentSummaryService` | Batch summarization of workshop content — fixed output shape, no user chat. | None |
| `internal.memory_rollover` | `MemoryRolloverService` / `RolloverOrchestrator` | Nightly memory rollover — read daily/journal/LTM, synthesize, write journal/LTM, clear daily memory. Not user-facing chat. | Orchestrator passes: `read_daily_memory`, `read_journal`, `read_long_term_memory`, `search_chat_history`, `write_journal_entry`, `write_long_term_memory`, `prune_long_term_memory`, etc. |

No `TOOL_FIRST_CONTEXT_RULES`. Tiny fixed system strings.

---

## Workshop resolved key

```
WorkshopScopeKey(
  mode: WorkshopEidosMode,
  phase: WorkshopProjectPhase,
  executionKind: CHAT | PLAN | BUILD_KICKOFF | EDIT_SESSION | DOC_ALIGN,
  docAlignScope: WorkshopDocAlignScope?,
)
```

Phase gates (`allowsCallPanelFunction`, Diff Review) are **block producers**, not separate profile ids unless golden tests require split.

---

## Context policy (summary)

| Resource | Most chat profiles | Workshop build/edit | Internal |
|----------|-------------------|---------------------|----------|
| Note body | Summary inject (subfolder) / tool-only | Summary inject (linked host subfolder) + `search_semantic` chunks in Chat; workshop files via manifest + reads | N/A |
| Daily memory | Inject (target) | No | Rollover reads via tools |
| LTM / journal | `search_semantic` only | No | Rollover tools |
| File bodies | Tool / semantic | Workshop manifest + `workshop_read_file` | N/A |
| Provider web | Per profile Web column | Chat yes; build no (target) | No |

---

## Section composition

```
## Identity
## Ontology (environment)     ← stable product copy per profile
## Location & scope           ← this-session entry + ids
## Rules (retrieval, note-write, provider web — profile-selected)
## Project context (folder, workshop manifest, gallery, etc.)
## This turn (volatile)
```

`EidosPromptComposer` is the only concatenation point. `assembleSystemPrompt()` → thin wrapper → deleted.

---

## `EidosScopeProfile` shape

**Implemented (2026-07-01):** the shipped `EidosScopeProfile` carries `loopPolicy: ToolLoopPolicy` (tool-round circuit breaker) and `transportHints: TransportHints` (incremental continuation, omit-system-on-continuation, tool-result replay rounds). `EidosApiClient.send()` reads both. See [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md). The full aspirational shape below (location/block producers) remains partially future work.

```kotlin
data class EidosScopeProfile(
    val id: String,
    val ontologyBlock: String,              // stable product copy — what this place is in OptimalX
    val locationBlockProducer: LocationBlockProducer,  // suspend; may vary by entrySurface / ids
    val identityBlocks: List<PromptBlockRef>,
    val scopeBlocks: List<PromptBlockRef>,
    val projectBlockProducers: List<ProjectBlockProducer>,
    val volatileProducers: List<VolatileBlockProducer>,
    val toolNames: Set<String>,
    val providerWebAllowed: Boolean,
    val contextPolicy: ContextPolicy,
    val historyPolicy: HistoryPolicy,
    val loopPolicy: ToolLoopPolicy,
    val transportHints: TransportHints,
)
```

---

## Router placement

| Call site | Today | Target |
|-----------|-------|--------|
| `EidosApiClient.send()` | `assembleSystemPrompt` + partial tool `when` | Resolve profile from `EidosSendContext` at send start |
| `EidosChatViewModel` | `buildBasePrompt()` | `entrySurface=APP_CHAT` only |
| `WidgetVoiceService` Quick Ask | `widgetBaseSystemPrompt`, `scopeType=general` | `entrySurface=WIDGET_ASK` |
| `WidgetVoiceService` Quick Note | same prompt, `quick_notes_day` | `entrySurface=WIDGET_QUICK_NOTE` |
| `WidgetChatActivity` | `scopeType=general` | `entrySurface=WIDGET_CHAT` |
| `EidosChatSendWorker` | `KEY_BASE_SYSTEM_PROMPT` | `scopeType` + `entrySurface` |
| Background services | Custom prompts | `entrySurface=INTERNAL` |

**Rule:** ViewModels and widgets do not assemble scope-specific prose.

---

## Migration phases

**Do not start Phase 1 code until Phase 0 sign-off below is checked.** Phases are ordered so tool wins can land before full prompt migration.

### Phase 0 — Product alignment ✅ signed off (2026-06-27)

**Deliverables (done):**

- Profile matrix in this document — **ontology**, **location block**, **tool tables** per profile (product-edited).
- `entrySurface` dimension documented (widget Ask / Chat / Quick Note vs app General).
- Memory/journal read tools scoped to internal rollover only (user chat = writes + `search_semantic`).
- Rejected patterns documented (meta-tool, dynamic escalation).
- Pre-Phase 1 doc hygiene (below) completed 2026-06-22.

**Sign-off checklist** — review and check before Phase 1:

- [x ] Every profile id in the matrix has ontology prose you approve (edit in place above if not).
- [x ] Tool ✅/❌ tables match intent for widget vs `general.app` vs Quick Notes vs workshop.
- [x] `widget.ask` and `widget.chat` — same tools; shared prompt v1 OK (documented 2026-06-22).
- [x] Workshop Chat cross-source retrieval (`read_file` + main-app `search_semantic`; notes via chunks/summaries, no `read_note`) accepted as Phase 4 target.
- [x] Deferred items accepted: user relocation, daily memory inject timing, `PROMPT_SYSTEM.md` bulk update in Phase 6 only.
- [x] [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md) drift list reviewed — see inventory §Known problems.
- [x] [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) updated — active track = this plan.

**Sign-off:** _Jesse________________ **Date:** __6/27/2026_______

---

### Pre-Phase 1 — Doc hygiene ✅ complete (2026-06-22)

| Doc | Action | Done |
|-----|--------|------|
| [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) | Banner + Phase 9 router track; fix stale `BASE_SYSTEM_PROMPT` / `EidosPromptSections` rows | ✅ |
| [PROMPT_ARCHITECTURE_FIX_PLAN.md](./PROMPT_ARCHITECTURE_FIX_PLAN.md) | Assembly/routing superseded by this plan | ✅ (see that doc header) |
| [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md) | Canonical spec → router plan; inventory = today + drift | ✅ |

---

### Phase 1 — Router skeleton + registry + composer shell (behavior parity) ✅ complete (2026-06-27)

**Implemented (2026-06-27):**

| # | Deliverable | Status |
|---|-------------|--------|
| 1 | `EidosIdentityPrompt` | ✅ |
| 2 | `EidosEntrySurface`, `EidosSendContext` | ✅ |
| 3 | `EidosScopeRouter` | ✅ |
| 4 | `EidosScopeProfileRegistry` | ✅ |
| 5 | `EidosScopeProfile`, `EidosPromptComposer` (passthrough) | ✅ |
| 6 | `EidosApiClient.send()` — trace `resolvedProfileId` + `entrySurface` | ✅ |
| 7 | Passthrough to `assembleSystemPrompt` | ✅ |
| 8 | `toolsForProfile()` stub | ✅ |
| 9 | Golden prompt snapshots | ⏳ pending (capture before Phase 3 migration) |
| 10 | `EidosScopeRouterTest` — resolution table | ✅ |

**Done when:** Snapshot hashes match pre-Phase-1 baseline; every `send()` logs `profileId` + `entrySurface`.

**Explicit sub-track:** begin removing `baseSystemPrompt` from callers (ViewModel passes `entrySurface` only; identity from `EidosIdentityPrompt` inside composer). Complete removal by end of Phase 3.

---

### Phase 2 — Tool allowlists + `entrySurface` wiring (first user-visible win) ✅ complete (2026-06-27)

**Implemented (2026-06-27):**

| # | Deliverable | Status |
|---|-------------|--------|
| 1 | `entrySurface` wired from all send paths | ✅ |
| 2 | `toolsForProfile()` reads registry allowlists | ✅ |
| 3 | Memory/journal read/prune/`search_chat_history` stripped from user-facing profiles | ✅ (via registry) |
| 4 | Executor boundary hardening | ✅ (existing `effectiveToolDefinitions` gate in `EidosApiClient`) |
| 5 | `EidosToolProfileTest` + registry alignment | ✅ |

**Done when:** Widget Ask cannot call `write_quick_note`; main General cannot be confused with widget profile; internal tools unreachable from chat.

---

### Phase 3 — Prompt migration (delete `assembleSystemPrompt` branches)

Migrate one profile at a time: composer uses registry **ontology** + profile-selected rule blocks + existing context producers (`buildSubfolderContext`, `PanelGalleryContext`, etc.).

**Order (lowest risk first):**

1. `panel_gallery`
2. `dump_edit`
3. `widget.ask` / `widget.chat`
4. `quick_notes.day` / `quick_notes.root`
5. `general.app`
6. `parent` / `subfolder`
7. `web_editor` / `web_widget`
8. `panel_runner`
9. Workshop family (last)

**Add during `parent` migration (inventory gap #9):**

- Bounded subfolder catalog inject (≤20 / capped list) per [PROMPT_SCOPE_INVENTORY.md](./PROMPT_SCOPE_INVENTORY.md).
- Orchestrator auto-enrich `search_semantic` with `scopeType=parent` + `scopeId=parentFolderId` (match workshop today).

**Done when:** `assembleSystemPrompt` deleted; `baseSystemPrompt` param removed from `send()` and all callers.

#### Phase 3 sub-phases (one PR each — delete legacy in same PR)

| Sub | Profile(s) | Delete from legacy assembler (indicative) |
|-----|------------|---------------------------------------------|
| 3.1 | `panel_gallery` | ✅ `PanelGalleryContext` branch removed; composer uses registry + `buildProjectContext` |
| 3.2 | `dump_edit` | ✅ DumpEdit `when` + `DUMP_EDIT_RULES` removed from legacy assembler |
| 3.3 | `widget.ask` / `widget.chat` | ✅ Widget surfaces use composer with profile-specific location blocks; no legacy general passthrough |
| 3.4 | `quick_notes.day` / `quick_notes.root` | ✅ Subfolder/parent fallthrough removed; Quick Notes-specific context + capture rules |
| 3.5 | `general.app` | ✅ General chat default branch migrated; APP_CHAT + BACKGROUND_WORKER use composer |
| 3.6 | `parent` | ✅ `buildParentFolderContext` removed; bounded subfolder inject + parent `search_semantic` auto-enrich |
| 3.7 | `subfolder` | ✅ `buildSubfolderContext` removed; composer uses `SubfolderContext` + `NOTE_WRITE_RULES` + panel bridge |
| 3.8 | `web_editor` / `web_widget` | ✅ Web scope rules + URL blocks in composer; legacy web branches removed |
| 3.9 | `panel_runner` | ✅ `PanelRunnerContext` + runner bridge in composer; legacy runner branches removed |
| 3.10 | `workshop.chat` | ✅ `WorkshopPanelContext` extracted; Chat mode uses composer (no legacy TOOL_FIRST) |
| 3.11 | `workshop.plan` | ✅ Plan mode uses composer via `WorkshopPanelContext`; legacy Plan branch skipped |
| 3.12 | `workshop.edit` / build / doc_align | ✅ `WORKSHOP_EDIT` + `WORKSHOP_INTAKE` in composer; legacy `PANEL_WORKSHOP` branch removed |

**Per sub-phase done when:** golden prompt test passes; grep shows no remaining `assembleSystemPrompt` branch for that profile; `ToolAlignmentTest` for that profile.

---

### Phase 4 — Scope hygiene (intentional prompt shrink)

Only after Phase 3 parity:

1. Drop `TOOL_FIRST_CONTEXT_RULES` from workshop build/edit profiles.
2. Deduplicate workshop retrieval policy (instruction blocks **or** tail append — not both).
3. `providerWebAllowed` gates provider web prose per profile.
4. Daily memory inject where `contextPolicy` says inject ([MEMORY_SYSTEM.md](../memory/MEMORY_SYSTEM.md)).
5. `workshop.chat`: add `read_file` to allowlist; wire main-app `search_semantic` scope when panel has a known host link (`CustomPanelAssignment` → optional orchestrator auto-enrich); ontology/location line about cross-source retrieval. No `read_note`; no cross-scope summary inject (v2).

**Done when:** Workshop hop-1 prompt measurably smaller vs Phase 0 baseline; web prose absent from workshop build.

#### Phase 4 sub-phases

| Sub | Scope | Status |
|-----|-------|--------|
| 4.1 | Workshop retrieval dedupe (tail append in `WorkshopPanelContext` only); `providerWebAllowed=false` on `workshop.plan` / `workshop.edit` / `workshop.intake` | ✅ |
| 4.2 | `contextPolicy` + daily memory inject (`general.app`, `parent`, `subfolder`) | ✅ |
| 4.3 | `workshop.chat` cross-source retrieval (`read_file` + host-link `search_semantic` auto-enrich) | ✅ |

---

### Phase 5 — Internal scopes + observability ✅ complete

1. Register `internal.content_summary`, `internal.memory_rollover`, `internal.note_rolling_summary`, `internal.conversation_summary` in composer (`EidosInternalPromptBlocks` + `RolloverPromptBlocks`).
2. Background services no longer pass ad-hoc `baseSystemPrompt`; rollover uses `internalVolatilePrompt`.
3. API trace: `toolAllowlistHash`, `stablePrefixSha256`, `sectionCharCountsJson` on `eidos_api_trace_runs` (DB v27).

**Done when:** No background `send()` uses ad hoc identity or chat assembler branches. ✅

---

### Phase 6 — Documentation truth sync (doc-only) ✅ complete (2026-06-22)

1. Update [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) row-by-row with `verified 2026-06-22` where composer tests cover the profile.
2. Close drift in [PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md](./PROMPT_SYSTEM_IMPLEMENTATION_PLAN.md) — Phase 9 router track marked complete.
3. Mark [PROMPT_ARCHITECTURE_FIX_PLAN.md](./PROMPT_ARCHITECTURE_FIX_PLAN.md) assembly items superseded by scope router (Phase 6 addendum).

**Do not block Phases 1–5 on Phase 6.** ✅

---

## Testing strategy

| Test | Purpose |
|------|---------|
| **Resolution** | `(scopeType, entrySurface, …)` → `profileId` for widget split and workshop matrix |
| **Tool alignment** | Profile `toolNames` ⊆ catalog; executor rejects tools not in allowlist |
| **Golden prompt** | Full composed string per profile |
| **Stable prefix** | Volatile-only diff does not change prefix through `## Project context` |
| **Parity** | Phase 1 hash match before intentional shrink (Phase 4) |
| **Phase 6** | PROMPT_SYSTEM rows verified 2026-06-22 (composer unit tests; full golden snapshots still ⏳) |

---

## File layout (proposed)

```
app/src/main/java/com/example/optimalx/data/eidos/prompt/
  EidosIdentityPrompt.kt
  EidosSendContext.kt
  EidosEntrySurface.kt
  EidosScopeRouter.kt
  EidosScopeProfile.kt
  EidosScopeProfileRegistry.kt   // ontology + tool name sets from this doc (post sign-off)
  EidosPromptComposer.kt
  profiles/
    WidgetProfiles.kt
    GeneralProfiles.kt
    QuickNotesProfiles.kt
    WorkshopProfiles.kt
    InternalProfiles.kt
```

---

## Explicit deferrals

| Item | Why defer |
|------|-----------|
| Eidos navigates user to correct app location | Product v-next |
| Meta-tool / dynamic tool expansion | Rejected |
| `widget.ask` vs `widget.chat` prompt split | Same tools first; split prompts only if needed |
| Daily memory inject | ✅ Shipped Phase 4.2 — `general.app`, `parent`, `subfolder` |
| Cross-scope note summary inject | **v2 (maybe)** — user links a `subfolderId` from anywhere (workshop Chat, general, widget, …) and that note's `NotePromptContext` summary is injected like today's `subfolder` scope. v1: summary inject only when chat is opened **in** that subfolder; elsewhere use `search_semantic` (`summary_memory` / `summary_content` chunks). Workshop Chat host-link enrich shipped Phase 4.3. |
| PROMPT_SYSTEM.md bulk rewrite | ✅ Phase 6 — verified 2026-06-22 |
| [PROMPT_ARCHITECTURE_FIX_PLAN.md](./PROMPT_ARCHITECTURE_FIX_PLAN.md) assembly items | ✅ Superseded — closed Phase 6 |

---

## Definition of done (whole initiative)

1. Phase 0 sign-off checklist completed (above).
2. No scope-specific prompt branches in `EidosApiClient` except router/composer.
3. Every `send()` logs `resolvedProfileId` + `entrySurface` in API trace.
4. `EidosIdentityPrompt` — single source for chat + widget + worker.
5. `EidosScopeProfileRegistry` holds signed-off ontology + tool sets from this document.
6. Widget Ask, Widget Chat, and main General resolve to **different profiles** with correct tool lists.
7. Quick Note tools appear **only** on `quick_notes.day` (+ widget quick note entry).
8. Memory read/prune/journal tools appear **only** on internal rollover profile.
9. Workshop Chat can retrieve from notes/files outside the workshop project via `search_semantic` (+ `read_file` for files); note content from chunks/summaries, not `read_note` (Phase 4).
10. Phase 6: PROMPT_SYSTEM rows verified — ✅ 2026-06-22 (composer tests; golden snapshots ⏳).

**Remaining cleanup:** delete legacy `assembleSystemPrompt` passthrough; golden prompt snapshot fixtures per profile.

---

## Suggested first PR (Phase 1 — ✅ shipped; Phases 0–6 complete 2026-06-22)

Historical checklist for Phase 1 landing. Follow-up debt: golden snapshot fixtures + remove `assembleSystemPrompt` passthrough.

1. `EidosIdentityPrompt` + `EidosEntrySurface` + `EidosSendContext`.
2. `EidosScopeRouter` + `EidosScopeProfileRegistry` (ontology + tool sets from signed-off profile tables).
3. `EidosPromptComposer` section shell; passthrough to `assembleSystemPrompt`.
4. `toolsForProfile()` stub — today's `toolsForScopedChat` for non-widget paths only.
5. `WidgetVoiceService` / `WidgetChatActivity` / `EidosChatViewModel` pass `entrySurface` (tool split still Phase 2).
6. `ScopeResolutionTest` + golden snapshot fixtures.
7. **No intentional prompt text changes** in PR 1.

---

## PROMPT_SYSTEM.md — how to use it

- Product intent checklist — **verified 2026-06-22** against composer unit tests.
- Full golden prompt snapshots per profile — ⏳ remaining debt (Phase 1 follow-up).
- **This document** = architecture, profile matrix (ontology/location/tools), migration order (complete).
- **PROMPT_SCOPE_INVENTORY.md** = code-traced drift notes; profile tables live here.
