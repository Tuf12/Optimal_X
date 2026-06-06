# PANEL_PLATFORM.md

**OptimalX Panel Platform — runtime contract for HTML/CSS/JS workshop panels**

| Field | Value |
|--------|--------|
| **Platform version** | `1` — machine-readable: `PanelPlatformSpec.kt` (`PLATFORM_VERSION`) |
| **Audience** | Eidos (Panel Workshop), human developers, future verify/diagnostics tooling |
| **Related docs** | [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md) (v2 phased UX), [WORKSHOP_MODES.md](WORKSHOP_MODES.md) (phases + modes), [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md) (active implementation plan), [EDITOR_AND_PANELS.md](EDITOR_AND_PANELS.md), `WorkshopAndroidLayoutRules.kt` |
| **Kotlin truth** | When this doc and code disagree, **Kotlin wins** — file a platform issue and update this doc |

---

## Purpose

Panel Workshop projects are **small web apps** (HTML, CSS, JavaScript) that run **inside OptimalX**, not in Chrome or Safari alone.

The **panel platform** is everything the Android app does so those files work reliably:

- **Assemble** multi-file projects into one loadable document
- **Host** them in a configured `WebView`
- **Expose** a JavaScript bridge for Eidos (`OptimalXPanelBridge`)
- **Enforce** layout and security boundaries
- **Ship scaffolds** so new panels start with correct Android WebView + bridge patterns

Eidos and panel authors must treat this file as the contract: **what Kotlin provides**, **what panel code must do**, and **what is not supported** without a platform change.

**Workshop build flow** (chat intake, design-before-logic, doc alignment) lives in [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md). This doc defines **runtime behavior** and **when platform rules apply** during each phase.

---

## Mental model

```text
┌─────────────────────────────────────────────────────────────────┐
│  OptimalX (Kotlin) — PANEL PLATFORM                              │
│  PanelHtmlComposer · WorkshopPreviewPanel · PanelBridgeRegistry  │
│  WorkshopEditorViewModel / EditorViewModel (composite HTML)      │
│  Scaffolds · WorkshopAndroidLayoutRules · PanelPlatformSpec      │
└────────────────────────────┬────────────────────────────────────┘
                             │ loadDataWithBaseURL + bridge shim
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Single HTML document in WebView (inlined CSS + ordered JS)      │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Panel project — YOUR CODE                                       │
│  index.html · style.css · bridge.js · script.js                  │
│  Business logic, UI, calculations, game rules                    │
└─────────────────────────────────────────────────────────────────┘
```

Panels are **not** compiled by Kotlin. Kotlin **packages** and **hosts** them. There is no `npm`, Gradle module, or APK per panel.

---

## Workshop lifecycle vs platform rules

v2 Panel Workshop splits **spec work**, **design shell**, and **logic** into gated phases. Platform instructions are applied **when Eidos writes runtime files**, not on every chat turn.

| Workshop phase | Platform context Eidos receives | Full `eidosWorkshopInstructions`? |
|----------------|--------------------------------|-----------------------------------|
| Intake / spec review | Layout summary only (`eidosContextSummary`, Plan mode) | No |
| Design build | Full layout, bundling, bridge order, scaffolds | Yes — **shell only** (no business logic) |
| Design review | Compact layout reminder; **code is truth**; no `.md` in context | No |
| Logic build | Full bridge + runtime checklist | Yes — behavior files |
| Logic review / Debug | Runtime + console + bridge; QuickJS when Kimi DEBUG | Partial + debug block |
| Complete / Update | Compact reminder per section | On design/logic writes only |

**Doc alignment:** Spec `.md` files are **human-readable snapshots** updated **code → spec on user approval** — not live build drivers during iteration. There is **no Sync to code** button. See [WORKSHOP_MODES.md](WORKSHOP_MODES.md).

---

## Where panels run

