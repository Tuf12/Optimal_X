
# VOICE_SYSTEM.md

## Purpose

This file defines the voice interaction architecture for OptimalX.

**Shipped STT routing (2026-05-31):**

| Surface | Engine |
|---------|--------|
| Chat + notes mics | OpenAI Whisper API when Settings toggle on + key saved; else `GoogleSpeechToTextEngine` |
| Widget Quick Ask + Quick Notes | `GoogleSpeechToTextEngine` only |
| Web search mics | `GoogleSpeechToTextEngine` only |
| Keyboard (Gboard, etc.) | User’s IME — no app API |

Chat/notes use `VoiceController` + `VoiceRuntime`. Widget voice uses `WidgetVoiceService`. Web search uses `WebSearchSttSession`.

Use this file alongside `WIDGET_SYSTEM.md` and `EIDOS_AGENT.md`.

---

## Where Voice Is Available

Voice interaction is available in three places:

| Location | Access method |
|---|---|
| Home screen widget | Mic button on the widget |
| Eidos bottom sheet (inside app) | Mic button in chat UI |
| Any in-app chat screen | Mic button in chat UI |

Voice is not a separate mode. It is an input/output layer for Eidos chat.

---

## Core STT Model (v3)

### Interaction model (unchanged UX)

Voice input remains push-to-talk:

- user taps mic → capture starts
- user speaks freely, including pauses for thinking
- user taps send (or mic stop action) → transcript is finalized and sent

There is no auto-send on silence.
There is no forced session timeout while mic is active.

### Architectural model (internals)

STT pipeline is continuous and local:

1. `AudioRecord` continuously captures PCM audio while mic is active.
2. Frames flow through a VAD stage (energy gate; optional Silero ONNX neural gate when available).
3. VAD emits utterance segments (speech start/speech end).
4. On-device ASR transcribes each segment (`SherpaOnnxTranscriber` and/or `WhisperLocalTranscriber`).
5. Partial/final text is merged into a persistent transcript buffer.
6. Buffer is displayed live in input UI and committed on user send.

The app does **not** use Android’s `SpeechRecognizer` for this pipeline. If both configured engines fail to run (e.g. rare native load failure), the user **types with the system keyboard** (and may use the IME’s voice input outside the app). There is no second in-app ASR fallback.

---

## Persistent Transcript Buffer

All spoken text is accumulated in a buffer owned by ViewModel/controller state, not in the recognizer/model runtime.

Rules:

- Buffer holds all captured speech since mic activation.
- Buffer is displayed live in the input bar.
- Buffer survives segment boundaries and model inference passes.
- Buffer is only cleared after successful send or explicit cancel.
- Starting mic never clears typed text; speech appends to existing input.
- Pause/resume appends new speech to current input content.

---

## Audio Pipeline Requirements

### Capture

- Use mono PCM (`PCM_16BIT`) at `16 kHz`.
- Capture on a dedicated audio thread with stable buffering.
- Maintain a small ring buffer to absorb scheduling jitter.

### VAD segmentation

- Use frame-level VAD over short frames (10–30 ms).
- Segment speech with start/end hysteresis (speech threshold + silence hangover).
- Allow natural pauses without dropping session state.

### Transcription

- **Sherpa ONNX:** Models ship under `assets/voice/sherpa/`; ONNX Runtime must load before Sherpa JNI (`OnnxRuntimeNativeLoader`). Works offline; no network required for ASR.
- **Whisper (optional):** If `WhisperEngineRegistry` holds an engine (user installed a model in Settings), **Auto** and **Local Whisper** preferences may route segments to Whisper instead of Sherpa per `VoiceRuntime`.
- Emit partial/final text per backend capabilities; merge into the persistent buffer without clobbering prior text.

### UI continuity

- Mic UI must remain continuously active while listening.
- No visible "off/on" flicker during segmentation/transcription.
- User should not perceive internal segment boundaries.

---

## Performance Profile (Target)

Default "balanced real-time" target:

- capture: 16 kHz mono PCM
- VAD frame step: 20 ms
- speech start gate: ~200 ms voiced frames
- speech end gate: ~700 ms silence hangover
- max segment length before forced flush: ~6 s
- partial update cadence: avoid UI flooding (throttle updates)

Device goals (mid-range Android):

- low-latency perceived transcript updates
- sustained operation without thermal runaway
- bounded RAM overhead for model + buffers

---

## Sending a Message

While mic is active:

| Action | Behavior |
|---|---|
| Tap send | Stop capture, finalize transcript buffer, send |
| Tap mic stop | Stop capture, finalize transcript buffer, send (or cancel if workflow requires) |

Implementation must always send the full accumulated buffer content.

---

## Eidos Response + TTS

Text-to-speech behavior is unchanged:

- Eidos responds in text.
- If read-aloud is on, response is spoken via Android `TextToSpeech`.
- If read-aloud is on, mic can auto-reactivate after TTS completion.
- If read-aloud is off, user manually starts next input.

There is no voice handoff phrase.

---

## Wake Word

Wake word remains widget-scoped (see `WakeWordDetector`):

