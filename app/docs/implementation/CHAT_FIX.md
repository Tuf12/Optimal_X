# Chat System Fix — Implementation Plan

**Status:** In progress — message display fix shipped (2026-05-30); remaining work tracked in [VOICE_CHAT_STT_COMPLETION_PLAN.md](VOICE_CHAT_STT_COMPLETION_PLAN.md) Phases 3–7  
**Architecture specs:** [CHAT_UI.md](../architecture/CHAT_UI.md), [CONVERSATION_DIRECTORY.md](../architecture/CONVERSATION_DIRECTORY.md), [WIDGET_SYSTEM.md](../architecture/WIDGET_SYSTEM.md)  
**Related:** [STT_AND_CONVERSATION_ROUTING_CHECKLIST.md](STT_AND_CONVERSATION_ROUTING_CHECKLIST.md), [VOICE_SYSTEM.md](../systems/VOICE_SYSTEM.md), [DATA_MODEL.md](../architecture/DATA_MODEL.md)

Phased repair of the **conversation directory**, **in-app chat UI**, and **widget chat** surfaces. Each phase ships a verifiable improvement on its own.

---

## Goal

Today the chat stack has correct *intent* in architecture docs (`viewedScope` vs `currentScope`, explicit Move Here) but several **cache**, **lifecycle**, and **voice** wiring gaps produce user-visible bugs:

| Symptom | Likely root cause |
|---------|-------------------|
| Chat message text clipped / not fully visible | Selectable `AndroidView` capped at 55% screen height + Compose not remeasuring on text layout |
| Moved conversations still appear in the old folder | Folder list UI not refreshed; stale `ChatSessionPointers` at old scope |
| Landscape rotation “destroys” the open chat | Compose `DisposableEffect` scope resets; voice torn down on `ON_STOP`; non-persisted sheet/UI state |
| Widget chat mic feels tied to Read aloud | Global `READ_ALOUD` pref + shared `VoiceController` / TTS loop across widget service and chat UI |
| Misc. inconsistencies | Dead `moveConversationToViewedDirectory()` API; widget New Chat has no confirm; parent list query includes subfolder threads |

After this plan:

- **Move Here** updates DB scope, session pointers, and all visible directory surfaces consistently.
- **Rotation** preserves the active conversation and chat UI where the user left off (or fails gracefully with reload from DB).
- **Widget voice** and **Read aloud** are independently understandable controls with documented behavior.
- **Scope lifecycle** no longer clears chat when navigating between composables or rotating.

---

## Core invariants (do not regress)

From [CONVERSATION_DIRECTORY.md](../architecture/CONVERSATION_DIRECTORY.md):

1. **`viewedScope`** — page the user opened chat from; target for **Move Here** and **New Chat**.
2. **`currentScope`** — loaded conversation’s DB home; shown in header until Move Here.
3. **Move Here is explicit** — uses `viewedScope`, never history-browser chip selection alone.
4. **Widget general chat** — `WidgetPrefs.activeConversationId`; main-app general — `ChatSessionPointers.generalMain`.
5. **Conversation identity** — stable `conversationId` across widget and app entry points.

---

## Current gap (code audit — 2026-05-30)

| Location | Problem |
|----------|---------|
| `EidosChatViewModel.moveActiveConversationToCurrentScope()` | Updates DB + new pointer; **does not clear old** `ChatSessionPointers` / `WidgetPrefs` when scope changes |
| `ConversationListScreen` / `ConversationListViewModel` | `load()` only in `init` + delete/rename — **no refresh** after Move Here or return from chat |
| `EidosChatViewModel.moveConversationToViewedDirectory()` | Uses `_selectedHistoryDirectory` (history chips); **never called** — dead, contradicts docs |
| `DumpEditScreen` `DisposableEffect` | `onDispose { setGeneralScope() }` runs when leaving DumpEdit for `EIDOS_CHAT` or on rotation — **wipes in-memory chat** and restores general pointer |
| `EidosChatScreen` / `WidgetChatActivity` | `ON_STOP` → `voiceController.stopSession()` — kills mic/TTS on every rotation |
| `WidgetChatActivity` | No `configChanges`; all `remember { }` UI state lost on rotation |
| `WebPanel` | ~~`eidosSheetOpen` in plain `remember`~~ — **fixed:** `webPanelEidosSheetOpen` in `EidosChatViewModel` (pager breaks `rememberSaveable`) |
| `readAloud` / `SettingsKeys.READ_ALOUD` | Single global pref drives widget service TTS loop, chat auto-TTS, and Read aloud Switch |
| `ConversationListScreen` parent scope | Uses `getRecentByParentIncludingSubfolders` — subfolder threads listed under parent (by design, but confusing next to Move Here) |
| `WidgetChatActivity` New Chat | Single tap, no confirm (main app has confirm dialog) |

