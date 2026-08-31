# Eidos Navigation — Implementation Plan

**Status:** Phase 0–3 shipped (desktop) — Phase 4+ open  
**Architecture spec:** [EIDOS_NAVIGATION.md](../architecture/EIDOS_NAVIGATION.md)  
**Related:** [CHAT_UI.md](../architecture/CHAT_UI.md), [desktop-chat-ui-plan.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/desktop-chat-ui-plan.md), [DIFF_REVIEW_IMPLEMENTATION_PLAN.md](DIFF_REVIEW_IMPLEMENTATION_PLAN.md), [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md)

Phased rollout of **clickable navigation from Eidos chat** to notes, folders, DumpEdit, and workshop projects. Desktop first (Electron renderer + `chat-service` stream); Android follows with the same JSON contract and URI grammar.

Each phase ships something usable on its own.

---

## Goal

Today, when Eidos runs `write_note`, `create_subfolder`, or workshop file tools, the user sees tool names in the activity log (or `(id=42)` in tool-result text) but cannot **open that place in one click**.

After this plan:

| User action | Result |
|-------------|--------|
| Eidos creates or edits a note | **Open note · {name}** chip appears; click → note editor |
| Eidos creates a parent or subfolder | **Open folder · {name}** chip → subfolder list or new note |
| Eidos writes a workshop file | **Open file · {path}** chip → workshop editor on that file |
| Assistant reply contains `optimalx://` link | Same resolver as chips (optional, model-authored) |
| User reopens chat history | Chips still work from persisted `navigation_targets_json` |

---

## Core invariants

1. **Chips over prose** — structured `navigation` on `tool_end` is authoritative; markdown links are optional echo.
2. **User-initiated only** — no auto-navigation on tool success (existing editor reload / Diff Review hooks stay).
3. **One resolver** — `navigateFromEidosTarget()` handles chips, URI clicks, and future external intents.
4. **Extensible registry** — new tools add rows to `resolveNavigation()` + docs in [EIDOS_NAVIGATION.md](../architecture/EIDOS_NAVIGATION.md); avoid one-off click handlers in chat UI.
5. **Unsaved guard** — every navigation path calls `confirmLeaveUnsaved` (desktop) or editor ViewModel equivalent (Android).

---

## Phase 0 — Contract + resolver skeleton (desktop)

**Ships:** Shared module with parse/format + navigation table; no visible UI yet.

**Status:** Shipped 2026-07-29 — `electron/eidos/eidos-navigation.js`, `navigation-resolver.js`, Jest-style node tests.

| Task | File / area | Notes |
|------|-------------|-------|
| 0a | `electron/eidos/eidos-navigation.js` (new) | `EidosNavigationTarget` typedef, `parseOptimalxUri()`, `formatOptimalxUri()`, `dedupeTargets()` |
| 0b | `electron/eidos/navigation-resolver.js` (new) | `resolveNavigation(toolName, args, result)` — initial tools: `write_note`, `edit_note_section`, `create_subfolder`, `create_parent_folder`, `workshop_write_file`, `workshop_replace_string`, `workshop_edit_file_section` |
| 0c | Unit tests | `tests/eidos/navigation-resolver.test.js`, `tests/eidos/eidos-navigation-uri.test.js` |
| 0d | Doc | Keep [EIDOS_NAVIGATION.md](../architecture/EIDOS_NAVIGATION.md) `kind` table in sync when adding tools |

**Resolver hints (desktop)**

| Tool | Target extraction |
|------|-------------------|
| `write_note`, `edit_note_section` | `args.subfolderId`; label from `subfolders.name` lookup |
| `create_subfolder` | `lastInsertRowid` or parse result string; label = `args.name` |
| `create_parent_folder` | new parent id; `kind: parent` |
| `workshop_*` file | `args.subfolderId` or scope default; `path` from `args.path` |

**Acceptance:** Jest green; `resolveNavigation('write_note', { subfolderId: 1 }, …)` returns `{ kind: 'note', subfolderId: 1, label: '…' }`.

---

## Phase 1 — Stream chips (desktop, live only)

