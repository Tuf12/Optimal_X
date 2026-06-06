# Voice, STT & Chat — completion plan

**Status:** In progress — Phases 0–1 shipped (2026-05-30); Phases 2–9 not started  
**Supersedes / completes:** [STT_WHISPER_WIDGET_PLAN.md](STT_WHISPER_WIDGET_PLAN.md) (core shipped), [CHAT_FIX.md](CHAT_FIX.md) (Phases 0e–0f shipped)  
**Architecture specs:** [VOICE_SYSTEM.md](../systems/VOICE_SYSTEM.md), [CHAT_UI.md](../architecture/CHAT_UI.md), [CONVERSATION_DIRECTORY.md](../architecture/CONVERSATION_DIRECTORY.md), [WIDGET_SYSTEM.md](../systems/WIDGET_SYSTEM.md)  
**Related:** [STT_AND_CONVERSATION_ROUTING_CHECKLIST.md](STT_AND_CONVERSATION_ROUTING_CHECKLIST.md)

Phased completion of **remaining STT hardening**, **read-aloud / mic-passback controls**, **chat directory & lifecycle fixes**, **rotation survival**, **tests**, and **doc alignment**. Each phase ships a verifiable improvement on its own.

---

## Goal

The Whisper + widget Quick Ask rollout (2026-05-31) landed the main routing. What remains causes user-visible gaps:

| Symptom | Root cause |
|---------|------------|
| Read aloud always re-opens the mic after TTS | `thenListen = true` whenever `READ_ALOUD` is on — no way to keep TTS without mic pass-back |
| Whisper API failure leaves user with empty/partial input | No Google retry; no user-visible error |
| Moved conversations still in old folder list | Stale pointers + list not refreshed |
| Chat wiped when leaving DumpEdit or rotating | Scope dispose + `ON_STOP` kills voice unconditionally |
| Widget Ask Eidos TTS tied to global Read aloud | `WidgetVoiceService` reads `READ_ALOUD` for TTS + `handMicBackToUser()` |
| Docs contradict shipped code | `VOICE_SYSTEM.md` body still describes Sherpa/VAD; STT checklist stale |

After this plan:

- **Read aloud** and **mic pass-back after reply** are **independent** toggles (both can be on; either can be off alone).
- **Hands-free loop** is documented honestly: user **taps Send** to commit speech and send — not silence-driven auto-send.
- **Whisper** fails gracefully with optional **Google retry** and visible feedback.
- **Move Here**, **scope lifecycle**, and **rotation** behave per architecture docs.
- **Tests + manual matrix** signed off.

---

## Decisions locked

| Topic | Decision |
|-------|----------|
| Read aloud | **`READ_ALOUD`** — auto TTS on new assistant messages (chat UIs + existing global pref). Unchanged semantics. |
| Mic pass-back after TTS | New **`READ_ALOUD_MIC_PASSBACK`** (default **`true`**) — when `READ_ALOUD` is on, controls whether TTS ends with `thenListen` / widget `handMicBackToUser()`. When off: speak reply only; mic stays idle until user taps mic. |
| Hands-free UX | **Not** silence auto-send. After mic pass-back, user speaks, then **taps Send** (or pause mic) to transcribe + send. Document in UI helper text. |
| Widget home mic loop | **`WIDGET_VOICE_HANDS_FREE`** (default `false`) — Quick Ask / Quick Notes post-reply TTS + mic loop in `WidgetVoiceService`. **Not** tied to `READ_ALOUD`. |
| Widget mic pass-back | When widget hands-free TTS runs, respect **`READ_ALOUD_MIC_PASSBACK`**: TTS without re-opening mic if pass-back off. |
| Whisper failure | On API/network error: show snackbar/toast; **retry once** with `GoogleSpeechToTextEngine` if recording buffer non-empty (chat/notes `VoiceController` only). |
| Rotation — voice | `stopSession()` on `ON_STOP` **only when** `!activity.isChangingConfigurations`. |
| Move Here API | Keep `moveActiveConversationToCurrentScope()` only; **delete** dead `moveConversationToViewedDirectory()`. |
| Mic pass-back UI placement | In-chat: **⋮ overflow → Pass-back mic** when Read aloud is on ([CHAT_UI_REDESIGN_PLAN](../archive/CHAT_UI_REDESIGN_PLAN.md) — no labeled switches in top bar). Settings Voice section: same toggle + helper text (global default). |
| Testing | JVM tests for pointer cleanup, async commit, Whisper client mock; manual matrix M1–M10 below. |

