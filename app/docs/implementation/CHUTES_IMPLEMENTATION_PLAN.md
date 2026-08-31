# Chutes.ai — Implementation Plan

**Status:** Planned — 2026-06-09  
**Goal:** Add Chutes as a **full peer provider** in Eidos — same integration depth as Kimi, Anthropic, OpenAI, and xAI. Live model catalog, account routing aliases, streaming, tool loops, prompt caching, API trace, and Settings UX. No staged “MVP” path that leaves half-wired provider code behind.

**Product position:** Kimi K2.6 remains the **primary** LLM ([KIMI_K26_MOONSHOT_SPEC.md](./KIMI_K26_MOONSHOT_SPEC.md)). Chutes is a **first-class selectable provider** with competitive pricing and a large model catalog — suitable for everyday chat, workshop builds, and model comparison without being a second-class integration.

**Authority:** Transport and hop policy defer to [PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md](./PROMPT_TRANSPORT_AND_CONTEXT_FIX_PLAN.md) and [EIDOS_LLM_CONTEXT_CLEANUP.md](./EIDOS_LLM_CONTEXT_CLEANUP.md). Kimi-specific behavior is unchanged.

**Related docs**

| Doc | Role |
|-----|------|
| [API.md](../reference/API.md) | Provider table — update on ship |
| [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md) | Per-call request/response fields |
| [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) | Provider table + web policy |
| [EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md](./EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md) | Trace wiring pattern |
| [Chutes llms.txt](https://chutes.ai/llms.txt) | External API reference |

**External platform summary**

| Item | Value |
|------|-------|
| Inference base URL | `https://llm.chutes.ai/v1` |
| Chat endpoint | `POST /v1/chat/completions` (OpenAI-compatible) |
| Model discovery | `GET /v1/models` — live source of truth |
| Auth | `Authorization: Bearer cpk_...` |
| Routing aliases | `default`, `default:latency`, `default:throughput`, inline `modelA,modelB:latency` |
| Account routing | Dashboard pool (up to 20 models) — [model routing](https://chutes.ai/app/api/model-routing) |

---

## What Chutes provides vs what OptimalX provides

| Capability | Chutes | OptimalX / Eidos |
|------------|--------|------------------|
| OpenAI Chat Completions transport | ✅ | `ChutesProvider` |
| Live model catalog + pricing metadata | ✅ | `ChutesModelCatalog` |
| Model routing / failover aliases | ✅ | Settings model picker |
| Function calling (`tools` in `supported_features`) | ✅ per model | Full `EidosToolCatalog` (scoped per context) |
| Provider-hosted web search / fetch | ❌ | **Not available** on Chutes — local tools + semantic search only |
| Kimi Formula tools | ❌ | N/A |
| Prefix / prompt caching | ✅ (`input_cache_read` pricing) | `cache_control` + `prompt_cache_key` (Kimi/Anthropic pattern) |
| Streaming | ✅ | Reuse Chat Completions SSE accumulator |
| Reasoning models (`reasoning` feature) | ✅ per model | Replay `reasoning_content` on tool rows when model advertises reasoning |

**System prompt policy when `activeProvider == "chutes"`:** Do **not** advertise provider web search. Instruct Eidos to use `search_semantic` and local read/write tools; state clearly that live web lookup is unavailable on this provider.

---

## Architecture overview

```
Settings
  ├─ chutes_api_key (EncryptedSharedPreferences)
  ├─ chutes_model (DataStore — model id or routing alias)
  └─ active_provider = "chutes"

ChutesModelCatalog
  ├─ GET /v1/models (Bearer cpk_...)
  ├─ Parse + filter (tools required for Eidos catalog section)
  ├─ Cache to DataStore with fetchedAt + TTL (24h default)
  └─ Expose: routing aliases (fixed) + catalog models (sorted)

EidosApiClient.send()
  ├─ getProviderConfig() → chutes key
  ├─ resolve chutes_model from Settings (validate against catalog + routing set)
  ├─ assembleSystemPrompt(..., activeProvider = "chutes") — no web / Formula prose
  ├─ providerFamily("chutes") → MESSAGES_CACHED
  ├─ tool loop (same caps as other providers)
  └─ ChutesProvider.send(EidosRequest)
        ├─ stream=true, stream_options.include_usage=true
        ├─ messages[] + local tools + cache_control breakpoints
        ├─ prompt_cache_key from conversation id
        ├─ reasoning replay when selected model has `reasoning` feature
        └─ emitProviderExchange → API Trace
```

---

## Provider transport — `MESSAGES_CACHED`

Chutes uses OpenAI Chat Completions. Wire it in the **same family** as Kimi and Anthropic:

| Hop | Payload |
|-----|---------|
| `FULL` | Cached system block + trimmed history + user message + tools |
| `TOOL_CONTINUATION` | **Omit system** (`includeSystemPromptInMessagesPayload`); growing `messages[]`; `prompt_cache_key` carries hop-1 prefix |

Code: extend [EidosProviderFamily.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosProviderFamily.kt):

```kotlin
"anthropic", "kimi", "chutes" -> EidosProviderFamily.MESSAGES_CACHED
```

Outbound history pipeline (same order as Kimi):

1. `EidosHistoryTrimmer.prepareOutboundHistory` — bulk tool stub trim  
2. `EidosHistoryTrimmer.prepareReasoningOutboundHistory` — reasoning replay rules (see below)

Workshop: use the **same** Responses incremental fix path as other `MESSAGES_CACHED` providers — no Chutes-specific workshop transport exceptions.

---

## `ChutesProvider`

**File:** [ChutesProvider.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/ChutesProvider.kt) (new)

| Concern | Implementation |
|---------|----------------|
| Endpoint | `https://llm.chutes.ai/v1/chat/completions` |
| Model | Constructor param from Settings (`chutes_model`) |
| `max_tokens` | 32_384 (match Anthropic/Kimi ceiling) |
| Streaming | `stream: true`; reuse [KimiStreamParser.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/KimiStreamParser.kt) (`ChatCompletionsStreamAccumulator` rename optional — same OpenAI SSE shape) |
| Tools | Local `EidosToolDefinition` only — OpenAI `function` schema; **no** hosted web tools |
| System | Anthropic-style `cache_control` on system text block when non-blank |
| Tools cache | `cache_control` on last tool definition (Kimi pattern) |
| History cache | `cache_control` on last history message |
| `prompt_cache_key` | From `EidosRequest.promptCacheKey` |
| Tool calls | Filter to `request.toolDefinitions` names only |
| Usage | [ProviderUsageParser](../../src/main/java/com/example/optimalx/data/eidos/provider/ProviderUsageParser.kt) — Chat Completions fields + `input_cache_read` if present |
| Trace | `request.emitProviderExchange(requestBody, response)` |
| Reasoning | When `modelSupportsReasoning == true`: emit `reasoning_content` on assistant rows with `tool_calls`; parse `delta.reasoning_content` / `message.reasoning_content` from stream |

### Reasoning models

Chutes marks reasoning-capable models with `supported_features` containing `"reasoning"`.

1. Store `supportsReasoning: Boolean` on catalog entries and pass into `ChutesProvider`.
2. Generalize `prepareKimiOutboundHistory` → `prepareReasoningOutboundHistory` in [EidosHistoryTrimmer.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosHistoryTrimmer.kt) (keep `prepareKimiOutboundHistory` as deprecated alias calling the shared function).
3. On tool continuations with reasoning models: preserve `assistantReasoningContent` on in-flight assistant+tool rows (same rules as Kimi).
4. UI: existing reasoning hop display + [ReasoningPersistPolicy.kt](../../src/main/java/com/example/optimalx/data/eidos/ReasoningPersistPolicy.kt) — no Chutes-specific chat schema changes.

If a reasoning model fails when `reasoning_content` is omitted on tool rows, treat as provider bug and document model id in this file’s **Verified models** section after QA.

### Sampling parameters

Respect `supported_sampling_parameters` from catalog when building payload:

- Always send `temperature` only if listed (default 0.6 when supported).
- Do **not** send unsupported params — Chutes documents per-model acceptance via catalog metadata.

---

## `ChutesModelCatalog`

**Files:**

| File | Role |
|------|------|
| [ChutesModelCatalog.kt](../../src/main/java/com/example/optimalx/data/eidos/ChutesModelCatalog.kt) | Fetch, parse, filter, cache |
| [ChutesModelCatalogEntry.kt](../../src/main/java/com/example/optimalx/data/eidos/ChutesModelCatalogEntry.kt) | Serializable row (id, contextLength, pricing label, features, TEE flag) |

### Fetch

```
GET https://llm.chutes.ai/v1/models
Authorization: Bearer <cpk_...>
```

Parse `data[]` fields used in UI and provider:

- `id` — API model string
- `context_length`, `max_output_length`
- `supported_features` — require `tools` for **Eidos-compatible** catalog section
- `supported_sampling_parameters`
- `confidential_compute` — TEE badge in UI
- `pricing.prompt` / `pricing.completion` (USD per 1M) — display only

### Cache

- Persist JSON blob + `fetchedAtMillis` in DataStore (`SettingsKeys.CHUTES_MODEL_CATALOG_JSON`, `CHUTES_MODEL_CATALOG_FETCHED_AT`).
- TTL: **24 hours**; stale cache still shown with “Refresh” affordance.
- On key save: trigger fetch if cache empty or older than TTL.
- Manual **Refresh models** button in Settings always refetches.

### Routing aliases (fixed entries — not from `/v1/models`)

Always available at top of model picker regardless of catalog fetch:

| Model id | Label |
|----------|-------|
| `default` | Account routing pool (failover) |
| `default:latency` | Account pool — lowest TTFT |
| `default:throughput` | Account pool — highest TPS |

Persisted `chutes_model` may be any routing alias, any catalog `id`, or a user-typed inline routing string (e.g. `modelA,modelB:latency`) — validate non-empty and max length 512; do not restrict inline lists to catalog ids.

### Default model

When no preference stored and catalog has loaded: pick first catalog entry sorted by `(pricing.prompt + pricing.completion)` ascending with `tools` + `context_length >= 32_000`. If none match, fall back to `default:latency`.

`SettingsDefaults.CHUTES_MODEL = "default:latency"`.

---

## Settings UX

**Files:** [SettingsPreferences.kt](../../src/main/java/com/example/optimalx/data/preferences/SettingsPreferences.kt), [SettingsViewModel.kt](../../src/main/java/com/example/optimalx/ui/settings/SettingsViewModel.kt), [SettingsScreen.kt](../../src/main/java/com/example/optimalx/ui/settings/SettingsScreen.kt)

### API keys section

- Label: **Chutes**
- Secure field → `ApiKeyNames.CHUTES = "chutes_api_key"`
- Help link: `https://chutes.ai/app/api/api-keys`
- On save: `viewModel.refreshChutesModelCatalog()`

### Model picker (below key field)

`ChutesModelPicker` composable:

1. **Routing** group — `default`, `default:latency`, `default:throughput`
2. **Models** group — live catalog (tools-capable), sorted by display name
   - Row: model id (truncated), context (e.g. `128k`), price hint, TEE chip
3. **Refresh models** text button + last-fetched timestamp
4. Loading / error states (show stale cache + error banner on fetch failure)
5. Optional filter toggle: **Show all models** (includes non-`tools` entries greyed out with “No tool calling” — for curiosity only; not selectable for active chat)

### Provider section

```kotlin
ProviderOption(
    label = "Chutes",
    selected = activeProvider == "chutes",
    onClick = { viewModel.setActiveProvider("chutes") },
)
```

Helper text under Provider when Chutes selected:

> Uses your Chutes API key and selected model. Local Eidos tools only — no provider web search. Configure routing pools at chutes.ai/app/api/model-routing.

### Preference keys

```kotlin
// SettingsKeys
val CHUTES_MODEL = stringPreferencesKey("chutes_model")
val CHUTES_MODEL_CATALOG_JSON = stringPreferencesKey("chutes_model_catalog_json")
val CHUTES_MODEL_CATALOG_FETCHED_AT = longPreferencesKey("chutes_model_catalog_fetched_at")

// SettingsDefaults
const val CHUTES_MODEL = "default:latency"

// ApiKeyNames
const val CHUTES = "chutes_api_key"
```

---

## `EidosApiClient` wiring

**File:** [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt)

| Change | Detail |
|--------|--------|
| `getProviderConfig()` | `"chutes" -> ApiKeyNames.CHUTES` |
| `getProvider()` | `"chutes" -> ChutesProvider(..., model = chutesModel, supportsReasoning = catalogLookup.supportsReasoning)` |
| Resolve `chutesModel` | DataStore `CHUTES_MODEL`; validate routing aliases + last-known catalog ids; else default |
| `providerDisplayName` | `"Chutes"` |
| `providerEndpoint` | `https://llm.chutes.ai/v1/chat/completions` |
| `buildProviderErrorMessage` | Include Chutes in copy |
| `assembleSystemPrompt` | New branch: `activeProvider == "chutes"` → local-tools-only web note; **skip** Kimi Formula blocks and generic “provider web search” block |
| `preloadKimiFormulaTools` | No-op when not Kimi (unchanged) |
| Tool loop / trace | No Chutes exceptions |

---

## Other code touch points

| File | Change |
|------|--------|
| [EidosProviderFamily.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosProviderFamily.kt) | Add `chutes` → `MESSAGES_CACHED` |
| [EidosHistoryTrimmer.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosHistoryTrimmer.kt) | `prepareReasoningOutboundHistory` (generalize Kimi) |
| [MemoryRolloverService.kt](../../src/main/java/com/example/optimalx/data/eidos/MemoryRolloverService.kt) | Chutes works via `EidosApiClient` when selected — no change unless rollover hardcodes provider list |
| [WorkshopPreviewPanel.kt](../../src/main/java/com/example/optimalx/ui/workshop/WorkshopPreviewPanel.kt) | Add `"chutes"` to `nativeEidosInfer` provider allowlist + `ChutesProvider` branch + model from Settings |
| [ImageVisionService.kt](../../src/main/java/com/example/optimalx/data/eidos/ImageVisionService.kt) | Unchanged (OpenAI vision path) |
| [EidosChatViewModel.kt](../../src/main/java/com/example/optimalx/ui/eidos/EidosChatViewModel.kt) | No Kimi preload for Chutes; streaming listener already provider-agnostic |
| [EidosApiTrace implementation plan](../implementation/EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md) | Add ChutesProvider to provider list in diagram |

---

## System prompt — Chutes branch

In `assembleSystemPrompt`, when `activeProvider == "chutes"`:

```
Provider: Chutes (OpenAI-compatible inference).
Local Eidos tools are available (search_semantic, read/write, workshop tools as scoped).
Provider-hosted web search is NOT available on Chutes — use search_semantic and local reads for grounding.
For public web facts the user explicitly needs, say live web lookup is unavailable on this provider.
```

Do **not** inject Kimi Formula convert/date/excel lines or web_search/fetch instructions.

Web panel scope (`web_editor` / `web_widget`): note that Formula fetch is unavailable; user must switch provider for live page fetch.

---

## Implementation order

Build in this sequence. Each step should be **complete and shippable** before moving on — no “temporary” hardcoded model that never gets replaced.

### Chunk 1 — Data layer + catalog ✅ (2026-06-09)

- [x] `ChutesModelCatalogEntry` + JSON (de)serialization
- [x] `ChutesModelCatalog` fetch/parse/filter/cache/TTL
- [x] Unit tests: parse sample `/v1/models` payload; filter `tools`; routing alias constants
- [x] Settings keys + defaults

### Chunk 2 — Provider ✅ (2026-06-09)

- [x] `ChutesProvider` — full payload, streaming, tools, cache markers, usage, trace
- [x] `prepareReasoningOutboundHistory` generalization
- [x] Unit tests: payload shape FULL vs TOOL_CONTINUATION; tool-call parse; reasoning row replay
- [x] Stream tests via existing `KimiStreamParser` test patterns

### Chunk 3 — Eidos integration ✅ (2026-06-09)

- [x] `EidosProviderFamily` + `EidosProviderFamilyTest`
- [x] `EidosApiClient` provider factory, model resolution, error strings
- [x] `assembleSystemPrompt` Chutes branch
- [x] `WorkshopPreviewPanel` `nativeEidosInfer` support

### Chunk 4 — Settings UI ✅ (2026-06-09)

- [x] API key field + save → catalog refresh
- [x] `ChutesModelPicker` with routing group + catalog + refresh
- [x] Provider radio option
- [x] `SettingsViewModel` flows: `chutesKey`, `chutesModel`, `chutesCatalogState`

### Chunk 5 — Docs + verification

- [ ] Update [API.md](../reference/API.md) provider table
- [ ] Update [LLM_API_REFERENCE.md](../reference/LLM_API_REFERENCE.md)
- [ ] Update [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) provider table
- [ ] Mark this plan **Shipped** with date
- [ ] Run full verification checklist below

---

## Tests

| Test file | Coverage |
|-----------|----------|
| `ChutesModelCatalogTest.kt` | Parse, filter, TTL, routing alias list |
| `ChutesProviderTest.kt` | Payload, phase omit-system, tools-only, reasoning flag |
| `EidosProviderFamilyTest.kt` | `chutes` → `MESSAGES_CACHED` |
| `EidosHistoryTrimmerTest.kt` | `prepareReasoningOutboundHistory` (rename/refactor tests) |
| `ChutesStreamParserTest.kt` | Optional if accumulator renamed; else extend Kimi stream tests |

Use recorded JSON fixtures from Chutes docs — no live API in unit tests.

---

## Verification checklist

Manual QA after all chunks:

### Settings

- [ ] Save Chutes API key → catalog fetch runs → models appear
- [ ] Refresh models updates timestamp
- [ ] Select routing alias `default:latency` → persists across app restart
- [ ] Select concrete model id → persists
- [ ] Select Chutes as active provider

### Chat (subfolder)

- [ ] Send message → streaming text appears
- [ ] Tool call (`search_semantic`) → result → final answer
- [ ] Multi-hop tool loop (3+ rounds) → no duplicate system bloat (API Trace round 2+ has empty/omitted system)
- [ ] Reasoning model (if available): thinking visible in UI; tool loop completes

### Workshop

- [ ] Build kickoff with tool reads/writes
- [ ] Auto-continue handoff still works (same provider)

### Web policy

- [ ] Ask for live web facts → Eidos states web unavailable (no hallucinated “searching web”)
- [ ] Semantic search still works

### API Trace

- [ ] Enable trace → Chutes send produces rounds with `FULL` + `TOOL_CONTINUATION`
- [ ] Request JSON shows `https://llm.chutes.ai/v1/chat/completions` shape

### Routing

- [ ] `default:latency` resolves (observe model_used in response if Chutes returns it)
- [ ] Dashboard-configured pool used when `default` selected

### Errors

- [ ] Missing key → clear error before send
- [ ] Invalid/expired key → user-readable message

Build: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`

---

## Verified models (post-QA)

Fill after manual testing — not a hardcoded allowlist in code.

| Model id | Tools | Reasoning | Workshop | Notes |
|----------|-------|-----------|----------|-------|
| _(QA)_ | | | | |

---

## Non-goals

- Chutes provider-hosted web search (platform does not offer)
- Replacing Kimi as default / primary provider
- Per-conversation provider override (global Settings only — same as today)
- Custom Chute deployment URLs (`https://{user}-{name}.chutes.ai/v1`) — shared inference endpoint only
- Research-opt-in proxy (`research-data-opt-in-proxy.chutes.ai`) — separate setting if ever needed
- TAO billing UI — USD pricing display from catalog is sufficient

---

## File index (target tree)

```
data/eidos/ChutesModelCatalog.kt
data/eidos/ChutesModelCatalogEntry.kt
data/eidos/provider/ChutesProvider.kt
data/eidos/EidosProviderFamily.kt
data/eidos/EidosHistoryTrimmer.kt
data/eidos/EidosApiClient.kt
data/preferences/SettingsPreferences.kt
ui/settings/SettingsViewModel.kt
ui/settings/SettingsScreen.kt
ui/workshop/WorkshopPreviewPanel.kt
test/.../ChutesModelCatalogTest.kt
test/.../ChutesProviderTest.kt
test/.../EidosProviderFamilyTest.kt
docs/implementation/CHUTES_IMPLEMENTATION_PLAN.md
docs/reference/API.md
docs/reference/LLM_API_REFERENCE.md
docs/systems/PROMPT_SYSTEM.md
```

---

## Post-ship documentation

Do **not** create a standalone `CHUTES_PROVIDER.md` unless QA surfaces non-obvious per-model behavior worth an authoritative spec (mirror Kimi spec only if needed).

On ship, update the three reference tables and mark this plan **Shipped — YYYY-MM-DD**.
