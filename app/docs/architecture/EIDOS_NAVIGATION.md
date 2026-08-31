# EIDOS_NAVIGATION.md

## Purpose

When Eidos creates folders, writes notes, edits workshop files, or otherwise changes app state, the user should be able to **jump directly to the affected place** from chat — without hunting through the directory.

This document defines a **shared navigation contract** for OptimalX (Android + Desktop). It is intentionally extensible: new tools and surfaces add new `kind` values and resolver branches over time; chat UI stays a thin consumer.

**Implementation plan:** [EIDOS_NAVIGATION_IMPLEMENTATION_PLAN.md](../implementation/EIDOS_NAVIGATION_IMPLEMENTATION_PLAN.md)  
**Related:** [CHAT_UI.md](CHAT_UI.md), [DIFF_REVIEW.md](DIFF_REVIEW.md), [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md), [DUMPEDIT.md](DUMPEDIT.md), [DATA_MODEL.md](DATA_MODEL.md)

---

## Design goals

| Goal | Rationale |
|------|-----------|
| **Deterministic** | Navigation targets come from tool execution metadata, not from hoping the model formats a correct link |
| **Extensible** | New tools register targets in one resolver table; URI scheme and UI chips share the same payload |
| **Cross-platform** | Same `EidosNavigationTarget` JSON and `optimalx://` URI grammar on mobile and desktop |
| **Non-destructive** | Navigation respects unsaved-editor guards (`confirmLeaveUnsaved` / Compose equivalent) |
| **History-safe** | Targets persist on the assistant message so “Open” still works after reload or sync |

---

## Non-goals (v1)

- Deep links from **outside** the app (browser, notification intents) — same URI grammar may be reused later, but v1 is in-app chat only
- Scrolling to a **line range** inside a note or workshop file (future `fragment` on note targets)
- Auto-navigating without a user click (no “teleport on tool_end”)
- Replacing search, pins, or the conversation directory

---

## User-visible surfaces

Two complementary affordances share one resolver:

### 1. Structured action chips (primary)

Shown in the assistant bubble **activity / tool log** (and optionally inline under the reply). Each successful mutating tool can render:

```text
Open note · Meeting notes
Open folder · Jobs
Open workshop · Snake Game
```

Built from server-emitted `navigation` metadata on `tool_end` stream events and persisted on the message.

### 2. Markdown links in assistant text (secondary)

The model may echo human-readable links using the same URI scheme:

```markdown
Saved to [Meeting notes](optimalx://note/42).
```

Chat markdown rendering intercepts `optimalx://` clicks and routes through the same resolver. **Do not rely on the model alone** — chips are authoritative.

---

## Navigation target contract

Canonical shape (JSON). All producers and consumers use this; URIs are a serialized view.

```json
{
  "kind": "note",
  "label": "Meeting notes",
  "subfolderId": 42,
  "parentFolderId": 7,
  "parentName": "Jobs",
  "path": null,
  "line": null
}
```

### Fields

| Field | Required | Notes |
|-------|----------|-------|
| `kind` | yes | Discriminator — see table below |
| `label` | yes | User-visible title (subfolder name, parent name, file name, etc.) |
| `subfolderId` | per kind | Note + workshop projects are keyed by subfolder id (1:1 with `notes`) |
| `parentFolderId` | optional | Speeds navigation; resolver can look up if missing |
| `parentName` | optional | Display only; not trusted for routing |
| `path` | workshop file tools | Relative path inside workshop tree |
| `line` | future | 1-based line hint for editor scroll |

### `kind` registry (initial)

| `kind` | Opens | Key ids | Source tools (initial) |
|--------|-------|---------|-------------------------|
| `note` | Note editor | `subfolderId` | `write_note`, `edit_note_section`, `read_note` (read-only chip optional) |
| `parent` | Parent subfolder list | `parentFolderId` | `create_parent_folder`, `rename_parent_folder` |
| `subfolder` | Note editor (new subfolder) | `subfolderId` | `create_subfolder` |
| `quick_notes` | Quick Notes day inbox | `subfolderId` | `write_quick_note` |
| `dump_edit` | DumpEdit buffer | — | `write_dump_edit`, `read_dump_edit` |
| `workshop` | Workshop editor (project) | `subfolderId` | workshop create / phase tools that return project id |
| `workshop_file` | Workshop editor + file focus | `subfolderId` + `path` | `workshop_write_file`, `workshop_replace_string`, `workshop_edit_file_section`, `workshop_read_file` |
| `panel_runner` | Panel Runner | `subfolderId` | future — publish / open runtime |
| `trash` | Trash screen | — | future — restore flows |

