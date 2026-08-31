# VOICE_SYSTEM.md

## Purpose

This file documents the **shipped** voice stack in OptimalX Android — what the code does today, not retired experiments.

Use alongside `WIDGET_SYSTEM.md`, `CHAT_UI.md`, and `GemmaLocal.md` (local Gemma scribe tuning).

**Desktop counterpart:** OptimalX Desktop uses Kokoro TTS + sherpa-onnx Parakeet STT (`electron/voice/`). See `app/docs/cross-repo/README.md`.

---

## Shipped summary (2026)

| Role | Chat + notes | Widget Quick Ask / Quick Notes | Web search fields |
|------|----------------|--------------------------------|-------------------|
| **STT** | See routing below | Google only | Google only |
| **TTS (read aloud)** | Android `TextToSpeech` via `ReadAloudSession` | Same when widget hands-free is on | — |

### Chat + notes STT routing (`VoiceController`)

Priority when starting a mic session (`recreateSttEngineIfNeeded`):

1. **Local Gemma scribe** — if `mic_use_local_gemma_scribe` is on → `GemmaLocalScribeEngine`
2. **OpenAI Whisper API** — if `mic_use_whisper_api` is on **and** OpenAI API key is saved → `WhisperApiSpeechToTextEngine`
3. **Google STT** (default) → `GoogleSpeechToTextEngine`

There is **no** in-app Sherpa ONNX, Parakeet, Silero VAD, or local Whisper.cpp path in shipped Kotlin. Legacy preference keys (`stt_backend`, `sherpa_onnx`, etc.) are migrated to `google_recognizer` at startup and are not used for routing.

---

## Where voice is available

| Location | Mic (STT) | Read aloud (TTS) |
|----------|-----------|------------------|
| Eidos chat (in-app) | `VoiceController` | `ReadAloudSession` |
| Note editor / DumpEdit | `VoiceController` | `ReadAloudSession` |
| Home widget Quick Ask / Quick Notes | Google STT only (`WidgetVoiceService`) | Optional widget hands-free |
| Web panel address / search | Google only (`WebSearchSttSession`) | `ReadAloudSession` on chat-style surfaces |
| Widget wake word | Separate `WakeWordDetector` path | — |
| System keyboard (Gboard, etc.) | User’s IME — **not** OptimalX STT | — |

Voice is an input/output layer for chat and notes, not a separate app mode.

---

## Interaction model

### Push-to-talk (default)

- User opens mic → capture starts.
- User speaks (pauses for thinking are allowed).
- User commits via **Send**, **Transcribe and insert** (notes), or mic stop — behavior depends on surface and STT backend.

There is **no** auto-send on silence for chat. There is **no** forced session timeout while the mic is open (Google uses long silence windows; buffered engines record until stop).

### Starting mic never clears typed text

`TranscriptAssembler.setBase(existingText)` seeds the buffer so speech **appends** to whatever is already in the composer or note field.

### Pause / resume (Whisper API and Gemma scribe only)

- **Pause** stops `AudioRecord` without calling the cloud or Gemma — audio stays buffered.
- **Resume** continues into the same buffer.
- **Discard** drops buffered audio/text.
- **Google STT** does not use this path; tapping mic while listening finalizes Google text into the composer instead of pausing.

---

## Persistent transcript buffer

All engines merge into `TranscriptAssembler` (`voice/pipeline/TranscriptAssembler.kt`):

| Method | Behavior |
|--------|----------|
| `setBase(text)` | Seed from existing input |
| `setPartial(text)` | Live hypothesis only |
| `commitFinal(text)` | Append to committed with a space |
| `displayText()` | `committed + partial` for UI |
| `takeAndReset()` | Flush partial as final, return all, clear |

Rules:

- Committed text survives segment boundaries and engine restarts.
- Buffer clears on successful commit/cancel, not on internal STT segments.
- Note dictation passes `existingText = ""` so the note body is not duplicated — heard text is appended in the editor callback only.

Partial updates to Compose are throttled to **90 ms** (`VoicePipelineConfig.UI_UPDATE_THROTTLE_MS`).

---

## STT engines (detail)

### Google STT — default (`GoogleSpeechToTextEngine`)