**Ships:** During an in-flight send, successful tools show clickable chips in the activity panel. Chips also attach to the finished assistant message for the current session (not yet persisted in history).

**Status:** Shipped 2026-07-29 — `chat-service` `tool_end`/`done` navigation payload, activity panel chips, `renderer/js/eidos-navigation.js`, folder nav IPC.

| Task | File / area | Notes |
|------|-------------|-------|
| 1a | `electron/eidos/chat-service.js` | Import resolver; on `tool_end`, set `navigation: resolveNavigation(…)` when success |
| 1b | `electron/eidos/chat-service.js` | Accumulate `navigationTargets[]` per turn; pass to stream `done` if useful for UI finalize |
| 1c | `renderer/js/eidos-chat.js` | `recordToolEnd`: store `navigation` on activity log entry |
| 1d | `renderer/js/chat-message-bubble.js` | `renderToolLogItem`: if `entry.navigation`, render button/link styled as `eidos-nav-chip` |
| 1e | `renderer/js/eidos-navigation.js` (new) | `navigateFromEidosTarget(target)` — DB lookup via preload IPC for names/parents |
| 1f | `renderer/js/eidos-navigation.js` | Reuse branching from `search.js` (Workshop parent, Quick Notes, normal) |
| 1g | `renderer/css/app.css` | Chip styles (compact, matches tool log) |
| 1h | IPC (if needed) | `eidos:resolveNavigationTarget` or extend existing folder/note getters |

**Acceptance (manual)**

- [x] From general chat, ask Eidos to append to a note → chip appears → editor opens on correct note
- [x] `create_subfolder` → chip opens new note
- [x] Workshop write → chip opens workshop editor (file selection Phase 2)
- [x] Unsaved note → confirm dialog before navigate
- [x] Failed tool → no chip

---

## Phase 2 — Workshop file focus + DumpEdit (desktop)

**Status:** Shipped 2026-07-29 — `openWorkshopEditor({ selectPath })`, Quick Notes + DumpEdit resolver kinds.

| Task | Notes |
|------|-------|
| 2a | `workshop_file` kind: after `openWorkshopEditor`, select file in tree / editor tab |
| 2b | `dump_edit` kind: `navigatePin('dumpedit')` |
| 2c | `write_quick_note` → `quick_notes` kind |
| 2d | Extend resolver tests |

**Acceptance:** Workshop chip opens the file Eidos edited, not just the project root.

---

## Phase 3 — Markdown `optimalx://` links (desktop)

**Status:** Shipped 2026-07-30 — chat markdown sanitizer, link delegation, prompt hints, `shell.openExternal` for https.

| Task | File / area | Notes |
|------|-------------|-------|
| 3a | `renderer/js/note-content-codec.js` | DOMPurify: allow `optimalx` URI scheme in chat markdown only |
| 3b | `renderer/js/chat-message-list.js` or bubble | Delegated click on `.markdown-body a[href^="optimalx://"]` → `preventDefault` → resolver |
| 3c | `electron/eidos/prompt-layers.js` | Optional one-line hint for model-authored links (general + subfolder scopes) |

**Acceptance:** Assistant message with `[Foo](optimalx://note/12)` navigates on click; `https://` links still open externally or default browser policy.

---

## Phase 4 — Persist targets on assistant messages (cross-repo)

**Status:** Shipped 2026-07-30 — `navigation_targets_json` on desktop + Android Room v29; sync wire field; chips render on history reload.

**Ships:** Chips survive history reload and Tier 2 sync.

| Task | Desktop | Android |
|------|---------|---------|
| 4a | Migration: `chat_messages.navigation_targets_json TEXT` | Room migration + entity field |
| 4b | `chat-service.js` | Save JSON array on `insertChatMessage` for assistant row |
| 4c | Sync tier | Include field in Tier 2 `chat_messages` payload ([structure.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/structure.md)) |
| 4d | Message load API | Return parsed array to renderer |
| 4e | Bubble render | Render persisted chips on non-streaming assistant bubbles |

**Acceptance**

- [ ] Send turn with two note writes → two chips after reload
- [ ] Sync conversation to second device → chips appear there (after ids exist)

