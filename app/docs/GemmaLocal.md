# Gemma 4 E4B — local transport (Mobile)

Canonical policy for **on-device Gemma** (`provider = local`, `gemma-4-E4B-it.litertlm`) inside OptimalX Android. Runtime wiring lives in `[GemmaLocalPolicy](../../src/main/java/com/example/optimalx/data/litert/GemmaLocalPolicy.kt)`. LiteRT integration (engine, backends, model install) remains in [LITERT_LM.md](LITERT_LM.md).

**Not the same as cloud Eidos.** Gallery runs Gemma with a short instruction and in-chat KV memory. OptimalX cloud providers use rolling summary + large outbound history + full tool surfaces. Local Gemma uses a **dedicated slim transport** defined here.

---



## Product role (v1)


| Good fit                                                   | Defer to cloud                                    |
| ---------------------------------------------------------- | ------------------------------------------------- |
| General chat, quick Q&A                                    | Long workshop build loops                         |
| `search_semantic` over your notes                          | Full 12-tool general-app surface                  |
| Create parent/subfolder + `write_note` capture             | Heavy prefetch / daily memory dumps in every turn |
| Local scribe / describe image (separate warm-engine paths) | Widget with full Eidos scope                      |


**Future (document here as we ship):** route **individual OptimalX surfaces** to local Gemma as dedicated modes — e.g. “Quick capture”, “Note Q&A only”, “Scribe-only” — each with its own slim prompt and tool allowlist. v1 ships one **chat transport** for the `local` provider.

---



## Context budget

`gemma-4-E4B-it.litertlm` has a practical LiteRT ceiling we record as 32k (`MODEL_MAX_NUM_TOKENS`); upstream Gemma 4 E4B may claim ~128k. `EngineConfig.maxNumTokens` **pre-allocates** the KV cache at init. **32768 OOMs / kills the process on phone** (observed on S25). Current mobile default is **8192** (was 4096 Gallery-class). Probe upward on-device (8192 → 12288 → 16384); rescale `MAX_OUTBOUND_CHARS` with the window. All knobs live in `GemmaLocalPolicy.kt`.


| Layer         | Policy                                                                                        | Constant                                               |
| ------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| Engine window | Explicit LiteRT KV / context length (input + output)                                          | `MAX_NUM_TOKENS = 8192` (`MODEL_MAX_NUM_TOKENS = 32768`) |
| System prompt | Slim local prompt only — no prefetch block, no rolling-summary injection, no full `APP_MODEL` | `MAX_SYSTEM_PROMPT_CHARS` (~2.5k target)               |
| Chat history  | **No rolling summary** on the wire; verbatim messages only                                    | —                                                      |
| History decay | Drop **oldest user-led exchanges** when over budget                                           | `MAX_USER_EXCHANGES = 6`, `MAX_OUTBOUND_CHARS = 12000` |
| Tool JSON     | Four tools only (below)                                                                       | —                                                      |


Decay is **lossy** for old turns (like Gallery when the thread gets too long). Full thread remains in Room + semantic index; local model only sees the trimmed tail. Leave headroom under `MAX_NUM_TOKENS` for system, tool schemas, and decode.

---



## History

Local sends use `[ConversationOutboundHistory.buildForLocalGemma](../../src/main/java/com/example/optimalx/data/eidos/ConversationOutboundHistory.kt)` — DB messages only, then decay trim. Cloud sends use a verbatim tail plus active-conversation prefetch. There is no rolling conversation summary.

---



## Tool allowlist (v1)

Registered for LiteRT-LM when `provider = local` **and** tools mode is on (`LOCAL_GEMMA_TOOLS_ENABLED`, default true):


| Tool                   | Purpose                                        |
| ---------------------- | ---------------------------------------------- |
| `search_semantic`      | Primary retrieval over notes, files, past chat |
| `create_parent_folder` | New top-level project folder                   |
| `create_subfolder`     | New workspace under a parent                   |
| `write_note`           | Append markdown to a subfolder’s note          |


Execution remains in **OptimalX** (`RoomToolExecutor` via `EidosApiClient`); `automaticToolCalling = false` on the LiteRT conversation.

**Chat-only mode (experiment):** Chat top bar wrench icon (Local Gemma only) toggles tools off. Next send uses empty `toolDefinitions` + [GemmaLocalPrompt](../../src/main/java/com/example/optimalx/data/litert/GemmaLocalPrompt.kt) chat-only system text (no tool rules). Pref: `SettingsKeys.LOCAL_GEMMA_TOOLS_ENABLED`. Cloud providers ignore this. Mid-thread switch is fine.

