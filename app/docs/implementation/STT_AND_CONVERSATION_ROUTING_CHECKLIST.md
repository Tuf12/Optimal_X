# STT backends + conversation routing — implementation checklist

Use with: `app/docs/architecture/CONVERSATION_DIRECTORY.md`, `CHAT_UI.md`, `WIDGET_SYSTEM.md`, and **`app/docs/systems/VOICE_SYSTEM.md`** (authoritative for STT/TTS).

## Product architecture (current)

**In-app chat + notes mic** (`VoiceController`):

| Priority | When | Engine |
|----------|------|--------|
| 1 | `mic_use_local_gemma_scribe` on | `GemmaLocalScribeEngine` — rolling WAV slices → LiteRT scribe |
| 2 | `mic_use_whisper_api` on + OpenAI key | `WhisperApiSpeechToTextEngine` — buffer PCM → Whisper API on stop |
| 3 | Default | `GoogleSpeechToTextEngine` — streaming `SpeechRecognizer` |

**Widget Ask Eidos / Quick Notes** (`WidgetVoiceService`): **Google STT only**.

**Web search fields** (`WebSearchSttSession`): **Google STT only**.

**Read aloud (TTS):** Android `TextToSpeech` via `ReadAloudSession` (chunked long text). See `VOICE_SYSTEM.md`.

**Retired (not in codebase):** Sherpa ONNX, Parakeet, Silero VAD, `ContinuousSpeechToTextEngine`, local Whisper.cpp, `SpeechToTextEngine` wrapper. Legacy `stt_backend` keys are migrated at startup and do not affect routing.

Do not reintroduce removed local ASR pipelines without revisiting `VOICE_SYSTEM.md` and this checklist.

---

## Terminology (navigation → chat scope)

This matches **`AppNavigation.kt`** wiring today (verify after any shell changes):

| User location | Navigation route | `EidosChatViewModel` scope | DB `Conversation.scopeType` |
|---------------|-----------------|----------------------------|-----------------------------|
| Top-level **folder grid** (nothing inside a folder open) | `PARENT_FOLDERS` → Eidos | `setGeneralScope()` → `ConversationScope.General` | `"general"` |
| **Subfolder list** for a chosen parent (cards under that parent) | `SUBFOLDERS/{parentFolderId}` → Eidos | `setParentFolderScope(parentFolderId)` → `ParentFolder` | `"parent"` |
| **Editor / panels** for a subfolder | `EDITOR/{subfolderId}` → Eidos | `setSubfolderScope(subfolderId)` → `Subfolder` | `"subfolder"` |

Quick Notes / Memory / Reasoning inboxes use additional scopes (see `ConversationScope`).

- [x] **Directory (UI)** = browser chips (`Recent`, `General`, `Parent`, …). See `CONVERSATION_DIRECTORY.md`.
- [x] **Scope (storage)** = `Conversation.scopeType`: `"general"` | `"parent"` | `"subfolder"` | quick-notes variants.
- [x] **Widget general chat** = `scopeType = "general"`; widget uses **`WidgetPrefs`** for active thread (`WidgetVoiceService.activeConversationId`), not `ChatSessionPointers`.
- [x] **Main-app General restore** = `EidosChatViewModel.restoreGeneralScopeConversation()` when `entrySurface == MAIN_APP` → `ChatSessionPointers.getGeneralMain`.
- [x] **Widget General restore** = same `"general"` bucket; `entrySurface == WIDGET` → `WidgetPrefs.getActiveConversationId`.

**Obsolete confusion (resolved):** the **top-level parent folder grid** uses **general** chat, not parent scope. **`"parent"`** is for chat opened from inside a **parent’s SubfolderScreen** only.

---

## Phase 0 — Verify current behavior