- Android `SpeechRecognizer`, preferring Google Quick Search recognition services when installed.
- **Streaming partials** while mic is open.
- **Continuous dictation:** after each utterance `onResults`, commits text and **restarts** listening (~120 ms delay) until `stopListening`.
- Dictation-oriented extras: 6 s possibly-complete / 10 s complete silence, 60 s minimum session length.
- `pauseCapture` / `resumeCapture` keep assembler state but release the recognizer (used when pausing Google path via `finalizeListeningForEdit`).
- **No app-level PCM chunking** — the OS recognizer handles segmentation.

### OpenAI Whisper API (`WhisperApiSpeechToTextEngine`)

- Records **16 kHz mono PCM16** via `AudioCaptureBuffer` while mic is open.
- **No live partials** during capture — UI shows base/committed text only until stop.
- On `stopListening`: full buffer → WAV → OpenAI `whisper-1` HTTP API (`WhisperTranscriptionClient`).
- Requires Settings toggle + saved OpenAI API key + network.
- Supports pause/resume/discard without transcribing mid-session.

### Local Gemma scribe (`GemmaLocalScribeEngine`)

- On-device transcription via **Gemma 4 E4B** multimodal LiteRT path (`LitertLmScribeService`).
- Enabled when Settings → **Use local Gemma scribe for mic** is on.
- **Rolling slice** long-form capture (see `GemmaLocal.md` § Local scribe):
  - `SCRIBE_SLICE_SECONDS` = **8**
  - `SCRIBE_SLICE_OVERLAP_SECONDS` = **0.5**
  - `SCRIBE_MIN_FLUSH_SECONDS` = **0.4**
- Continuous `AudioRecord` → peel overlapping WAV windows → one reused LiteRT conversation per recording session → `commitFinal` → live partials in UI.
- Committed PCM prefix dropped after each slice (`dropPcmBefore`) so RAM stays bounded.
- Supports pause/resume/discard like Whisper API.
- **No VAD** — fixed-time windows with audio overlap to avoid clipped words at boundaries (no text dedup at joins).

### Widget + web search

- `WidgetVoiceService` → `VoiceRuntime.createGoogleOnlySttEngine()` always.
- `WebSearchSttSession` → Google only for browser search fields.

---

## Text-to-speech (read aloud)

### Engine

- Android system **`TextToSpeech`** (`TextToSpeechEngine`) — user’s preferred TTS engine from system settings.
- Language from device `LocaleList` (fallback US English).
- Markdown/citations stripped before speak (`stripMarkdownForTts`, `TtsTextSanitizer`).

### Session orchestration (`ReadAloudSession`)

App-scoped session shared by chat, notes, DumpEdit, and web chat surfaces:

- Long text split into chunks (`chunkNoteForReadAloud` / `NoteReadAloudChunking.kt`) under `TextToSpeech.getMaxSpeechInputLength()`.
- Chunks spoken sequentially via `TextToSpeech.QUEUE_ADD`.
- Notification controls: play/pause, ±10 s skip, stop.
- Chat uses `startFromChat`; notes use `startFromNote` (rich-text sanitized separately).

`VoiceController.speakResponse()` still wraps `TextToSpeechEngine` for narrow legacy paths; **primary read-aloud UX uses `ReadAloudSession`**, not `VoiceController` TTS directly.

### When read aloud runs

| Setting | Default | Behavior |
|---------|---------|----------|
| `read_aloud` | Off | Speak assistant replies after send |
| `read_aloud_mic_passback` | On | Re-open mic after TTS completes (user still taps Send to send) |
| `widget_voice_hands_free` | — | Widget speaks reply after send |

Eidos chat: after assistant message, `ReadAloudSession.startFromChat` → optional mic passback via `VoiceController.startListening`.

---

## Wake word

- Widget-scoped (`WakeWordDetector`), default phrase **"Hey Eidos"** (`wake_word` setting).
- Uses Google `SpeechRecognizer` for phrase detection — **separate** from chat/widget mic STT above.
- Triggers `WidgetVoiceService`, not in-app chat mic.

---

## Voice settings (DataStore)