---

## Voice behavior matrix (target)

| Surface | Read aloud (`READ_ALOUD`) | Mic pass-back (`READ_ALOUD_MIC_PASSBACK`) | Widget hands-free (`WIDGET_VOICE_HANDS_FREE`) |
|---------|---------------------------|-------------------------------------------|-----------------------------------------------|
| `EidosChatScreen` | Auto TTS on new assistant msg | After TTS → `startListening` if both prefs on | N/A |
| `WebPanel` Eidos composer | Same | Same | N/A |
| `WidgetChatActivity` | Same | Same | N/A |
| `WidgetVoiceService` Quick Ask / Quick Notes | **Does not** read `READ_ALOUD` | After hands-free TTS → `handMicBackToUser()` if pass-back on | Enables TTS + optional mic loop |
| Notes read-aloud | Separate note TTS toolbar | N/A (notes mic is manual tap only) | N/A |

---

## Phase checklist

### Phase 0 — Doc alignment (pre-flight) ✅

Lock behavior before Kotlin changes.

| ID | Task | Status |
|----|------|--------|
| 0a | Add **Known issues / tracker** subsection to `CHAT_UI.md` pointing at this plan | ✅ |
| 0b | `CONVERSATION_DIRECTORY.md` — pointer cleanup on Move Here; list refresh expectation | ✅ |
| 0c | `WIDGET_SYSTEM.md` — Read aloud vs mic pass-back vs widget hands-free (three prefs) | ✅ |
| 0d | Copy manual test matrix (below) into QA notes | ✅ → [VOICE_CHAT_STT_QA.md](VOICE_CHAT_STT_QA.md) |

**Acceptance:** Architecture docs agree with the behavior matrix above.

---

### Phase 1 — Read aloud vs mic pass-back split ✅

**Priority:** addresses explicit product request — TTS without forced mic reopen.

| ID | Task | Status |
|----|------|--------|
| 1a | Add `SettingsKeys.READ_ALOUD_MIC_PASSBACK` + `SettingsDefaults.READ_ALOUD_MIC_PASSBACK = true` in `SettingsPreferences.kt` | ✅ |
| 1b | `SettingsViewModel` + `SettingsScreen` Voice section: toggle **“Re-open mic after read aloud”** with helper: *Speak reply aloud; when on, mic opens after — tap Send to send. When off, mic stays idle.* | ✅ |
| 1c | `EidosChatViewModel` (or shared flow): expose `readAloudMicPassback` from DataStore for chat surfaces | ✅ |
| 1d | `EidosChatScreen`, `WebPanel`, `WidgetChatActivity`: `thenListen = readAloud && readAloudMicPassback` in assistant-message `LaunchedEffect` | ✅ |
| 1e | In-chat UI: pass-back control in **⋮ overflow** when Read aloud on (redesign replaced labeled top-bar switches; see archived [CHAT_UI_REDESIGN_PLAN](../archive/CHAT_UI_REDESIGN_PLAN.md)) | ✅ |
| 1f | `WidgetVoiceService`: stop using `READ_ALOUD` for Quick Ask / Quick Notes TTS; use `WIDGET_VOICE_HANDS_FREE` instead | ✅ |
| 1g | `WidgetVoiceService`: `handMicBackToUser()` only when `WIDGET_VOICE_HANDS_FREE && READ_ALOUD_MIC_PASSBACK` (after TTS) | ✅ |
| 1h | Settings: add **Widget hands-free** toggle for `WIDGET_VOICE_HANDS_FREE` (sub-label: home widget only) | ✅ |
| 1i | Audit manual “Read selection aloud” paths (`thenListen = false` on user-initiated replay) — ensure unchanged | ✅ |

