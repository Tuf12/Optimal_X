
# VOICE_SYSTEM.md

## Purpose

This file defines the voice interaction architecture for OptimalX v3.

The core change from v2 is that speech-to-text moves from session-based Android `SpeechRecognizer` loops to a dedicated local continuous ASR pipeline:

- continuous microphone capture with `AudioRecord`
- voice activity detection (VAD) to segment speech
- on-device transcription model for STT

This spec covers interaction behavior, system architecture, performance targets, fallback behavior, and implementation rules.

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

- user taps mic -> capture starts
- user speaks freely, including pauses for thinking
- user taps send (or mic stop action) -> transcript is finalized and sent

There is no auto-send on silence.
There is no forced session timeout while mic is active.

### Architectural model (new internals)

STT pipeline is continuous and local:

1. `AudioRecord` continuously captures PCM audio while mic is active.
2. Frames flow through a VAD stage.
3. VAD emits utterance segments (speech start/speech end).
4. On-device ASR transcribes each segment.
5. Partial/final text is merged into a persistent transcript buffer.
6. Buffer is displayed live in input UI and committed on user send.

The app should not depend on repeated recognizer restarts to remain active.

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

- Use frame-level VAD over short frames (10-30 ms).
- Segment speech with start/end hysteresis (speech threshold + silence hangover).
- Allow natural pauses without dropping session state.

### Transcription

- Use an on-device model suitable for near-real-time operation.
- Emit partial text during active speech where possible.
- Emit final text on segment close.
- Merge partial/final output into persistent buffer without clobbering prior text.

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

Wake word remains widget-scoped:

- default phrase: "Hey Eidos"
- customizable in settings
- runs only when widget voice features are enabled
- should be designed for low battery impact

Wake word should activate the same listening state as tapping mic.

---

## Voice Settings

| Setting | Default | Description |
|---|---|---|
| Wake word | "Hey Eidos" | Phrase that activates voice mode |
| Read aloud | On | Speak assistant responses aloud |
| STT engine mode | Local continuous | Preferred engine for speech capture/transcription |

If compatibility mode is exposed, it should be explicit and user-selectable.

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

## Fallback + Rollout Strategy

During migration, keep a fallback engine path:

- primary: local continuous ASR pipeline
- fallback: Android recognizer-based STT path

Rules:

- fallback must preserve the same push-to-talk UX contract
- transcript buffer semantics must match between engines
- engine selection should be deterministic and logged for debugging

---

## Privacy and Data Handling

- Audio and transcription stay on device for local engine mode.
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

## Implementation Map (v3 shipped)

The v3 pipeline is implemented in `app/src/main/java/com/example/optimalx/voice/pipeline/`:

| Class | Role |
|---|---|
| `VoicePipelineConfig` | Single source of truth for sample rate, frame size, VAD thresholds, segment limits, UI throttle |
| `AudioCaptureSource` | `AudioRecord` 16 kHz mono PCM capture on a dedicated audio thread |
| `VadSegmenter` | Frame-level RMS VAD with start/end hysteresis, adaptive noise floor, max-segment guard |
| `TranscriptAssembler` | Persistent draft buffer; merges partial / final transcripts; flushes partial as final on stop |
| `OnDeviceTranscriber` | Pluggable transcriber interface; `ownsMicrophone` flag selects PCM-consuming vs mic-owning path |
| `RecognizerOnDeviceTranscriber` | v1 concrete backend: Android on-device `SpeechRecognizer` with extended silence windows and internal auto-restart |
| `ContinuousSpeechToTextEngine` | Orchestrator — owns the assembler, runs capture + VAD for PCM-consuming backends, exposes `onDraftChanged` / `onSessionEnded` / `onTransientError` callbacks |
| `VoiceRuntime` | Central `transcriberFactory`. Swapping the backend (e.g. to a bundled Whisper adapter) is a single assignment and propagates to both in-app and widget |

Both entry points route through the same engine:

- `voice/VoiceController.kt` (in-app chat) — uses `VoiceRuntime.newEngine(app)`
- `widget/WidgetVoiceService.kt` (home screen widget) — uses `VoiceRuntime.newEngine(this)`

This guarantees the UX contract (persistent buffer, no flicker, no silence auto-send) is enforced once and shared across surfaces.

### Current vs future transcriber backend

- **v1 (shipped):** `RecognizerOnDeviceTranscriber` wraps Android's on-device recognizer. It runs entirely on device but manages its own microphone, so `ContinuousSpeechToTextEngine` skips `AudioCaptureSource` / `VadSegmenter` while it is active (they are still fully implemented and tested for the future path).
- **v2+ (planned):** A PCM-consuming backend (e.g. Whisper-class model loaded from app storage such as `filesDir/models/` or from `app/src/main/assets/models/`) implements `OnDeviceTranscriber` with `ownsMicrophone = false`. The engine then drives capture + VAD, forwards frames via `onAudioFrame`, and signals segment boundaries via `onSegmentStart` / `onSegmentEnd`. No controller/service code changes.