| Surface | Kotlin entry | `panelContextType` | Eidos scope | Notes |
|---------|----------------|-------------------|-------------|--------|
| Workshop **Preview** | `WorkshopPreviewPanel` | `workshop_preview` | `panel_workshop` | Composite HTML from `WorkshopEditorViewModel.getCompositeHtml()`; bridge in DEBUG mode only |
| **Panel Gallery** runner | `PanelRunnerScreen` → `WorkshopPreviewPanel` | `gallery` | `panel_runner` | Live workshop files; persisted state scope `global`; bridge **always** eligible when visible |
| Editor **custom panel tab** | `WorkshopPreviewPanel` | `custom_panel` | `subfolder` (host job) | Composite HTML from `EditorViewModel.getCustomPanelHtml()`; state scope `subfolder:{hostSubfolderId}`; bridge when tab visible |
| **Panel Gallery** list | `PanelGalleryScreen` | N/A | `panel_gallery` | Browse/launch only — no WebView; metadata context only |
| In-app **Web panel** | `WebPanel` | N/A | `web_editor` | **Different system** — live URLs, not workshop projects |

Workshop/custom panels always go through **`PanelHtmlComposer`** before display.

---

## Standard project files

| File | Required | Role in v2 workflow |
|------|----------|---------------------|
| `README.md` | Yes (after Generate specs) | Short human-language summary — **not** an intake form |
| `STRUCTURE.md` | Spec | File map (short) |
| `FEATURES.md` | Spec | Feature bullets (short) |
| `FLOW.md` | Spec | Interaction steps (short) |
| `DESIGN.md` | Spec | Layout + **Android WebView layout** rules (from platform) |
| `index.html` | Yes (runtime) | Entry markup; references `./style.css` and scripts (see bundling) |
| `style.css` | Typical | Styles — design build phase |
| `bridge.js` | **Strongly recommended** | Eidos/Android bridge wiring — loaded **before** app logic |
| `script.js` | Yes (runtime) | App logic — stub in design build; full logic in logic build |

**Spec prompt rule:** Summarize intent in **human language** — do not rewrite HTML/JS into the spec files.