| Key | Default | Description |
|-----|---------|-------------|
| `wake_word` | `"Hey Eidos"` | Widget wake phrase |
| `read_aloud` | `false` | TTS for assistant replies |
| `read_aloud_mic_passback` | `true` | Mic after read aloud |
| `widget_voice_hands_free` | — | Widget TTS after send |
| `mic_use_whisper_api` | `false` | Whisper API for chat/notes mic |
| `mic_use_local_gemma_scribe` | `false` | Gemma local scribe for chat/notes mic |
| `stt_backend` | `google_recognizer` | **Legacy** — migrated at startup; routing uses toggles above |

Whisper toggle is cleared automatically if no OpenAI key is saved (`OptimalXApplication.migrateLegacySttPreferences`).

---

## Privacy and data handling

| Backend | Audio leaves device? |
|---------|----------------------|
| Google STT | Yes — Google recognition service (device/network per OEM) |
| Whisper API | Yes — OpenAI on commit |
| Gemma scribe | No — on-device LiteRT |
| Android TTS | Depends on system TTS engine |

- Persist only **transcript text** required for chat/notes.
- Whisper/Gemma buffers are in-memory; cleared on discard/stop.
- Gemma scribe drops committed PCM prefixes during long sessions.

---

## Implementation map (shipped)

### Routing and façade

| Class | Role |
|-------|------|
| `VoiceRuntime` | `createChatSttEngine` / `createGoogleOnlySttEngine` |
| `VoiceController` | Chat + notes mic/TTS state, STT backend selection |
| `SttEngine` | Common contract (partial/final, pause/resume, discard) |
| `WidgetVoiceService` | Widget mic — Google STT only |
| `WebSearchSttSession` | Web search mic — Google STT only |

### STT engines

| Class | Role |
|-------|------|
| `GoogleSpeechToTextEngine` | Default streaming STT |
| `WhisperApiSpeechToTextEngine` | Buffered PCM → OpenAI Whisper |
| `GemmaLocalScribeEngine` | Rolling slices → LiteRT scribe |
| `WhisperTranscriptionClient` | OpenAI HTTP client |

### Audio + merge

| Class | Role |
|-------|------|
| `AudioCaptureBuffer` | 16 kHz PCM ring for Whisper/Gemma |
| `AudioWavCodec` | WAV encode + byte length helpers |
| `TranscriptAssembler` | Committed + partial merge |
| `VoicePipelineConfig` | Sample rate, UI throttle |

### TTS

| Class | Role |
|-------|------|
| `ReadAloudSession` | Chunked read-aloud + notification controls |
| `TextToSpeechEngine` | Android TTS wrapper |
| `NoteReadAloudChunking` | Sentence-boundary chunking for long notes |
| `TtsTextSanitizer` / `stripMarkdownForTts` | Strip markup for speech |

### UI wiring

| Surface | Mic | Read aloud |
|---------|-----|------------|
| `EidosChatScreen` | `VoiceController` | `ReadAloudSession` |
| `NotePanel` / `RichTextNotePanel` | `VoiceController` | `ReadAloudSession` |
| `DumpEditPanel` | `VoiceController` | `ReadAloudSession` |
| `WidgetChatActivity` | `WidgetVoiceService` | `ReadAloudSession` |

---

## Retired / not in codebase

The following were planned or documented previously but are **not** shipped in Kotlin:

- Sherpa ONNX / Parakeet local ASR
- Silero VAD segmentation pipeline
- `ContinuousSpeechToTextEngine`, `SherpaOnnxTranscriber`, `WhisperLocalTranscriber`
- `SpeechToTextEngine` wrapper around the above
- STT backend preference matrix (`AUTO` / `SHERPA_ONNX` / `LOCAL_WHISPER`)

Do not reintroduce without a new design doc and explicit product sign-off. For long-form local capture today, use **Gemma scribe** or **Whisper API**; for lowest-latency partials, use **Google STT**.

---

## Related docs

| Doc | Contents |
|-----|----------|
| `app/docs/GemmaLocal.md` | Local Gemma chat transport + scribe slice tuning |
| `app/docs/LITERT_LM.md` | LiteRT model install and engine lifecycle |
| `app/docs/systems/WIDGET_SYSTEM.md` | Widget voice wiring |
| `app/docs/architecture/CHAT_UI.md` | Chat composer + mic chrome (some STT sections may lag this file) |
| `app/docs/implementation/VOICE_CHAT_STT_COMPLETION_PLAN.md` | Historical completion checklist |

When this file and older architecture docs disagree, **this file and the Kotlin sources listed above are authoritative**.