**Adding a kind:** extend the registry here, add a row to `resolveNavigation()` in the tool executor, add a branch in `navigateFromEidosTarget()`, add a chip renderer case, add acceptance tests.

---

## URI scheme (`optimalx://`)

Human- and model-friendly serialization of `EidosNavigationTarget`.

| URI | Maps to |
|-----|---------|
| `optimalx://note/{subfolderId}` | `{ kind: "note", subfolderId }` |
| `optimalx://parent/{parentFolderId}` | `{ kind: "parent", parentFolderId }` |
| `optimalx://workshop/{subfolderId}` | `{ kind: "workshop", subfolderId }` |
| `optimalx://workshop/{subfolderId}/{path}` | `{ kind: "workshop_file", subfolderId, path }` (path URL-encoded) |
| `optimalx://dumpedit` | `{ kind: "dump_edit" }` |
| `optimalx://quick-notes/{subfolderId}` | `{ kind: "quick_notes", subfolderId }` |

**Parsing rules**

- Unknown host or missing ids → show toast / inline error; no-op
- Deleted or `ai_blind` note → “Note unavailable” (same as opening from search)
- `workshop_file` with missing file → open project root; optional toast

**Security (in-app v1)**

- Only handle `optimalx://` from chat message bodies and official navigation chips
- Do not pass arbitrary URLs from tool results into `window.open`
- DOMPurify / Compose link annotation allowlist includes `optimalx` scheme only in chat renderer

---

## Data flow

```text
┌─────────────────────────────────────────────────────────────────────┐
│ Tool execution (NodeToolExecutor / RoomToolExecutor)                │
│   write_note, create_subfolder, workshop_write_file, …              │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ success + ids from DB
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ resolveNavigation(toolName, args, result) → EidosNavigationTarget?  │
│   (shared logic; per-platform copy or shared doc-tested table)      │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
          ┌─────────────────────┴─────────────────────┐
          ▼                                           ▼
┌──────────────────────┐                    ┌──────────────────────────┐
│ Stream: tool_end     │                    │ Persist on assistant msg │
│   + navigation       │                    │   metadata_json (Tier 2) │
└──────────┬───────────┘                    └────────────┬─────────────┘
           │                                             │
           ▼                                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Chat renderer                                                        │
│   • Activity panel chips (live + history)                            │
│   • optional markdown link delegate (optimalx://)                    │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ user click
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ navigateFromEidosTarget(target)                                      │
│   confirm leave unsaved → resolve names → App.open* / NavController  │
└─────────────────────────────────────────────────────────────────────┘
```

### Relationship to existing behavior

**Desktop today** (`eidos-chat.js` `tool_end`):

- Workshop file writes refresh workshop editor and Diff Review — **side effect**, not user navigation
- `write_note` / `edit_note_section` reload the note editor **only when already on that subfolder**

**After this feature:** same refresh hooks remain; navigation chips are **additive** and user-initiated.

**Search precedent (desktop):** `search.js` `openTextResult` already sets `parentId` / `parentName` and calls `openEditor` / `openWorkshopEditor`. The navigation resolver should reuse that branching (Quick Notes, Panel Workshop parent, read-only Eidos system folders).

---

## Persistence model

Tool names alone are stored today in `assistant_reasoning_content` (`• write_note`). That is not enough to rebuild links after reload.

### Recommended: `navigation_targets` on assistant messages

Append-only assistant rows gain optional metadata:

| Column | Type | Notes |
|--------|------|-------|
| `navigation_targets_json` | TEXT nullable | JSON array of `EidosNavigationTarget` for that turn |

