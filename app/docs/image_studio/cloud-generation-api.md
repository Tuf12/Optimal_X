# Image Studio — cloud generation API (Android)

## Scope

Android Image Studio v1 generates images **through cloud HTTP APIs**.

This doc defines the **provider abstraction**, tier mapping, request/response contract, cost rules, and error handling implementers must follow. Additional backends (local or LAN) can plug into `ImageGenerationService` without changing this contract.

---

## Architecture

```text
ImageStudioPanel (Compose)
        │
        ▼
ImageStudioViewModel
        │
        ▼
ImageGenerationService  ← interface
        │
        ├── XaiImageGenerationService   (v1 default — Grok Imagine)
        
        
        └── ChutesImageGenerationService (later — desktop Phase 4)
        │
        ▼
Encrypted API key store (existing xAI key — ApiKeyNames.XAI)
```

Generation runs off the main thread (`Dispatchers.IO`). UI observes `StateFlow<ImageStudioJobState>`.

**v1 key policy:** Reuse the **existing xAI API key** from Settings → AI. Do **not** add a separate Image Studio API key field in v1.

---

## Provider interface (Kotlin)

```kotlin
interface ImageGenerationService {
    suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult
    fun estimateCost(request: ImageGenerationRequest): CostEstimate?
    fun isConfigured(): Boolean
}

data class ImageGenerationRequest(
    val prompt: String,
    val negativePrompt: String,
    val tier: ImageTier,
    val aspectRatio: AspectRatio,
    val seed: Long?,
)

data class ImageGenerationResult(
    val imageBytes: ByteArray,
    val mimeType: String,
    val modelId: String,
    val seed: Long?,
    val width: Int,
    val height: Int,
    val actualCostUsd: Double?,
    val providerRequestId: String?,
)

enum class ImageTier { DRAFT, QUALITY }
```

Swap providers via Settings without changing panel UI (provider picker is a later enhancement; v1 ships Grok Imagine only).

---

## v1 default provider: Grok Imagine (xAI)

Mobile Image Studio uses the **Grok Imagine** image API via the user's **existing xAI API key** (`ApiKeyNames.XAI` in `EncryptedSharedPreferences`). Same account and key as Grok chat — no new provider signup.