**Reference patterns to mirror:**

- **Pointer persist/clear:** `persistConversationPointerIfNeeded()` + `clearStoredPointersForNewChat()` in `EidosChatViewModel.kt`
- **Scope on enter only:** prefer setting scope when opening a surface, not resetting sibling scopes on dispose (DumpEdit pattern is the anti-pattern to fix)
- **Inbox resync:** `QuickNotesInboxScreen` periodic DB resync — model for folder list refresh

---

## Decisions locked

| Topic | Decision |
|-------|----------|
| Move Here API | **Keep** `moveActiveConversationToCurrentScope()` only; **remove or repurpose** `moveConversationToViewedDirectory()` |
| Pointer cleanup on move | On move: **clear old scope pointer(s)**, then persist at new scope; include widget `WidgetPrefs` when leaving/entering general |
| Folder list refresh | Reload on **Lifecycle ON_RESUME** and when popping back from `EIDOS_CHAT` (navigation callback or shared event) |
| DumpEdit scope lifecycle | **Remove** `onDispose { setGeneralScope() }`; scope owned by navigation entry (`setDumpEditScope` on enter) — chat route must not trigger scope wipe |
| Rotation — voice | Call `stopSession()` on `ON_STOP` **only when** `!activity.isChangingConfigurations` |
| Rotation — widget activity | Add `configChanges` for orientation/size **or** `rememberSaveable` for sheet flags + scroll anchors |
| Read aloud vs mic | **Split prefs:** keep `READ_ALOUD` for chat UI auto-TTS; widget hands-free loop uses **`WIDGET_VOICE_HANDS_FREE`** (or equivalent) — document in `WIDGET_SYSTEM.md` |
| Parent Chats folder list | **Phase 6 decision:** either stay inclusive (subfolder + parent) with clear source labels, or switch to `getAllByParentFolder` for parent-only rows — pick one and update `CHAT_UI.md` |
| New Chat (widget) | Add confirm dialog to match main app **or** document intentional single-tap — default: **add confirm** |
| Testing | JVM tests for pointer cleanup + move scope fields; manual matrix for rotate + move (below) |

---

## Phase checklist

### Phase 0 — Message display & scroll (shipped 2026-05-30) ✅

Fix clipped / partially hidden chat bubbles before directory and lifecycle work.

| ID | Task | Status |
|----|------|--------|
| 0e | `MarkdownRichText` — remove default 55% height cap; sync Compose height to measured `SelectableTextScrollContainer` after text updates | ✅ |
| 0f | `EidosChatScreen` / `WidgetChatActivity` / `WebPanel` — follow stream preview + bubble height growth when user is near bottom | ✅ |

**Files:** `MarkdownRichText.kt`, `EidosChatScreen.kt`, `WidgetChatActivity.kt`, `WebPanel.kt`

**Acceptance:** Long assistant replies show full text (scroll the list, not a hidden clip). Streaming Kimi preview stays in view while sending.

---

### Phase 0 — Doc pass & test matrix ☐

Lock behavior on paper before Kotlin changes.

| ID | Task | Status |
|----|------|--------|
| 0a | Add **Known issues / fix tracker** subsection to `CHAT_UI.md` pointing at this plan | ☐ |
| 0b | `CONVERSATION_DIRECTORY.md` — document pointer cleanup on Move Here; folder list must refresh | ☐ |
| 0c | `WIDGET_SYSTEM.md` — split Read aloud vs widget hands-free mic loop (after Phase 5 pref name chosen) | ☐ |
| 0d | Manual test matrix (below) — copy into QA notes or instrumented test backlog | ☐ |

**Manual test matrix (run after each phase):**

| # | Steps | Expected |
|---|--------|----------|
| M1 | Parent A Chats → open conv → Move Here from Parent B | Conv appears in B list only; absent from A list after back |
| M2 | Subfolder X → move to General via Move Here | Gone from X and parent-inclusive list |
| M3 | Open Eidos from DumpEdit → send message → back/rotate | Same DumpEdit thread, not general |
| M4 | Widget Chat → rotate device | Messages + scroll reasonable; mic can restart cleanly |
| M5 | Toggle Read aloud in widget chat → use home widget Mic | Hands-free behavior independent unless hands-free pref ON |
| M6 | Editor Web tab → open Eidos sheet → rotate | Sheet state recoverable or reloads same web thread |

**Acceptance:** Docs and this plan agree on `viewedScope` / Move Here / pointer rules.

---

### Phase 1 — Move Here & session pointers ☐

Fix “conversation stuck in old directory” at the persistence layer.