**Files:** `SettingsPreferences.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `EidosChatViewModel.kt`, `EidosChatScreen.kt`, `WebPanel.kt`, `WidgetChatActivity.kt`, `WidgetVoiceService.kt`, `WIDGET_SYSTEM.md`

**Acceptance:**

| # | Steps | Expected |
|---|--------|----------|
| V1 | Read aloud **on**, pass-back **on** → assistant replies | TTS plays → mic opens → user speaks → **Send** transcribes + sends |
| V2 | Read aloud **on**, pass-back **off** → assistant replies | TTS plays → mic **does not** open |
| V3 | Read aloud **off** | No auto TTS; pass-back control hidden/disabled |
| V4 | Widget hands-free **on**, pass-back **off**, Quick Ask | TTS may play; mic **does not** auto-reopen |
| V5 | Widget hands-free **off** | Quick Ask behaves as today (no post-reply TTS loop) |

---

### Phase 2 — STT robustness & tests ☐

Complete gaps from [STT_WHISPER_WIDGET_PLAN.md](STT_WHISPER_WIDGET_PLAN.md) Phase 1c / Phase 3.

| ID | Task | Status |
|----|------|--------|
| 2a | `VoiceController`: on Whisper `onError` during commit, if buffer had audio → destroy Whisper engine → recreate Google → show snackbar “Whisper failed — used Google speech” (once per session) | ☐ |
| 2b | `WhisperApiSpeechToTextEngine`: expose whether last stop had capturable audio (for fallback decision) | ☐ |
| 2c | User-visible errors: snackbar on Whisper/network failure; Settings privacy line: *Audio sent to OpenAI when Whisper mic is on* | ☐ |
| 2d | JVM test: `WhisperTranscriptionClient` with mock OkHttp (parse `{ "text": "..." }`) | ☐ |
| 2e | JVM test: fake `SttEngine` — `stopListeningAndCommit` delivers on terminal callback, not synchronously | ☐ |
| 2f | Remove or fully migrate legacy `STT_BACKEND` DataStore key (keep migration in `OptimalXApplication`, document deprecation) | ☐ |

**Files:** `VoiceController.kt`, `WhisperApiSpeechToTextEngine.kt`, `WhisperTranscriptionClient.kt`, `SettingsScreen.kt`, `app/src/test/...`

**Acceptance:**

| # | Steps | Expected |
|---|--------|----------|
| S1 | Chat mic, Whisper on, airplane mode → commit | Snackbar; empty or Google retry if implemented |
| S2 | Chat mic, Whisper on, valid key | Record → Transcribing → input filled |
| S3 | `:app:testDebugUnitTest` | New STT tests green |

---

### Phase 3 — Move Here & session pointers ☐

From [CHAT_FIX.md](CHAT_FIX.md) Phase 1.

| ID | Task | Status |
|----|------|--------|
| 3a | `clearConversationPointerForScope()` (or equivalent) in `EidosChatViewModel` | ☐ |
| 3b | `moveActiveConversationToCurrentScope()`: capture pre-move scope → clear old pointer → DB update → reload → persist new pointer | ☐ |
| 3c | Sync `WidgetPrefs` when move enters/leaves **general** on widget surface | ☐ |
| 3d | `requestRetrievalSync` reason includes old + new scope | ☐ |
| 3e | JVM test: move parent→parent clears old pointer, updates scope fields | ☐ |

**Files:** `EidosChatViewModel.kt`, `ChatSessionPointers.kt`, `WidgetPrefs.kt`

**Acceptance:** M1, M2 (below) pass at persistence layer.

---

### Phase 4 — Directory list UI refresh ☐

From [CHAT_FIX.md](CHAT_FIX.md) Phase 2.

| ID | Task | Status |
|----|------|--------|
| 4a | `ConversationListViewModel.load()` callable on `ON_RESUME` | ☐ |
| 4b | `ConversationListScreen` — lifecycle observer → reload on resume | ☐ |
| 4c | Optional: `conversationDirectoryRevision` flow on move/delete | ☐ |
| 4d | After Move Here, list refresh via resume or revision bump | ☐ |
| 4e | `EidosSystemScreens` cached chat lists — same pattern | ☐ |

**Acceptance:** M1 — returning to folder list shows updated membership without app restart.

---

### Phase 5 — Scope lifecycle ☐

From [CHAT_FIX.md](CHAT_FIX.md) Phase 3.

| ID | Task | Status |
|----|------|--------|
| 5a | **Remove** `onDispose { setGeneralScope() }` from `DumpEditScreen` | ☐ |
| 5b | Audit other dispose→scope resets; enter-only scope setters | ☐ |
| 5c | `Routes.EIDOS_CHAT` must not depend on underlying screen staying composed | ☐ |
| 5d | Document scope ownership in `CONVERSATION_DIRECTORY.md` | ☐ |
| 5e | `EditorScreen`: guard `setSubfolderScope` when `activeConversationId != null` + scope unchanged | ☐ |

**Acceptance:** M3 pass.

---

### Phase 6 — Rotation & configuration survival ☐

From [CHAT_FIX.md](CHAT_FIX.md) Phase 4 + STT manual matrix.

| ID | Task | Status |
|----|------|--------|
| 6a | `EidosChatScreen`, `WidgetChatActivity`, `WebPanel`, `NotePanel`: skip `voiceController.stopSession()` when `isChangingConfigurations` | ☐ |
| 6b | `WidgetChatActivity` manifest: `configChanges` **or** `rememberSaveable` for sheet/scroll state | ☐ |
| 6c | `WebPanel`: `rememberSaveable` for `eidosSheetOpen` | ☐ |
| 6d | Verify `EidosChatViewModel` messages survive rotate; document process-death reload | ☐ |

**Acceptance:** M4, M6 pass.

---

### Phase 7 — API cleanup & dead code ☐

From [CHAT_FIX.md](CHAT_FIX.md) Phase 6 + STT leftovers.

| ID | Task | Status |
|----|------|--------|
| 7a | **Delete** `moveConversationToViewedDirectory()` | ☐ |
| 7b | Parent **Chats** list: decide inclusive vs parent-only; update `ConversationListScreen` + `CHAT_UI.md` | ☐ |
| 7c | `WidgetChatActivity` — New Chat confirm dialog (match `EidosChatScreen`) | ☐ |
| 7d | Remove dead `notifyWidgetChatSessionReset()` bus + collector (New button already removed) | ☐ |
| 7e | Remove or wire `EXTRA_AUTO_START_MIC` | ☐ |
| 7f | `openConversationBrowser()` — history chips sync with `viewedScope` after move | ☐ |

**Acceptance:** No dead move API; widget New Chat matches main app.

---

### Phase 8 — Documentation refresh ☐

| ID | Task | Status |
|----|------|--------|
| 8a | Rewrite `VOICE_SYSTEM.md` **Core STT Model** section for Google + Whisper API (remove Sherpa/VAD as primary) | ☐ |
| 8b | Update [STT_AND_CONVERSATION_ROUTING_CHECKLIST.md](STT_AND_CONVERSATION_ROUTING_CHECKLIST.md) to match shipped routing | ☐ |
| 8c | Mark [STT_WHISPER_WIDGET_PLAN.md](STT_WHISPER_WIDGET_PLAN.md) complete with link to this plan for follow-ups | ☐ |
| 8d | Update [CHAT_FIX.md](CHAT_FIX.md) status — point remaining work here or mark superseded | ☐ |
| 8e | Cursor journal / release note when Phases 1–6 complete | ☐ |

---

### Phase 9 — Regression tests & manual sign-off ☐

| ID | Task | Status |
|----|------|--------|
| 9a | JVM: `refreshSummaries()` after move excludes conv from old parent query | ☐ |
| 9b | JVM: scope/history labels after Quick Notes + Workshop moves | ☐ |
| 9c | Manual matrix M1–M10 signed off on device | ☐ |

---

## Manual test matrix (full)

Run after Phases 1–6; re-run after Phase 9.

| # | Steps | Expected |
|---|--------|----------|
| M1 | Parent A Chats → open conv → Move Here from Parent B | Conv in B only; absent from A after back |
| M2 | Subfolder X → Move Here to General | Gone from X list |
| M3 | Eidos from DumpEdit → send → back/rotate | Same DumpEdit thread, not general |
| M4 | Widget Chat → rotate | Messages + scroll OK; voice survives rotate if mic was open |
| M5 | Read aloud on in widget chat; home widget Quick Ask | Widget loop independent unless hands-free pref on |
| M6 | Web tab → Eidos sheet → rotate | Sheet state recoverable |
| M7 | Read aloud on, pass-back off | TTS only, no mic reopen |
| M8 | Read aloud on, pass-back on, Whisper mic | TTS → mic → speak → **Send** → message sent |
| M9 | Whisper on, disable network, commit | Error shown; Google fallback or clear message |
| M10 | Web search mic | Google inline STT only; never Whisper |

---

## Implementation order (recommended)

```
Phase 0 (docs pre-flight)
    ↓
