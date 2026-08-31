# EIDOS_API_TRACE — Implementation Plan

**Status:** Shipped — 2026-06-04  
**Product spec:** [EIDOS_API_TRACE.md](../systems/EIDOS_API_TRACE.md)  
**Prompt context:** [PROMPT_SYSTEM.md](../systems/PROMPT_SYSTEM.md) · [EidosApiClient.assembleSystemPrompt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt)

Tracks the developer API Trace inspector: capture pipeline, storage, UI, and feature gating.

---

## Architecture overview

```
User send (EidosApiClient.send)
  ├─ EidosApiTraceFeature.isEnabled? → EidosApiTraceRecorder.createIfEnabled
  ├─ beginRun(directory, conversation, provider, user preview)
  ├─ tool loop:
  │    buildTracedRequest(..., onProviderExchange)
  │      → KimiProvider / OpenAIProvider / AnthropicProvider / XAIProvider
  │      → emitProviderExchange(bodyJson, EidosResponse)
  │      → recorder.recordExchange(phase, request, response)
  └─ finally: finishRun(status)
```

UI reads the same Room tables via DAO + Compose screens; navigation wired in [AppNavigation.kt](../../src/main/java/com/example/optimalx/ui/navigation/AppNavigation.kt).

---

## Data layer ✅

| Item | File |
|------|------|
| Entities | [EidosApiTraceModels.kt](../../src/main/java/com/example/optimalx/data/model/EidosApiTraceModels.kt) |
| DAO | [EidosApiTraceDao.kt](../../src/main/java/com/example/optimalx/data/dao/EidosApiTraceDao.kt) |
| DB v21 migration | [AppDatabase.kt](../../src/main/java/com/example/optimalx/data/db/AppDatabase.kt) — `MIGRATION_20_21` |

Tables:

- `eidos_api_trace_runs` — one row per user send
- `eidos_api_trace_rounds` — FK `runId`, `roundIndex`, `phase`, `requestJson`, `responseJson`

Directory list query groups by `directoryKey`, orders by `MAX(startedAtMillis) DESC`.

---

## Feature gate ✅

| Item | File |
|------|------|
| Feature flag | [EidosApiTraceFeature.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiTraceFeature.kt) |
| Settings key | [SettingsPreferences.kt](../../src/main/java/com/example/optimalx/data/preferences/SettingsPreferences.kt) — `EIDOS_API_TRACE_ENABLED` |
| Settings UI | [SettingsScreen.kt](../../src/main/java/com/example/optimalx/ui/settings/SettingsScreen.kt) — Developer section |
| ViewModel | [SettingsViewModel.kt](../../src/main/java/com/example/optimalx/ui/settings/SettingsViewModel.kt) — `eidosApiTraceEnabled` |

When disabled: `createIfEnabled` returns `null`; `EidosRequest.onProviderExchange` stays `null`; no Eidos section card.

---

## Capture pipeline ✅

| Component | Responsibility |
|-----------|----------------|
| [EidosApiTraceDirectoryResolver](../../src/main/java/com/example/optimalx/data/eidos/EidosApiTraceDirectory.kt) | Map `scopeType` + ids → `directoryKey` / label |
| [EidosApiTraceRecorder](../../src/main/java/com/example/optimalx/data/eidos/EidosApiTraceRecorder.kt) | `beginRun` / `recordExchange` / `finishRun`; prune to 250 runs |
| [EidosApiTraceJson](../../src/main/java/com/example/optimalx/data/eidos/EidosApiTraceJson.kt) | Pretty request JSON; summarize `EidosResponse` |
| [EidosModels.kt](../../src/main/java/com/example/optimalx/data/eidos/model/EidosModels.kt) | `EidosRequest.onProviderExchange` callback |
| [EidosProviderTrace.kt](../../src/main/java/com/example/optimalx/data/eidos/provider/EidosProviderTrace.kt) | `emitProviderExchange()` |
| Providers | Kimi, OpenAI, Anthropic, xAI — call `emitProviderExchange` after building body + parsing response |
| [EidosApiClient.kt](../../src/main/java/com/example/optimalx/data/eidos/EidosApiClient.kt) | `buildTracedRequest`, `beginRun` before loop, `finishRun` in `finally` |

### Status values

- `in_progress` — set at `beginRun`
- `completed` — successful return from `send()`
- `error` — exception path before `finally` overwrites (default `traceStatus` starts `completed`, set `error` on failure branch)

---

## UI ✅

| Screen | Composable | Route |
|--------|------------|-------|
| Directories | `EidosApiTraceDirectoriesScreen` | `eidos_api_trace` |
| Runs in directory | `EidosApiTraceRunsScreen` | `eidos_api_trace_runs/{directoryKey}` |
| Run detail | `EidosApiTraceRunDetailScreen` | `eidos_api_trace_run/{runId}` |

Defined in [EidosApiTraceScreens.kt](../../src/main/java/com/example/optimalx/ui/eidos/EidosApiTraceScreens.kt).

Entry point: [EidosSectionScreen](../../src/main/java/com/example/optimalx/ui/eidos/EidosSystemScreens.kt) shows **API Trace** `FolderCard` when `apiTraceEnabled` is true.

Navigation: [AppNavigation.kt](../../src/main/java/com/example/optimalx/ui/navigation/AppNavigation.kt) — `Routes.EIDOS_API_TRACE*` + `apiTraceEnabled` flow on `EIDOS_SECTION`.

Detail UX: expandable rounds, Request/Response tabs, copy to clipboard, per-run delete, per-directory clear, global clear.

---

## Verification checklist

- [ ] Enable toggle → Eidos section shows **API Trace**
- [ ] Disable toggle → card hidden; new sends not recorded
- [ ] Send in subfolder chat → run appears under `subfolder:{id}`, newest first
- [ ] Tool-using turn → run has multiple rounds (`FULL` + `TOOL_CONTINUATION`)
- [ ] Round 1 request JSON contains `system` / instructions + tools + messages (provider-specific shape)
- [ ] Clear directory / clear all removes rows
- [ ] 251st run triggers prune of oldest run

Build: `./gradlew :app:compileDebugKotlin`

---

## Known gaps / follow-ups

| Item | Notes |
|------|-------|
| Stale `previousResponseId` retry path | May issue an extra provider call not yet traced if that branch fires |
| Settings deep-link | Optional: navigate to API Trace from Developer toggle |
| Export | No share/export file yet — copy per round only |
| Widget-only sends | Use `web_widget` directory bucket |

---

## File index

```
data/model/EidosApiTraceModels.kt
data/dao/EidosApiTraceDao.kt
data/db/AppDatabase.kt                    (v21)
data/eidos/EidosApiTraceFeature.kt
data/eidos/EidosApiTraceDirectory.kt
data/eidos/EidosApiTraceRecorder.kt
data/eidos/EidosApiTraceJson.kt
data/eidos/EidosApiClient.kt              (wiring)
data/eidos/model/EidosModels.kt           (callback)
data/eidos/provider/EidosProviderTrace.kt
data/eidos/provider/KimiProvider.kt
data/eidos/provider/OpenAIProvider.kt
data/eidos/provider/AnthropicProvider.kt
data/eidos/provider/XAIProvider.kt
data/preferences/SettingsPreferences.kt
ui/settings/SettingsScreen.kt
ui/settings/SettingsViewModel.kt
ui/eidos/EidosApiTraceScreens.kt
ui/eidos/EidosSystemScreens.kt
ui/navigation/AppNavigation.kt
docs/systems/EIDOS_API_TRACE.md
docs/implementation/EIDOS_API_TRACE_IMPLEMENTATION_PLAN.md
```