| ID | Task | Status |
|----|------|--------|
| 1a | Add `clearConversationPointerForScope(conversation: Conversation)` (or clear by previous scope before update) in `EidosChatViewModel` | ☐ |
| 1b | In `moveActiveConversationToCurrentScope()`: capture **pre-move** scope from loaded conversation; clear old pointer; update DB; `loadConversationInternal`; persist new pointer | ☐ |
| 1c | When move enters/leaves **general** on widget surface, sync `WidgetPrefs` | ☐ |
| 1d | `requestRetrievalSync` reason string includes old + new scope for index rebuild | ☐ |
| 1e | JVM test: move parent→parent updates `scopeType`, `parentFolderId`, nulls `subfolderId`; old parent pointer cleared | ☐ |

**Files:** `EidosChatViewModel.kt`, `ChatSessionPointers.kt` (optional helper), `WidgetPrefs.kt`

**Acceptance:** M1, M2 pass; stale pointer not returned from `getParentFolder` / `getSubfolder` for old location.

---

### Phase 2 — Directory list UI refresh ☐

Fix folder browser showing stale rows after move.

| ID | Task | Status |
|----|------|--------|
| 2a | `ConversationListViewModel.load()` callable from composable on `ON_RESUME` | ☐ |
| 2b | `ConversationListScreen` — `LifecycleEventObserver` or `DisposableEffect` → reload on resume | ☐ |
| 2c | Optional: `EidosChatViewModel` exposes `conversationDirectoryRevision` flow incremented on move/delete; list collects and reloads | ☐ |
| 2d | After Move Here from chat, increment revision or call list refresh via navigation (`popBackStack` to list triggers resume reload) | ☐ |
| 2e | `EidosSystemScreens` chat sections (if any cached lists) — same refresh pattern | ☐ |

**Files:** `ConversationListScreen.kt`, `ConversationListViewModel.kt`, optionally `EidosChatViewModel.kt`, `AppNavigation.kt`

**Acceptance:** M1 — returning to folder list without killing app shows updated membership.

---

### Phase 3 — Scope lifecycle (no accidental chat wipe) ☐

Fix navigation and rotation clearing active thread.

| ID | Task | Status |
|----|------|--------|
| 3a | **Remove** `onDispose { setGeneralScope() }` from `DumpEditScreen` | ☐ |
| 3b | Audit other screens for dispose→`setGeneralScope()` / `applyScope` resets; fix Panel Gallery/Runner when those land (use enter-only scope setters) | ☐ |
| 3c | Opening `Routes.EIDOS_CHAT` must **not** depend on underlying screen staying composed for scope | ☐ |
| 3d | Document scope ownership: last **explicit** `setXScope()` from navigation wins until user navigates to a different surface | ☐ |
| 3e | Review `EditorScreen` pager `LaunchedEffect` — guard `setSubfolderScope` so rotation/restart does not clear messages when `activeConversationId != null` and scope unchanged | ☐ |

**Files:** `DumpEditScreen.kt`, `EditorScreen.kt`, `EidosChatViewModel.kt` (`applyScope` alreadyOnPage path)

**Acceptance:** M3 pass; open chat from DumpEdit → conversation survives back stack navigation.

---

### Phase 4 — Rotation & configuration survival ☐

Fix landscape “destroying” chat session UX.

| ID | Task | Status |
|----|------|--------|
| 4a | `EidosChatScreen` + `WidgetChatActivity`: skip `voiceController.stopSession()` when `LocalContext.findActivity()?.isChangingConfigurations == true` | ☐ |
| 4b | `WidgetChatActivity` manifest: `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` **or** save UI state with `rememberSaveable` (sheet open, scroll anchor ids) | ☐ |
| 4c | `WebPanel`: `webPanelEidosSheetOpen` in `EidosChatViewModel` (+ skip voice `stopSession` on `isChangingConfigurations`) | ✅ |
| 4d | Optional MainActivity `configChanges` if full-app rotate testing shows NavHost glitches | ☐ |
| 4e | After rotate, verify `EidosChatViewModel` messages unchanged (ViewModel survives); document if process death requires DB reload | ☐ |

**Files:** `EidosChatScreen.kt`, `WidgetChatActivity.kt`, `WebPanel.kt`, `AndroidManifest.xml`

**Acceptance:** M4, M6 pass; no empty message list after rotate while staying on same route.

---

### Phase 5 — Widget voice vs Read aloud decoupling ☐

Fix mic / read-aloud feeling like one control.

| ID | Task | Status |
|----|------|--------|
| 5a | Add `SettingsKeys.WIDGET_VOICE_HANDS_FREE` (default `false`) — when true, `WidgetVoiceService` TTS + `handMicBackToUser()` loop | ☐ |
| 5b | `WidgetVoiceService`: use hands-free pref for post-reply TTS loop; **not** `READ_ALOUD` | ☐ |
| 5c | `WidgetChatActivity` Read aloud Switch — keep `READ_ALOUD` for in-UI auto-TTS on new assistant messages only | ☐ |
| 5d | Settings screen: optional sub-label clarifying widget vs chat read-aloud (or defer to `WIDGET_SYSTEM.md` only) | ☐ |
| 5e | Ensure input-row **Mic** only starts STT via `VoiceController`; no side effect on Switch state | ☐ |
| 5f | Review `thenListen = true` in chat LaunchedEffect — only when Read aloud ON, not when user manually tapped mic | ☐ |