Phase 1 (read aloud / pass-back split)  ← user-facing win first
    ↓
Phase 2 (STT robustness + unit tests)     ← can overlap with Phase 1
    ↓
Phase 3 (Move Here pointers) ──→ Phase 4 (list refresh)
    ↓
Phase 5 (scope lifecycle)
    ↓
Phase 6 (rotation)          Phase 7 (cleanup)  ← parallel after Phase 5
    ↓
Phase 8 (docs) → Phase 9 (regression + sign-off)
```

**Minimum viable PR (voice):** Phase 1 only — pass-back toggle + widget pref split.  
**Minimum viable PR (chat directory):** Phase 3 + Phase 4 + Phase 5a.

---

## Files touched (summary)

| Phase | Primary files |
|-------|----------------|
| 1 | `SettingsPreferences.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `EidosChatViewModel.kt`, `EidosChatScreen.kt`, `WebPanel.kt`, `WidgetChatActivity.kt`, `WidgetVoiceService.kt` |
| 2 | `VoiceController.kt`, `WhisperApiSpeechToTextEngine.kt`, `WhisperTranscriptionClient.kt`, `app/src/test/...` |
| 3–4 | `EidosChatViewModel.kt`, `ConversationListScreen.kt`, `ConversationListViewModel.kt`, `WidgetPrefs.kt` |
| 5 | `DumpEditScreen.kt`, `EditorScreen.kt`, `EidosChatViewModel.kt` |
| 6 | `EidosChatScreen.kt`, `WidgetChatActivity.kt`, `WebPanel.kt`, `NotePanel.kt`, `AndroidManifest.xml` |
| 7 | `EidosChatViewModel.kt`, `WidgetChatActivity.kt`, `OptimalXApplication.kt` |
| 8–9 | `app/docs/...`, `app/src/test/...` |

