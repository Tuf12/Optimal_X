# STT backends + conversation routing — implementation checklist

Use with: `app/docs/architecture/CONVERSATION_DIRECTORY.md`, `CHAT_UI.md`, `WIDGET_SYSTEM.md`, and **`app/docs/systems/VOICE_SYSTEM.md`** (authoritative for STT).

## Product architecture (current)

- **In-app and widget mic:** local continuous pipeline (`AudioCaptureSource` + `VadSegmenter` + `OnDeviceTranscriber`).
- **Default ASR:** **Sherpa ONNX** (models bundled under `assets/voice/sherpa/` at build time). Works offline.
- **Optional:** **Whisper** when `WhisperEngineRegistry` has an engine (user-installed model). **Auto** prefers Whisper, then Sherpa.
- **No Android `SpeechRecognizer`** for chat/widget transcription. If both Whisper and Sherpa are unusable for a session, the pipeline reports errors and the user **types** or uses the **system keyboard’s** voice input — not an in-app recognizer fallback.

Do not reintroduce `RecognizerOnDeviceTranscriber` / mic-owning STT without revisiting this document and `VOICE_SYSTEM.md`.

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
- [x] **Shared STT routing** — **`VoiceController`** and **`WidgetVoiceService`** both use **`SpeechToTextEngine`**, which builds **`ContinuousSpeechToTextEngine`** with **`VoiceRuntime.createTranscriber`**. Global preference: **`VoiceRuntime.backendPreference`** (synced from DataStore in `SettingsViewModel` + startup in `OptimalXApplication`). Transcriber selection: **`VoiceRuntime.buildTranscriber`** (`WhisperLocalTranscriber` vs `SherpaOnnxTranscriber`).
- [x] **Wake word** — `WakeWordDetector` is separate from chat STT (`VOICE_SYSTEM.md`).

### Mic / composer entry inventory

| Entry surface | Engine construction | Notes |
|---------------|----------------------|-------|
| **EidosBottomSheet** (in-app) | **`VoiceController`** → `SpeechToTextEngine` → `VoiceRuntime` | Same Sherpa/Whisper policy as settings. |
| **WidgetChatActivity** | Separate **`VoiceController`** instance → same global `VoiceRuntime` preference. | |
| **WidgetVoiceService** (home mic) | **`SpeechToTextEngine(this)`** in `onCreate` | Transcript → `handleTranscript` / Eidos; not `VoiceRuntime.newEngine` (that helper exists for tests/alternate wiring). |

---

## Phase 1 — Session contract (conversation + STT)

Optional hardening (not yet required by VOICE_SYSTEM):

- [ ] Immutable **voice session descriptor** at mic start: `conversationId`, scope, `entrySurface`.
- [ ] **Stop/cancel** clears descriptor to avoid bleed-through.

---

## Phase 2 — Per-session STT override (optional)

Today, STT backend is **process-wide** via `VoiceRuntime.backendPreference`. Future: allow override per `ContinuousSpeechToTextEngine` instance (e.g. `transcriberFactory` parameter) without races — see `VoiceRuntime.transcriberOverride` for tests.

---

## Phase 3 — Bind transcript to conversation that started recording

- [ ] On `startListening` / widget `ACTION_START_VOICE`, snapshot **active `conversationId`**; on send, use that snapshot (`EidosChatViewModel` / `WidgetPrefs` rules — see Phase 0).

---

## Phase 4 — Settings, copy, errors

- [x] Settings STT section: Auto / Sherpa ONNX only / Local Whisper only (**no** Android recognizer).
- [ ] User-visible copy when Sherpa fails to init (optional toast): point user to typed input.
- [ ] Optional: analytics `stt_surface=...`.

---

## Phase 5 — Test matrix (manual)

Log: surface, backend (`VoiceRuntime.activeBackendLabel()`), conversation id, scope.

- [ ] In-app General: mic → transcript in input → send.
- [ ] Widget: mic → send → thread under General; open main app → same messages.
- [ ] Auto with Whisper installed → Whisper path; Auto without → Sherpa path.
- [ ] Sherpa-only setting → always Sherpa.

---

## Quick reference — key files

| Area | File(s) |
|------|---------|
| STT routing | `voice/pipeline/VoiceRuntime.kt`, `SpeechToTextEngine.kt`, `ContinuousSpeechToTextEngine.kt`, `SherpaOnnxTranscriber.kt` |
| Widget voice | `widget/WidgetVoiceService.kt`, `widget/WidgetPrefs.kt` |
| In-app voice VM | `voice/VoiceController.kt` |
| ONNX load order | `voice/pipeline/OnnxRuntimeNativeLoader.kt` |
| Chat / scope | `ui/eidos/EidosChatViewModel.kt`, `AppNavigation.kt` |

_Last updated: aligned with Sherpa-primary, no in-app Android STT (2026-05-10)._