**Deduping:** `dedupeTargets()` on save — same `kind` + `subfolderId` + `path` once per message.

---

## Phase 5 — Android parity

**Status:** Shipped 2026-07-30 — resolver, navigation routing, chips, markdown links, leave guard.

| Task | Notes |
|------|-------|
| 5a | `EidosNavigationResolver.kt` — port resolver table |
| 5b | `EidosNavigation.kt` — `navigate(context, target, navController)` |
| 5c | Stream events from `EidosChatSendWorker` / trace UI |
| 5d | Tool activity composable — chips on `ChatMessageBubble` |
| 5e | Markdown link handler in chat markdown renderer |
| 5f | Room migration aligned with desktop Phase 4 |

**Acceptance:** Same manual scenarios as Phase 1 on device.

---

## Phase 6 — Extensions (backlog)

Track in architecture doc; implement as tools ship.

| Item | `kind` | Trigger |
|------|--------|---------|
| Panel Runner | `panel_runner` | Publish / open runtime from workshop chat |
| Trash restore | `trash` | Future restore tool |
| Semantic search hit | `note` + line | `search_semantic` result chips (not tool mutation) |
| Conversation directory | — | Open linked note from directory row (reuse resolver) |
| External intent | — | `optimalx://` from notification / widget |

---

## Desktop file checklist

| File | Phase |
|------|-------|
| `electron/eidos/eidos-navigation.js` | 0 |
| `electron/eidos/navigation-resolver.js` | 0 |
| `electron/eidos/chat-service.js` | 1, 4 |
| `renderer/js/eidos-navigation.js` | 1 |
| `renderer/js/eidos-chat.js` | 1 |
| `renderer/js/chat-message-bubble.js` | 1, 4 |
| `renderer/js/note-content-codec.js` | 3 |
| `renderer/js/chat-message-list.js` | 3 |
| `renderer/css/app.css` | 1 |
| `electron/preload.js` / `ipc/handlers.js` | 1 (if lookup IPC added) |
| `electron/db/migrate.js` | 4 |
| `structure.md` | 4 |
| `design.md` | 0 (link to arch doc) |

---

## Android file checklist

| File | Phase |
|------|-------|
| `data/eidos/EidosNavigationResolver.kt` | 5 |
| `ui/eidos/EidosNavigation.kt` | 5 |
| `data/eidos/EidosChatSendWorker.kt` (stream) | 5 |
| `ui/eidos/components/ChatMessageBubble.kt` | 5 |
| `data/db/AppDatabase.kt` + migration | 4–5 |
| `ui/navigation/AppNavigation.kt` | 5 |

---

## Manual QA script (full feature)

1. **Note write (scoped)** — Open Eidos from editor subfolder A; ask to append text → chip → already on A or navigate to A.
2. **Note write (general)** — From home, ask Eidos to write to subfolder B → chip → lands in B.
3. **Create subfolder** — New folder under parent → chip → new empty note editor.
4. **Create parent** — New parent → chip → subfolder list for that parent.
5. **Workshop file** — In workshop chat, edit `index.html` → chip → file open in workshop editor.
6. **DumpEdit** — Write DumpEdit → chip → DumpEdit view.
7. **Queued diff** — Write with review queue on → chip still opens editor; Diff Review sheet unchanged.
8. **Unsaved** — Dirty note → chip → confirm dialog.
9. **History** — Close chat, reopen thread → chips still present (Phase 4+).
10. **Markdown link** — Model (or test injection) includes `optimalx://` in reply → click works (Phase 3+).

---

## Changelog

| Date | Change |
|------|--------|
| 2026-07-30 | Phase 5 Android UI parity — resolver, chips, optimalx:// links, leave guard |
| 2026-07-30 | Phase 4 persist navigation_targets_json (desktop + Android sync) |
| 2026-07-29 | Phase 2 workshop file focus, Quick Notes + DumpEdit resolver kinds |
| 2026-07-29 | Phase 1 desktop stream chips + session nav actions on finished reply |
| 2026-07-29 | Phase 0 shipped on desktop (`eidos-navigation.js`, `navigation-resolver.js`) |
| 2026-07-29 | Initial implementation plan |