- default phrase: "Hey Eidos"
- customizable in settings
- runs only when widget voice features are enabled

This path is **separate** from the chat/widget mic STT pipeline above and is not described as the primary in-app transcription engine.

---

## Voice Settings

| Setting | Default (see `SettingsPreferences`) | Description |
|---|---|---|
| Wake word | "Hey Eidos" | Phrase for widget wake path |
| Read aloud | Off | Speak assistant responses aloud |
| STT backend | `sherpa_onnx` | Auto (Whisper then Sherpa), Sherpa only, or Local Whisper only |

---

## Widget Voice vs In-App Voice

Voice interaction behavior should be identical across widget and in-app chat.
Only conversation storage scope differs.

| Location | Conversation saved to |
|---|---|
| Widget | Eidos Chats system folder (app root) |
| In subfolder | That subfolder's chat folder |
| In-app outside subfolder | Eidos Chats system folder (app root) |

See `WIDGET_SYSTEM.md` and `JOURNAL_SYSTEM.md` for storage details.

---

## Fallback Strategy

- **In-app / widget mic:** **Sherpa ONNX** is the default shipped path; **Auto** prefers **Whisper** when registered, else **Sherpa**.
- **No Android `SpeechRecognizer` fallback** for chat/widget STT.
- If initialization fails (e.g. assets/native load), the session ends with a non-fatal/fatal error per `ContinuousSpeechToTextEngine`; the user may type in the field or use the system keyboard’s voice input.

---

## Privacy and Data Handling

- Audio and transcription stay on device for local engine modes.
- Do not upload raw microphone audio to external providers for STT.
- Persist only transcript text required for chat/conversation flow.
- Temporary audio buffers must be in-memory when feasible and cleared at session end.

---

## Technical Notes

- Local STT accuracy/latency depends on model size and device performance.
- Optimize for predictable latency over maximum benchmark accuracy.
- Avoid blocking UI thread during model inference.
- Keep the transcript buffer as source of truth; model outputs are incremental updates.
- Treat VAD/ASR internal state as replaceable implementation details.

---

## Implementation Map (shipped)

Pipeline code lives in `app/src/main/java/com/example/optimalx/voice/pipeline/`:

| Class | Role |
|---|---|
| `VoicePipelineConfig` | Sample rate, frame size, VAD thresholds, segment limits, UI throttle |
| `AudioCaptureSource` | `AudioRecord` 16 kHz mono PCM on a dedicated audio thread |
| `VadSegmenter` | Frame-level VAD; optional `NeuralSpeechGate` (Silero ONNX) |
| `TranscriptAssembler` | Persistent draft buffer; merges partial / final; flush on stop |
| `OnDeviceTranscriber` | Pluggable backend; **all shipped paths are PCM-consuming** (`ownsMicrophone = false`) |
| `SherpaOnnxTranscriber` | **Default** on-device ASR (Sherpa-ONNX + bundled assets) |
| `WhisperLocalTranscriber` | Optional when `WhisperEngineRegistry` has an engine |
| `ContinuousSpeechToTextEngine` | Orchestrator — assembler, capture + VAD, callbacks |
| `VoiceRuntime` | Selects transcriber from `Settings` / `WhisperEngineRegistry` |

Shared entry façade:

| Component | Wiring |
|---|---|
| `SpeechToTextEngine` | Wraps `ContinuousSpeechToTextEngine` with `VoiceRuntime.createTranscriber` |
| `VoiceController` | In-app chat — `SpeechToTextEngine` |
| `WidgetVoiceService` | Widget mic — `SpeechToTextEngine` |

Both surfaces honor the same `VoiceRuntime.backendPreference` and the same transcript semantics.

### Backend selection matrix

| Preference | Whisper registered | Result |
|---|---|---|
| AUTO | yes | `WhisperLocalTranscriber` |
| AUTO | no | `SherpaOnnxTranscriber` |
| LOCAL_WHISPER | yes | `WhisperLocalTranscriber` |
| LOCAL_WHISPER | no | `SherpaOnnxTranscriber` + warning log |
| SHERPA_ONNX | either | `SherpaOnnxTranscriber` |

Legacy `STT_BACKEND = "android_recognizer"` in preferences is migrated at startup to `sherpa_onnx` (see `OptimalXApplication`).

---

## Integrating a Whisper backend (optional)

Whisper remains optional for evaluation; registration is unchanged.

### Classes involved

| Class | Role |
|---|---|
| `WhisperEngine` | `transcribe(pcm: FloatArray): String`, `release()` |
| `WhisperEngineRegistry` | `register(engine)` / `get()` |
| `WhisperLocalTranscriber` | PCM segments → `WhisperEngine.transcribe` |
| `VoiceRuntime.backendPreference` | `AUTO` / `SHERPA_ONNX` / `LOCAL_WHISPER` |

### Runtime flow when Whisper is active

Same capture + VAD as Sherpa; segments are decoded by Whisper when selected by the matrix above.

### Known UX characteristic

Segment-based (chunked) partials for Whisper-bound paths; Sherpa emits finals per VAD segment. See code for partial behavior per transcriber.