**Greenfield caps** (guideline): see [PANEL_WORKSHOP.md](PANEL_WORKSHOP.md#spec-files).

Storage: `files/workshop/{subfolderId}/{filename}` with `FileReference` rows in Room.

### Built-in scaffolds (new projects)

`FolderRepository.createWorkshopProject` seeds runtime files from `WORKSHOP_*_SCAFFOLD` constants:

| Scaffold | Embeds |
|----------|--------|
| `index.html` | Viewport meta, script order (`bridge.js` then `script.js`), title placeholder |
| `style.css` | Phone layout baseline from `WorkshopAndroidLayoutRules` |
| `bridge.js` | `getState` / `runAction`, `registerTools`, `panelGetState` / `panelHandleAction` forwarding |
| `script.js` | DOM-ready stub; hooks for panel logic |

Eidos **design build** should extend scaffolds, not replace platform patterns. **Logic build** fills in `script.js` / `bridge.js` behavior.

`WorkshopAndroidLayoutRules.kt` supplies condensed rules for DESIGN.md seeds and Eidos Plan/Design prompts (touch targets, canvas sizing, scroll-safe body).

---

## Platform capability: HTML bundling (`PanelHtmlComposer`)

**Kotlin:** `app/src/main/java/com/example/optimalx/ui/workshop/PanelHtmlComposer.kt`

Before the WebView loads, the app builds **composite HTML**:

1. Read `index.html` (or first `.html` in project).
2. **Remove** external `<script src="…">` and `<link rel="stylesheet" href="…">` from the HTML string (avoids double-load).
3. **Inline** every project `.css` into `<style>…</style>` before `</head>`.
4. **Inline** every project `.js` into `<script>…</script>` before `</body>`.
5. **Order JS:** `bridge.js` first, then all other `.js` files (alphabetically among non-bridge names).

Load via `WebView.loadDataWithBaseURL(file://{projectDir}/, compositeHtml, …)`.

### Implications for Eidos / panel authors

| Do | Don't |
|----|--------|
| Keep logic in `script.js` and bridge in `bridge.js` | Assume `<script src="./script.js">` runs reliably on its own |
| List scripts in HTML as `bridge.js` then `script.js` (documentation + fallback) | Depend on CDN or `https://` scripts (blocked by product policy) |
| Use separate `.css` / `.js` files in the project | Put irreplaceable logic only in external URLs |

If JavaScript “does nothing” (UI static, totals stuck at `$0`), first check: **did composite bundling run?** (preview/custom tab with current app build) and **console errors** (see Debugging).

---

## Platform capability: WebView settings

**Kotlin:** `PanelHtmlComposer.configureWebViewForLocalPanel` + `WorkshopPreviewPanel`

| Setting | Value | Effect |
|---------|--------|--------|
| `javaScriptEnabled` | `true` | Required for all panels |
| `domStorageEnabled` | `true` | `localStorage` / DOM storage may work |
| `allowFileAccess` | `true` | Local workshop files |
| `allowContentAccess` | `false` | No content-URI access from panel host |
| `allowFileAccessFromFileURLs` | `true` (deprecated API) | Helps resolve relative assets when using file base URL |
| `allowUniversalAccessFromFileURLs` | `true` (deprecated API) | Same |

**Not enabled for workshop panels (vs live Web panel):** third-party cookies, multi-window browsing, mixed content to arbitrary origins.

---

## Platform capability: scrolling inside the editor pager

**Kotlin:** `WorkshopPreviewPanel` — `setOnTouchListener` calls `requestDisallowInterceptTouchEvent(true)` on `ACTION_DOWN` / `ACTION_MOVE` so the parent **`HorizontalPager`** does not steal vertical drags.

**Same pattern:** `WebPanel` (live browser tab).

### Implications for CSS/JS

| Do | Don't |
|----|--------|
| Let the **WebView** scroll the document (default) | `body { overflow-y: auto }` as primary scroll container |
| Use `min-height: 100vh` on `body`, flex column layout | `touchmove` + `stopPropagation()` on wrappers to “fix” scroll |
| Use `touch-action: manipulation` on buttons/inputs | `position: fixed` / `100dvh` / safe-area hacks for main layout unless user requested |

Full layout rules: **`WorkshopAndroidLayoutRules`** (injected into DESIGN.md seeds and Eidos workshop prompt summary).

---

## Platform capability: JavaScript bridge (Eidos ↔ panel)

**Kotlin:**

- `PanelBridgeWebRuntime.kt` — shim installed on `onPageFinished`
- `PanelBridgeRegistry.kt` — routes `call_panel_function` to visible instance
- `@JavascriptInterface` name: `OptimalXPanelBridge` (exposed as `window.OptimalXPanelBridge` after shim)

### Panel-side files

**`bridge.js`** (platform scaffold — do not put game/calculator logic here):

- Defines global async functions **`getState(args)`** and **`runAction(args)`** (required names for Android invocation).
- Forwards to optional hooks: **`window.panelGetState`**, **`window.panelHandleAction`** in `script.js`.
- Calls `OptimalXPanelBridge.registerTools([...])` on boot and on `optimalx-bridge-ready`.
- May `emitBridgeEvent("panel_loaded", {})`.

**`script.js`**:

- All UI, math, game rules, DOM listeners.
- Optionally implements `panelGetState` / `panelHandleAction` for Eidos control.
- Design build: stubs only. Logic build: full behavior.

### Eidos tool

| Tool | When |
|------|------|
| `call_panel_function` | `functionName` = `getState` \| `runAction` \| custom registered name; `args` = JSON string |

**Requirement:** A **visible** workshop preview, **gallery runner** (`panel_runner` scope), or **custom panel tab** for that `workshopSubfolderId`. Otherwise the tool fails with an actionable message.

| Surface | Bridge routing | Eidos file writes |
|---------|----------------|-------------------|
| Workshop Preview | Eligible in **DEBUG** mode only; otherwise use file tools | Yes — phase/mode gated |
| Gallery runner | **Always** eligible when runner WebView is visible | **No** — runtime + `call_panel_function` only |
| Editor custom tab | Eligible when tab is visible/focused | No — use Workshop for project edits |

Available from **design build** onward when preview is open — primary use in **logic review**, **Debug**, and **Panel Runner**.

### `runAction` argument shape

```javascript
// Eidos / Android passes a single JSON object:
await runAction({ action: "reset", ... });

// panelHandleAction must read:
window.panelHandleAction = function(args = {}) {
  const action = args.action || args;  // support legacy string-only if needed
  ...
};
```

### Optional native intercept

`WorkshopPreviewPanel` may handle **`runAction({ action: "eidosInfer", prompt: "...", ... })`** in Kotlin (LLM call) without panel JS implementing it. Do not rely on this unless documented in `PanelPlatformSpec` for your app version.

---

## Platform capability: panel state persistence

**Kotlin:** `panel_state` Room table, `PanelStateRepository`, `PanelStateScope`

Gallery runner and editor custom tabs can persist opaque JSON blobs so user input survives refresh and app restart. Workshop preview does **not** persist state (ephemeral build surface).

| Surface | `scopeKey` | Storage |
|---------|------------|---------|
| Panel Gallery runner | `global` | One blob per workshop project |
| Editor custom panel tab | `subfolder:{hostSubfolderId}` | One blob per host subfolder assignment |

**Bridge API (when `panelStateScopeKey` is set on `WorkshopPreviewPanel`):**

| JS | Native | Purpose |
|----|--------|---------|
| `OptimalXPanelBridge.loadPersistedState()` | `@JavascriptInterface loadPersistedState()` | Read saved JSON (default `{}`) |
| `OptimalXPanelBridge.savePersistedState(json)` | `@JavascriptInterface savePersistedState(json)` | Upsert opaque JSON blob |

On page load, Kotlin also attempts restore via `panelRestoreState(state)` or `panelHandleAction({ action: "__restoreState", state })`.

**Scaffold behavior (new projects):**

| Helper | When |
|--------|------|
| `persistPanelStateDebounced(ms?)` | Call from `script.js` after user edits (default 800 ms) |
| `flushPersistedPanelState()` | On `visibilitychange` (hidden) and `pagehide` |
| `runAction` | Also calls immediate save when `panelGetState` exists (safety net) |
| `restorePersistedStateIfAvailable()` | On bridge ready — prefers `panelRestoreState` |

Legacy projects may lack debounced helpers until bridge.js is updated (see persistence implementation plan Phase 4).

Panel JS owns the schema inside the blob; Kotlin stores it verbatim. No export-to-gallery step — gallery loads live workshop files from disk.

**Implementation plan (contract, Eidos enforcement, scaffold debounce):** [PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md](../implementation/PANEL_STATE_PERSISTENCE_IMPLEMENTATION_PLAN.md).

### What Kotlin does **not** do

The platform does **not** automatically persist panel UI:

- No DOM scrape of `<input>`, `<textarea>`, or canvas pixels
- No inference of “game state” from HTML alone
- No save on every keystroke unless **panel JS** calls the bridge

`index.html` is layout only. **`script.js`** (and scaffold **`bridge.js`**) must define what to save and when.

### Panel author checklist (stateful panels)

| Step | Requirement |
|------|-------------|
| 1 | `window.panelGetState = function () { return { version: 1, … }; }` — JSON-serializable snapshot |
| 2 | `window.panelRestoreState = function (state) { … }` **or** handle `{ action: "__restoreState", state }` in `panelHandleAction` |
| 3 | After user-mutable changes, persist via scaffold `persistPanelStateDebounced()` (Phase 3) or `runAction` / explicit `savePersistedState` |
| 4 | Document in **FEATURES.md** what is saved and per-scope behavior (gallery `global` vs editor `subfolder:{id}`) |
| 5 | Test in **Panel Gallery** or **editor custom tab** — not Workshop Preview (ephemeral) |

Static validation: `PanelPlatformSpec.validatePersistenceContract(scriptJs, bridgeJs)` — used by workshop health / Finish gate.

### Anti-patterns

| Anti-pattern | Why |
|--------------|-----|
| Only `localStorage` for scores/saves | Lost on WebView clear; not in Room backup coherently — use bridge + Room |
| Relying on Preview for save testing | Preview has no `panelStateScopeKey` — saves appear to “not work” |
| Huge blobs (full canvas ImageData) | Slow save/restore — store game fields, not raw pixels |
| Missing `version` in JSON | Harder to migrate saved data when panel logic changes |

---

## Platform capability: console capture (debug)

**Kotlin:** `WorkshopPreviewPanel` `onConsoleMessage` → `WorkshopEditorViewModel.addConsoleError`

| Level | Captured in workshop preview |
|--------|------------------------------|
| ERROR | Yes |
| WARNING | Yes (v1) |

Use `console.error(...)` in `script.js` when init fails. Eidos should ask the user to open Preview and report console buffer content when debugging.

**Debug mode** (from logic build phase): console buffer + `call_panel_function` + Kimi Formula **`quickjs`** (Phase 3.6) to isolate JS vs **platform gap** (Kotlin ↔ bridge ↔ WebView).

When QuickJS passes but Preview fails, Eidos should file a **platform issue** — not keep patching panel JS.

---

## Eidos Panel Workshop tools

**Kotlin:** `EidosToolCatalog.panelWorkshop`

| Tool | Purpose |
|------|---------|
| `workshop_read_file` | Read project file by `fileReferenceId` |
| `workshop_write_file` | Overwrite project file |
| `workshop_create_file` | Create file in workshop subfolder |
| `call_panel_function` | Invoke live panel JS via bridge |
| Formula `quickjs` | **DEBUG mode + Kimi only** — sandbox JS snippets (Phase 3.6) |

Tool availability is filtered by **`WorkshopProjectPhase`** and **`WorkshopEidosMode`** — see [WORKSHOP_MODES.md](WORKSHOP_MODES.md). Example: no `.md` read/write during **design review**.

### System context (phase + mode aware)

**Kotlin:** `EidosApiClient.buildWorkshopPanelContext()` + `PanelPlatformSpec.eidosInstructionsForMode()`

| Prompt helper | Use |
|---------------|-----|
| `eidosPlanModeInstructions()` | Generate / edit specs |
| `eidosBuildDesignInstructions()` | Design build — shell only |
| `eidosDesignModeInstructions()` | Design review — code only |
| `eidosBuildLogicInstructions()` | Logic build |
| `eidosEditModeInstructions()` | Targeted runtime edits |
| `eidosDebugModeInstructions()` | Debug + platform gap + QuickJS note |
| `eidosChatModeInstructions()` | Intake / discussion — no writes |
| `eidosAlignDocsFromCodeInstructions()` | Approval gates — short code → spec |
| `eidosContextSummary()` | Compact layout + platform hint |
| `formatValidationReport(validateProjectFiles(...))` | Logic build + Debug only |

**Legacy (remove during overhaul):** monolithic `eidosWorkshopInstructions()` + `workshopBuildKickoffFooter()` for one-shot Start Build / Sync to code.

**Intake:** Persisted `intake_summary` in prefs — not README form content.

---

## HTML / CSS / JS authoring checklist (Eidos)

Apply during **design build** and **logic build** writes (and scoped Update edits).

### HTML

- `<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">`
- Semantic structure: header / sections / footer; controls **below** canvas when using canvas games
- Element `id`s stable for `script.js` (`getElementById`)
- Script order in source files: `bridge.js` then `script.js`

### CSS

- Phone-first; `min-height: 100vh`; flex column; centered shell
- Touch targets ~**56px** min height on primary controls
- Canvas games: pixel size on `<canvas width height>`; CSS `width: 100%`, `height: auto`, `aspect-ratio: W / H`
- Avoid nested scroll on `body` (platform handles pager + WebView scroll)

### JavaScript

- Init on `DOMContentLoaded` if needed, or at end of `script.js` after DOM exists
- Use **event delegation** on containers for dynamic rows (calculators, room lists)
- Never call `calculateAll()` from inside `updateRates()` if `updateRates()` is called from `calculateAll()` (infinite recursion)
- Prefer `addEventListener('input', …)` on grids; use `change` for `<select>`
- Return JSON-serializable objects from `panelGetState`
- Do **not** redefine global `getState` / `runAction` in `script.js` (owned by `bridge.js`)

---

## Unsupported or unreliable without platform changes

| Panel wants | Status in v1 | Workaround / escalation |
|-------------|--------------|-------------------------|
| `fetch` / XHR to internet | **Out of scope** (product policy) | Ask user; no panel network unless platform adds opt-in |
| `navigator.clipboard` | **Unreliable** in WebView | Future: `runAction({ action: "copyText", text })` — **platform issue** |
| `window.print()` / PDF | **Limited** | Future bridge export — **platform issue** |
| File picker / native share | **Not exposed** | **platform issue** |
| Background work when tab hidden | **Not guaranteed** | Design for visible tab |
| Multiple concurrent bridge instances | **One focused visible** | User opens correct preview/tab |
| External `<script src="https://…">` | **Stripped / blocked** | Inline project JS only |
| Syntax highlighting in workshop editor | **Not available** | N/A |

When something in this table is required, Eidos should **not** hack around silently — document a **platform issue** for the app developer (see template below).

**Debug workflow:** If panel JS validates in QuickJS but fails in Preview/bridge, classify as **platform gap** and use the template — this surfaces missing Kotlin ↔ JS contract work.

---

## Division of responsibility

| Layer | Owns |
|--------|------|
| **Kotlin platform** | Bundling, WebView config, scroll/touch, bridge injection, Eidos routing, console capture, composite reload policy, project scaffolds |
| **`bridge.js`** | `getState` / `runAction` globals, `registerTools`, event emit to Android |
| **`script.js`** | Business logic, DOM, calculations, `panelGetState` / `panelHandleAction` |
| **`index.html` / `style.css`** | Structure and presentation |
| **Spec `.md` files** | Short human-language snapshots for the user — aligned **from code on approval**, not authoritative during iteration |
| **Eidos** | Phase-appropriate code generation, `call_panel_function` tests, user communication, platform issue escalation |

---

## Common failure modes (debugging)

| Symptom | Likely cause | Fix |
|---------|----------------|-----|
| UI renders but JS inert | Scripts not loaded (old `file://` only); JS syntax error | Use app with `PanelHtmlComposer`; check console buffer |
| Totals always `$0` | Logic bug, or init never ran | Fix `script.js`; ensure `init()` runs; no recursion in calc |
| Scroll “sticky” / fights swipe | CSS `overflow` on `body` or touch hacks | Remove; rely on platform touch listener |
| Eidos `call_panel_function` fails | Preview/tab not visible | User opens Preview or custom panel tab |
| `Identifier 'rooms' has already been declared` | Scripts executed twice | Composite build strips external src; reload preview |
| Bridge idle | Missing `bridge.js` or `panelGetState` not defined | Add scaffold `bridge.js` |
| QuickJS OK, Preview broken | Platform / bridge / bundling gap | Platform issue — not panel JS |

---

## Platform issue template (for developer)

When Eidos cannot fix panel code alone:

```markdown
## Panel platform issue

**Platform version:** 1
**Category:** bridge | webview | composer | layout-rules | security | new-capability
**Requested capability:** (what the panel needs to do)
**Why panel JS is insufficient:** (WebView limit, no API, policy)
**Workshop subfolderId:** 
**Workshop phase:** (e.g. LOGIC_REVIEW, DEBUG)
**Repro steps:** 
**Console / verify output:** 
**QuickJS result (if run):** 
**Minimal panel snippet:** 
**Suggested Kotlin / bridge API:** (e.g. runAction action name + args)
```

---

## Kotlin reference map

| Component | Path |
|-----------|------|
| HTML bundle + load | `ui/workshop/PanelHtmlComposer.kt` |
| WebView host | `ui/workshop/WorkshopPreviewPanel.kt` |
| Bridge shim + JS interface | `ui/workshop/PanelBridgeWebRuntime.kt` |
| Bridge routing | `data/eidos/PanelBridgeRegistry.kt` |
| Panel state store | `data/repository/PanelStateRepository.kt`, `data/model/PanelState.kt` |
| Gallery runner | `ui/gallery/PanelRunnerScreen.kt` |
| Workshop composite | `ui/workshop/WorkshopEditorViewModel.kt` → `getCompositeHtml()` |
| Editor composite | `ui/editor/EditorViewModel.kt` → `getCustomPanelHtml()` |
| **Platform spec (Eidos + validation)** | `data/eidos/PanelPlatformSpec.kt` |
| Layout rules (DESIGN.md seeds) | `data/eidos/WorkshopAndroidLayoutRules.kt` |
| Project scaffolds | `data/repository/FolderRepository.kt` |
| Phase + mode prefs | `data/preferences/WorkshopProjectPreferences.kt` | ✅ Phase 0 — `project_phase`, `intake_summary`, `update_section` |
| Phase enum (planned) | `data/eidos/WorkshopProjectPhase.kt` |
| Eidos workshop context | `data/eidos/EidosApiClient.kt` |
| Eidos tools | `data/eidos/EidosToolCatalog.kt` |
| Product UX | `docs/architecture/PANEL_WORKSHOP.md` |
| Implementation plan | `docs/implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md` |

---

## `PanelPlatformSpec.kt`

Kotlin object mirroring this document:

| API | Use |
|-----|-----|
| `PLATFORM_VERSION` | Version gate for issues and docs |
| `RUNTIME_FILES` / `SPEC_MARKDOWN_FILES` | Expected project layout |
| `capabilities` / `forbiddenApis` | Machine-readable platform matrix |
| `eidosContextSummary()` | Short Eidos hint (layout + platform) |
| `eidosInstructionsForMode(mode, phase)` | Phase + mode workshop block (**v2 target**) |
| `eidosPlanModeInstructions()` | Spec generation / Plan |
| `eidosBuildDesignInstructions()` | Design build shell (**v2 — planned**) |
| `eidosDesignModeInstructions()` | Design review (**v2 — planned**) |
| `eidosBuildLogicInstructions()` | Logic build (**v2 — planned**) |
| `eidosAlignDocsFromCodeInstructions()` | Approval gate doc sync (**v2 — planned**) |
| `eidosWorkshopInstructions(subfolderId)` | **Legacy** monolithic BUILD block |
| `validateProjectFiles(fileNames)` | Static file presence checks |
| `formatValidationReport(...)` | Text for Eidos context |
| `workshopBuildKickoffFooter()` | **Legacy** Start Build footer — remove in overhaul |
| `platformIssueTemplate()` | Escalation template for developer |
| `designMarkdownSection()` | DESIGN.md seed from `WorkshopAndroidLayoutRules` |

**Wired today:** `EidosApiClient.buildWorkshopPanelContext`, legacy kickoff in `EidosChatViewModel`, `FolderRepository` DESIGN.md seed.

**Workshop recovery:** [PANEL_WORKSHOP_RECOVERY_PLAN.md](../implementation/PANEL_WORKSHOP_RECOVERY_PLAN.md). Historical rollout: [archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md](../archive/PANEL_WORKSHOP_OVERHAUL_PLAN.md).

**Planned next:** `panel_verify` Eidos tool; phase-aware prompts; remove Sync to code paths.

When this doc and `PanelPlatformSpec.kt` disagree, **update both** — Kotlin is runtime truth.

---

## Document changelog

| Version | Date | Notes |
|---------|------|--------|
| 1 | 2026-05-18 | Initial platform doc: bundling, WebView, scroll, bridge, authoring rules, unsupported matrix |
| 2 | 2026-05-25 | Aligned with Panel Workshop v2: phased platform context, scaffolds, doc snapshots, no Sync to code, Debug + QuickJS, new prompt helpers |