---

## Out of scope

- True hands-free auto-send on silence (by design — user taps Send).
- Whisper live partials during recording (Whisper remains commit-on-stop).
- “View Quick Ask log” UI in widget or settings.
- [PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md](PANEL_EIDOS_CHAT_IMPLEMENTATION_PLAN.md) — apply Phase 5 dispose lessons when panel chat lands.
- Full `VoiceSessionDescriptor` immutability ([STT checklist](STT_AND_CONVERSATION_ROUTING_CHECKLIST.md) Phase 1).
- Tablet dual-pane chat + directory browser.

---

## Changelog

| Date | Phase | Notes |
|------|-------|-------|
| 2026-05-31 | — | [CHAT_UI_REDESIGN_PLAN](../archive/CHAT_UI_REDESIGN_PLAN.md) shipped: `ChatTopBar` + `ChatComposerBar`, Read aloud icon, overflow menu, Send/Stop merge. |
| 2026-05-30 | 1 | Read aloud / mic pass-back split; widget hands-free pref. |
| 2026-05-30 | 0 | Doc alignment: `CHAT_UI.md`, `CONVERSATION_DIRECTORY.md`, `WIDGET_SYSTEM.md`, `VOICE_CHAT_STT_QA.md`. |
| 2026-05-30 | — | Plan created: consolidates STT follow-ups, CHAT_FIX Phases 0–7, and `READ_ALOUD_MIC_PASSBACK` product decision. |