### Threading + lifecycle rules

- Audio capture runs on its own `THREAD_PRIORITY_URGENT_AUDIO` thread.
- All listener callbacks into the engine are marshalled to the main thread before touching assembler/UI state.
- Transcribers must release native / ML resources in `OnDeviceTranscriber.destroy`. The engine always calls `destroy` when a session ends.
- The engine is safe to call `stop()` / `cancel()` repeatedly; state is guarded by an `active` flag.

---

## Integrating a Whisper backend

The Whisper integration is wired end-to-end at the pipeline level and only waits
on a concrete engine being registered. No controller/service/UI changes are
required once an engine is installed.

### Classes involved

| Class | Role |
|---|---|
| `WhisperEngine` | Interface: `transcribe(pcm: FloatArray): String`, `release()` |
| `WhisperEngineRegistry` | Process-wide singleton. `register(engine)` makes Whisper the active backend |
| `WhisperLocalTranscriber` | `OnDeviceTranscriber` implementation. `ownsMicrophone = false`. Consumes PCM via `onAudioFrame`, buffers per VAD segment (with 300 ms pre-roll), runs `engine.transcribe` on a single background worker thread |
| `VoiceRuntime.backendPreference` | `AUTO` (default) / `LOCAL_WHISPER` / `ANDROID_RECOGNIZER`. User-facing toggle in Settings |

### Backend selection matrix

| Preference | Whisper registered | Result |
|---|---|---|
| AUTO | yes | `WhisperLocalTranscriber` |
| AUTO | no | `RecognizerOnDeviceTranscriber` |
| LOCAL_WHISPER | yes | `WhisperLocalTranscriber` |
| LOCAL_WHISPER | no | `RecognizerOnDeviceTranscriber` + warning log |
| ANDROID_RECOGNIZER | either | `RecognizerOnDeviceTranscriber` |

### What a concrete engine must do

1. Load a Whisper-class GGML model (e.g. `ggml-small.en-q5_1.bin`) at app startup.
   Supported locations:
   - `filesDir/models/` (used by the in-app one-tap installer in Settings)
   - `app/src/main/assets/models/` (developer/manual bundling path)
2. Implement `WhisperEngine.transcribe(pcm)`:
   - `pcm` is 16 kHz mono float, values in `[-1, 1]`, length = segment duration × 16 000.
   - Return the best transcription (or empty string).
   - Treat as blocking; the transcriber worker thread serializes calls.
3. Release native resources in `release()`.
4. Register once in `OptimalXApplication.initWhisperEngine()` using
   `WhisperEngineRegistry.register(engine)`.

### Supported paths

**Path A — whisper.cpp JNI (recommended):**
- Build whisper.cpp's Android AAR (`examples/whisper.android` in the ggerganov repo).
- Drop the AAR into `app/libs/` and add to `app/build.gradle.kts` dependencies.
- Drop a GGML model into `app/src/main/assets/models/`. Recommended: `ggml-small.en-q5_1.bin` (~181 MB) for the best quality/size balance; `ggml-small.en-q8_0.bin` (~252 MB) for higher quality; `ggml-base.en-q8_0.bin` (~78 MB) for lower-spec devices. Note: distil-whisper-small.en is only published as fp16 (~336 MB) today, no public q8 conversion exists.
- Thin `WhisperEngine` wrapper over whisper.cpp's `WhisperContext`.

**Path B — TensorFlow Lite Whisper:**
- Obtain encoder/decoder tflite + vocab from a community conversion (e.g. nyadla-sys/whisper-tflite).
- Reuse the app's existing `tensorflow-lite:2.14.0` dependency.
- Implement mel-spectrogram → encoder → autoregressive decoder loop.
- Greater implementation effort but no native AAR.

Both paths end at the same `WhisperEngineRegistry.register(...)` call; everything
above that is already wired.

### Runtime flow with Whisper active

1. User taps mic. `AudioCaptureSource` opens the microphone once and keeps it open.
2. `VadSegmenter` tracks speech start/end using an energy gate with adaptive noise floor.
3. When a segment closes (natural pause or 6 s max), `WhisperLocalTranscriber`
   hands a float PCM buffer (with 300 ms pre-roll) to `WhisperEngine.transcribe`.
4. The returned text is committed through `TranscriptAssembler` and appears in
   the input bar.
5. Repeat until the user taps send or stop. No recognizer sessions, no beeps, no restart gaps.

### Known UX characteristic

This is a segment-based (chunked) flavor. Text appears in bursts at pause
boundaries or every ~6 seconds of continuous speech. The audio experience is
continuous even though the transcript refresh is chunked. Moving to
word-by-word streaming would require overlapping rolling windows and is a
future enhancement; the architecture supports it without touching the
controller/service layer.
