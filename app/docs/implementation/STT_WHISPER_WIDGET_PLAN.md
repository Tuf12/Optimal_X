# STT Whisper + Widget Quick Ask — execution checklist

Status: Shipped (2026-05-31)

## Phase 1 — Google STT backup

- [x] `GoogleSpeechToTextEngine` with dictation/silence extras and recognizer recycle
- [x] Async commit in `VoiceController` and `WidgetVoiceService`

## Phase 2 — Settings + routing

- [x] `SttEngine` interface + `VoiceRuntime` factory
- [x] `MIC_USE_WHISPER_API` toggle in Settings

## Phase 3 — Whisper API

- [x] `WhisperTranscriptionClient`, `AudioCaptureBuffer`, `WhisperApiSpeechToTextEngine`

## Phase 4 — Chat UIs

- [x] `EidosChatScreen`, `WebPanel` Eidos composer, `WidgetChatActivity` use `VoiceController` + Whisper toggle
- [x] `TRANSCRIBING` placeholders

## Phase 5 — Widget Quick Ask

- [x] Bottom mic → Ask Eidos (Google STT, Quick Ask log conversation)
- [x] Removed widget **New** button
- [x] `WidgetPrefs.quick_ask_log_conversation_id` separate from chat UI pointer
- [x] Widget Chat UI independent from service transcripts

## Phase 6 — Notes mic

- [x] Mic on `FormattingToolbar` / `NotePanel` with Whisper toggle via `VoiceController`

## Phase 7 — Web search (Google only)

- [x] `WidgetWebSearchActivity` inline `WebSearchSttSession`
- [x] `WebPanel` expanded address bar mic via `WebSearchSttSession`

## Phase 8 — Docs

- [x] This checklist
- [x] Update `VOICE_SYSTEM.md` and `WIDGET_SYSTEM.md`

## Manual verification

| Case | Expected |
|------|----------|
| Chat mic, Whisper on + key | Record → transcribe → input |
| Chat mic, Whisper off | Google partials; async commit on send |
| Widget Ask Eidos | Google STT; DB log; Chat opens separate thread |
| Widget Quick Notes | Unchanged day-folder behavior |
| Notes mic | Appends dictated text to note body |
| Web search mic | Google inline STT only |