**Why assistant row, not per-tool rows:** one assistant message follows a full tool loop; chips belong with the visible reply. Multiple tools in one turn → multiple targets in the array (dedupe by `kind` + ids + `path`).

**Sync:** Tier 2 field on `chat_messages` — same as `assistant_reasoning_content`. Both repos migrate in lockstep.

**Fallback without migration (Phase 1 only):** stream-time chips from `tool_end`; history view shows tool names without links until persistence ships.

### Alternative considered: embed in reasoning text

Encoding JSON after the `---\nTools:\n` block was rejected — fragile parsing, breaks mobile/desktop parity, hard to extend.

---

## Platform resolver map

| `kind` | Desktop | Android |
|--------|---------|---------|
| `note` | `App.openEditor(subfolderId, label)` after parent context | `Routes.editor(subfolderId)` |
| `parent` | `App.openParent(parentFolderId, label)` | `Routes.subfolders(parentFolderId)` |
| `subfolder` / `quick_notes` | `openEditor` or Quick Notes inbox helpers | `Routes.editor` / `Routes.quickNotesInbox` |
| `dump_edit` | `App.navigatePin('dumpedit')` + `loadDumpEdit` | DumpEdit route |
| `workshop` | `App.openWorkshopEditor({ id: subfolderId })` | `Routes.workshopEditor(subfolderId)` |
| `workshop_file` | `openWorkshopEditor` + select file in tree | workshop editor + file tab focus |

Shared helper (each platform):

```text
navigateFromEidosTarget(target):
  if !confirmLeaveUnsaved(): return
  row = lookupSubfolder(target.subfolderId)  // when needed
  branch on kind + parent name (Workshop, Quick Notes, system folders)
  open appropriate surface
```

---

## Prompt layer (optional, Phase 3)

Light instruction in scope-specific prompts:

> When you create or update user-visible content, you may include one markdown link using `optimalx://` so the user can open it. Prefer the note/folder name as link text. Navigation chips are shown automatically; links are for readability only.

No tool API changes required for prompt-only echo.

---

## Privacy and edge cases

| Case | Behavior |
|------|----------|
| `ai_blind` note | No chip; link click shows “Note unavailable” |
| `ai_locked` note | Chip allowed on successful write; opening editor is user action |
| Queued diff (pending change) | Chip opens editor; Diff Review banner still applies |
| User already on target | `openEditor` no-op when same `subfolderId` (desktop already guards) |
| Trash / deleted folder | Resolver fails gracefully; offer remove stale pin pattern |
| Cross-device sync delay | Target ids are local SQLite ids; sync must round-trip before link works on other device (same as today for all folder opens) |

---

## Testing strategy

| Layer | Tests |
|-------|-------|
| `resolveNavigation` | Unit table per tool name (desktop Jest; Android JVM) |
| URI parse/format | Round-trip all kinds |
| Resolver integration | Mock DB rows → expect correct `open*` call |
| UI | Manual: tool creates note → chip visible → click lands in editor |
| Sync | Assistant message with `navigation_targets_json` survives Tier 2 push/pull |

---

## File map (implementation reference)

| Area | Desktop | Android |
|------|---------|---------|
| Tool → target | `electron/eidos/navigation-resolver.js` (new) | `EidosNavigationResolver.kt` (new) |
| Stream payload | `electron/eidos/chat-service.js` | `EidosChatSendWorker` stream events |
| Chat UI chips | `renderer/js/chat-message-bubble.js`, `eidos-chat.js` | `ChatMessageBubble.kt` / tool activity composable |
| Click routing | `renderer/js/eidos-navigation.js` (new) | `EidosNavigation.kt` + `AppNavigation` hooks |
| Markdown links | `renderer/js/note-content-codec.js` (DOMPurify + delegate) | Chat markdown link handler |
| Schema | `structure.md`, migration | `AppDatabase` migration |

---

## Changelog

| Date | Change |
|------|--------|
| 2026-07-29 | Phase 0 desktop modules: `eidos-navigation.js`, `navigation-resolver.js` |
| 2026-07-29 | Initial architecture spec |
