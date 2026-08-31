# LiteRT-LM + Gemma 4 E4B (on-device Eidos)

**Status:** Shipped in OptimalX Android (`litertlm-android:0.13.1`); validated on Samsung S25 via Google AI Edge Gallery + in-app smoke test.

**Upstream:** [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) · [Android guide](https://developers.google.com/edge/litert-lm/android) · [API overview](https://developers.google.com/edge/litert-lm/api_overview) · [Gemma 4 models](https://developers.google.com/edge/litert-lm/models/gemma-4)

**Related OptimalX docs:** [GemmaLocal.md](GemmaLocal.md) (local transport policy) · [PROMPT_SYSTEM.md](systems/PROMPT_SYSTEM.md) · [LLM_API_REFERENCE.md](reference/LLM_API_REFERENCE.md)

---

## Goal

Run **Gemma 4 E4B** locally on Android through **LiteRT-LM** as an optional Eidos LLM backend — same product surfaces as cloud providers (chat, tools, vision, voice input), with no network required for inference.

| Capability | LiteRT-LM / Gemma 4 E4B | OptimalX surface (target) |
|------------|-------------------------|---------------------------|
| Text chat | `Conversation.sendMessage` / `sendMessageAsync` | Eidos chat, widget, workshop |
| Tool use (Agent Skills) | `ToolSet`, `OpenApiTool`, automatic or manual tool loop | `RoomToolExecutor` + `EidosToolCatalog` |
| Image vision | `Content.ImageFile` / `ImageBytes` + `visionBackend` | Note images, `read_file`, workshop assets |
| Audio transcription | `Content.AudioBytes` + transcribe prompt | Optional alternative to OpenAI Whisper API ([`WhisperApiSpeechToTextEngine`](../../src/main/java/com/example/optimalx/voice/WhisperApiSpeechToTextEngine.kt)) |

LiteRT-LM is for **chat + tools + vision + local transcription** — not for semantic search embeddings (`EmbeddingEngine` / MediaPipe stays separate).

---

## Model artifacts

### What to install on the phone

The model Google AI Edge Gallery downloads for on-device Gemma 4 chat is the LiteRT-LM package — not the raw `google/gemma-4-E4B` weight repo.

| Artifact | Role |
|----------|------|
| **[litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)** | **Install this** — `.litertlm` for LiteRT-LM on Android (same family Gallery uses) |
| File on disk | `gemma-4-E4B-it.litertlm` (~3.65 GB) |
| [google/gemma-4-E4B-it](https://huggingface.co/google/gemma-4-E4B-it) | Upstream model card only — use if you self-convert; not the Gallery / LiteRT-LM install path |

Do **not** bundle the `.litertlm` in the APK for production; keep it on device storage or download on first run. If the model is already on the S25 from Gallery, point OptimalX at that path or copy the file once.

### Developer device setup (validated)

Model installed and tested on developer Samsung S25 via Google AI Edge Gallery (GPU backend). Typical dev workflow if pushing manually:

```bash
# Example path — adjust to your on-device location
adb shell mkdir -p /data/local/tmp/llm/
adb push gemma-4-E4B-it.litertlm /data/local/tmp/llm/gemma-4-E4B-it.litertlm
```

Persisted app path should be configurable (DataStore), e.g. `context.getExternalFilesDir("models")` or a user-picked directory. `/data/local/tmp/` is fine for engineering builds only.

CLI sanity check (optional, off-device):

```bash
uv tool install litert-lm
litert-lm run \
  --from-huggingface-repo=litert-community/gemma-4-E4B-it-litert-lm \
  gemma-4-E4B-it.litertlm \
  --backend=gpu \
  --enable-speculative-decoding=true \
  --prompt="What is the capital of France?"
```

---

## Gradle dependency

Add to version catalog and `app/build.gradle.kts`. Package is on **Google Maven** (already in `settings.gradle.kts`).

**`gradle/libs.versions.toml`**

```toml
[versions]
litertLm = "0.13.1"   # pin after first successful build; or use dynamic resolution policy

[libraries]
litertlm-android = { group = "com.google.ai.edge.litertlm", name = "litertlm-android", version.ref = "litertLm" }
```

**`app/build.gradle.kts`**

```kotlin
dependencies {
    implementation(libs.litertlm.android)
}
```

Google also documents `latest.release` as a dynamic coordinate; prefer a pinned version in OptimalX for reproducible builds and 16 KB audits.

**Toolchain:** `litertlm-android` 0.13.x is compiled with Kotlin **2.3** metadata. OptimalX pins Kotlin `2.3.0`, KSP `2.3.0`, and Room `2.7.2` (KSP2 + Room suspend-`Unit` fix).

**Do not add** `org.tensorflow:tensorflow-lite` alongside LiteRT-LM — MediaPipe embeddings already bundle TFLite inside `libmediapipe_tasks_text_jni.so` (see [ANDROID_16KB_PAGE_SIZE_COMPLIANCE.md](implementation/ANDROID_16KB_PAGE_SIZE_COMPLIANCE.md)).

---

## Android manifest (GPU on Samsung / Snapdragon)

Gallery on the S25 already runs Gemma 4 E4B on **GPU** — that confirms the device and model support GPU inference. OptimalX still needs the same manifest entries Gallery ships (Gallery is a separate APK with its own manifest).

On Android 12+, declare vendor OpenCL libraries so LiteRT can reach the GPU driver ([LiteRT-LM #2292](https://github.com/google-ai-edge/LiteRT-LM/issues/2292)). Without these, some bare consumer apps fall back to OpenGL and `Engine.initialize()` can fail on Adreno — even though Gallery works.

Inside `<application>` in `AndroidManifest.xml`:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

NPU backend (optional, device-specific): set `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)` per [LiteRT-LM NPU docs](https://ai.google.dev/edge/litert-lm/android).

---

## Runtime initialization

`Engine.initialize()` can take several seconds — run on a background dispatcher (`Dispatchers.IO` / `withContext`), not the main thread.

```kotlin
import com.google.ai.edge.litertlm.*

@OptIn(ExperimentalApi::class)
suspend fun createGemmaEngine(context: Context, modelPath: String): Engine {
    ExperimentalFlags.enableSpeculativeDecoding = true  // MTP — recommended on GPU

    val config = EngineConfig(
        modelPath = modelPath,
        backend = Backend.GPU(),
        visionBackend = null,          // set GPU only when chat attach / describe_image needs it
        audioBackend = Backend.CPU(),  // Gemma 4 audio subgraphs require CPU
        maxNumTokens = 8192,           // see GemmaLocalPolicy.MAX_NUM_TOKENS; 32768 OOMs at init on phone
        cacheDir = context.cacheDir.path,
    )
    return Engine(config).also { it.initialize() }
}
```

**Logging:** `Engine.setNativeMinLogSeverity(LogSeverity.ERROR)` reduces native spam in production.

### Memory and lifecycle (plain language)

OptimalX **can** run chat, semantic search, and local Gemma on an S25 — Gallery already proves the model + GPU path on the same phone. Lifecycle tuning means **when to load and unload** the big model so Android does not kill the app — **not** reloading Gemma on every mic message.

#### Product goal: keep the engine warm for scribe

Cold `Engine.initialize()` on mobile takes a long time (Gallery-scale wait). **Do not** load/unload Gemma per transcribe message. Keep one shared `Engine` warm while the user might chat locally or use local Gemma scribe.

#### `LitertLmEngineHolder` (planned singleton)

Single process-wide holder (e.g. in `OptimalXApplication`) owns the one `Engine` instance. Chat (`LitertLmProvider`) and scribe (`GemmaLocalScribeEngine` or similar) both borrow it.

| Event | Action |
|-------|--------|
| User enables **local** Eidos provider | Start background `initialize()`; show loading UI until ready |
| User enables **local Gemma scribe** toggle (Settings) | Same — **prefetch on toggle**, not on first mic tap |
| User sends chat or finishes mic scribe | **Keep engine loaded** — no unload after each message |
| User disables local provider **and** local scribe | `engine.close()`; free GPU/RAM |
| User switches to Kimi/xAI but **scribe toggle still on** | **Keep warm** for scribe |
| App minimized, recents, or hop to home-screen widget | **Keep loaded** — process is still alive |
| User closes the app (swipe away / process death) | Engine dies with the process; next launch warms again |
| OS critical memory (`TRIM_MEMORY_RUNNING_CRITICAL`) | Last-ditch unload to keep the process alive; re-warm on next foreground |

**Unload rule:** only when **neither** local provider **nor** local scribe is enabled, the process is killed, or the OS reports **running-critical** memory. Minimize / `TRIM_MEMORY_UI_HIDDEN` must not unload.

#### UX while warming

- First load (or after process death / critical-memory unload): blocking or banner — “Loading local model…” (same honesty as Gallery).
- User enables scribe toggle: start load immediately in background so mic is ready sooner.
- Mic tap while still loading: queue or disable mic until ready — never start a second parallel `initialize()`.
- `cacheDir` in `EngineConfig` speeds **re**-load after unload; it does not remove the first-load cost — warm pool is what avoids per-message wait.

| Situation | Engine in RAM? |
|-----------|----------------|
| Local provider **or** local scribe on | Yes — stay warm |
| Cloud provider + Google STT only | No |
| Cloud provider + local scribe on | Yes — warm for scribe only |
| Both local features off | No |

Gemma 4 E4B uses substantial RAM while loaded (~700 MB GPU peak during inference; more on CPU). That is the trade for instant mic/chat without reload. Avoid duplicate `Engine` instances.

---

## Conversation API (chat)

```kotlin
val conversationConfig = ConversationConfig(
    systemInstruction = Contents.of(systemPromptText),
    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
    tools = listOf(/* see Tool use below */),
)

engine.createConversation(conversationConfig).use { conversation ->
    conversation.sendMessageAsync(userText)
        .catch { /* LiteRtLmJniException */ }
        .collect { chunk -> /* stream to UI */ }
}
```

Map streaming chunks to the same `EidosChatViewModel` path used for Kimi/xAI streaming where possible.

---

## Tool use (OptimalX Agent Skills)

LiteRT-LM supports:

1. **`ToolSet` + `@Tool`** — Kotlin reflection; good for small demos.
2. **`OpenApiTool`** — JSON schema + `execute(paramsJsonString)`; **preferred for OptimalX** because `EidosToolCatalog` already defines OpenAPI-shaped `EidosToolDefinition` payloads.

### Recommended bridge

| Layer | Responsibility |
|-------|----------------|
| `EidosToolCatalog` | Canonical tool names, parameters, scope allowlists |
| `LitertLmOpenApiToolAdapter` (new) | One `OpenApiTool` per allowed tool, or one multiplexing tool with dispatch |
| `RoomToolExecutor` | Existing DB / file / workshop execution |
| `ConversationConfig.automaticToolCalling` | `false` when mirroring Eidos loop policy (caps, pause, trace) |

**Manual tool loop** (aligns with `EidosApiClient`):

```kotlin
ConversationConfig(
    tools = litertToolsFromCatalog(profile),
    automaticToolCalling = false,
)

val response = conversation.sendMessage(userText)
if (response.toolCalls.isNotEmpty()) {
    val toolResponses = response.toolCalls.map { call ->
        val resultJson = roomToolExecutor.executeLitertCall(call.name, call.arguments)
        Content.ToolResponse(call.name, resultJson)
    }
    conversation.sendMessage(Message.tool(Contents.of(toolResponses)))
}
```

Reuse `EidosToolLoopPause`, `ToolLoopPolicy`, and API trace recording from cloud sends. Provider family for local model is a new enum value (see Integration architecture).

**Scope:** Filter tools with `EidosToolCatalog.toolsForProfile(profile)` — same as cloud providers. Hosted web search tools (`web_search`, Kimi Formula) are **not** available locally; system prompt should state local-tools-only (same pattern as Chutes in [CHUTES_IMPLEMENTATION_PLAN.md](implementation/CHUTES_IMPLEMENTATION_PLAN.md)).

---

## Multi-modality

### Vision

Send images already on disk (note attachments, `read_file` paths, workshop tree):

```kotlin
conversation.sendMessage(
    Contents.of(
        Content.Text("Describe this image."),
        Content.ImageFile(absolutePath),
    )
)
```

Wire from Eidos when the user attaches an image or when `list_images` / file tools surface a path. Set `visionBackend` in `EngineConfig`.

### Audio transcription (local alternative to Whisper API)

Validated on the S25 in Google AI Edge Gallery: **record audio → send clip to Gemma 4 E4B-it → model returns transcript**. Same interaction pattern as OptimalX’s OpenAI Whisper path (`WhisperApiSpeechToTextEngine`: record locally, transcribe on stop) — except inference runs on-device.

Uses the **same warm `Engine`** as local chat (see lifecycle above) — scribe does not trigger its own load/unload cycle.

**Rolling slices (shipped):** `GemmaLocalScribeEngine` keeps `AudioRecord` open continuously (no VAD mic restart). While recording, it peels fixed-duration WAV windows (`GemmaLocalPolicy.SCRIBE_SLICE_SECONDS`, soft overlap `SCRIBE_SLICE_OVERLAP_SECONDS`), each as a fresh lightweight conversation → appends text via `TranscriptAssembler`. On stop, the remaining tail is flushed. Whisper API stays one-shot upload; users pick Gemma vs Whisper in Settings for their session length needs.

`Content.AudioBytes` must be a **WAV container** (mono 16 kHz PCM16 LE + RIFF header). Raw PCM fails miniaudio with `MA_INVALID_FILE` (-10). `AudioCaptureBuffer` / `AudioWavCodec` wrap PCM the same way Whisper upload does.

```kotlin
// Lightweight scribe conversation — no Eidos tools; shares Engine with chat holder
scribeConversation.sendMessage(
    Contents.of(
        Content.AudioBytes(wavBytes), // RIFF WAVE, not raw PCM
        Content.Text("Transcribe this audio."),
    )
)
```

Optional **Settings toggle** (Whisper-parity): “Local Gemma scribe”. Enabling it starts engine warmup in background so mic taps do not wait for cold load.

| Mic / transcription mode | Engine warm? | How it works |
|--------------------------|--------------|--------------|
| Google STT (default live mic) | No | Streaming recognizer while mic is open |
| OpenAI Whisper API | No | Record → upload WAV → API |
| **Local Gemma scribe** | **Yes** (while toggle or local provider on) | Continuous record → rolling `AudioBytes` slices → warm Gemma → live partials |

---

## Integration architecture (Eidos)

Target: new provider id `local` or `litert` selectable in Settings alongside Kimi / xAI / OpenAI / Anthropic.

```
EidosChatViewModel.send()
    → EidosApiClient.send()
        → when (activeProvider == "local") LitertLmProvider
        → else existing cloud EidosProvider implementations
```

| Component | Action |
|-----------|--------|
| `EidosProvider` | New `LitertLmProvider` implementing `send(EidosRequest): EidosResponse` |
| `EidosProviderFamily` | New `LOCAL_CONVERSATION` — single growing conversation object; no `previous_response_id` / Moonshot cache |
| `EidosPromptComposer` | Same system prompt assembly; omit provider-web blocks |
| `SettingsScreen` / `ChatTopBar` | Provider toggle + model path + backend (CPU/GPU) |
| `OptimalXApplication` | Lazy `LitertLmEngineHolder` — shared warm pool for chat + scribe |
| `LLM_API_REFERENCE.md` | Document local provider row when shipped |

**Prefetch:** `EidosPrefetchService` runs before hop 1 — unchanged (uses existing embedding stack, not LiteRT-LM).

**Thinking / reasoning:** Gemma 4 may emit reasoning channels in LiteRT-LM; map to existing `ChatMessage` thinking UI if exposed in the Kotlin API for this model.

---

## 16 KB page size compliance

Adding `litertlm-android` ships new native libraries (`liblitertlm_jni.so`, etc.). **Before release:**

1. Build `app-debug.apk` after adding the dependency.
2. Run the ELF audit from [ANDROID_16KB_PAGE_SIZE_COMPLIANCE.md](implementation/ANDROID_16KB_PAGE_SIZE_COMPLIANCE.md).
3. Bump LiteRT-LM version if any `.so` shows `Align 0x1000`.

Target devices (S25, Play SDK 36) use 16 KB pages.

---

## Performance notes (Gemma 4 E4B, reference)

From Google’s Gemma 4 benchmarks (S26 Ultra class, 1024 prefill / 256 decode tokens):

| Backend | Prefill (tok/s) | Decode (tok/s) | TTFT (s) | Peak CPU mem (MB) |
|---------|-----------------|----------------|----------|-------------------|
| CPU | ~195 | ~18 | ~5.3 | ~3283 |
| GPU | ~1293 | ~22 | ~0.8 | ~710 |

Enable **MTP / speculative decoding** on GPU for best decode throughput. Workshop long-context turns may need explicit `maxNumTokens` / KV limits in `EngineConfig` — tune per profile.

---

## Implementation checklist

### Phase 1 — Dependency + smoke test
- [x] Pin `litertlm-android` in `libs.versions.toml` (0.13.1; Kotlin bumped to 2.3.0, Room 2.7.2)
- [x] Add manifest `uses-native-library` entries
- [x] Dev smoke screen: Settings → Developer → LiteRT-LM smoke test
- [x] Instrumented test: `LitertLmSmokeInstrumentedTest` (skips if no model on device)
- [x] 16 KB native audit (`app-debug.apk` arm64 — all `0x4000`, including `liblitertlm_jni.so`)

### Phase 2 — `LitertLmProvider` chat (no tools)
- [x] Settings: provider `local`, model path, backend (GPU/CPU)
- [x] Stream tokens into chat UI via `LitertLmProvider` + `EidosStreamListener`
- [x] System prompt from `EidosPromptComposer`

### Phase 3 — Tool loop
- [x] `OpenApiTool` adapters → `RoomToolExecutor` (via `EidosApiClient` tool loop; `automaticToolCalling=false`)
- [x] Tool loop caps + `EidosToolLoopPause` (reuses existing `EidosApiClient` loop policy)
- [x] API trace for local sends (`buildTraceJson` + `emitProviderExchange`)

### Phase 4 — Multimodal
- [x] Image attachments in chat → `Content.ImageFile` (`EidosRequest.attachedImagePaths` + `LitertLmProvider`)
- [x] `describe_image` tool via local Gemma (`LitertLmVisionService` + `ImageVisionService`)
- [x] `GemmaLocalScribeEngine` → shared warm `Engine`, record → `AudioBytes`
- [x] Settings: local scribe toggle → prefetch engine on enable (`applyLitertEngineWarmState`)

### Phase 5 — Polish
- [x] Model first-run UX — Settings shows model on-disk status + Hugging Face download URL (no in-app downloader)
- [x] Warm pool stays loaded while the process is alive (minimize / widget hops); unload when both local features are off, process dies, or running-critical memory
- [x] Update [API.md](reference/API.md) and [LLM_API_REFERENCE.md](reference/LLM_API_REFERENCE.md)

---

## Open decisions

| Topic | Options | Notes |
|-------|---------|-------|
| Provider id | `local` vs `litert` | Prefer `local` in UI; `litert` in code if needed for clarity |
| Model delivery | Manual path vs HF download worker | **Shipped:** Settings → download (~3.7 GB) to app storage; scan Gallery/Downloads; file picker |
| Local transcription UX | **Decided:** warm pool — prefetch on scribe toggle; never unload per message | Separate Settings toggle like Whisper; shares `LitertLmEngineHolder` |
| Workshop on local | Full tool surface vs chat-only v1 | **Shipped v1:** [GemmaLocal.md](GemmaLocal.md) — slim prompt, 4 tools, decay history |
| Widget | Local provider on widget | May defer — model RAM + cold start |

---

## Quick reference — key classes

| LiteRT-LM | Purpose |
|-----------|---------|
| `Engine` / `EngineConfig` | Load model, pick backends |
| `Conversation` / `ConversationConfig` | Multi-turn state, tools, sampler |
| `Message`, `Contents`, `Content.*` | Text, image, audio, tool responses |
| `ToolSet`, `@Tool`, `OpenApiTool` | Function calling |
| `Backend.CPU` / `GPU` / `NPU` | Hardware selection |
| `ExperimentalFlags.enableSpeculativeDecoding` | MTP on GPU |
| `LiteRtLmJniException` | Native errors — catch at provider boundary |