**External docs:** [xAI Image Generation](https://docs.x.ai/developers/model-capabilities/images/generation) · [Imagine overview](https://docs.x.ai/developers/model-capabilities/imagine)

### Configuration (Settings)

| Setting | Storage |
|---------|---------|
| Provider id | `image_studio_provider` preference — v1 fixed to `xai_imagine` |
| API key | **Reuse** `ApiKeyNames.XAI` — no separate Image Studio key |
| Enabled | Require xAI key before Generate |

User-facing copy: “Image generation uses your xAI account (Grok Imagine). You pay xAI directly.”

If no xAI key is configured, disable **Generate** and link to **Settings → AI → xAI API key**.

### Tier → model mapping (initial)

| Tier | xAI model (pin at implement time) | Intent | Approx. cost |
|------|-----------------------------------|--------|--------------|
| Draft | `grok-imagine-image` | Fast iteration, lower cost | ~$0.02 / image |
| Quality | `grok-imagine-image-quality` | Higher fidelity, 2K-capable | ~$0.05 / image |

Maintain mapping in `ImageStudioModelCatalog.kt` (single source). Document pinned model ids in code comments when locked. xAI may ship newer ids (e.g. `grok-imagine-image-2.0`) — update catalog when promoting a successor.

**Negative prompt:** Grok Imagine may not expose a dedicated negative-prompt field. If unsupported, omit from the API request but still persist `negativePrompt` in `metadata_json` when the user entered one (for reload-settings UX).

### Aspect ratio

Match desktop [`aspect-ratio.js`](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/electron/image-studio/aspect-ratio.js) for **metadata width/height** and gallery layout. Pass `aspect_ratio` to xAI using the same ratio strings:

| Ratio | `aspect_ratio` param | Metadata width | Metadata height |
|-------|----------------------|----------------|-----------------|
| `1:1` | `1:1` | 1024 | 1024 |
| `2:3` | `2:3` | 832 | 1216 |
| `9:16` | `9:16` | 768 | 1344 |
| `3:2` | `3:2` | 1216 | 832 |
| `16:9` | `16:9` | 1344 | 768 |

Use `resolution: "1k"` for Draft and `"2k"` for Quality when the selected model supports it. Actual output dimensions may differ slightly — prefer provider-reported size in metadata when available.

### Request flow

1. Validate xAI API key present (`ApiKeyNames.XAI`).
2. Validate name uniqueness (local DB) **before** paid API call.
3. `POST https://api.x.ai/v1/images/generations` with `Authorization: Bearer <xai_key>`.
4. Request body: `model`, `prompt`, `n: 1`, `aspect_ratio`, optional `resolution`, `response_format: "b64_json"` (preferred — avoids temporary URL expiry).
5. Await synchronous response (coroutine + timeout, default 120s).
6. Decode image bytes from `b64_json` or download from returned URL if needed.
7. Normalize to PNG if needed.
8. Write file + insert `file_references` + `metadata_json` in one transaction.

### Cancellation

- User taps **Cancel** → cancel coroutine + abort HTTP if possible.
- If provider already charged, show message; do not save partial file.

---

## Cost estimation

Grok Imagine uses **flat per-image** pricing (not per-megapixel). Estimates are tier-based only — aspect ratio does not change cost on xAI.

| When | Behavior |
|------|----------|
| Tier change | Recompute estimate from static catalog |
| Before Generate | Show estimate in UI |
| After success | Prefer catalog actual for tier; xAI may not return usage in image response |

`ImageStudioModelCatalog` holds `estimatedUsdPerImage[tier]` — update when xAI pricing changes.

If estimate unavailable: show “Billed by xAI — see console” and still allow Generate.

Store `estimatedCostUsd` and `actualCostUsd` in metadata when known.

---

## Error handling

| Code | User message | Retry? |
|------|--------------|--------|
| `no_api_key` | Add your xAI API key in Settings → AI | After configure |
| `duplicate_name` | That name already exists in this folder | Change name |
| `api_auth_failed` | xAI API key rejected | Check key in Settings |
| `api_rate_limit` | Too many requests — wait and retry | Yes |
| `api_content_policy` | Provider rejected prompt (rare) | Edit prompt |
| `api_timeout` | Generation timed out | Yes |
| `api_error` | Provider error + detail if safe | Maybe |
| `network_offline` | No internet connection | When online |
| `cancelled` | Generation cancelled | — |
| `save_failed` | Image generated but save failed — contact support | No auto retry |

Log `providerRequestId` to Eidos API trace when debug enabled.

Never persist a `file_references` row without valid image bytes.

---

## Security

- API keys only in encrypted storage; never in logs, metadata, or sync JSON.
- Do not send file names or folder paths to the provider unless required.
- Prompt text is user content — standard LLM data handling disclaimer in Settings.

---

## Network requirements

- Cloud backends require internet for Generate.
- Show offline state on panel when `ConnectivityManager` reports no network.
- Metered connection: optional warning (“Generation uses mobile data”) — v1.1 polish.

---

## Testing without live API

| Layer | Approach |
|-------|----------|
| Unit | Fake `ImageGenerationService` returns fixed PNG bytes |
| DAO | Metadata + uniqueness tests |
| UI | Compose screenshot tests with fake ViewModel |
| Integration | Optional recorded HTTP (MockWebServer) behind flag |

CI must not require a live xAI API key.

---

## Future providers (not v1 blockers)

| Provider | When | Notes |
|----------|------|-------|
| OpenAI Images | v1.1 if requested | DALL·E / gpt-image API; reuse `ApiKeyNames.OPENAI` |
| fal.ai | Optional later | FLUX model catalog; separate fal key |
| Chutes diffusion | After desktop Phase 4 | See [chutes-implementation.md](https://github.com/Tuf12/OptimalXDesktop1.0/blob/main/chutes-implementation.md) |
| Desktop LAN proxy | **Deferred past v1** | Would need new protocol + desktop server |
| On-device diffusion | **Deferred past v1** | Separate backend behind `ImageGenerationService` |

---

## Settings UI copy (draft)

**Image Studio** (under Settings → AI)

> Generate images with Grok Imagine using your xAI API key. OptimalX does not host image generation servers.

- **Provider:** Grok Imagine (xAI) — v1 only
- **API key:** Uses your **xAI key** above — [Go to xAI settings]
- **Default tier:** Draft / Quality

**Test connection:** minimal `grok-imagine-image` call or lightweight xAI auth check (document cost if non-zero).

---

## `metadata_json.backend` values

| Value | Meaning |
|-------|---------|
| `xai_imagine` | Grok Imagine (xAI) — **v1 mobile default** |
| `openai_images` | OpenAI Images API |
| `fal_cloud` | fal.ai REST (optional future provider) |
| `chutes_cloud` | Chutes diffusion |
| `sd_cli_local` | Desktop-only — may appear on synced rows from PC |

In v1, mobile-generated rows use `xai_imagine`.