- [x] **Navigation → scope table** — Static alignment with `AppNavigation.kt`.
- [ ] Manual device walk (recommended): ParentFolderScreen Eidos = General → SubfolderScreen Eidos = parent → Editor Eidos = subfolder.
- [x] **Widget general vs Quick Note** — `ensureGeneralWidgetConversation` vs `ensureQuickNotesConversation` / `captureTarget`.
- [x] **Distinct general pointers** — `ChatSessionPointers` only under `MAIN_APP`; widget uses `WidgetPrefs`.
- [x] **Shared STT routing** — `VoiceController.recreateSttEngineIfNeeded()` selects `VoiceRuntime.createChatSttEngine` backend (Gemma scribe → Whisper API → Google). `WidgetVoiceService` uses `VoiceRuntime.createGoogleOnlySttEngine()`.
- [x] **Wake word** — `WakeWordDetector` is separate from chat STT (`VOICE_SYSTEM.md`).

### Mic / composer entry inventory

| Entry surface | Engine construction | Notes |
|---------------|----------------------|-------|
| **Eidos chat** (in-app) | `VoiceController` → routed `SttEngine` | Same prefs as Settings toggles. |
| **Notes / DumpEdit** | `VoiceController` per editor | Same routing; note dictation avoids duplicating note body. |
| **WidgetChatActivity** | `VoiceController` | Same routing as in-app chat. |
| **WidgetVoiceService** (home mic) | `VoiceRuntime.createGoogleOnlySttEngine()` | Ask Eidos / Quick Notes — Google only. |
| **Web search** | `WebSearchSttSession` | Google only. |

---

## Phase 1 — Session contract (conversation + STT)

Optional hardening (not yet required by `VOICE_SYSTEM.md`):

- [ ] Immutable **voice session descriptor** at mic start: `conversationId`, scope, `entrySurface`.
- [ ] **Stop/cancel** clears descriptor to avoid bleed-through.

---

## Phase 2 — Per-session STT override (optional)

Today, STT backend is **process-wide** via Settings toggles (`mic_use_local_gemma_scribe`, `mic_use_whisper_api`). Future: per-session override without races when switching engines mid-flight.

---

## Phase 3 — Bind transcript to conversation that started recording

- [ ] On `startListening` / widget `ACTION_START_VOICE`, snapshot **active `conversationId`**; on send, use that snapshot (`EidosChatViewModel` / `WidgetPrefs` rules — see Phase 0).

---

## Phase 4 — Settings, copy, errors

- [x] Settings Voice section: Google default; optional Whisper API; optional local Gemma scribe.
- [ ] User-visible copy when Whisper API fails (snackbar + optional Google retry — see `VOICE_CHAT_STT_COMPLETION_PLAN.md` Phase 2).
- [ ] Optional: analytics `stt_surface=...`, `stt_backend=google|whisper|gemma_scribe`.

---

## Phase 5 — Test matrix (manual)

Log: surface, backend (`VoiceController` / engine class), conversation id, scope.

- [ ] In-app General: mic → transcript in input → send.
- [ ] Widget: mic → send → thread under General; open main app → same messages.
- [ ] Gemma scribe on → rolling partials during long dictation.
- [ ] Whisper API on + key → pause/resume → transcribe on send.
- [ ] Whisper on, network off → error feedback (and retry if Phase 2 shipped).
- [ ] Google default → streaming partials, utterance restart while mic open.

---

## Quick reference — key files

| Area | File(s) |
|------|---------|
| STT routing | `voice/VoiceRuntime.kt`, `voice/VoiceController.kt` |
| STT engines | `GoogleSpeechToTextEngine.kt`, `WhisperApiSpeechToTextEngine.kt`, `GemmaLocalScribeEngine.kt` |
| Transcript merge | `voice/pipeline/TranscriptAssembler.kt`, `VoicePipelineConfig.kt` |
| Audio capture | `AudioCaptureBuffer.kt`, `AudioWavCodec.kt` |
| TTS read aloud | `ReadAloudSession.kt`, `TextToSpeechEngine.kt` |
| Widget voice | `widget/WidgetVoiceService.kt`, `widget/WidgetPrefs.kt` |
| Web search STT | `WebSearchSttSession` in `VoiceController.kt` |
| Chat / scope | `ui/eidos/EidosChatViewModel.kt`, `AppNavigation.kt` |
| Scribe policy | `data/litert/GemmaLocalPolicy.kt`, `GemmaLocal.md` |

_Last updated: aligned with Google + Whisper API + Gemma scribe routing (2026-08-13)._