**Not on local v1:** `edit_note_section`, `read_file`, folder trash/rename, memory writers, workshop tools, web tools, etc. Expand in later “surface modes” sections below.

---



## System prompt (v1)

Composed by `[GemmaLocalPrompt](../../src/main/java/com/example/optimalx/data/litert/GemmaLocalPrompt.kt)`:

- Short Eidos identity (concise, on-device).
- **Tools on:** brief tool-use rules (`search_semantic` first for content; `write_note` appends at end; folder ids).
- **Tools off:** chat-only core (no tool rules; tell the model tools are unavailable).
- Optional **active location** line (`subfolderId` / `parentFolderId`) when the chat is scoped.

Does **not** include: full ontology blocks, prefetch RAG blocks, daily memory injection, `TOOL_FIRST_CONTEXT_RULES`, or cloud provider web notes.

---



## Local scribe (rolling slices)

Mic path when Settings → **Use local Gemma scribe** is on (`GemmaLocalScribeEngine`).

- **Capture:** continuous `AudioRecord` into `AudioCaptureBuffer` (pause/resume allowed). No VAD mic restart.
- **Process:** while recording, peel fixed WAV windows — `SCRIBE_SLICE_SECONDS` (default 8) with `SCRIBE_SLICE_OVERLAP_SECONDS` (0.5). One LiteRT conversation is reused for the recording session (creating a new `Gemma4DataProcessor` per slice OOMs). Each window → `sendMessage` → append via `TranscriptAssembler` (chat shows live partials).
- **Stop:** flush remaining tail if ≥ `SCRIBE_MIN_FLUSH_SECONDS`. Committed PCM prefix is dropped after each slice so long sessions do not retain the full tape in RAM.
- **Engine:** warm pool skips the vision backend until chat attach / `describe_image`. First audio conversation still compiles the CPU audio encoder.
- **Tuning:** raise/lower slice length with `MAX_NUM_TOKENS`. Whisper API remains a separate manual toggle for long one-shot cloud transcription.


## Implementation map


| Concern                                  | Location                                                          |
| ---------------------------------------- | ----------------------------------------------------------------- |
| Policy constants + active-provider check | `GemmaLocalPolicy.kt`                                             |
| Slim system prompt                       | `GemmaLocalPrompt.kt`                                             |
| Outbound history (no summary, decay)     | `ConversationOutboundHistory.buildForLocalGemma`                  |
| Tool override + prompt override          | `EidosApiClient.send` when `isLocalProvider`                      |
| Skip fold on send                        | `EidosChatViewModel`, `EidosChatSendWorker`, `WidgetVoiceService` |
| Inference                                | `LitertLmProvider`, `LitertLmEngineHolder`                        |
| Rolling local scribe                     | `GemmaLocalScribeEngine`, `AudioCaptureBuffer`, `AudioWavCodec`   |

**Streaming:** LiteRT `Message.toString()` yields **deltas** (new tokens only). Gallery accumulates with `content += delta`, then normalizes `\\n` → newline. OptimalX does the same in `LitertLmProvider`, then strips turn markup via `LitertLmGemmaChatMarkup`. Replacing (not appending) each delta leaves only the last chunk — classic 2–3 word replies. Do not use `renderMessageIntoString` for chat display (turn-template junk).

**Tools + chat:** LiteRT allows tools on the same `Conversation` as chat (unlike Gallery’s separate UI modes). v1 uses four tools on general chat; if quality suffers, add a chat-only mode in Future surface modes below.

---



## Future surface modes (placeholder)

Add subsections here when we wire dedicated local modes:

- [ ] **Quick capture** — `write_note` / `create_subfolder` only, no search.
- [ ] **Note Q&A** — `search_semantic` + read paths, no writes.
- [ ] **Subfolder-scoped chat** — inject minimal subfolder catalog, cap tools to search + write.
- [ ] **Workshop local** — explicit opt-in; likely chat-only or read-only first.

Each mode should define: prompt template, tool set, history budget, and whether vision/audio backends are enabled.

---



## Related docs

- [LITERT_LM.md](LITERT_LM.md) — engine, GPU/CPU backends, model file, warm pool
- [LLM_API_REFERENCE.md](reference/LLM_API_REFERENCE.md) — cloud transport (rolling summary, 30k cap)
- [PROMPT_SYSTEM.md](systems/PROMPT_SYSTEM.md) — full Eidos scope profiles (cloud)

