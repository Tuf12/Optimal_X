# Chat vision attach — mobile implementation plan

**Status:** Phase 2 shipped 2026-08-14 — Phase 3 polish planned  
**Desktop (shipped):** [design.md — Chat vision](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md#chat-vision-desktop-shipped)  
**Product UI:** [CHAT_UI.md](../architecture/CHAT_UI.md) (composer attach)  
**Parity:** [features.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/features.md)

Bring the **desktop chat-image attach** to Android so the user can paste or pick a picture in Eidos chat and get a description in that thread.

This is **not** `describe_image(fileReferenceId)`. That tool already exists on mobile for Files rows. Chat attach puts pixels on **this send** and shows a thumbnail on the user bubble.

---

## Desktop behavior to match (locked)

| Rule | Desktop today |
|------|----------------|
| Entry | Composer image button (file picker) **or** paste a screenshot |
| Mode | **Per send**, not a sticky vision mode |
| Empty text | Becomes “Describe this image.” |
| Bubble | Thumbnail on the user message |
| Persistence | `chat_messages.image_attachment_json` `{ fileName, mimeType, storedName }` + bytes on disk |
| Next send | Normal text model / provider unless another image is attached |
| Files | Chat images are **not** `file_references` rows |

Full flow: desktop [design.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/design.md#chat-vision-desktop-shipped).

---

## What mobile already has

| Piece | Status |
|-------|--------|
| `describe_image` on Files | Shipped — [TOOL_FUNCTIONS.md](../reference/TOOL_FUNCTIONS.md), `ImageVisionService` / LiteRT vision |
| Composer attach button | **Shipped (Phase 1)** — gallery / camera / paste; pending “Vision this send” chip; widget omits attach |
| `ChatMessage.imageAttachmentJson` | **Shipped (Phase 0)** — Room v30 + Tier 2 sync; bubble filename chip when JSON is present |
| Multimodal user parts on cloud providers | **Shipped (Phase 2)** — current send only, same families as `ImageVisionService` |
| Desktop GPU proxy `/api/v1/llm/chat` | Not a mobile chat provider yet; local/cloud vision used instead |

---

## Phases

### 0 — Schema + sync field — **shipped 2026-08-14**

- Add `imageAttachmentJson TEXT` on `chat_messages` (same JSON shape as desktop).
- Room migration; `ChatMessage` + `SyncDtos` / apply mappers.
- Desktop already syncs the field on Tier 2. Bytes stay on the PC (`files/chat-images/`). Phone shows a filename chip if JSON is present but local bytes are missing.

**Done when:** a desktop-attached message syncs to the phone without crashing; bubble can show “image attached” even if pixels are absent.

### 1 — Composer + bubble (any provider) — **shipped 2026-08-14**

- Image button on `ChatComposerBar` (gallery / camera / paste where Android allows).
- Pending chip; send with image even if text is empty.
- User bubble thumbnail from a local cache file (app files dir, not Files panel).
- Persist JSON + copy bytes next to the conversation (device-local path in `storedName` or a phone-side cache key).

**Done when:** user can pick or paste an image, send, and see the thumbnail after rotation / reopen.

### 2 — Inference wiring — **shipped 2026-08-14**

Pick the active Eidos provider for **that send only**:

| Provider | How the model sees the image |
|----------|------------------------------|
| Kimi / OpenAI / Anthropic / xAI / Chutes | Multimodal user content (same families `ImageVisionService` already speaks) |
| Local Gemma (LiteRT) | Existing vision backend (`LitertLmVisionService`) |
| Desktop GPU proxy | Forward image with the chat request **or** fall back to on-device / cloud vision — decide in this phase; do not silently drop the picture |

Do **not** require a GPU model swap the way desktop Ollama does unless the active provider is text-only.

Keep `describe_image` for Files. Chat attach does not create a `file_references` row.

**Done when:** “what’s in this picture?” in Eidos chat returns a description from the attached image on the user’s current provider.

### 3 — Polish (optional same PR if small)

- Widget composer: omit attach in v1 unless it is free.
- Size cap 4 MB after ingest: camera / gallery stills are downscaled (2048 px long edge) and JPEG-recompressed so phone originals are not rejected. PNG / JPEG / WebP / GIF (HEIC convert via ImageDecoder).
- Chat UI copy/edit: keep the image on retry; editing text does not strip the attachment.

---

## Non-goals (v1)

- Syncing chat-image **bytes** phone ↔ desktop (metadata only).
- Saving chat attaches into the subfolder Files list.
- Replacing `describe_image`.
- Sticky “vision mode” in the composer.

---

## Implementation map (expected)

| Layer | Likely files |
|-------|----------------|
| UI | `ChatComposerBar.kt`, `ChatMessageBubble` / footer, `EidosChatViewModel` |
| Model | `ChatMessage.kt`, `AppDatabase` migration |
| Sync | `SyncDtos.kt`, `SyncMappers.kt` |
| Send | `EidosApiClient` / provider multimodal user parts; LiteRT vision path |
| Docs | this file, [CHAT_UI.md](../architecture/CHAT_UI.md), [DATA_MODEL.md](../architecture/DATA_MODEL.md) |

---

## Changelog

| Date | Change |
|------|--------|
| 2026-08-14 | Plan created after desktop chat vision shipped |
| 2026-08-14 | Phase 0: `imageAttachmentJson` on `chat_messages` (Room v30), sync wire, filename chip |
| 2026-08-14 | Phase 1: composer attach (gallery/camera/paste), local `chat-images` bytes, bubble thumbnail |
| 2026-08-14 | Phase 2: attach image on current send (cloud multimodal + LiteRT); missing local bytes are not dropped silently (in-app + worker) |
| 2026-08-30 | Ingest downscale: persist camera/gallery stills at 2048 px JPEG so the 4 MB cap no longer rejects phone originals |