**Files:** `WidgetVoiceService.kt`, `WidgetChatActivity.kt`, `EidosChatScreen.kt`, `SettingsKeys.kt`, `WIDGET_SYSTEM.md`

**Acceptance:** M5 pass; toggling Read aloud in chat UI does not change home widget mic loop unless hands-free pref set.

---

### Phase 6 — API cleanup & directory semantics ☐

Reduce confusion and align list queries with docs.

| ID | Task | Status |
|----|------|--------|
| 6a | **Delete** `moveConversationToViewedDirectory()` or rename + wire only if history-browser “move to browsed folder” becomes a product feature (default: delete) | ☐ |
| 6b | Decide parent **Chats** list query: inclusive vs parent-only; update `ConversationListScreen` + `CHAT_UI.md` | ☐ |
| 6c | `WidgetChatActivity` — New Chat confirm dialog (match `EidosChatScreen`) | ☐ |
| 6d | `openConversationBrowser()` — ensure history directory chips sync with `viewedScope` after move | ☐ |
| 6e | Remove or implement `EXTRA_AUTO_START_MIC` (currently unused from widget provider) | ☐ |

**Files:** `EidosChatViewModel.kt`, `ConversationListScreen.kt`, `WidgetChatActivity.kt`, `OptimalXWidget.kt`, docs

**Acceptance:** No dead move API; product owner sign-off on parent folder list behavior.

---

### Phase 7 — Polish & regression guard ☐

| ID | Task | Status |
|----|------|--------|
| 7a | JVM tests: `refreshSummaries()` after move excludes conv from old parent chip query | ☐ |
| 7b | JVM tests: `scopeToDirectory` / history labels after Quick Notes + Workshop moves | ☐ |
| 7c | Update [STT_AND_CONVERSATION_ROUTING_CHECKLIST.md](STT_AND_CONVERSATION_ROUTING_CHECKLIST.md) Phase 3 bind-transcript items if touched | ☐ |
| 7d | Cursor journal / release note entry when Phase 1–4 complete | ☐ |

**Acceptance:** CI green; manual matrix M1–M6 signed off.

---

## Implementation order (recommended)

```
Phase 0 (docs)
    ↓
Phase 1 (pointers + move) ──→ Phase 2 (list refresh)
    ↓
Phase 3 (scope lifecycle)
    ↓
Phase 4 (rotation)     Phase 5 (voice decouple)  ← can parallelize after Phase 3
    ↓
Phase 6 (cleanup) → Phase 7 (tests + ship)
```

**Minimum viable fix (one PR):** Phase 1 + Phase 2 + Phase 3a (pointer cleanup, list reload, DumpEdit dispose).

---

## Files touched (summary)

| Phase | Primary files |
|-------|----------------|
| 1 | `EidosChatViewModel.kt`, `ChatSessionPointers.kt`, `WidgetPrefs.kt` |
| 2 | `ConversationListScreen.kt`, `ConversationListViewModel.kt` |
| 3 | `DumpEditScreen.kt`, `EditorScreen.kt`, `EidosChatViewModel.kt` |
| 4 | `EidosChatScreen.kt`, `WidgetChatActivity.kt`, `WebPanel.kt`, `AndroidManifest.xml` |
| 5 | `WidgetVoiceService.kt`, `SettingsKeys.kt`, `WidgetChatActivity.kt`, `EidosChatScreen.kt` |
| 6 | `EidosChatViewModel.kt`, `ConversationListScreen.kt`, `WidgetChatActivity.kt`, architecture docs |
| 7 | `app/src/test/...`, checklists |

---

## Out of scope (follow-ups)

- [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md) — panel gallery/runner scopes (separate track; apply Phase 3 dispose lessons there).
- Full `VoiceSessionDescriptor` immutability ([STT checklist](STT_AND_CONVERSATION_ROUTING_CHECKLIST.md) Phase 1).
- Conversation search / semantic index UX after move (retrieval sync already requested; UI unchanged).
- Tablet dual-pane chat + directory browser (future shell).

---

## Changelog

| Date | Phase | Notes |
|------|-------|-------|
| 2026-05-30 | 0e–0f | Message bubble height sync + auto-scroll during streaming (see `MarkdownRichText.kt`). |
| 2026-05-30 | — | Plan created from chat system audit (directory move, rotation, widget voice). |
